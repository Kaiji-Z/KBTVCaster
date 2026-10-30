# SPEC · Phase 3：Clean-room 行为对齐（乐播 HPPlay 8.2.04 → KBTVCaster）

> 2026-10-31 立，取代 Phase 2 SPEC（旧文见 git 历史）。前情：Phase 2 的 M1-M4 已达成
> （镜像发现/会话/GL 直通 60fps p95 34-71ms/音频引擎），遗留 M5 验收被两个深水问题阻塞，
> 经 2026-10-30 全链取证结案后升级为本 goal。用户 2026-10-31 拍板：路线 A（行为对齐，
> 保留 UxPlay 基座），预算 25 轮。

## 0. 决策（已拍板）

- **路线 A 行为对齐**：保留 UxPlay 基座，clean-room 提取乐播行为逐一移植进 raop 层。
  不重写底座（B 全量重写仅在 A 于 G1/G2 连续 3 轮同类失败时升级重议）。
- **Clean-room 铁律**：乐播反编译产物（代码/资源/字符串表）**一行不进仓库**。只读提取
  事实（行为序列、参数值、调用顺序、协议应答形状），实现全部自写。方法：读产物记事实
  → 合上产物 → 从事实规格自写代码。
- **预算**：25 轮，顺序 G1→G2→G4→G3→G5；每轮只验一个假设；机器门全绿后才请求用户轮。

## 1. 结案事实（已证明，勿再重查）

2026-10-30 五信道逐层取证 + M0 对照实验，**接收端全链无罪**（复现器 12 连翻全绿、
RTSP 全 200、NTP 52-167ms 零校正、音频解码零错、对 UxPlay master 无协议差异）。
真凶：iOS 870.14.1 发送端在密集流内切换后整体冻结（视频/音频/NTP/RTSP 四信道同秒
变哑）；同操作 M0 存活。**这不是"我们某层坏了"，是接收端会话行为与乐播存在差异面。**

乐播架构实证（2026-10-30 M0 会话 logcat，电视 PID 3344）：
- TCP×3 通道（无 UDP）；专用 airplayService
- `mirror_frame_store`：按会话键存帧，逐帧追踪 nNetDelay / nDeltaPts / nDeltaClock
- `ScreenStream` 每秒心跳（incoming heartbeat）
- `Dispatcher:stopPrevious stop session ... dying ... latest`：**内容切换时内部会话拆掉重建**
- `mirror_audio: start drop frame mode`：音频缓冲 ~170KB 超限自动丢帧
- 机型调参：按 PN 匹配低延迟键（nLowlatencyType/bLowlatency）；csd-0 blob；
  fdk-aac 软件 AAC-ELD（已提取并落地我方 audio_decoder.h）
- 渲染：SurfaceTexture + set_surface（我方 MirrorGlDisplay 同构，已落地）
- 冷启动：MStar 首次 create 拒用，乐播 init 序列可趟开（M0 投一次后我方也正常）

我方已知固件事实：MStar 流内切换 = 端口 flush + 缓冲重协商（BadParameter 是噪音，
解码器无视照跑）；nBufferCountActual 拒绝不影响解码；冷拒只拦合成流，真实流可用
（warmup cold ≠ 会话失败，2026-10-30 证明）。

## 2. 取证资产与方法

- APK 源：`C:\Users\kaiji\Downloads\com.hpplay.happyplay.aw.new_8.2.04.apk`
- 反编译：jadx 解到 `~/lebo-ref`（仓库外）；原生库 `lib/arm64-v8a/liblelink-tv.so`
  用 Ghidra（本机已有工具链；WebSearch 配额已尽，勿依赖联网检索）
- 每个提取物记入 `.goal/FACTS.md`：事实条目 + 产物位置 + 我方落点。此文件是
  clean-room 合规台账，每轮提交时同步。
- 我方落点：`app/src/main/cpp/third_party/UxPlay/lib/*.c`（[kbtv] 补丁模式，带审计注释）、
  `cpp/android_raop_callbacks.c`、`airplay/*.kt`、`androidTest/e2e/`、`scripts/probe/`

## 3. G1-G5 规格

### G1 连播冻结（最高优先）
- 提取目标：乐播 AirPlay 会话层对 iOS 的应答行为——RTSP SETUP/SET_PARAMETER/RECORD
  应答构造（头/字段/plist 形状）、切换时会话语义（stopPrevious 等价物）、type-5 性能包
  处理、心跳通道。重点 diff 面：我们 SET_PARAMETER 应答 vs 乐播（需先 jadx 定位其
  airplay 模块的应答构造）。
