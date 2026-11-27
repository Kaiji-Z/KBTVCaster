# AGENTS.md - KBTVCaster

**Updated:** 2026-11-27 | **Branch:** master

Android TV **投屏接收端**：DLNA（基于 Cling UPnP 栈的 MediaRenderer，接收手机视频 App 推送的媒体 URL，用 Media3 ExoPlayer 播放）+ AirPlay 屏幕镜像（UxPlay 内核，实验性，flag 门控）。前台服务 + 开机自启。

**DLNA 投屏稳定；AirPlay 镜像实验性**（`-Pkbtv.flags.airplay=true` 构建，详见 README「关于屏幕镜像」）。架构上以 `CastProtocol` 接口承载两种协议，未来协议可插拔。

## MANDATORY PROTOCOL

开发任何功能、改任何代码之前，先读并遵循项目根目录的 [VERIFICATION.md](VERIFICATION.md)。它承载本项目的 §8 参数（验收标准、工具链落地、flag 方案）——只在本仓库里改它，永远不要改全局 skill 目录。违反 VERIFICATION.md §7 红线的产出视为无效。

## BEHAVIORAL RULES

**Think before coding.** State assumptions. If confused, stop and ask. Surface tradeoffs.
**Simplicity first.** Minimum code that solves the problem. No speculative abstractions.
**Surgical changes.** Touch only what you must. Match existing style. Every changed line traces to the request.
**Goal-driven.** Define success criteria as verifiable outcomes. Loop until proven.

## QUICK REFERENCE

```bash
./gradlew assembleDebug                                    # Debug APK
./gradlew assembleRelease                                  # Release APK（无签名配置时为未签名包；CI 用 secrets 签名）
./gradlew test                                             # All unit tests
./gradlew test --tests "com.kbtv.caster.dlna.*"           # DLNA tests only
./gradlew lint                                             # Lint (non-blocking, informational)
adb logcat -s "CastCoordinator:*" "Timber:*" "KbtvNet:*"  # Debug log filter
```

**Build:** AGP 8.5.0, Kotlin 1.9.24, Gradle 8.14.3 (wrapper), JDK 11+, compileSdk 34, minSdk 23.

## ARCHITECTURE

```
Phone (DLNA control point — B站/腾讯/优酷等 App)
    │  UPnP over SSDP/HTTP
    ▼
CastCoordinatorService (foreground, START_STICKY)  ── implements CastProtocol.PlaybackSink
  │
  ├── List<CastProtocol>
  │   └── DlnaProtocol : CastProtocol, DmrPlaybackListener
  │         └── DlnaBootstrap (Kotlin object) → bind KbtvUpnpServiceImpl (Cling + emulator/net config)
  │               └── RendererDevice assembles LocalDevice (AVTransport + RenderingControl + ConnectionManager)
  │                     └── RendererPlayer forwards UPnP commands → DmrPlaybackListener → DlnaProtocol
  │                           └── onCommand(Play/Pause/Stop/Seek) → CastCoordinatorService
  └── ExoPlayer (service-owned single instance; receives PlaybackCommands, renders to Surface)

UI: MainActivity binds service via LocalBinder → getExoPlayer() → PlayerView
Boot: BootReceiver → CastCoordinatorService(ACTION_START) after 5s Handler.postDelayed
```

**协议扩展点**：新增投屏协议（如未来手机镜像）只需实现 `CastProtocol`，加入 `CastCoordinatorService.protocols` 列表。协议只负责发现/接收命令并转发 `PlaybackCommand`，不触碰 ExoPlayer。

