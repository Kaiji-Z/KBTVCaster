#!/usr/bin/env python3
"""Headless mirror-stream injector for the KBTVCaster AirPlay receiver.

The rig that ends user-as-test-subject rounds: speaks enough RTSP to
establish a mirror session (OPTIONS -> SETUP), then streams a real H.264
elementary stream through the native mirror data channel in the AirPlay
TCP packet format (128-byte headers), driving the FULL receiver pipeline:
raop_rtp_mirror -> renderer -> MediaCodec -> GL. Requires a debug build
with -Pkbtv.flags.injector=true (plaintext mirror bypass, see
mirror_buffer.c) because a synthetic sender cannot run the FairPlay
handshake.

Packet format (documented in raop_rtp_mirror.c):
  [0:4]  payload size (BE)
  [4:6]  type: 0x0000 non-IDR (encrypted) / 0x0010 IDR / 0x0100 SPS+PPS
  [6:8]  option (0x0000 for VCL, 0x0116 style for SPS/PPS)
  [8:16] NTP timestamp (BE, microseconds since 1900)

Usage:
  python mirror_inject.py [--host 192.168.0.24] [--port 7000]
                          [--h264 path/to/warmup.h264] [--seconds 20]
                          [--fps 30] [--switch-every 0] [--stop]

--switch-every N: re-SETUP every N seconds on the same connection (the
video-switch pattern; exercises the retire/reaper path with live data).
Exit 0 = streamed; pair with assert_session.py for the verdict.
"""
import argparse
import plistlib
import socket
import struct
import sys
import time

NTP_EPOCH_OFFSET = 2208988800  # 1900 -> 1970


def ntp_micros() -> int:
    return int((time.time() + NTP_EPOCH_OFFSET) * 1_000_000)


def mirror_packet(payload: bytes, ptype: int, option: int, ts_us: int,
                  size=None) -> bytes:
    # LITTLE-endian length and timestamp: the receiver parses the header with
    # byteutils_get_int/get_long, which are native (LE) reads on ARM — a BE
    # length made the receiver wait for an 84MB payload (2026-10-31 lesson).
    # The type/option bytes at [4:8] are read as individual bytes, order as
    # documented in raop_rtp_mirror.c.
    # size=(w, h): the fmt (SPS/PPS) packet carries the video size as LE
    # floats at 16-19/20-23 (source) and 56-59/60-63; zero sizes make
    # video_report_size deliver 0x0 and the renderer ignores the resolution.
    head = bytearray(struct.pack("<I", len(payload)))
    head += bytes([ptype >> 8, ptype & 0xFF, option >> 8, option & 0xFF])
    head += struct.pack("<Q", ts_us)
    head += bytearray(128 - len(head))
    if size:
        w, h = size
        for off in (16, 40):
            head[off:off + 4] = struct.pack("<f", float(w))
            head[off + 4:off + 8] = struct.pack("<f", float(h))
        head[56:60] = struct.pack("<f", float(w))
        head[60:64] = struct.pack("<f", float(h))
    return bytes(head) + payload


def parse_sps_dimensions(nal_with_startcode: bytes):
    """Minimal H.264 SPS parse for width/height (exp-golomb). Enough for the
    ffmpeg-generated warmup assets (High/Baseline, 4:2:0); returns None when
    the parse walks off the buffer — the injector then omits the size floats."""
    sc = 4 if nal_with_startcode[:4] == b"\x00\x00\x00\x01" else 3
    body = nal_with_startcode[sc + 1:]  # strip start code + NAL header
    # strip emulation-prevention bytes
    raw = bytearray()
    i = 0
    while i < len(body):
        if i + 2 < len(body) and body[i] == 0 and body[i + 1] == 0 and body[i + 2] == 3:
            raw += body[i:i + 2]
            i += 3
        else:
            raw.append(body[i])
            i += 1
    bits = bin(int.from_bytes(bytes(raw) + b"\x00\x00", "big"))[2:].zfill(len(raw) * 8 + 16)

    pos = 0

    def uv(n):
        nonlocal pos
        val = int(bits[pos:pos + n], 2)
        pos += n
        return val

    def ue():
        nonlocal pos
        z = 0
        while pos < len(bits) and bits[pos] == "0":
            z += 1
            pos += 1
        pos += 1  # the leading 1
        return (1 << z) - 1 + (uv(z) if z else 0)

    profile = uv(8)
    uv(8)  # constraints
    uv(8)  # level
    ue()   # sps_id
    if profile in (100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 144):
        chroma = ue()
        if chroma == 3:
            uv(1)
        ue()
        ue()
        uv(1)
        if uv(1):
            return None  # scaling lists unsupported here
    ue()   # log2_max_frame_num
    poc_type = ue()
    if poc_type == 0:
        ue()
    elif poc_type == 1:
        uv(1)
        ue()
        ue()
        for _ in range(ue()):
            ue()
    ue()   # max_num_ref_frames
    uv(1)  # gaps_allowed
    w_mbs = ue()
    h_map = ue()
    frame_mbs_only = uv(1)
    if not frame_mbs_only:
        uv(1)
    uv(1)  # direct_8x8
    crop_l = crop_r = crop_t = crop_b = 0
    if uv(1):
        crop_l, crop_r, crop_t, crop_b = ue(), ue(), ue(), ue()
    width = (w_mbs + 1) * 16 - (crop_l + crop_r) * 2
    height = (2 - frame_mbs_only) * (h_map + 1) * 16 - (crop_t + crop_b) * 2
    return width, height


