# KBTVCaster - Android TV DLNA 投屏接收端

Android TV 上的 DLNA/UPnP 投屏接收端：手机上的视频 App（B 站、腾讯视频、优酷等）可以把媒体推送到电视上播放。基于 Cling UPnP 栈（vendored 源码）+ Media3 ExoPlayer。

## 功能特性

- **DLNA/UPnP 投屏接收**：作为 MediaRenderer 被手机端发现，接收 `SetAVTransportURI`/`Play`/`Pause`/`Stop`/`Seek` 命令
- **ExoPlayer 播放**：Media3 1.2.1，支持普通视频流 + HLS（含百度网盘 UA 适配）
- **遥控器控制**：D-Pad 媒体键映射（播放/暂停/快进快退/音量/停止）
- **开机自启**：`BOOT_COMPLETED` 后延迟 5 秒拉起前台服务
- **协议可扩展架构**：DLNA 通过 `CastProtocol` 接口接入，未来屏幕镜像协议可插拔（见下文）

## 关于屏幕镜像（重要）

本应用**只做 DLNA 媒体投屏，不做屏幕镜像**。如果你期待"手机屏幕镜像到电视"（类似[厂商]投屏），需要了解以下技术现实：

- **iPhone AirPlay 镜像**需要 FairPlay DRM 认证，无合法开源实现。
- **安卓 Miracast sink** 需要 `WifiDisplayController` 系统 API（`@hide` + SystemApi），普通第三方 App 拿不到，且 sink 代码已从现代 AOSP 删除。
- **Google Cast 镜像接收**依赖设备证书，2025 年 3 月 Google 一次 CA 变更让所有逆向 receiver 集体失效。
- [厂商]投屏"不用手机装 App"的体验来自**电视厂商出厂预装的系统组件**（system app，有 Miracast 权限），第三方 APK 版做不到。

架构上已预留 `CastProtocol` 接口，未来若引入"手机装配套 App + MediaProjection 推流"的镜像方案，只需新增一个 `MirrorProtocol` 实现。

## 项目结构

```
KBTVCaster/
├── app/src/main/java/com/kbtv/caster/
│   ├── CasterApplication.kt          # 入口：Timber、通知 channel、SAX driver
│   ├── ui/MainActivity.kt            # 主界面 + 遥控器按键处理
│   ├── service/
│   │   ├── CastCoordinatorService.kt # 前台服务，持有 ExoPlayer + List<CastProtocol>
│   │   └── protocol/
│   │       ├── CastProtocol.kt       # 协议抽象（start/stop + PlaybackSink）
│   │       └── DlnaProtocol.kt       # DLNA 实现，桥接 DLNAUtils
│   ├── receiver/BootReceiver.kt      # 开机自启
│   └── dlna/DLNAUtils.java           # Java 桥：bind Cling，创建 ZxtMediaRenderer
├── app/src/main/java/com/zxt/dlna/dmr/  # UPnP DMR 实现（AVTransport/RenderingControl）
├── app/src/main/java/org/fourthline/cling/  # vendored Cling UPnP 源码（只读，勿改）
├── app/libs/                          # Cling 依赖 jar（Jetty/seamless/httpclient）
└── app/src/test/                      # 单元测试
```

## 环境要求

- **Android Studio**：Android Studio Otter 2 Feature Drop | 2025.2.2 Patch 1
- **JDK**：17 (Android Studio自带)
- **Gradle**：8.7
- **Android SDK**：Android 6.0 (API 23) 及以上

## 快速开始

### 在Android Studio中打开项目

1. 打开Android Studio
2. 选择 **Open** 或 **Import Project**
3. 导航到 `KBTVCaster` 文件夹
4. 等待Gradle同步完成
5. 连接Android TV设备（通过USB或网络ADB）

### 构建和安装

```bash
# 构建调试APK
./gradlew assembleDebug

# 构建发布APK
./gradlew assembleRelease

# 运行测试
./gradlew test

# 运行lint检查
./gradlew lint
```

### 部署到电视

#### 方法1：USB调试部署

1. 在Android TV上启用开发者选项和USB调试
2. 通过USB线连接电视到电脑
3. 在Android Studio中点击 **Run** 按钮
4. 选择TV设备作为目标

#### 方法2：网络ADB部署