**Design decisions (non-obvious):**
- `kotlin.incremental=false` — Cling vendored 源码编译需要它
- `usesCleartextTraffic=true` — DLNA/UPnP 需要明文 HTTP
- UPnP 描述符 friendlyName = ASCII `"KBTVCaster"`（`dlna/DlnaBootstrap.kt`，挑剔控制点对非 ASCII 失败）；UI/通知显示名仍为 resources 的「凯机投屏」
- 无 DI 框架 —— 单例 via companion object DCL pattern
- 无 ViewModel —— Activity 直接 bind service，取 ExoPlayer

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| Start/stop protocols | `service/CastCoordinatorService.kt` | `start()`/`stop()` 遍历 `protocols` 列表 |
| Add a new protocol | `service/protocol/CastProtocol.kt` + 新实现 | 实现 `start()/stop()`，命令经 `PlaybackSink` 转发 |
| Change DLNA device name | `dlna/DlnaBootstrap.kt` `FRIENDLY_NAME` | ASCII only（挑剔控制点兼容）；UI 显示名走 resources |
| Add DLNA command | `dlna/renderer/RendererTransport.kt` | 继承 Cling `AbstractAVTransportService`，动作映射到 `RendererPlayer` |
| Playback command handling | `service/CastCoordinatorService.kt` `onCommand()` / `handlePlay()` | Baidu UA + 前台化在此 |
| HLS detection | `service/protocol/HlsDetection.kt` | 纯函数 `isHlsStream()`，从 handlePlay 抽出以便单测 |
| UI / remote keys | `ui/MainActivity.kt` | D-pad 媒体键，PlayerView 生命周期 |
| Boot auto-start | `receiver/BootReceiver.kt` | BOOT_COMPLETED + QUICKBOOT_POWERON + REBOOT |
| Notification channels | `CasterApplication.kt` | 创建 `caster_service` |
| UPnP service registration | `AndroidManifest.xml` → `dlna/KbtvUpnpServiceConfiguration.kt`（类 `KbtvUpnpServiceImpl` 定义于此） | Cling 服务 + 网络/模拟器配置层，not exported |
| Playback routing decision | `service/protocol/PlaybackPlan.kt` | 纯函数 HLS/Baidu-UA 决策，`kbtv.flags.playbackPlan` 门控 |
| AirPlay mirror session | `airplay/AirPlayMirrorProtocol.kt` | 原生栈生命周期、网络重看门狗、会话状态机（flag 门控） |
| Versioning | `.opencode/skills/kbtv-versioning/` | Changelog 模板 + 脚本。Releases in `releases/` |

## STRUCTURE

```
app/src/main/java/
├── com/kbtv/caster/              # App code (Kotlin)
│   ├── CasterApplication.kt      # 入口：Timber(debug only)、通知 channel、SAX driver
│   ├── service/
│   │   ├── CastCoordinatorService.kt  # 前台服务，持有 ExoPlayer + protocols 列表
│   │   └── protocol/
│   │       ├── CastProtocol.kt   # 协议抽象接口
│   │       ├── DlnaProtocol.kt   # DLNA 实现，桥接 DlnaBootstrap
│   │       ├── HlsDetection.kt   # 纯函数：isHlsStream(uri) HLS 检测
│   │       ├── PlaybackPlan.kt   # 纯函数路由决策（kbtv.flags.playbackPlan 门控）
│   │       └── FeatureFlags.kt   # BuildConfig 旗的运行时入口
│   ├── receiver/BootReceiver.kt  # 开机自启
│   ├── ui/
│   │   ├── MainActivity.kt       # 主界面 + 遥控器 + 镜像 SurfaceView
│   │   └── PlayerMenu.kt         # 播放器菜单纯状态机（kbtv.flags.playerMenu 门控）
│   ├── airplay/                  # AirPlay 镜像（flag 门控 kbtv.flags.airplay）：
│   │                             #   MirrorProtocol(原生栈) + mDNS 应答器 + 渲染/预热/GL
│   ├── trace/TraceRecorder.kt    # JSONL 结构化运行记录（debug 写 files/trace/）
│   └── dlna/
│       ├── DlnaBootstrap.kt      # 绑定 Cling 服务，注册 RendererDevice，UDN/descriptor trace 事件
│       ├── KbtvUpnpServiceConfiguration.kt # Cling 服务配置 + KbtvUpnpServiceImpl 入口（模拟器网络容错）
│       └── renderer/             # 净室 DMR：RendererDevice/Transport/Control/Player/ConnectionManager (Kotlin)
├── io/github/jqssun/airplay/bridge/ # JNI 桥：NativeBridge external 声明 + 回调/日志接口
├── org/fourthline/cling/         # EMBEDDED: Cling UPnP (533 Java files) — DO NOT MODIFY
app/libs/                         # JARs: Jetty 8.1, Cling support, seamless, HTTP, servlet（javax.annotation 为 compileOnly）
app/src/main/cpp/                 # NDK: native_bridge + 音频引擎 + vendored UxPlay/libplist + 预编译 openssl/ffmpeg
app/src/test/                     # JVM 单元测试
app/src/androidTest/              # e2e/真机探针 + controlpoint/ DLNA 控制点模拟器
```