def parse_nals(data: bytes):
    """Split an H.264 ES on Annex-B start codes; yield (nal_type, nal_bytes)
    where nal_bytes still carries its start code. to_length_prefixed converts
    to the AirPlay wire format: 4-byte BE length + NAL, no start codes
    (raop_rtp_mirror.c swaps the length back for a start code on arrival)."""
    nals = []
    starts = []
    i = 0
    while i < len(data) - 3:
        if data[i] == 0 and data[i + 1] == 0 and data[i + 2] == 1:
            starts.append((i, 3))
            i += 3
        elif data[i] == 0 and data[i + 1] == 0 and data[i + 2] == 0 and data[i + 3] == 1:
            starts.append((i, 4))
            i += 4
        else:
            i += 1
    for idx, (pos, sc) in enumerate(starts):
        end = starts[idx + 1][0] if idx + 1 < len(starts) else len(data)
        nal = data[pos:end]
        nals.append((nal[sc] & 0x1F, nal))
    return nals


def to_length_prefixed(nal_with_startcode: bytes) -> bytes:
    sc = 4 if nal_with_startcode[:4] == b"\x00\x00\x00\x01" else 3
    body = nal_with_startcode[sc:]
    return struct.pack(">I", len(body)) + body


def rtsp(sock: socket.socket, method: str, cseq: int, body: bytes = b"") -> bytes:
    head = (
        f"{method} rtsp://inject/1 RTSP/1.0\r\nCSeq: {cseq}\r\n"
        f"User-Agent: MirrorInjector/1.0\r\n"
    )
    if body:
        head += (
            "Content-Type: application/x-apple-binary-plist\r\n"
            f"Content-Length: {len(body)}\r\n"
        )
    sock.sendall(head.encode("ascii") + b"\r\n" + body)
    buf = b""
    sock.settimeout(8.0)
    while b"\r\n\r\n" not in buf:
        chunk = sock.recv(65536)
        if not chunk:
            break
        buf += chunk
    head_end = buf.index(b"\r\n\r\n") + 4
    # follow Content-Length for the body
    headers = buf[:head_end].decode("ascii", "replace").lower()
    clen = 0
    for line in headers.split("\r\n"):
        if line.startswith("content-length:"):
            clen = int(line.split(":", 1)[1].strip())
    body_buf = buf[head_end:]
    while len(body_buf) < clen:
        chunk = sock.recv(65536)
        if not chunk:
            break
        body_buf += chunk
    return buf[:head_end], body_buf[:clen]


def setup_plist() -> bytes:
    return plistlib.dumps(
        {
            "ekey": b"\x00" * 72,
            "eiv": b"\x11" * 16,
            "streams": [{"type": 110}],
        },
        fmt=plistlib.FMT_BINARY,
    )


def fmt_payload_for(sps_nal: bytes, pps_nal: bytes) -> bytes:
    # fmt packet payload uses the UxPlay-internal layout that raop_rtp_mirror.c
    # expects (NOT length-prefixed NALs): 6-byte header, BE short sps_size,
    # raw SPS, 1 separator byte, BE short pps_size, raw PPS. A wrong layout
    # makes the signed-short pps_size read garbage and abort on its dump
    # assert (2026-10-31 crash, utils.c:192).
    def body(nal):
        sc = 4 if nal[:4] == b"\x00\x00\x00\x01" else 3
        return nal[sc:]
    sps_body, pps_body = body(sps_nal), body(pps_nal)
    return (
        b"\x00" * 6
        + struct.pack(">H", len(sps_body)) + sps_body
        + b"\x00"
        + struct.pack(">H", len(pps_body)) + pps_body
    )


