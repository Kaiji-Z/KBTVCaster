<div align="center">

# KBTVCaster

**把 Android TV 变成投屏接收端：DLNA 媒体推送 + AirPlay 屏幕镜像**

手机视频 App 一键推送视频到电视；iPhone 不装任何 App，控制中心直接镜像画面。

[![License](https://img.shields.io/badge/license-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%20TV-green.svg)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF.svg)]()
[![Min SDK](https://img.shields.io/badge/minSDK-23%20(Android%206.0)-orange.svg)]()
[![DLNA](https://img.shields.io/badge/DLNA-UPnP-red.svg)]()
[![AirPlay](https://img.shields.io/badge/AirPlay-镜像%20🧪%20实验性-yellow.svg)]()
[![CI](https://github.com/Kaiji-Z/KBTVCaster/actions/workflows/android.yml/badge.svg)](https://github.com/Kaiji-Z/KBTVCaster/actions/workflows/android.yml)

[功能](#-功能) · [下载](#-下载安装) · [使用](#-使用方法) · [工作原理](#-工作原理) · [FAQ](#-faq) · [开发](#-开发) · [许可证](#-许可证)

</div>

---

> [!NOTE]
> 这是一个**自用为主**的开源项目，旨在绕开商业投屏 App 的限制。欢迎提 issue 和 PR。

## ✨ 功能

| 能力 | 支持情况 |
|---|---|
| DLNA/UPnP 媒体投屏（推送 URL） | ✅ 完整支持 |
| 普通视频流（mp4 / mkv / 直接 URL） | ✅ |
| HLS 流（`.m3u8`） | ✅ 含百度网盘 UA 适配 |
| 遥控器控制（播放 / 暂停 / 快进 / 音量） | ✅ D-Pad 全键映射 |
| 开机自启 | ✅ 前台服务，常驻运行 |
| AirPlay 屏幕镜像（iPhone → 电视） | 🧪 实验性，构建 flag 门控 |
| AirPlay 镜像音频 | 🧪 解码链已接通（AAC-ELD/LC + ALAC），真机联调中 |

> [!IMPORTANT]
> **AirPlay 镜像是实验性功能**，协议默认不注册，需要用 `-Pkbtv.flags.airplay=true` 构建（Releases 里的 `app-debug-airplay.apk` 已开启）。验证状态：模拟器端到端全通过（900 帧零丢帧，帧延迟 p95 ≈ 148ms）；真机（Android 6 电视）设备列表与连接已确认，画面/音频联调收尾中。镜像基于 UxPlay，仅支持**非 DRM** 内容。

## 📥 下载安装

### 方式 1：直接下载 APK

前往 [Releases](https://github.com/Kaiji-Z/KBTVCaster/releases) 下载（由 CI 自动构建）：

| APK | 内容 |
|---|---|
| `app-debug.apk` | DLNA 稳定版（镜像协议未注册） |
| `app-debug-airplay.apk` | 含实验性 AirPlay 镜像 |

### 方式 2：从源码构建

```bash
git clone https://github.com/Kaiji-Z/KBTVCaster.git
cd KBTVCaster
./gradlew assembleDebug                              # DLNA 版
./gradlew assembleDebug -Pkbtv.flags.airplay=true    # 含 AirPlay 镜像
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

### 安装到电视

```bash
# 网络 adb（电视需开启网络调试）
adb connect <电视IP>:5555
adb install -r app-debug-airplay.apk

# 或拷进 U 盘，用电视文件管理器侧载
```

**环境要求：** Android 6.0 (API 23) 及以上，手机/电脑与电视在同一局域网。ABI：`arm64-v8a`、`x86_64`、`x86`（网络 adb 安装慢时可加 `-Pkbtv.build.abi=arm64-v8a` 构建单 ABI 瘦身包，约 19MB）。

## 📱 使用方法

### DLNA 投屏（默认）

1. 在电视上打开 KBTVCaster，屏幕显示「凯机投屏」待机界面（前台服务常驻）
2. 手机和电视连**同一个 WiFi**
3. 手机打开 B 站 / 腾讯视频 / 优酷等 App，播放视频时点「TV / 投屏」按钮
4. 设备列表里选择 **「凯机投屏」**
5. 视频在电视上播放，用电视遥控器控制

### AirPlay 屏幕镜像（实验性）

1. 电视安装 airplay 构建版并打开 App
2. iPhone 与电视连**同一个 WiFi**
3. iPhone 控制中心 → **屏幕镜像** → 选择 **KBTVCaster**
4. 首次连接后约 10 秒出画面（AirPlay 协议内部 HLS 处理窗口，属正常现象）

> 收到投屏 / 镜像时，App 会自动把播放界面带到前台。

## 🔧 工作原理

```
手机（控制点）                    iPhone
  │ DLNA / UPnP                    │ AirPlay (mDNS + RAOP)
  ▼                                ▼
┌─────────────────────────────────────────────────────┐
│ CastCoordinatorService (前台服务)                     │
│   ├─ List<CastProtocol>                              │
│   │   ├─ DlnaProtocol                                │
│   │   │     └─ DlnaBootstrap → Cling UPnP 栈         │
│   │   │         └─ dlna/renderer/ (净室 DMR)         │
│   │   └─ AirPlayMirrorProtocol (flag 门控)           │
│   │         └─ NativeBridge (JNI)                    │
│   │             └─ UxPlay 内核 (libairplay_native)   │
│   │                 ├─ MirrorVideoRenderer           │
│   │                 │   (MediaCodec H.264 → Surface) │
│   │                 └─ AudioEngine                   │
│   │                     (AAC ELD/LC + ALAC → oboe)   │
│   └─ ExoPlayer (单实例，渲染到 Surface)               │
└─────────────────────────────────────────────────────┘
```

**协议抽象设计**：所有投屏协议通过 `CastProtocol` 接口接入，只负责发现/接收命令并转发统一的 `PlaybackCommand`，不触碰播放器。目前注册 `DlnaProtocol` 与 `AirPlayMirrorProtocol`（后者由 `kbtv.flags.airplay` 门控），未来新增协议只需加一个实现。

**AirPlay 侧要点**：UxPlay 内核以源码内嵌（GPLv3，许可一致），经一层薄 JNI（`NativeBridge`）驱动；视频走逐 NAL 喂入的 MediaCodec 硬解（模拟器自动回退软解），音频走 NdkMediaCodec（AAC-ELD/LC）+ 自编译最小 FFmpeg ALAC 子集 + oboe 低延迟输出；mDNS 在 Android 6 上由 NsdManager 与自带应答器双路注册。

## ❓ FAQ

### 关于屏幕镜像

**iPhone 的 AirPlay 屏幕镜像已经支持**（实验性，flag 门控）。此前「普通 App 做不到镜像」的结论需要修正：三条系统级路径中，AirPlay 是唯一可行的——

| 方案 | 结论 |
|---|---|
| **AirPlay 镜像** | ✅ 可行。开源 UxPlay 实现了完整的 AirPlay 接收端（非 DRM 内容不需要 MFi 硬件密钥），本项目将其移植到 Android。 |
| **Miracast sink** | ❌ `WifiDisplayController` 是系统私有 API（`@hide` + SystemApi），且 sink 代码已从现代 AOSP 删除，普通 App 拿不到。 |
| **Google Cast 镜像** | ❌ 镜像流走 WebRTC + 设备证书认证，没有 Google 颁发的密钥拿不到；Google 一次 CA 变更就会让所有逆向 receiver 集体失效。 |

> [厂商]投屏「不用装手机 App 就能镜像」的体验，来自**电视厂商出厂预装的系统组件**（system app，有 Miracast 权限）。第三方 APK 的通用路径就是本项目走的 AirPlay。

### 投屏发现不了设备？

- 确认手机和电视在**同一 WiFi**（不是访客网络 / 不同子网）
- 某些路由器隔离了多播（AP 隔离），需关闭
- 电视上确认 KBTVCaster 在前台运行（通知栏有图标）
- 查日志：`adb logcat -s "CastCoordinator:*" "Timber:*" "KbtvNet:*"`

### 投屏了但播放失败？

- 查看日志中的 ExoPlayer 错误：`adb logcat -s "ExoPlayerLib:*" "MediaPeriod:*"`
- 确认手机推送的媒体 URL 在电视网络可达
- HLS 流（`.m3u8`）已自动适配；百度网盘链接会自动设置专用 UA

### 镜像连上了但没画面 / 没声音？

- 没画面：确认电视上 KBTVCaster 处于前台（镜像会话会自动带前台；如仍黑屏请提 issue 附 logcat）
- 没声音：镜像音频仍在真机联调中，属于已知限制
- 连接后约 10 秒才出画面是协议固有行为，不是故障

## 🛠️ 开发

**技术栈：** Kotlin 1.9 · Media3 ExoPlayer · Cling UPnP（vendored 源码）· UxPlay（C，NDK/CMake）· oboe 1.9 · FFmpeg（最小 ALAC 子集，预编译）

```bash
./gradlew assembleDebug                            # 构建（DLNA 版）
./gradlew assembleDebug -Pkbtv.flags.airplay=true  # 构建（含镜像协议）
./gradlew test                                     # JVM 单元测试（39 个）
./gradlew connectedDebugAndroidTest                # 端到端测试（需 TV 模拟器/真机，7 个）
./gradlew lint                                     # 静态检查（非阻塞）
```

| Gradle 属性 | 作用 |
|---|---|
| `-Pkbtv.flags.airplay=true` | 注册 AirPlay 镜像协议 |
| `-Pkbtv.flags.playbackPlan=true` | 启用纯函数 PlaybackPlan 路径（对照实验用） |
| `-Pkbtv.build.abi=<abi>` | 单 ABI 构建（网络 adb 安装提速） |

推送 master / PR 时 CI 自动跑构建 + 单测（flag off/on 双跑），见 `.github/workflows/android.yml`。打 `v*` tag 时 CI 自动构建双 APK 并挂到 GitHub Release，见 `.github/workflows/release.yml`；本地发版入口是 `scripts/release.sh <version>`（约定见 `.opencode/skills/kbtv-versioning/`）。

代码规范、架构说明、已知坑点详见 [AGENTS.md](AGENTS.md)。

> [!WARNING]
> 以下目录是**只读 vendor 代码**，请勿修改：
> - `app/src/main/java/org/fourthline/cling/`（533 个 Java 文件；Cling 从未发布到 Maven Central，无法换成依赖）
> - `app/libs/`（Jetty 等 JAR，供应 Cling 传递依赖）
> - `app/src/main/cpp/third_party/`（UxPlay / libplist 上游树；本项目适配改动在 `cpp/` 自有源码与 `cpp/patches/` 中）

### 项目结构

```
app/src/main/java/com/kbtv/caster/
├── CasterApplication.kt          # 入口
├── service/
│   ├── CastCoordinatorService.kt # 前台服务，持有 ExoPlayer + 协议列表
│   └── protocol/                 # CastProtocol 抽象 + DlnaProtocol + HLS 检测
├── receiver/BootReceiver.kt      # 开机自启
├── ui/MainActivity.kt            # 主界面 + 遥控器 + 镜像 SurfaceView
├── airplay/                      # AirPlay 镜像：协议、mDNS、请求解析、视频渲染
├── trace/TraceRecorder.kt        # JSONL 结构化运行记录（debug）
└── dlna/                         # DlnaBootstrap + 净室 DMR（dlna/renderer/）
app/src/main/cpp/                 # NDK：NativeBridge + dnssd shim + 音频引擎
├── third_party/                  #   UxPlay / libplist（只读 vendor）
└── prebuilt/                     #   FFmpeg / OpenSSL 预编译静态库
```

## 🤝 贡献

欢迎提 issue 反馈 bug 或需求。PR 请保持：

- 一个 commit 一个逻辑改动（[提交规范](AGENTS.md#git)）
- 改动附测试（如有可单测的逻辑）
- 不修改上述 vendor 目录；对 UxPlay 的适配改动放 `cpp/` 自有代码或 `cpp/patches/`

## 📄 许可证

[GPLv3](LICENSE)（2026-10 由 MIT 切换；审计见 [docs/LICENSE-SWITCH.md](docs/LICENSE-SWITCH.md)）

**第三方组件清单：**

| 组件 | 位置 | 许可证 | 与 GPLv3 关系 |
|---|---|---|---|
| UxPlay（含其内嵌 llhttp、playfair 等） | `app/src/main/cpp/third_party/UxPlay/` | GPLv3 | 一致 |
| libplist | `app/src/main/cpp/third_party/libplist/` | LGPL-2.1 | 兼容（单向） |
| FFmpeg 最小 ALAC 子集（自编译） | `app/src/main/cpp/prebuilt/ffmpeg/` | LGPL-2.1+ | 兼容（单向） |
| OpenSSL | `app/src/main/cpp/prebuilt/openssl/` | Apache-2.0 | 兼容（单向） |
| oboe | Maven prefab | Apache-2.0 | 兼容（单向） |
| Cling UPnP | `org/fourthline/cling/` | LGPL-2.1+ / CDDL 双许可 | 适用 LGPL 腿 |
| Jetty 8 等 `app/libs/` JAR | `app/libs/` | Apache-2.0 等 | 兼容（单向） |

> DMR 层已按 UPnP 公开规范净室重写为 Kotlin（`com/kbtv/caster/dlna/renderer/`，原 GPL-2.0 来源的 `com/zxt/dlna/` 已删除），细节见审计文档。
