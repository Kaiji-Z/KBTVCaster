# AGENTS.md - KBTVCaster

**Updated:** 2026-10-13 | **Branch:** master

Android TV **DLNA 投屏接收端**：基于 Cling UPnP 栈的 MediaRenderer，接收手机视频 App 推送的媒体 URL，用 Media3 ExoPlayer 播放。前台服务 + 开机自启。

**只做 DLNA 媒体投屏，不做屏幕镜像。** 详见 README「关于屏幕镜像」。架构上已抽象 `CastProtocol` 接口，未来镜像协议可插拔。

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
./gradlew assembleRelease                                  # Release APK (no signing config)
./gradlew test                                             # All unit tests
./gradlew test --tests "com.kbtv.caster.dlna.*"           # DLNA tests only
./gradlew lint                                             # Lint (non-blocking, informational)
adb logcat -s "DLNAUtils:*" "CastCoordinator:*" "Timber:*" # Debug log filter
```

**Build:** AGP 8.5.0, Kotlin 1.9.24, Gradle 8.7, JDK 11+, compileSdk 34, minSdk 23.

## ARCHITECTURE

```
Phone (DLNA control point — B站/腾讯/优酷等 App)
    │  UPnP over SSDP/HTTP
    ▼
CastCoordinatorService (foreground, START_STICKY)  ── implements CastProtocol.PlaybackSink
  │
  ├── List<CastProtocol>
  │   └── DlnaProtocol : CastProtocol, ZxtMediaPlayer.PlaybackListener
  │         └── DLNAUtils (Java) → bind AndroidUpnpServiceImpl (Cling)
  │               └── ZxtMediaRenderer registers LocalDevice (AVTransport + RenderingControl + ConnectionManager)
  │                     └── ZxtMediaPlayer forwards UPnP commands → PlaybackListener → DlnaProtocol
  │                           └── onCommand(Play/Pause/Stop/Seek) → CastCoordinatorService
  └── ExoPlayer (service-owned single instance; receives PlaybackCommands, renders to Surface)

UI: MainActivity binds service via LocalBinder → getExoPlayer() → PlayerView
Boot: BootReceiver → CastCoordinatorService(ACTION_START) after 5s Handler.postDelayed
```

**协议扩展点**：新增投屏协议（如未来手机镜像）只需实现 `CastProtocol`，加入 `CastCoordinatorService.protocols` 列表。协议只负责发现/接收命令并转发 `PlaybackCommand`，不触碰 ExoPlayer。

**Design decisions (non-obvious):**
- `kotlin.incremental=false` — Cling vendored 源码编译需要它
- `usesCleartextTraffic=true` — DLNA/UPnP 需要明文 HTTP
- 设备名硬编码为 "凯机投屏" in CastCoordinatorService.onCreate
- 无 DI 框架 —— 单例 via companion object DCL pattern
- 无 ViewModel —— Activity 直接 bind service，取 ExoPlayer

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| Start/stop protocols | `service/CastCoordinatorService.kt` | `start()`/`stop()` 遍历 `protocols` 列表 |
| Add a new protocol | `service/protocol/CastProtocol.kt` + 新实现 | 实现 `start()/stop()`，命令经 `PlaybackSink` 转发 |
| Change DLNA device name | `service/CastCoordinatorService.kt` `onCreate` | 硬编码，非 resources |
| Add DLNA command | `dlna/DLNAUtils.java` → `com/zxt/dlna/dmr/` | `AVTransportService.java` for UPnP actions |
| Playback command handling | `service/CastCoordinatorService.kt` `onCommand()` / `handlePlay()` | Baidu UA + 前台化在此 |
| HLS detection | `service/protocol/HlsDetection.kt` | 纯函数 `isHlsStream()`，从 handlePlay 抽出以便单测 |
| UI / remote keys | `ui/MainActivity.kt` | D-pad 媒体键，PlayerView 生命周期 |
| Boot auto-start | `receiver/BootReceiver.kt` | BOOT_COMPLETED + QUICKBOOT_POWERON + REBOOT |
| Notification channels | `CasterApplication.kt` | 创建 `caster_service`, `caster_playback` |
| UPnP service registration | `AndroidManifest.xml` → `AndroidUpnpServiceImpl` | Cling 服务，not exported |
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
│   │       ├── DlnaProtocol.kt   # DLNA 实现，桥接 DLNAUtils
│   │       └── HlsDetection.kt   # 纯函数：isHlsStream(uri) HLS 检测
│   ├── receiver/BootReceiver.kt  # 开机自启
│   ├── ui/MainActivity.kt        # 主界面 + 遥控器
│   ├── airplay/                  # AirPlay 接收（flag 门控 kbtv.flags.airplay）：Request/Mapper 解析 + Server + Protocol(mDNS)
│   ├── trace/TraceRecorder.kt    # JSONL 结构化运行记录（debug 写 files/trace/）
│   └── dlna/DLNAUtils.java       # Java 桥：bind Cling，创建 ZxtMediaRenderer
├── org/fourthline/cling/         # EMBEDDED: Cling UPnP (533 Java files) — DO NOT MODIFY
└── com/zxt/dlna/                 # EMBEDDED: ZxtMediaRenderer DMR (Java)
    ├── dmr/                      # AVTransportService, AudioRenderingControl, ZxtMediaPlayer 等
    └── util/                     # UpnpUtil, Utils, FileUtil, DevMountInfo (历史代码)
app/libs/                         # JARs: Jetty 8.1, Cling support, seamless, HTTP, servlet
app/src/test/                     # 单元测试
```

## CONVENTIONS

**Only what differs from Kotlin/Android defaults:**

