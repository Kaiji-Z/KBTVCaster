#!/usr/bin/env python3
"""Session-loop hammer for the KBTVCaster AirPlay receiver.

The 2026-10-29 field lesson in script form: cross-session defects (httpd
thread death, network-stack staleness) never show in a single session —
they need repeated connect/takeover/teardown cycles, which until now only
a human with an iPhone produced.

Each round, WITHOUT any iPhone:
  1. connection A sends OPTIONS (gets identified as the RAOP client)
  2. connection B sends OPTIONS while A is open — exercises the nohold
     takeover path (A is evicted -> conn_destroy -> session close ->
     5s debounce -> full native-stack rebuild in the app)
  3. after the rebuild window, asserts RTSP answers 200 again

Full FairPlay mirror sessions still need a real sender; this hammer covers
the protocol-stack health layer that burned four field days.

Usage:
  python reconnect_hammer.py [--host 192.168.0.24] [--port 7000] [--rounds 5]
                             [--interval 12] [--rebuild-window 15]

Exit 0 = every round recovered; 1 = at least one round failed.
"""
import argparse
import socket
import sys
import time

OPTS = b"OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r\n"


def one_options(host: str, port: int, timeout: float = 3.0):
    """Open a connection, send OPTIONS, return (socket, status_line)."""
    sock = socket.socket()
    sock.settimeout(timeout)
    sock.connect((host, port))
    sock.sendall(OPTS)
    data = sock.recv(256)
    return sock, data.split(b"\r\n", 1)[0].decode("ascii", "replace")


def takeover_round(host: str, port: int) -> tuple:
    """Steps 1-2: A identified, B takes over. Returns (ok, detail)."""
    a = b = None
    try:
        a, st_a = one_options(host, port)
        if not st_a.startswith("RTSP/1.0 200"):
            return False, f"conn A answered {st_a!r}"
        b, st_b = one_options(host, port)
        if not st_b.startswith("RTSP/1.0 200"):
            return False, f"conn B takeover answered {st_b!r}"
        return True, f"A={st_a!r} B={st_b!r}"
    finally:
        for s in (a, b):
            try:
                if s:
                    s.close()
            except OSError:
                pass


def health_with_retry(host: str, port: int, window: float) -> tuple:
    """Step 3: require a 200 answer (latency < 2s) within the rebuild window."""
    deadline = time.time() + window
    last = "no attempt"
    while time.time() < deadline:
        sock = socket.socket()
        sock.settimeout(2.0)
        try:
            sock.connect((host, port))
            start = time.time()
            sock.sendall(OPTS)
            data = sock.recv(256)
            latency = time.time() - start
            last = data.split(b"\r\n", 1)[0].decode("ascii", "replace")
            if last.startswith("RTSP/1.0 200") and latency < 2.0:
                return True, f"{last!r} {latency * 1000:.0f}ms"
        except (OSError, socket.timeout) as e:
            last = f"{type(e).__name__}: {e}"
        finally:
            try:
                sock.close()
            except OSError:
                pass
        time.sleep(1.5)
    return False, last


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--host", default="192.168.0.24")
    ap.add_argument("--port", type=int, default=7000)
    ap.add_argument("--rounds", type=int, default=5)
    ap.add_argument("--interval", type=float, default=12.0,
                    help="seconds between rounds (must exceed the app's 5s rebuild debounce)")
    ap.add_argument("--rebuild-window", type=float, default=15.0,
                    help="max seconds RTSP may take to answer again after a round")
    args = ap.parse_args()

    failures = 0
    for i in range(1, args.rounds + 1):
        ok, detail = takeover_round(args.host, args.port)
        if not ok:
            print(f"round {i}/{args.rounds} TAKEOVER FAIL: {detail}")
            failures += 1
            continue
        ok2, detail2 = health_with_retry(args.host, args.port, args.rebuild_window)
        mark = "PASS" if ok2 else "FAIL"
        print(f"round {i}/{args.rounds} {mark} takeover[{detail}] recovery[{detail2}]")
        if not ok2:
            failures += 1
        if i < args.rounds:
            time.sleep(args.interval)

    if failures:
        print(f"RESULT FAIL {failures}/{args.rounds} rounds failed")
        return 1
    print(f"RESULT PASS {args.rounds}/{args.rounds} rounds recovered")
    return 0


if __name__ == "__main__":
    sys.exit(main())
