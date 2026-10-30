# Device probes — machine-checkable health & acceptance (stdlib-only)

Born from the 2026-10-29 field lesson: cross-session defects were caught by a
human with an iPhone, four days in a row, because every probe lived only in
the session that wrote it. These are the frozen versions (VERIFICATION.md §10
gaps #1–#3).

| Script | Asserts | Typical use |
|---|---|---|
| `rtsp_health.py` | RTSP OPTIONS answered `200` within 2s | smoke test after install / after any session close |
| `mdns_query.py` | multicast answer mentions the receiver name | "is the device visible" (mirrors iPhone list visibility) |
| `reconnect_hammer.py` | N rounds of connect → nohold takeover → teardown → RTSP recovers within 15s | before calling any reconnect fix done; caught the httpd-thread-death class |
| `assert_session.py` | trace sessions have `frames ≥ 300`, `p95 ≤ 150ms`, ≥2 sessions (= reconnect proven) | acceptance after a live iPhone round — the phone produces, this judges |

All run on the host against the real TV (`--host 192.168.0.24`) or against an
emulator (`adb forward tcp:17000 tcp:7000` then `--host 127.0.0.1 --port 17000`).
`assert_session.py` needs `--serial` and pulls the trace via `run-as`.

Boundary: a FULL mirror session (FairPlay pair + H264/AAC stream) still needs
a physical sender; the hammer covers the protocol-stack health layer beneath
it. mDNS name check is a raw substring match (no DNS decompression); the
TXT features-bit assertion stays manual for now.

Regression loop for any AirPlay-stack change:

```bash
python scripts/probe/rtsp_health.py                 # before
python scripts/probe/reconnect_hammer.py --rounds 5 # the loop
# ...live iPhone round produces sessions...
python scripts/probe/assert_session.py --serial 192.168.0.24:5555 --since-hours 1
```