## CONVENTIONS

**Only what differs from Kotlin/Android defaults:**

```kotlin
// Logging: Timber everywhere (Kotlin-only app code)
Timber.d("state: $value")                    // String interpolation, never concatenation
Timber.e(e, "Failed to initialize")          // Throwable as 2nd arg

// Error handling: catch+log, never re-throw
try { risky() } catch (e: Exception) { Timber.e(e, "desc"); /* fallback or return */ }

// Safe calls preferred — force unwrap !! is an anti-pattern
val player = exoPlayer ?: return

// State exposure: _ backing field + public readonly
private val _status = MutableLiveData(Status.STOPPED)
val status: LiveData<Status> = _status
```

| Element | Pattern | Example |
|---------|---------|---------|
| Constants | `UPPER_SNAKE_CASE` in companion object | `ACTION_START`, `NOTIFICATION_ID` |
| LiveData/StateFlow backing | `_` prefix | `_serviceStatus` |
| Test methods | Backticks | `` `should handle play command` `` |
| TAG constant | Vestigial `private const val TAG = "..."` | Unused — Timber auto-tags. Safe to ignore. |

**Import order:** `android.*` → `androidx.*` → `com.google.*` → `timber.log.Timber` → `kotlinx.*` → `java.*` → `org.fourthline.cling.*` → `com.kbtv.caster.*`

**Mixed-language logging:** Chinese messages common in DLNA layer (e.g. `"DLNA服务已启动"`). Match surrounding file's language.

## GOTCHAS

| Issue | Status | Resolution |
|-------|--------|------------|
| **Notification channel mismatch** | ✅ Fixed (16de7b4) | `castertv_channel` → `caster_service` in CastCoordinatorService.kt |
| **BootReceiver blocks** | ✅ Fixed (3ebdb99) | `Thread.sleep(5000)` → `Handler.postDelayed({}, 5000)` |
| **versionName stale** | ✅ Fixed (5dcb091) | `0.1.1` → `0.2.1` |
| Build artifacts in git | ✅ Fixed (11f5da8) | `.gitignore` fixed |
| **Foreground service crash on boot** | ✅ Fixed (1cfebf6) | `startForeground()` moved to top of `start()`; `restartHandler` callbacks cleared in `stop()` |
| **DLNAUtils ServiceConnection leak** | ✅ Fixed (7b2082f) | ServiceConnection stored as field; `unbindService()` in stop。语义保留于 `DlnaBootstrap.stop()` |
| **MainActivity ExoPlayer listener leak** | ✅ Fixed (c01251a) | Listener stored as field; `removeListener()` in `onDestroy()`; `playerView.player = null` |
| **Action string package mismatch** | ✅ Fixed (1cfebf6) | `com.caster.tv.action.*` → `com.kbtv.caster.action.*` |
| **ExoPlayer release not resilient** | ✅ Fixed (1cfebf6) | Separate try/catch for `stop()` and `release()`; always null out |
| **ZxtMediaRenderer LastChange thread leak** | ✅ Fixed (68ba432)，后经净室重写 | `volatile` 停机旗标语义保留于 `RendererDevice.shutdown()` |
| Cling is vendored source, not Maven | By design | `org/fourthline/cling/` (533 files). Cling never published to Maven Central; treat as read-only vendor code |
| `com/zxt/dlna/**` removed | ✅ Clean-room rewritten (Kotlin `dlna/renderer/`) | GPL-2.0 风险消除，见 `docs/LICENSE-SWITCH.md` |
| ~~No instrumented tests~~ | ✅ Resolved | `app/src/androidTest/` 10 个 e2e/探针文件（DLNA 控制点链、镜像解码/GL/回放、解码器 AB、音频管线）；espresso 依赖仍在但未引用 |
| `kotlin.incremental=false` | By design | Cling source compilation requires it; slows full rebuild |

