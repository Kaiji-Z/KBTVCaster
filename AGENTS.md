# AGENTS.md - KBTVCaster

**Updated:** 2026-06-15 | **Branch:** master

Android TV **DLNA 投屏接收端**：基于 Cling UPnP 栈的 MediaRenderer，接收手机视频 App 推送的媒体 URL，用 Media3 ExoPlayer 播放。前台服务 + 开机自启。

**只做 DLNA 媒体投屏，不做屏幕镜像。** 详见 README「关于屏幕镜像」。架构上已抽象 `CastProtocol` 接口，未来镜像协议可插拔。

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
| Playback command handling | `service/CastCoordinatorService.kt` `onCommand()` / `handlePlay()` | HLS 检测 + Baidu UA + 前台化在此 |
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
│   │       └── DlnaProtocol.kt   # DLNA 实现，桥接 DLNAUtils
│   ├── receiver/BootReceiver.kt  # 开机自启
│   ├── ui/MainActivity.kt        # 主界面 + 遥控器
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
- **Test files:** `dlna/TransportStateTest.kt`, `control/MediaUriTest.kt`

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

- **Embedded libraries** (`org.fourthline.cling.*`, `com.zxt.dlna.*`): Source files, not Maven deps. Treat as read-only vendor code. ~550 Java files total.
- **`app/libs/`**: JARs (Jetty 8.1.9, Cling support 2.0.1, seamless 1.0-alpha2, Apache HTTP). Supply Cling's transitive deps.
- **Release process**: Use `.opencode/skills/kbtv-versioning/` skill. Changelogs in `releases/`.
- **Lint**: Non-blocking (`abortOnError=false`). Informational only.
- **`temp/` directory**: Working notes and scripts. In `.gitignore`.