def build_gops(nals):
    """Group (SPS, PPS, IDR, P...) sequences so each replay starts with its
    own parameter sets and an IDR — replaying bare P-frames starves the
    decoder's reference chain and it renders nothing (frames=0, dropped≈all)."""
    gops = []
    pending_sps = pending_pps = None
    for t, n in nals:
        if t == 7:
            pending_sps = n
        elif t == 8:
            pending_pps = n
        elif t == 5 and pending_sps and pending_pps:
            gops.append((pending_sps, pending_pps, [(t, n)]))
        elif t == 1 and gops:
            gops[-1][2].append((t, n))
    return gops


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--host", default="192.168.0.24")
    ap.add_argument("--port", type=int, default=7000)
    ap.add_argument("--h264", required=True, help="Annex-B H.264 elementary stream")
    ap.add_argument("--seconds", type=float, default=20.0)
    ap.add_argument("--fps", type=int, default=30)
    ap.add_argument("--switch-every", type=float, default=0.0,
                    help="re-SETUP interval on the same connection (0=never)")
    ap.add_argument("--stop", action="store_true", help="TEARDOWN at the end")
    args = ap.parse_args()

    es = open(args.h264, "rb").read()
    nals = parse_nals(es)
    sps = [n for t, n in nals if t == 7]
    pps = [n for t, n in nals if t == 8]
    vcl = [(t, n) for t, n in nals if t in (1, 5)]
    if not sps or not pps or not vcl:
        print(f"FAIL h264 asset lacks SPS/PPS/VCL (sps={len(sps)} pps={len(pps)} vcl={len(vcl)})")
        return 1
    print(f"asset: {len(nals)} NALs (sps={len(sps)} pps={len(pps)} vcl={len(vcl)})")

    ctl = socket.socket()
    ctl.settimeout(8.0)
    ctl.connect((args.host, args.port))
    _, _ = rtsp(ctl, "OPTIONS", 1)

    body = setup_plist()
    _, resp_body = rtsp(ctl, "SETUP", 2, body)
    resp = plistlib.loads(resp_body)
    data_port = None
    for s in resp.get("streams", []):
        if s.get("type") == 110:
            data_port = s.get("dataPort")
    if not data_port:
        print(f"FAIL no mirror dataPort in SETUP response: {resp}")
        return 1
    print(f"SETUP ok, mirror data port {data_port}")

    stream = socket.socket()
    stream.settimeout(8.0)
    stream.connect((args.host, data_port))

    # initial format packet: SPS+PPS (unencrypted type 0x0100, length-prefixed),
    # then the first VCL — it MUST share the SPS/PPS timestamp or the receiver
    # discards the parameter sets (raop_rtp_mirror.c prepend check) and the
    # renderer never receives its csd.
    gops = build_gops(nals)
    if not gops:
        print("FAIL no complete GOP (SPS+PPS+IDR) in asset")
        return 1
    size = parse_sps_dimensions(gops[0][0])
    if not size:
        print("WARN SPS parse failed; fmt packets go out with zero size floats")
    else:
        print(f"SPS parsed: {size[0]}x{size[1]}")

    deadline = time.time() + args.seconds
    next_switch = time.time() + args.switch_every if args.switch_every > 0 else None
    frame_interval = 1.0 / args.fps
    sent = 0
    cseq = 2
    try:
        # Real senders emit the fmt (0x0100) packet once at stream start (or
        # on parameter-set changes) — NOT per GOP. A mid-stream SPS/PPS pair
        # re-feeding the OMX put the decoder into an error state a frame later
        # (2026-10-31: restart storm, one rendered frame per cycle), so the
        # replay loops VCL only; the codec configures from the opening fmt.
        ts_gop = ntp_micros()
        stream.sendall(mirror_packet(fmt_payload_for(gops[0][0], gops[0][1]), 0x0100, 0x0116, ts_gop, size=size))
        first_vcl_ts = ts_gop
        while time.time() < deadline:
            for _, _, gop_vcl in gops:
                if time.time() >= deadline:
                    break
                for i, (t, nal) in enumerate(gop_vcl):
                    if time.time() >= deadline:
                        break
                    ptype = 0x0010 if t == 5 else 0x0000
                    ts = first_vcl_ts if first_vcl_ts else ntp_micros()
                    first_vcl_ts = None
                    stream.sendall(mirror_packet(to_length_prefixed(nal), ptype, 0x0000, ts))
                    sent += 1
                    if next_switch and time.time() >= next_switch:
                        cseq += 1
                        _, rb = rtsp(ctl, "SETUP", cseq, body)
                        print(f"re-SETUP #{cseq} sent ({time.strftime('%H:%M:%S')})")
                        next_switch += args.switch_every
                    time.sleep(frame_interval)
    finally:
        if args.stop:
            try:
                rtsp(ctl, "TEARDOWN", 998)
            except OSError:
                pass
        try:
            stream.close()
            ctl.close()
        except OSError:
            pass
    print(f"sent {sent} VCL packets over {args.seconds:.0f}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
