# Version 0.4.1

**Release Date**: 2026-10-30  
**Status**: Released  
**Previous Version**: v0.4.0

## What's New

<EDIT ME — summarize the changes from the commits below>

## Recent Commits

```
40356db Restore INFO log level for production builds
7c68ab1 Enable raop DEBUG logging and rotate incident dumps
c3bbf17 Add headless resolution-flip repro harness and raise the dump cap to 32MB
08bb01e Revert "Recreate the decoder on real in-band resolution switches"
ae6e5fd Recreate the decoder on real in-band resolution switches
7916d13 Document the no-hammer-during-live-session rule
dc512f9 Rebuild the native stack only when the connection truly dies
0a1bc0d Collect all mDNS answers before judging receiver visibility
52a778c Run stack rebuilds off-main with a process-restart watchdog
032ef89 Add device probe toolkit and session-loop hammer
17d103a Refresh verification diagnosis for the Phase-2 field rounds
ec9657d Rebuild the native AirPlay stack after every mirror session close
30b4280 Run session callbacks off native threads and reject degenerate crop dims
33e0b78 Restart the AirPlay stack when the network interface changes
```

## Rollback

```bash
git checkout v0.4.0
```
