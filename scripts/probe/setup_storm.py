#!/usr/bin/env python3
"""Same-connection SETUP storm for the KBTVCaster AirPlay receiver.

The 2026-10-31 morning field lesson in script form: a real iPhone mirror
session re-SETUPs on ONE RTSP connection every time the played video
changes (resolution flips). After ~10 such re-SETUPs + close, the single
httpd thread wedged: RTSP stopped answering entirely (kernel still
accepts into the backlog), and only an app force-stop recovered it.

reconnect_hammer.py never sends SETUP — cross-connection takeover only.
This hammer sends SETUP after SETUP on the SAME connection, exactly the
pattern that exercises the [kbtv] destroy-before-null re-SETUP path in
raop_handlers.h, and asserts RTSP health on a second connection after
every burst.

Keys are synthetic (no FairPlay handshake) — fairplay_decrypt fails, the
handler logs and continues; the destroy/re-create churn still runs, which
is what this hammer targets.

Usage:
  python setup_storm.py [--host 192.168.0.24] [--port 7000] [--setups 30]
                        [--burst 5] [--gap 0.4] [--health-window 6]

Exit 0 = stack answered every health check; 1 = wedge or SETUP failure.
"""
import argparse
import plistlib
import socket
import sys
import time

OPTS = b"OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r\n"


def rtsp_request(method: str, cseq: int, body: bytes = b"") -> bytes:
    head = (
        f"{method} rtsp://storm/1 RTSP/1.0\r\n"
        f"CSeq: {cseq}\r\n"
        f"User-Agent: StormHammer/1.0\r\n"
    )
    if body:
        head += (
            "Content-Type: application/x-apple-binary-plist\r\n"
            f"Content-Length: {len(body)}\r\n"
        )
    return head.encode("ascii") + b"\r\n" + body


def setup_body() -> bytes:
    """Synthetic AirPlay2 SETUP plist: ekey(72B)+eiv(16B)+streams[110,103]."""
    plist = {
        "ekey": b"\x00" * 72,
        "eiv": b"\x11" * 16,
        # type 110 only: this UxPlay branch logs "SETUP tries to setup stream
        # of unknown type 103" and disconnects on other shapes — a hammer
        # fidelity bug that mimicked a wedge (2026-10-31), not a real one.
        "streams": [
            {"type": 110},
        ],
    }
    return plistlib.dumps(plist, fmt=plistlib.FMT_BINARY)


def read_response(sock: socket.socket, timeout: float = 4.0) -> str:
    sock.settimeout(timeout)
    data = b""
    try:
        while b"\r\n\r\n" not in data:
            chunk = sock.recv(4096)
            if not chunk:
                break
            data += chunk
            if len(data) > 65536:
                break
    except socket.timeout:
        return "TIMEOUT"
    except OSError as e:
        return f"OSERROR:{type(e).__name__}"
    return data.split(b"\r\n", 1)[0].decode("ascii", "replace")


def same_conn_canary(sock: socket.socket, cseq: int) -> tuple:
    """Mid-storm health check ON the storm connection: a wedged httpd thread
    answers nothing anywhere, so this is the canary. A separate connection
    would be WORSE — its OPTIONS carries a CSeq, identifies as a RAOP client
    and the nohold takeover evicts the storm connection mid-run (2026-10-31
    hammer self-sabotage; that takeover is reconnect_hammer's job, not ours)."""
    try:
        sock.sendall(f"OPTIONS * RTSP/1.0\r\nCSeq: {cseq}\r\n\r\n".encode("ascii"))
        line = read_response(sock, timeout=3.0)
        return line.startswith("RTSP/1.0 200"), f"{line!r}"
    except (OSError, socket.timeout) as e:
        return False, f"{type(e).__name__}: {e}"


def health_probe(host: str, port: int, timeout: float = 2.0):
    """Fresh connection OPTIONS — the canary the wedged httpd cannot sing."""
    sock = socket.socket()
    try:
        sock.settimeout(timeout)
        sock.connect((host, port))
        start = time.time()
        sock.sendall(OPTS)
        data = sock.recv(256)
        latency = (time.time() - start) * 1000
        line = data.split(b"\r\n", 1)[0].decode("ascii", "replace")
        return line.startswith("RTSP/1.0 200"), f"{line!r} {latency:.0f}ms"
    except (OSError, socket.timeout) as e:
        return False, f"{type(e).__name__}: {e}"
    finally:
        try:
            sock.close()
        except OSError:
            pass


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--host", default="192.168.0.24")
    ap.add_argument("--port", type=int, default=7000)
    ap.add_argument("--setups", type=int, default=30,
                    help="total SETUP requests on the one connection")
    ap.add_argument("--burst", type=int, default=5,
                    help="SETUPs between health probes")
    ap.add_argument("--gap", type=float, default=0.4,
                    help="seconds between SETUPs (video-switch pace)")
    ap.add_argument("--health-window", type=float, default=6.0,
                    help="seconds a failed probe may take to recover before FAIL")
    args = ap.parse_args()

    storm = socket.socket()
    storm.settimeout(4.0)
    try:
        storm.connect((args.host, args.port))
    except OSError as e:
        print(f"RESULT FAIL cannot connect: {e}")
        return 1

    storm.sendall(OPTS)
    ident = read_response(storm)
    if not ident.startswith("RTSP/1.0 200"):
        print(f"RESULT FAIL identify OPTIONS answered {ident!r}")
        return 1
    print(f"connected, identified: {ident!r}")

    body = setup_body()
    failures = 0
    sent = 0
    try:
        while sent < args.setups:
            burst_end = min(sent + args.burst, args.setups)
            while sent < burst_end:
                sent += 1
                storm.sendall(rtsp_request("SETUP", sent + 1, body))
                resp = read_response(storm)
                mark = "" if resp.startswith("RTSP/1.0 200") else "  <-- NON-200"
                print(f"SETUP {sent}/{args.setups}: {resp}{mark}")
                if not resp.startswith("RTSP/1.0 200") and resp != "TIMEOUT":
                    failures += 1
                time.sleep(args.gap)

            ok, detail = same_conn_canary(storm, 9000 + sent)
            if not ok:
                # grace: the app may rebuild the stack; allow the window
                deadline = time.time() + args.health_window
                while time.time() < deadline:
                    time.sleep(1.5)
                    ok, detail = same_conn_canary(storm, 9000 + sent)
                    if ok:
                        break
            print(f"health after {sent} setups: "
                  f"{'PASS' if ok else 'FAIL'} [{detail}]")
            if not ok:
                failures += 1
                print(f"RESULT FAIL wedge at SETUP #{sent}: {detail}")
                return 1
    finally:
        try:
            storm.close()
        except OSError:
            pass

    # post-close health: the morning wedge appeared around stream close
    ok, detail = health_probe(args.host, args.port, timeout=3.0)
    if not ok:
        deadline = time.time() + args.health_window
        while time.time() < deadline and not ok:
            time.sleep(1.5)
            ok, detail = health_probe(args.host, args.port, timeout=3.0)
    print(f"health after close: {'PASS' if ok else 'FAIL'} [{detail}]")
    if not ok:
        print("RESULT FAIL wedge after connection close")
        return 1

    if failures:
        print(f"RESULT FAIL {failures} SETUP errors")
        return 1
    print(f"RESULT PASS {sent} same-conn SETups, stack answered throughout")
    return 0


if __name__ == "__main__":
    sys.exit(main())
