<div align="center">

# KBTVCaster

**把 Android TV 变成 DLNA 投屏接收端**

手机上的视频 App（B 站、腾讯视频、优酷等）一键把媒体推送到电视播放。

[![License](https://img.shields.io/badge/license-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%20TV-green.svg)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF.svg)]()
[![Min SDK](https://img.shields.io/badge/minSDK-23%20(Android%206.0)-orange.svg)]()
[![DLNA](https://img.shields.io/badge/DLNA-UPnP-red.svg)]()

[功能](#-功能) · [下载](#-下载安装) · [使用](#-使用方法) · [工作原理](#-工作原理) · [FAQ](#-faq) · [开发](#-开发)

</div>

---

> [!NOTE]
> 这是一个**自用为主**的开源项目，旨在绕开商业投屏 App 的限制。欢迎提 issue 和 PR。

## ✨ 功能

把电视变成一个 DLNA MediaRenderer，手机上的视频 App 不需要装任何配套软件，直接用各 App 自带的「投屏」按钮就能推送视频。

| 能力 | 支持情况 |
|---|---|
| DLNA/UPnP 媒体投屏（推送 URL） | ✅ 完整支持 |
| 普通视频流（mp4 / mkv / 直接 URL） | ✅ |
| HLS 流（`.m3u8`） | ✅ 含百度网盘 UA 适配 |
| 遥控器控制（播放 / 暂停 / 快进 / 音量） | ✅ D-Pad 全键映射 |
| 开机自启 | ✅ 前台服务，常驻运行 |
| **屏幕镜像（手机画面实时投到电视）** | ❌ 不支持，且普通 App 做不到（见 [FAQ](#-为什么不做屏幕镜像)） |

## 📥 下载安装

### 方式 1：从源码构建

```bash
git clone https://github.com/Kaiji-Z/KBTVCaster.git
cd KBTVCaster
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

### 方式 2：直接下载 APK

前往 [Releases](https://github.com/Kaiji-Z/KBTVCaster/releases) 下载最新的 `app-debug.apk`。

### 安装到电视

把 APK 装到 Android TV 上，任选其一：

```bash
# 网络 adb（电视需开启网络调试）
adb connect <电视IP>:5555
adb install -r app-debug.apk

# 或拷进 U 盘，用电视文件管理器侧载
```

**环境要求：** Android 6.0 (API 23) 及以上，需在同一局域网。

## 📱 使用方法

1. 在电视上打开 KBTVCaster，屏幕显示「凯机投屏」待机界面（前台服务常驻）
2. 手机和电视连**同一个 WiFi**
3. 手机打开 B 站 / 腾讯视频 / 优酷等 App，播放视频时点「TV / 投屏」按钮
4. 设备列表里选择 **「凯机投屏」**
5. 视频在电视上播放，用电视遥控器控制

> 收到投屏时，App 会自动把播放界面带到前台。

## 🔧 工作原理

```
手机视频 App (DLNA control point)
    │  UPnP / SSDP 发现 + 控制
    ▼
┌─────────────────────────────────────────────┐
│ CastCoordinatorService (前台服务)             │
│   ├─ List<CastProtocol>                      │
│   │   └─ DlnaProtocol                        │
│   │       └─ DlnaBootstrap → Cling UPnP 栈   │
│   │           └─ dlna/renderer/ (净室 DMR)    │
│   │               (AVTransport/RenderingControl)
│   └─ ExoPlayer (单实例，渲染到 Surface)        │
└─────────────────────────────────────────────┘
    │
    ▼  投屏命令 (Play/Pause/Stop/Seek)
   ExoPlayer 播放手机推送的媒体 URL
```

**协议抽象设计**：所有投屏协议通过 `CastProtocol` 接口接入。目前注册了 `DlnaProtocol`，未来若加入其它协议（如自研镜像），只需新增一个实现，不用改动核心服务。

## ❓ FAQ

### 为什么不做屏幕镜像？

我调研过所有路径，结论是：**普通第三方 App（非系统签名、非 root）做不到「手机系统自带投屏按钮直接镜像过来」**。这不是写代码能绕过的硬限制：

| 方案 | 为什么走不通 |
|---|---|
| **Miracast sink** | `WifiDisplayController` 是系统私有 API（`@hide` + SystemApi），且 sink 代码已从现代 AOSP 删除。普通 App 拿不到。 |
| **Google Cast 镜像** | 镜像流走 WebRTC + 设备证书认证，没有 Google 颁发的密钥拿不到。2025 年 3 月 Google 一次 CA 变更就让所有逆向 receiver 集体失效。 |
| **iPhone AirPlay 镜像** | 需要 FairPlay DRM 认证，无合法开源实现。 |

> [厂商]投屏「不用装手机 App 就能镜像」的体验，来自**电视厂商出厂预装的系统组件**（system app，有 Miracast 权限），第三方 APK 版本做不到。APK 版的[厂商]镜像，手机端也必须装[厂商] App。

**唯一可行的镜像方案**是「手机装配套 App + MediaProjection 录屏推流」，这是[厂商] APK 版的真实做法。本项目架构上已为此预留 `CastProtocol` 扩展点，但当前未实现。

### 投屏发现不了设备？

- 确认手机和电视在**同一 WiFi**（不是访客网络 / 不同子网）
- 某些路由器隔离了多播（AP 隔离），需关闭
- 电视上确认 KBTVCaster 在前台运行（通知栏有图标）
- 查日志：`adb logcat -s "CastCoordinator:*" "Timber:*" "KbtvNet:*"`

### 投屏了但播放失败？

- 查看日志中的 ExoPlayer 错误：`adb logcat -s "ExoPlayerLib:*" "MediaPeriod:*"`
- 确认手机推送的媒体 URL 在电视网络可达
- HLS 流（`.m3u8`）已自动适配；百度网盘链接会自动设置专用 UA

## 🛠️ 开发

**技术栈：** Cling UPnP（vendored 源码）· Media3 ExoPlayer 1.2.1 · Kotlin 1.9.24 · AGP 8.5

```bash
./gradlew assembleDebug          # 构建
./gradlew test                   # 单元测试（39 个）
./gradlew lint                   # 静态检查
```

代码规范、架构说明、已知坑点详见 [AGENTS.md](AGENTS.md)。

> [!WARNING]
> 项目内嵌了完整的 Cling UPnP 源码（`org/fourthline/cling/`，533 个 Java 文件）。Cling 从未发布到 Maven Central，无法换成依赖，请**视为只读 vendor 代码**，不要修改。

### 项目结构

```
app/src/main/java/com/kbtv/caster/
├── CasterApplication.kt          # 入口
├── service/
│   ├── CastCoordinatorService.kt # 前台服务，持有 ExoPlayer + 协议列表
│   └── protocol/
│       ├── CastProtocol.kt       # 协议抽象
│       └── DlnaProtocol.kt       # DLNA 实现
├── receiver/BootReceiver.kt      # 开机自启
├── ui/MainActivity.kt            # 主界面 + 遥控器
└── dlna/                         # DlnaBootstrap + KbtvUpnpServiceImpl + renderer/（净室 DMR）
```

## 🤝 贡献

欢迎提 issue 反馈 bug 或需求。PR 请保持：
- 一个 commit 一个逻辑改动（[提交规范](AGENTS.md#git)）
- 改动附测试（如有可单测的逻辑）
- 不修改 `org/fourthline/cling/` 下的 vendor 代码

## 📄 许可证

[GPLv3](LICENSE)（2026-10 由 MIT 切换；贡献者与第三方许可证审计见 [docs/LICENSE-SWITCH.md](docs/LICENSE-SWITCH.md)）

> **注意**：本项目内嵌的第三方源码保留各自原始许可证——Cling UPnP（`org/fourthline/cling/`，LGPL-2.1+/CDDL 双许可，本项目适用其 LGPL 腿，与 GPLv3 兼容）；DMR 层已按 UPnP 公开规范净室重写为 Kotlin（`com/kbtv/caster/dlna/renderer/`，原 GPL-2.0 来源的 `com/zxt/dlna/` 已删除）；`app/libs/` JAR 多为 Apache-2.0（与 GPLv3 单向兼容，javax.annotation 已 compileOnly 化），细节见审计文档。
