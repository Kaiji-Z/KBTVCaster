# Version 0.6.0

**Release Date**: 2026-11-27  
**Status**: Released  
**Previous Version**: v0.5.2

## What's New

**发版形态变更：单一全功能 APK**。从本版起不再区分 default / airplay / menu 变体包——release 构建强制开启 AirPlay 镜像与播放器菜单，Releases 页只挂一个 `app-release.apk`。flag 机制保留用于 debug 构建与 CI 四态测试矩阵；`playbackPlan` 维持对照实验旗不变。

**全仓审计修复轮**（23 个提交，构建配置 / 行为修复 / 死代码 / 测试 / 脚本 / 文档）：

- **镜像会话统计修复**：`MirrorVideoRenderer` 新增会话级计数（`sessionFrames`），`mirror_session_end` 不再上报生命周期累计值——此前第二次会话起带着旧计数，`assert_session.py` 的 frames≥300 / p95<150ms 断言恒绿失效；latency 采样加上限（2 万条），`renderedFrames` 保留生命周期级供解码器恢复启发式。**待真机复验**：连续两次镜像确认第二次不再有首帧遮罩。
- **`AirPlayMirrorProtocol` PendingIntent 补 `FLAG_IMMUTABLE`**（Android 12+ 必需，此前 targetSdk 34 下缺省行为有崩溃风险）。
- **服务/通知收敛**：合并为单一通知通道；删除死 LiveData（`_deviceName`/`_rendererReady`）；MainActivity 补 STOPPED 状态显示。
- **删除旧 Kotlin AirPlay 服务端**（UxPlay 重写后遗留的 4 实现 + 3 测试文件）、原生死入口（`nativeSetH265Enabled`/`nativeServerAudioDebug`）、10 个无引用 drawable 与 8 个无引用 string。
- **测试重写**：新的 `RendererPlayerTest`（19 用例）驱动净室 DMR 真实状态机（URI 自动播放 / 命令转发序 / TransportAction / REL_TIME seek / URI 校验），替换只断言 vendored Cling 枚举的假测试；androidTest 缺真机 dump 的用例改为 Assume 跳过（不再误红）；DLNA 控制点探针只断言基线后新 trace 事件（旧事件不再误绿）。
- **构建配置**：删 jitpack/jCenter 死仓库与不存在的 proguard 规则；删未用依赖（okhttp/coroutines/viewmodel-ktx/lifecycle-service）；release 强制 `FLAG_INJECTOR_BYPASS=false`；Gradle wrapper 统一 8.14.3（修复 8.7 残缺下载导致的 `./gradlew` 失败）；CI 单测矩阵补 playerMenu 旗（四态）。
- **脚本与文档**：修损坏的 `analyze_changes.sh`、探针/构建脚本小错；AGENTS/README/VERIFICATION 对齐当前树（三 APK → 单 APK、78 用例、四态矩阵、探针工具入表）；`releases/INDEX.md` 补 v0.1.0–v0.1.2。

**验证**：78 JVM 用例 × 四态（默认 + 三旗）0 失败；injector 双态 BuildConfig 复核（release 强制 false）；原生单 ABI 编译通过；release 全量构建冒烟通过。

## Recent Commits

```
612c3a1 Align release docs with the single-APK flow
cf79514 Ship a single full-featured release APK
e680011 Unify the Gradle wrapper on 8.14.3
eaaab14 Update verification records and release index
556b1a2 Align project docs with the current tree
9aa88d0 Fix probe and build helper scripts
fc9fcf1 Fix broken analyze_changes.sh script
768c8bd Assert only new trace events in the DLNA control point probe
d168f0f Skip dump-dependent tests when the device dump is missing
2a3884c Reuse TestMediaServer in DlnaE2eTest
c737a87 Remove unused resources
93cf851 Remove unused native entry points
d00ffb2 Remove the retired Kotlin AirPlay server implementation
a67e60b Replace vendored-enum tests with RendererPlayer state machine tests
e9a4ff2 Fix stale docs and a nullable warning in the DLNA layer
728e97c Handle STOPPED status in MainActivity
c2d9ce5 Consolidate notification channels and drop dead status fields
65f2ba0 Make the mirror PendingIntent immutable
0e61b21 Count mirror session stats per session
b04de88 Track .opencode/skills in the repository
a7f7de1 Add playerMenu flag state to the CI test matrix
31015cf Tighten app build dependencies and injector handling
4ea1a72 Remove dead repositories and proguard rules
```

## Rollback

```bash
git checkout v0.5.2
```