```kotlin
// Logging: Timber in Kotlin, android.util.Log in Java (DLNAUtils, com.zxt.dlna)
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

**Import order:** `android.*` → `androidx.*` → `com.google.*` → `timber.log.Timber` → `kotlinx.*` → `java.*` → `com.kbtv.caster.*` → `com.zxt.dlna.*`

**Mixed-language logging:** Chinese messages common in DLNA Java layer (e.g. `"DLNA服务已启动"`). Match surrounding file's language.

## GOTCHAS

| Issue | Status | Resolution |
|-------|--------|------------|
| **Notification channel mismatch** | ✅ Fixed (16de7b4) | `castertv_channel` → `caster_service` in CastCoordinatorService.kt |
| **BootReceiver blocks** | ✅ Fixed (3ebdb99) | `Thread.sleep(5000)` → `Handler.postDelayed({}, 5000)` |
| **versionName stale** | ✅ Fixed (5dcb091) | `0.1.1` → `0.2.1` |
| Build artifacts in git | ✅ Fixed (11f5da8) | `.gitignore` fixed |
| **Foreground service crash on boot** | ✅ Fixed (1cfebf6) | `startForeground()` moved to top of `start()`; `restartHandler` callbacks cleared in `stop()` |
| **DLNAUtils ServiceConnection leak** | ✅ Fixed (7b2082f) | ServiceConnection stored as field; `unbindService()` in `stopDLNAService()`; volatile on static fields |
| **MainActivity ExoPlayer listener leak** | ✅ Fixed (c01251a) | Listener stored as field; `removeListener()` in `onDestroy()`; `playerView.player = null` |
| **Action string package mismatch** | ✅ Fixed (1cfebf6) | `com.caster.tv.action.*` → `com.kbtv.caster.action.*` |
| **ExoPlayer release not resilient** | ✅ Fixed (1cfebf6) | Separate try/catch for `stop()` and `release()`; always null out |
| **ZxtMediaRenderer LastChange thread leak** | ✅ Fixed (68ba432) | `while(true)` → `volatile lastChangeThreadRunning` flag, cleared in `stopAllMediaPlayers()` |
| Cling is vendored source, not Maven | By design | `org/fourthline/cling/` (533 files). Cling never published to Maven Central; treat as read-only vendor code |
| No instrumented tests | Open | `androidTest/` missing despite espresso deps |
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
- **Test files:** `dlna/TransportStateTest.kt`, `control/MediaUriTest.kt`, `service/protocol/CastProtocolTest.kt`, `service/protocol/DlnaProtocolTest.kt`, `service/protocol/HlsDetectionTest.kt`

## VERIFICATION STATUS（2026-10-12 诊断，详见 [VERIFICATION.md](VERIFICATION.md)）

- **ACI 审计**：§2.1 无 UI 运行 ✗ 不达标（端到端 headless 断裂，androidTest 缺失）；§2.2 状态记录 △ 部分（logcat 可程序化读取，但非结构化、不持久）；§2.3 程序化接口 ✗ 不达标（无外部状态/trace 原生接口，仅进程内 LiveData）
- **测试基建**：回归 `./gradlew test`（39 用例，JVM 纯逻辑）；断言 JUnit 4 + mockito-kotlin（捕获式 sink 模式，DlnaProtocolTest.kt:24-29）；无 supervisor（开发者裁定：纯确定性断言）；无 flag 机制（仅 BuildConfig.DEBUG 管日志）
- **验收标准**：已冻结于 VERIFICATION.md §8.5（含端到端项）；§8.7 落地范围 = TV 模拟器 instrumented 测试 + DLNA 控制点模拟脚本（SOAP 经 `adb forward` 直连 Cling 端口，跳过 SSDP 组播）

**验证积压（按优先级）：**

| # | 项 | 级别 | 状态 |
|---|---|---|---|
| 1 | 建 androidTest + TV 模拟器，端到端驱动 PlaybackSink 断言 ExoPlayer 状态机 | P0 | 待修复（需开发者装 TV 系统镜像） |
| 2 | 结构化运行记录（instrumentation 输出/文件），替代只靠 logcat 环形缓冲 | P0 | 待修复 |
| 3 | 验收标准冻结 | P0 | ✅ 本轮已完成 |
| 4 | flag 机制（Gradle 属性或 BuildConfig 字段，同套件 on/off 对比） | P1 | 待修复 |
| 5 | 抽纯函数 PlaybackPlan(uri)→{sourceType, ua}，Service 只执行 | P1 | 待修复 |
| 6 | LLM 裁判设计 | P1 | ✅ 裁定不需要（确定性断言） |
| 7 | CI（.github/workflows 跑 test + lint） | P2 | 待修复 |
| 8 | lint 升级为阻塞门禁 | P2 | 可选 |

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

- **License**: GPLv3（2026-10-13 自 MIT 切换）。审计在 `docs/LICENSE-SWITCH.md`。`com/zxt/dlna/**` 源自 TVRemoteIME/GPL-2.0，clean-room 重写完成前**不对外分发**。目标施工规格见 `.goal/SPEC.md`。

- **Embedded libraries** (`org.fourthline.cling.*`, `com.zxt.dlna.*`): Source files, not Maven deps. Treat as read-only vendor code. ~550 Java files total.
- **`app/libs/`**: JARs (Jetty 8.1.9, Cling support 2.0.1, seamless 1.0-alpha2, Apache HTTP). Supply Cling's transitive deps.
- **Release process**: Use `.opencode/skills/kbtv-versioning/` skill. Changelogs in `releases/` (index: `releases/INDEX.md`). Releases are tagged `v<version>` (existing: v0.1.0–v0.2.1).
- **Lint**: Non-blocking (`abortOnError=false`). Informational only.
- **`temp/` directory**: Working notes and scripts. In `.gitignore`.