## TESTING

```kotlin
@RunWith(MockitoJUnitRunner::class)
class FooTest {
    @Mock lateinit var mock: Bar

    @Before fun setup() { /* ... */ }

    @Test
    fun `should handle play command`() {
        `when`(mock.state).thenReturn(IDLE)
        assertEquals(IDLE, systemUnderTest.state)
    }
}
```

- **Framework:** JUnit 4 + Mockito 5 + mockito-kotlin
- **Naming:** Backtick descriptive names
- **No Robolectric, no Espresso** — pure JVM unit tests only
- **Test files:** `dlna/renderer/RendererPlayerTest.kt`, `service/protocol/CastProtocolTest.kt`, `service/protocol/DlnaProtocolTest.kt`, `service/protocol/FeatureFlagsTest.kt`, `service/protocol/HlsDetectionTest.kt`, `service/protocol/PlaybackPlanTest.kt`, `trace/TraceRecorderTest.kt`, `ui/PlayerMenuTest.kt`
- **androidTest:** `e2e/` 10 文件（DlnaE2eTest、AirPlayE2eTest、镜像解码/渲染/GL/回放链、DecoderAbTest、FlipStreamReproTest、AudioPipelineProbeTest、TestMediaServer 助手）；依赖真机 mirror dump 的用例缺 dump 时 Assume 跳过

## VERIFICATION STATUS（2026-10-30 复审，详见 [VERIFICATION.md](VERIFICATION.md) §10）

- **ACI 审计（2026-10-30）**：§2.1 无 UI 运行 ✅ 组件级达标（DLNA 全链 headless + AirPlay 组件级探针；完整镜像会话仍需物理 iPhone——FairPlay 配对无脚本驱动端，架构边界）；§2.2 状态记录 ✅ 达标（TraceRecorder JSONL 持久化跨重启 + 25 处 mirror_* 事件 + 原生 [kbtv] 计数器，2026-10-12 为 △）；§2.3 程序化接口 ✅ 达标（trace 拉取 / nativeServerAudioStats / RTSP·mDNS 探针）
- **测试基建**：回归 `./gradlew test`（78 JVM 用例 × 四态：默认 + 三旗，见 CI）；instrumented 10 文件（9 测试类 + TestMediaServer 助手；e2e：DLNA 控制点链、镜像解码路径、解码器 AB、GL 绘制、音频管线探针——真机实测可跑，依赖真机 dump 的用例缺文件时 Assume 跳过）；断言 JUnit 4 + mockito-kotlin；无 supervisor（开发者裁定 2026-10-12：纯确定性断言）；三生产 flag（airplay / playbackPlan / playerMenu）+ CI 四态矩阵（默认 + 三旗，2026-11-27 起 playerMenu 入矩阵）；debug-only 注入旗 `kbtv.flags.injector`（release 构建强制关闭）
- **验收标准**：Phase-1 已冻结于 §8.5（2026-10-12）；Phase-2 镜像验收（≥300 帧 / p95<150ms / 断开重连）拟冻结 + 回环锤验收项——**待开发者确认（§8.6 PENDING 行）**

