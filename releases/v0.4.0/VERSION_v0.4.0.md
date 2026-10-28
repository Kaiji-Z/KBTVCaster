# Version 0.4.0

**Release Date**: 2026-10-28  
**Status**: Released  
**Previous Version**: v0.3.0

## What's New

<EDIT ME — summarize the changes from the commits below>

## Recent Commits

```
3ee623d Fall back to a bundled synthetic stream for the decoder warmup
7b057d9 Add armeabi-v7a prebuilts and ABI target for 32-bit TVs
fa8dbcc Drive letterbox from decoder output format and split audio probe counters
819222d Debounce telemetry resolution flapping before tearing down the codec
70ec92f Warm the MStar decoder at protocol start to survive cold-boot refusal
2c41f9d Back off decoder restarts to one probe per minute when budget exhausts
fd06837 Actually retry GL twice before blit fallback (prior commit lost its own edit)
b820d12 Configure audio engine on live path and skip unfilled resend holes
e81d376 Close mirror session on video pause silence and probe the audio sync gate
56542ea End mirror session on connection close/reset and tighten idle reap to 10s
74cf00a Retry GL twice before falling back to the never-outputting blit tier
e9154fe Degrade to blit on any zero-frame surface-mode failure
0eb2ed9 Extend mirror idle timeout to 30s and reopen on lock-screen resume
3a22e16 Make mirror idle-watchdog timestamp volatile across callback threads
d4b1977 Add structure assertion and resolution matrix to decoder tests
2e2bfe8 Close mirror session after 10s idle and harden audio stats timer
9e16099 Add output-stall watchdog, blit degrade, and restart budget refresh
059a23b Fix GL display compositing: UV byte offset, opaque alpha, pre-sized texture, letterbox
c044e53 Ignore local scratch directories
23ddbf2 Rebuild prebuilt OpenSSL without assembly
adf12f3 Fix NTP thread leak and reject unknown FairPlay types in vendored UxPlay
13cfb04 Decode mirror audio in software via ffmpeg and add engine diagnostics
b0359fa Render mirror video through a GL forward tier on the MStar field TV
```

## Rollback

```bash
git checkout v0.3.0
```
