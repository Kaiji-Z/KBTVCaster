# SPEC · Phase 2：AirPlay 屏幕镜像（UxPlay 内核 + JNI）

> 2026-10-13 立。前情：Phase 1（净室 DMR + AirPlay URL 推送 + 真机验证，16 轮）已收口，旧规格见 git 历史（8789483 起）与 PROGRESS.md。本文件是当前 /goal 的细节载体，跨上下文压缩自包含。

## 0. 决策（已拍板）

- **路线**：UxPlay 内核 + JNI（HANDOFF §6 首选；用户 2026-10-13 再确认）。参考已趟通的安卓移植：jqssun/android-airplay-server（GPLv3, F-Droid 上架）、flymop/airplay-tv（GPLv3）。上游 github.com/FDH2/UxPlay。
- **License**：本项目 GPLv3，UxPlay 亦 GPLv3 → 源码可进树，保留版权头 + NOTICE 致谢；LICENSE-SWITCH.md 增补审计行。发布/推远端仍需用户确认。
- **预算**：25 轮（用户委托按规模定）。
- **备选切换条件**：JNI 路线同一思路连续 3 轮失败 → 停下重议（含 handoff 备选 serezhka/java-airplay MIT 纯 Java 路线）。

## 1. 现状资产（复用，勿重建）

| 资产 | 状态 | 位置 |
|---|---|---|
| AirPlay HTTP 面（URL 推送 /play /rate /stop /scrub） | ✅ 真机可用 | `airplay/AirPlayServer.kt` |
| mDNS 广播（_airplay/_raop；API<24 自建响应器 AirPlayMDns + MulticastLock；TXT 真 MAC） | ✅ 电视上 PC 探针实测应答 | `airplay/AirPlayProtocol.kt` `AirPlayMDns.kt` |
| H.264 解码 | ✅ 模拟器软解 900 帧 p95=76ms；真机硬解 OMX.MS.AVC.Decoder+Surface 实播 | `e2e/MirrorDecodePathTest.kt` |
| 真机靶台 | ✅ SHARP Android 6.0（API 23, arm64）`192.168.0.24:5555` ADB 直连；系统 USB 调试开着才监听 5555 | temp/ENVIRONMENT.md |
| 模拟器 | emulator-5554（TV API 33） | — |
| trace/三旗回归/e2e 套件 | ✅ 全绿 | VERIFICATION.md §8 |

## 2. 施工阶梯（每轮只验证一件事）

- **M1 JNI/NDK 构建链**（2-4 轮）：AGP `externalNativeBuild` + CMake；UxPlay 内核源（含 plist/ed25519 等其自带第三方）进 `app/src/main/cpp/`（或 `airplay/native/`）；`assembleDebug` 产出 arm64-v8a（armeabi-v7a 顺手）.so；模拟器与真机 `System.loadLibrary` 冒烟。
- **M2 镜像发现面**（2-3 轮）：features 翻镜像位（对齐 M0 实测形状 `0x5A7FFFF7,0x1E`）；iPhone「屏幕镜像」列表出现 KBTVCaster（用户肉眼 + PC 探针 TXT 核验）；点按后 /info /pair /stream 请求落 trace（协议取证）。
- **M3 镜像会话**（4-6 轮）：pair-verify 通过；/stream 协商 H.264；帧 → MediaCodec(surface) → SurfaceView 上屏。**真电视 Android 6 是第一验收机**（API 23 兼容坑在此暴露：NDK 最小 API、TLS、线程优先级）。
- **M4 音频 + 同步**（2-4 轮）：AAC-ELD/ALAC → AudioTrack；A/V 时序（UxPlay 自带 NTP 时钟）。
- **M5 验收 + 回归 + 文档**（2-3 轮）：全判据复测；JVM 三旗 + 模拟器 e2e（默认/airplay 旗）+ 真机 e2e；AGENTS/README/LICENSE-SWITCH/PROGRESS 更新。

## 3. 完成判据（可验证）

1. `$GRADLE assembleDebug` EXIT=0，APK 含 `lib/arm64-v8a/lib*.so`（镜像内核）
2. 真电视 mDNS TXT（PC 探针、正确 QDCOUNT 头）含镜像 features 位；**iPhone 控制中心「屏幕镜像」列表出现 KBTVCaster**（用户一句话确认 + PC 侧 TXT 证据存 PROGRESS）
3. **iPhone 镜像 → 电视出画面**：trace `mirror_session_started` → 渲染帧事件 ≥300；电视实际显示（用户确认）；断开重连各一次成功
4. 延迟代理：帧到达→上屏 p95 <150ms（trace 时间戳差，≥100 样本）——handoff「1080p <150ms 体感」的仪器化口径
5. 音频随镜像出声（用户确认），无肉眼 A/V 失同步
6. 回归零破坏：JVM 三旗 EXIT=0；模拟器 connectedDebugAndroidTest 默认+airplay 双旗 EXIT=0；真电视 DlnaE2eTest + MirrorDecodePathTest EXIT=0；AirPlay URL 推送（Phase 1 能力）不回归
7. GPLv3 合规：UxPlay 版权头保留、NOTICE/致谢、LICENSE-SWITCH.md 增补行

**反作弊**：禁改测试/判据凑达标；判据放宽仅限用户明示并记 PROGRESS。

## 4. 红线与停止条件

- **禁碰**：`org/fourthline/cling/**`、`app/libs/**`；[厂商]反编译物一行不进仓库
- **停止待用户**：需付费/云依赖；iOS 若要求 MFi 硬件密钥（UxPlay 社区无解类）；发布/推远端
- **连续 3 轮同一思路失败**（NDK 工具链 / pair-verify / API 23 兼容）→ 停下重议路线
- 物理动作（iPhone 点按、确认画面/声音）每轮最多要一次；能 ADB/网络取证的不问

## 5. 已知坑（前 16 轮血泪，必读）

- Gradle 用缓存 8.14.3 直调（wrapper 损坏）：`cmd //c "C:\Users\kaiji\.gradle\wrapper\dists\gradle-8.14.3-all\57ojdfz2de3j9gyous0kn3uiu\gradle-8.14.3\bin\gradle.bat" <task>`（$GRADLE）
- 真机 ADB 掉线：`adb connect 192.168.0.24:5555`（电视系统 USB 调试须为开）
- PC 网络取证：SSDP/mDNS 都要 `IP_ADD_MEMBERSHIP` 加组；**mDNS 查询头 QDCOUNT 必须=1**（R16 自坑）
- API 23：无 Codec2；`adb reverse` 不支持；file:// 走 /sdcard + `pm grant READ_EXTERNAL_STORAGE`
- 电视多网卡：绑定 wlan0；组播收包持 MulticastLock；同宿主[厂商] [VendorCast] 自养 mDNS——别学它的 0.3s 狂发，按规范
- 每 git 提交可独立构建；UxPlay 源码首次进树单独一个提交（license 审计清晰）

## 6. 轮协议

每轮 = 一批修改 → 当轮判据命令 → 证据（命令+退出码+关键输出）记 `.goal/PROGRESS.md`；round 编号续 Phase 1（从 17 起）。