**验证积压（2026-10-30 复审，按优先级）：**

| # | 项 | 级别 | 状态 |
|---|---|---|---|
| 1 | 会话回环压测（连接→拆→重连 N 轮 + RTSP 应答断言）——2026-10-29 httpd 线程死亡类缺陷全靠用户真机暴露 | **P0** | ✅ 已落地（2026-10-30：scripts/probe/ 四工具 + setup_storm/mirror_inject，详见 VERIFICATION.md §10） |
| 2 | 探针工具入库（RTSP/mDNS/回环锤，scripts/probe/，stdlib-only） | **P0** | ✅ 已落地（rtsp_health / mdns_query / reconnect_hammer / setup_storm / mirror_inject + README 纪律） |
| 3 | 真机验收断言固化（assert_session.py：cast.jsonl 断言 frames≥300 / p95<150ms / 连续会话） | **P0** | ✅ 已落地（assert_session.py；Phase-2 标准冻结仍待开发者确认，§8.6 PENDING） |
| 4 | 模拟器双旗 e2e 复验（上次全绿 2026-10-13，此后镜像层 ~30 commits 未复验） | P1 | 待修复（M5 回归轮） |
| 5 | GPLv3 合规文档（vendored UxPlay 补丁的 LICENSE-SWITCH.md 审计行 + NOTICE；v0.4.0 已带补丁发布；`cpp/patches/` 与树内改动已脱节，见 VERIFICATION §10 gap #5） | P1 | 待修复 |
| 6 | 冷启动解码器解锁（乐播 init 序列 clean-room 提取） | P1 | 待修复（需真机对照，Phase 3 G2） |
| 7 | lint 升级为阻塞门禁 | P2 | 可选 |
| 8 | VDEC 异常释放泄漏根因（已有栈重建+冷却探针兜底） | P2 | 可选 |
| — | 历史项 1-7（androidTest 建立/结构化 trace/验收冻结/flag 机制/PlaybackPlan/LLM 裁定/CI） | — | ✅ 全部完成（2026-10-13~18 落地，详见 git log 与 VERIFICATION.md §8） |

## GIT

**Atomic commits.** One logical change per commit. Each commit must build and be independently revertible.

**Commit message style:** Plain English imperative. No semantic prefixes (`feat:`, `fix:`).

```
Add .gitignore to ignore build artifacts          ✅
Rename project to KBTVCaster (com.caster.tv → …)  ✅
feat: add new DLNA handler                         ❌ don't use prefix
update stuff                                       ❌ too vague
```

**Rules:**
- 3+ files changed → split into 2+ commits (by module, by concern)
- Test + implementation go in the same commit
- Never commit `.gradle/`, `app/build/`, or `*.apk` — these are build artifacts
- Never commit `temp/` — working notes only
- `kotlin.incremental=false` means Cling source changes require full recompile — note in commit if touching `org/fourthline/cling/`

## NOTES

- **License**: GPLv3（2026-10-13 自 MIT 切换）。审计在 `docs/LICENSE-SWITCH.md`。`com/zxt/dlna/**`（GPL-2.0 来源）已净室重写为 Kotlin `dlna/renderer/` 并删除——分发门禁解除（发布仍需用户确认）。

- **Embedded library** (`org.fourthline.cling.*`): Source files, not Maven deps. Treat as read-only vendor code. 533 Java files.
- **`app/libs/`**: JARs (Jetty 8.1.9, Cling support 2.0.1, seamless 1.0-alpha2, Apache HTTP). Supply Cling's transitive deps.
- **Release process**: Use `.opencode/skills/kbtv-versioning/` skill. Changelogs in `releases/` (index: `releases/INDEX.md`). Releases are tagged `v<version>` (existing: v0.1.0–v0.5.2).
- **Lint**: Non-blocking (`abortOnError=false`). Informational only.
- **`temp/` directory**: Working notes and scripts. In `.gitignore`.
