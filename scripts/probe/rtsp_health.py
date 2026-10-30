#!/usr/bin/env python3
"""RTSP health probe for the KBTVCaster AirPlay receiver (port 7000).

Sends OPTIONS * RTSP/1.0 and asserts a 200 answer within the latency budget.
This is the smoke test for the whole native stack: when the vendored UxPlay
httpd thread dies (field 2026-10-29: thread gone after session churn), the
TCP port still accepts but NOTHING ever answers — exactly what this probe
turns into a non-zero exit.

Usage:
  python rtsp_health.py [--host 192.168.0.24] [--port 7000] [--timeout 2.0]
  # emulator / forwarded: adb forward tcp:17000 tcp:7000 first, then --host 127.0.0.1 --port 17000

Exit 0 = answered 200 within budget; 2 = connect/timeout/refused; 3 = bad status.
"""
import argparse
import socket
import sys
import time


def probe(host: str, port: int, timeout: float) -> tuple:
    """One OPTIONS round-trip. Returns (ok, detail, latency_s)."""
    req = b"OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r\n"
    start = time.time()
    sock = socket.socket()
    sock.settimeout(timeout)
    try:
        sock.connect((host, port))
        sock.sendall(req)
        data = sock.recv(256)
        latency = time.time() - start
        first = data.split(b"\r\n", 1)[0].decode("ascii", "replace")
        ok = first.startswith("RTSP/1.0 200")
        return ok, first, latency
    finally:
        sock.close()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--host", default="192.168.0.24")
    ap.add_argument("--port", type=int, default=7000)
    ap.add_argument("--timeout", type=float, default=2.0)
    args = ap.parse_args()

    try:
        ok, detail, latency = probe(args.host, args.port, args.timeout)
    except (OSError, socket.timeout) as e:
        print(f"FAIL {args.host}:{args.port} unreachable: {type(e).__name__}: {e}")
        return 2
    if not ok:
        print(f"FAIL {args.host}:{args.port} answered wrong: {detail!r}")
        return 3
    print(f"PASS {args.host}:{args.port} {detail!r} latency={latency * 1000:.0f}ms")
    return 0


if __name__ == "__main__":
    sys.exit(main())