- 我方落点：raop.c / raop_handlers.h 的应答路径 + AirPlayMirrorProtocol 会话状态机
- 判据：iPhone 快速连播切换 ≥5 次（间隔<2s）不冻结；`python scripts/probe/assert_session.py
  --serial 192.168.0.24:5555 --since-hours 1` PASS（连续 session_end frames≥300 含重连）；
  前置门：reconnect_hammer 5/5 + FlipStreamReproTest 绿 + `./gradlew test` 三旗 EXIT=0
- 用户轮：1 次（手机快速切换）

### G2 冷启动解锁
- 提取目标：liblelink-tv.so 解码器 init 序列（OMX 调用顺序/参数/timing，乐播如何趟开
  MStar 首次 create 拒用）。Ghidra 定位其 OMX/MediaCodec 封装。
- 我方落点：MirrorDecoderWarmup（替换合成流探针为乐播式 init 序列）或 JNI 探针
- 判据：电视断电重启（用户断电）→ 开机 → **不经多屏互动预热**首次投屏出画面；
  用户一句话确认 + 首场会话 trace frames≥300
- 用户轮：1 次（断电 + 首投）

### G4 机型调参库
- 提取目标：PN 匹配逻辑全集 + 本电视（SHARP）键值 + csd-0 blob 表
- 我方落点：DlnaBootstrap/KbtvUpnpServiceImpl 无关；落在解码器配置与音频引擎键
- 判据：键值生效有 trace/log 证据；三旗 JVM EXIT=0；模拟器双旗 e2e EXIT=0
- 用户轮：0

### G3 帧仓库（条件触发：仅当 G1 实现需要）
- 提取目标：mirror_frame_store 的缓冲/时钟策略（何时丢帧、心跳语义）
- 判据：30 分钟长跑 assert_session p95Micros<150000；用户确认无失同步
- 用户轮：1 次（挂机确认）

### G5 音频终验
- 判据：投屏播视频用户确认有声；同场 audio stats pkts>0 且 decodeErr=0
- 用户轮：1 次（可与 G1 用户轮合并执行）

### 收尾
- 三旗回归 + 模拟器双旗 e2e + 真机 DlnaE2eTest/MirrorDecodePathTest 全 EXIT=0
- VERIFICATION.md §10 积压 #6/#7 关闭；v0.5.0 发版（release.sh，用户一句话批准）

## 4. 红线与停止

- 禁碰：`org/fourthline/cling/**`、`app/libs/**`、判据/测试/探针本身（改它们凑达标=作废）
- 停止待用户：发版/推远端/删数据；clean-room 铁律守不住；jadx/Ghidra 不可用
- 同一思路连续 3 轮失败 → 停下重议（含升级 B 路线）
- 物理动作（iPhone 操作、断电、听声音）每 G 最多 1 次；能 ADB/网络取证的绝不问用户

## 5. 已知坑（继承 Phase 1/2 全部 + 新增）

- Gradle 缓存直调：`cmd //c "C:\Users\kaiji\.gradle\wrapper\dists\gradle-8.14.3-all\57ojdfz2de3j9gyous0kn3uiu\gradle-8.14.3\bin\gradle.bat" <task>`
- **heredoc 吃反斜杠**：多行代码补丁必须走 Write/Edit/写 .py 补丁脚本，禁止 bash heredoc 内嵌代码（本会话两次翻车：补丁自吞 + Kotlin 转义）
- **git apply 不落盘**：补丁用 python 字符串替换（assert old in s）+ 落盘后 grep 复核
- C:=D: junction；Git Bash adb 路径要 `//` 前缀；pty 会损坏 shell cat 二进制
- 电视断电/重启后：网络 ADB 重置（用户重开）；**logcat 缓冲也重置**——抓包前重设
  `adb logcat -G 16M`；fresh boot 后 am instrument 才正常
- `adb install` 失败也可能退出 0：装完必验 `dumpsys package com.kbtv.caster | grep versionName`
- **勿对使用中的接收端跑 reconnect_hammer**（接管探测会驱逐活会话，2026-10-30 事故）
- dump 录像曾截断证据：现已 rotate（mirror-dump-prev.h264）；录音上限 32MB
- 帧计数跨线程读必须 @Volatile（ARM 可见性，FlipRepro 教训）
- 电视使用中勿连 ADB 做破坏性操作；探针只读

## 6. 轮协议

每轮 = 一批修改 → 当轮判据命令 → 证据（命令+退出码+关键输出）记 `.goal/PROGRESS.md`
（round 编号续 PROGRESS.md 现值）；clean-room 提取同步记 `.goal/FACTS.md` 台账。