1. 确保电视和电脑在同一WiFi网络
2. 在电视上获取IP地址（设置 > 关于 > 状态）
3. 通过ADB连接：
   ```bash
   adb connect <TV_IP>:5555
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### 运行应用

1. 安装完成后，打开应用
2. 点击 **启动投屏服务**
3. 等待服务启动完成
4. 在手机上打开B站等视频App
5. 点击投屏按钮，选择 "凯机投屏" 设备

## 测试

### 单元测试

在Android Studio中：
1. 打开 `app/src/test/java/com/kbtv/caster/` 目录
2. 右键点击测试文件
3. 选择 **Run 'TestName'**

或使用命令行：
```bash
./gradlew test                                    # 运行所有测试
./gradlew test --tests "com.kbtv.caster.*"       # 运行所有测试类
./gradlew test --tests "com.kbtv.caster.dlna.TransportStateTest"  # 运行单个测试类
```

## 开发指南

### 代码规范

请参阅 [AGENTS.md](AGENTS.md) 文件，了解：
- 构建和测试命令
- 代码风格指南
- 命名约定
- 错误处理
- 日志规范
- 测试要求

### 调试

查看日志：
```bash
# 实时日志
adb logcat -d | grep -E "KBTVCaster|CastCoordinator|SSDP"

# 过滤DLNA相关日志
adb logcat -d | grep -E "DLNA|AVTransport|RenderingControl"
```

### 故障排除

#### 电视无法被发现

1. 确保电视和手机在同一网络
2. 检查防火墙设置允许多播
3. 重启电视上的KBTVCaster应用
4. 查看日志：`adb logcat -d | grep -E "caster|CastCoordinator|SSDP"`

#### 投屏失败

1. 检查DLNA服务是否运行（访问 http://TV_IP:5000/）
2. 验证网络连接
3. 查看日志：`adb logcat -d | grep -E "caster|AVTransport"`
4. 确保服务正在前台运行（通知栏有图标）

#### 媒体播放失败

1. 检查媒体URL是否可访问
2. 查看ExoPlayer日志：`adb logcat -d | grep -E "ExoPlayer|media"`
3. 确保电视有足够的网络带宽

## 技术说明

### DLNA/UPnP 实现

- 基于 **Cling UPnP 栈**（`org.fourthline.cling`，以源码形式 vendored，源自 TVRemoteIME）
- `ZxtMediaRenderer` 注册为 UPnP `MediaRenderer` 设备，含三个服务：
  - **AVTransport**：SetAVTransportURI / Play / Pause / Stop / Seek
  - **RenderingControl**：Get/SetMute、Get/SetVolume（控制系统音量）
  - **ConnectionManager**：声明支持的 MIME 类型
- 命令经 `ZxtMediaPlayer.PlaybackListener` 回调到 `DlnaProtocol`，再由 `CastProtocol.PlaybackSink` 转给 `CastCoordinatorService` 的 ExoPlayer

### ExoPlayer 媒体播放

- **AndroidX Media3 ExoPlayer 1.2.1**，服务持有单实例
- HLS 检测（`.m3u8` / `type=m3u8`）→ 用 `HlsMediaSource`
- 百度网盘 URL 自动设置 `pan.baidu.com` User-Agent
- 收到投屏时自动把 MainActivity 带到前台

## 关键依赖

| 库 | 版本 | 用途 |
|---|---|---|
| AndroidX Core-KTX | 1.12.0 | Android 核心扩展 |
| AndroidX Lifecycle | 2.7.0 | 生命周期管理 |
| Media3 ExoPlayer | 1.2.1 | 媒体播放（含 HLS） |
| Kotlinx Coroutines | 1.7.3 | 异步操作 |
| OkHttp | 4.12.0 | HTTP 客户端 |
| Timber | 5.0.1 | 日志（仅 debug 构建） |
| Cling UPnP | 2.0.1 (vendored 源码) | UPnP/DLNA 协议栈 |
| JUnit / Mockito | 4.13.2 / 5.8.0 | 单元测试 |

## 已知限制

1. **仅 DLNA 媒体投屏，无屏幕镜像**（详见上文"关于屏幕镜像"）
2. Cling UPnP 源码 vendored（533 文件）—— 该库未发布到 Maven Central，无法换成依赖；视为只读 vendor 代码
3. `kotlin.incremental=false`（兼容 Cling 源码编译所需，拖慢全量构建）
4. 需要在同一局域网内才能发现设备
5. Android 6.0 (API 23) 兼容性测试有限

## 许可证

MIT License
