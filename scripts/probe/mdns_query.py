#!/usr/bin/env python3
"""mDNS visibility probe for the KBTVCaster AirPlay receiver.

Sends a multicast PTR query for _airplay._tcp.local to 224.0.0.251:5353 and
checks the answer mentions the receiver name. This is the machine-checkable
form of "iPhone can see the device in the Screen Mirroring list": if the
receiver's mDNS stack is wedged (field 2026-10-29: ENODEV after the network
interface renumbered), no answer arrives.

Deliberate simplification: the check is a raw byte-substring on the answer
payload (b"KBTVCaster"), not full DNS name decompression — enough for a
health gate; TXT/features-bit deep parsing stays manual for now.

Usage:
  python mdns_query.py [--host 224.0.0.251] [--name KBTVCaster] [--timeout 4]

Exit 0 = answer mentions the name; 2 = no answer / name absent.
"""
import argparse
import socket
import struct
import sys


def query(name: bytes, timeout: float) -> bytes:
    """One PTR query; returns the (possibly empty) answer payload."""
    pkt = b"\x00\x00\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00"  # query, RD, 1 question
    for label in (b"_airplay", b"_tcp", b"local"):
        pkt += bytes([len(label)]) + label
    pkt += b"\x00" + struct.pack(">HH", 12, 1)  # type PTR, class IN
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(timeout)
    try:
        sock.sendto(pkt, ("224.0.0.251", 5353))
        data, _ = sock.recvfrom(4096)
        return data
    finally:
        sock.close()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--name", default="KBTVCaster")
    ap.add_argument("--timeout", type=float, default=4.0)
    args = ap.parse_args()

    want = args.name.encode("ascii")
    try:
        data = query(want, args.timeout)
    except socket.timeout:
        print("FAIL no mDNS answer within timeout (receiver invisible or network blocked)")
        return 2
    except OSError as e:
        print(f"FAIL mDNS send/recv error: {type(e).__name__}: {e}")
        return 2
    if want not in data:
        print(f"FAIL mDNS answered but {args.name!r} not mentioned in {len(data)}B payload")
        return 2
    print(f"PASS mDNS answer {len(data)}B mentions {args.name!r}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
