#!/usr/bin/env python3
"""Session acceptance assertions over the persisted cast trace.

The machine-checkable form of the Phase-2 acceptance criteria: the iPhone
only has to PRODUCE sessions; this script reads files/trace/cast.jsonl
(pulled via run-as, persists across reboots) and judges them.

Criteria per VERIFICATION.md §10 gap #3 (proposed freeze, §8.5 pending):
  - a picture-OK session: mirror_session_end with frames >= 300 AND
    p95Micros <= 150000 (goal: p95 < 150ms)
  - reconnect-OK: at least --min-sessions picture-OK sessions in the window
    (each new session_end after a close proves a successful re-connect)

Usage:
  python assert_session.py --serial 192.168.0.24:5555 [--min-frames 300]
                           [--max-p95-us 150000] [--min-sessions 2]
                           [--since-hours 0]  # 0 = whole file

Exit 0 = criteria met; 1 = not met (report printed); 2 = trace unreachable.
"""
import argparse
import json
import subprocess
import sys
import time

TRACE_PATH = "files/trace/cast.jsonl"


def fetch_trace(serial: str) -> str:
    cmd = ["adb", "-s", serial, "shell", "run-as", "com.kbtv.caster", "cat", TRACE_PATH]
    out = subprocess.run(cmd, capture_output=True, text=True, timeout=60,
                         encoding="utf-8", errors="replace")
    if out.returncode != 0 or not out.stdout.strip():
        raise RuntimeError(f"adb pull failed ({out.returncode}): {out.stderr.strip()[:200]}")
    return out.stdout


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--serial", required=True, help="adb device serial")
    ap.add_argument("--min-frames", type=int, default=300)
    ap.add_argument("--max-p95-us", type=int, default=150000)
    ap.add_argument("--min-sessions", type=int, default=2,
                    help="picture-OK sessions required (2 = proves reconnect)")
    ap.add_argument("--since-hours", type=float, default=0.0,
                    help="only consider sessions newer than this (0 = all history)")
    args = ap.parse_args()

    try:
        raw = fetch_trace(args.serial)
    except (RuntimeError, subprocess.TimeoutExpired) as e:
        print(f"FAIL trace unreachable: {e}")
        return 2

    cutoff_ms = 0
    if args.since_hours > 0:
        cutoff_ms = int((time.time() - args.since_hours * 3600) * 1000)

    sessions = []
    for line in raw.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            ev = json.loads(line)
        except json.JSONDecodeError:
            continue
        if ev.get("event") != "mirror_session_end":
            continue
        if ev.get("ts", 0) < cutoff_ms:
            continue
        sessions.append(ev)

    if not sessions:
        print(f"FAIL no mirror_session_end events in trace "
              f"(window={'all' if cutoff_ms == 0 else f'>{args.since_hours}h'})")
        return 1

    good = []
    for ev in sessions:
        frames = ev.get("frames", 0)
        p95 = ev.get("p95Micros", 0)
        ok = frames >= args.min_frames and 0 < p95 <= args.max_p95_us
        verdict = "OK " if ok else "LOW"
        print(f"session {ev.get('sid', '?')[:8]} ts={ev.get('ts')} "
              f"frames={frames} p95={p95 / 1000:.1f}ms [{verdict}]")
        if ok:
            good.append(ev)

    if len(good) < args.min_sessions:
        print(f"RESULT FAIL {len(good)}/{args.min_sessions} picture-OK sessions "
              f"(need frames>={args.min_frames} AND p95<={args.max_p95_us / 1000:.0f}ms)")
        return 1
    print(f"RESULT PASS {len(good)} picture-OK session(s) of {len(sessions)} total")
    return 0


if __name__ == "__main__":
    sys.exit(main())
