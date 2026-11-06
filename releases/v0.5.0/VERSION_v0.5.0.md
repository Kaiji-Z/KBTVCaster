# Version 0.5.0

**Release Date**: 2026-11-06  
**Status**: Released  
**Previous Version**: v0.4.1

## What's New

<EDIT ME — summarize the changes from the commits below>

## Recent Commits

```
ffca316 Record Round 32: teardown-rebuild bomb defused, G1 machine gates green
6e5dd88 Cancel the teardown-rebuild when a new mirror session starts
d87439a Record M5: emulator dual-flag e2e reverified via injector-fed dump
ec4dcc2 Support emulator and adb-serial injection targets in mirror injector
9053996 Record injector wire-format and field facts F26-F30
7cc0d74 Record Round 31: headless injector closed loop, three session-kill fixes
5b0a7b1 Ignore Python bytecode caches under scripts
c749c34 Never restart the AirPlay stack on network churn mid-session
32d1b5a Key the init-retry pacing off per-codec-instance frames
466ab15 Auto-detach JNI-attached native callback threads on exit
d92cfe3 Add headless mirror injector probe with debug-only plaintext bypass
ffc2bbd Wait out the cold-boot MStar input refusal instead of aborting into the dead tier
d1af9e8 Record Round 30: full behavior-port inventory complete
67484f4 Port the Lebo connecting-state UX and init-retry pacing
3727acb Record Round 29: watchdog fix, G4 knob table closes extraction, init retry detail
4f66afb Ignore network registration receipts and duplicate agents in the mirror watchdog
772ad72 Record Round 28: combined surface-race and reaper landing
ff48196 Stop stale codec errors and surface swaps from pinning sessions to the buffer tier
5f64ebd Revert "Roll back the async reaper to the morning-known-good receiver"
c7067df Record the rollback decision and open forensics debt
7e97a65 Roll back the async reaper to the morning-known-good receiver
2175468 Record the v2 regression lesson in Round 27 notes
9ca9bac Keep the mirror session-start path synchronous with a generation guard on teardown
7f632f4 Record Round 27: async reaper fix, storm probe evidence, restart-chain backlog
ba430c6 Add the same-connection SETUP storm probe
c9f834d Reap retired RAOP streams asynchronously instead of joining on the httpd thread
a76ba5d Record the mirror-player tuning knob inventory from strings
60c8581 Destroy previous stream instances on re-SETUP instead of leaking them
f9bb473 Round 25: forensics base, G1 first fix, gates green
d2bdaf4 Add the clean-room facts ledger seeded with extracted history
d1f7d7c Write Phase-3 clean-room behavior-alignment spec
4c5bdfb Record the sender-freeze closing state and the clean-room extraction plan
```

## Rollback

```bash
git checkout v0.4.1
```
