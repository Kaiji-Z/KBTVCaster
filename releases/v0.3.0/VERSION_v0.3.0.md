# Version 0.3.0

**Release Date**: 2026-10-18  
**Status**: Released  
**Previous Version**: v0.2.1

## What's New

### AirPlay 屏幕镜像（实验性，flag 门控）

- 内嵌 [UxPlay](https://github.com/antimof/UxPlay) 内核（C，GPLv3）并移植到 Android NDK：`libairplay_native` 经 `NativeBridge`（JNI）驱动，iOS 设备在控制中心「屏幕镜像」里直接可见、可连接
- mDNS 双路注册：NsdManager + 自带应答器（兼容 Android 6），iOS 级 TXT 特征位（含 HLS/镜像位）
- `MirrorVideoRenderer`：逐 NAL 喂入 MediaCodec 硬解渲染（模拟器自动回退软解），模拟器端到端 900 帧零丢帧、帧延迟 p95 ≈ 148ms
- 音频引擎：AAC-ELD/LC（NdkMediaCodec）+ ALAC（最小 FFmpeg 子集预编译）+ oboe 低延迟输出
- 修复：`/play` 文本请求处理（vendored llhttp 路径）、镜像会话自动把播放界面带到前台
- 默认不注册，需 `-Pkbtv.flags.airplay=true` 构建开启（Releases 附 `app-release-airplay.apk`）

### 稳定性 / 工程

- 净室重写 DMR 层：删除 GPL-2.0 来源的 `com/zxt/dlna/`，替换为 Kotlin `dlna/renderer/`，许可证切换至 **GPLv3**（审计见 `docs/LICENSE-SWITCH.md`）
- 验证体系：39 个 JVM 单测 + 7 个端到端 androidTest（DLNA 控制点模拟 + AirPlay HTTP e2e + 镜像解码路径），flag off/on 双跑
- 结构化运行记录（`trace/TraceRecorder`，JSONL）、单 ABI 构建（`-Pkbtv.build.abi`，网络 adb 安装提速）
- 待机界面重做：渐变底 + 投屏图标 + 实时服务状态胶囊
- CI：master 推送跑构建+单测（flag off/on）；`v*` tag 自动构建双 APK 挂 GitHub Release（`scripts/release.sh` 一条命令发版）

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
