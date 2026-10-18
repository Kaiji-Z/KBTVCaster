# Version 0.3.0

**Release Date**: 2026-10-18  
**Status**: Released  
**Previous Version**: v0.2.1

## What's New

<EDIT ME — summarize the changes from the commits below>

## Recent Commits

```
3b278e2 Avoid SIGPIPE in release.sh changelog scaffold
a0e1f88 Update AGENTS charter for the flag-gated AirPlay mirror
0e45e7f Modernize kbtv-versioning skill
7cc9ab4 Add tag-driven release pipeline
2a55f80 Rewrite README for the AirPlay mirror era
47fcf06 Redesign the standby screen
5614ca7 Bring the UI forward when a mirror session starts
68390e0 Record phase 2 round 24: real TV mirror stack and TXT bits verified
740ed5f Enable hls before nativeStart so video feature bits reach the TXT
a95e085 Support single-ABI APK builds for slow network installs
fb02c6f Record phase 2 round 23: real audio engine in, TV off-limits while in use
f12764e Wire AirPlay audio lifecycle into the mirror protocol
75ea5c2 Build real audio engine with oboe and prebuilt ffmpeg ALAC
856f8a0 Record phase 2 round 22: mirror render path green on emulator
38d4a62 Wire mirror renderer into protocol, coordinator and standby UI
3182b43 Add MirrorVideoRenderer decoding the mirror stream to a surface
3be64c0 Record phase 2 rounds 19-21: URL-push e2e green on emulator
e1f47c2 Wire AirPlay mirror protocol onto the native kernel with URL-push e2e
aca2966 Fix text-body play handling and add play-path probes in vendored UxPlay
70dea63 Add x86 ABI prebuilt for the 32-bit TV emulator
cd6b4ad Record phase 2 rounds 17-18: native kernel builds, milestone M1 done
7cf126e Build native kernel into app with prebuilt OpenSSL imports
06051c9 Vendor UxPlay kernel, libplist and Android port bridge (GPLv3)
805a1a1 Wire UxPlay native kernel into build with prebuilt-openssl pivot
73ff489 Record round 16: Baidu criterion achieved, iOS visibility fixes
77520d2 Ship own mDNS responder for pre-N devices and iOS-grade TXT records
2216f22 Record round 15: field-diagnosed Baidu push rejection and iOS invisibility, fixes on TV
e9795ac Fix AirPlay mDNS TXT records for iOS visibility
ea1f6b3 Advertise DLNA profiles and wildcard in SinkProtocolInfo
e750c70 Record round 14: real-TV full-chain verification
017aadf Harden decode-path test: csd for OMX-era decoders, feed deadline, byType on real devices
eb8d466 Record real-TV network recon: [vendor] [VendorCast] built in, old build alive, SSDP rhythm evidence
2029fd6 Update README architecture and license notes for renderer rewrite
a4eb383 Record rounds 12-13 in goal ledger
5a30760 Document clean-room rewrite outcome: AGENTS.md, license audit, layer report
91cf74a Replace GPL-derived com/zxt DMR with clean-room Kotlin renderer
09d6f41 Add [LinkX] three-gate decision framework and capture SOP
bd38a30 Add mirror decode-path e2e test (900-frame canned H.264, p95 latency probe)
d7ea7ca Make DLNA e2e verification emulator-capable and fix Cling on modern Android
fcda5fd Add verification foundation and AirPlay push receiver behind flags
```

## Rollback

```bash
git checkout v0.2.1
```
