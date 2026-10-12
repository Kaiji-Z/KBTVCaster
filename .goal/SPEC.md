# KBTVCaster 四阶段目标规格（.goal/SPEC.md）

> 本文件是 /goal 目标文本的细节载体，跨上下文压缩与跨会话自包含。
> 目标四阶段：**Stage 0** GPLv3 切换 → **Stage A** 验证地基 → **Stage B** AirPlay 接收端 → **Stage C** 百度网盘发现修复 → **Stage D** [LinkX] 过门实验。
> 决策来源：`HANDOFF.md`（交接，已拍板事项不重新讨论）+ `VERIFICATION.md`（验证协议，§8 参数已填）。
> 进度与证据：`.goal/PROGRESS.md`；真机清单：`.goal/SMOKE.md`。

## 0. 阶段依赖（顺序不可换）

- **Stage 0 → 一切**：GPLv3 必须先于任何 GPLv3 代码（UxPlay 衍生）进树。✅ 2026-10-13 完成（Round 1），审计 `docs/LICENSE-SWITCH.md`。
- **Stage A → B**：AirPlay 按 VERIFICATION.md §5 闭环 SOP 开发，需要 flag 机制（A2）与 androidTest 骨架（A4）先就位。
- **TV AVD → A4 运行/判据 2**：本机无 Android TV 镜像（仅 Pixel_7/android-36.1）。**安装就绪预检已过（2026-10-13）**：sdkmanager/avdmanager 在 `C:\Users\kaiji\AppData\Local\Android\Sdk\cmdline-tools\latest\bin\`，emulator 36.3.10 可用，SDK license 已接受，`tv_1080p` 设备定义存在（id 58）。授权后即执行：
  ```
  cmd //c "%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" "system-images;android-34;android-tv;x86_64"
  cmd //c "%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin\avdmanager.bat" create avd -n KBTV_TV -d tv_1080p -k "system-images;android-34;android-tv;x86_64"
  ```
- **com/zxt 重写 → 任何分发**：license 门禁（见 LICENSE-SWITCH.md §4），排 Stage A 末尾。
- **Stage D 只到报告**：三门结论产出即停，实施与否 = 用户决策（法律门）。

## 1. 完成判据 → 命令与阈值（目标判据 0–8 的可执行化）

| # | 判据 | 命令/检查 | 通过阈值 |
|---|---|---|---|
| 0 | GPLv3 | `grep -m1 "Version 3, 29 June 2007" LICENSE`；`grep "GPLv3" README.md`；`ls docs/LICENSE-SWITCH.md` | 三者命中 |
| 1 | JVM 回归 | `$GRADLE test`（见 §6 infra 注） | 退出码 0；累计新增用例 ≥35（`--rerun-tasks` 输出统计） |
| 2 | 端到端 | TV AVD 在线（`adb devices` 列出）→ `$GRADLE connectedDebugAndroidTest` | 退出码 0；含 DLNA e2e：sink Play→STATE_READY+isPlaying、BOOT_COMPLETED→RUNNING、控制点 SOAP→播放 |
| 3 | 结构化 trace | `adb shell run-as com.kbtv.caster ls files/trace/` + `adb pull` 后 `grep -c '"event"' *.jsonl` | 每个工作流步骤（命令到达/状态变化/协议启停）≥1 条事件 |
| 4 | AirPlay | androidTest 内断言 | mDNS `_airplay._tcp`+`_raop._tcp` 自发现 ≤10s；URL 推送→READY+isPlaying；canned H.264 解码 ≥100 帧；延迟探针 p95<150ms；服务重启后 mDNS ≤10s 恢复 |
| 5 | 真机烟测 | `.goal/SMOKE.md` | AirPlay 4 项 + DLNA 回归 1 项全 [x]（用户执行） |
| 6 | 百度网盘 | `docs/research/baidu-layer-2026-10.md`（层定位+证据）→ 修复后模拟器判据绿 | 报告存在且三要素齐（现象/证据/层结论）；SMOKE.md 追加 2 项 [x] |
| 7 | [LinkX] 过门 | `docs/research/[linkx]-gate-2026-10.md` | 三节齐：_lelink 服务名格式 / 配对是否过云 / 镜像流封装 |
| 8 | CI + 协议状态 | `ls .github/workflows/*.yml` + push 后 Actions 绿；`grep -A2 "8.2\|8.3" VERIFICATION.md` | workflow 存在且含 test 步骤；VERIFICATION.md §8.2/8.3 状态更新为已落地 |

**反作弊：** 禁改测试/判据/阈值本身凑达标；判据任何放宽（如模拟器延迟不达标改为「记录+真机复核」）仅限用户明示批准，并记录于 PROGRESS.md。

## 2. 已拍板决策（固化，不再讨论）

| 决策 | 来源 |
|---|---|
| UxPlay 内核 + JNI 首选路线；serezhka/java-airplay(MIT) 仅当首选连续 3 轮失败后启用 | HANDOFF §6 |
| 纯确定性断言，无 LLM 裁判 | VERIFICATION.md §8.4 |
| AirPlay 模块 `caster/airplay/`，CastProtocol 接入；镜像会话期 wakelock+multicast lock | HANDOFF §6 |
| DACP 反控后置；不做 sender；不做云/B2B/预装 | HANDOFF §1/§9 |
| Phase 3（[LinkX]）只到过门报告；[厂商]反编代码一行不进仓库（clean-room） | HANDOFF §5/§8 |
| 模拟器组播不可靠 → 控制点验证走 `adb forward` 单播 SOAP + AVD 内 mDNS 自发现 | 诊断 Round 结论 |
| mDNS 广播用 Android NsdManager 优先；JmDNS jar 备选（引入需用户确认） | HANDOFF §6 路线 1 |
| GPLv3 已切换（2026-10-13）；分发门禁 = com/zxt 重写完成 | docs/LICENSE-SWITCH.md |

## 3. Stage A 任务（验证地基）

- [x] A0 GPLv3 切换 + 审计（Round 1 完成）
- [x] A1 `PlaybackPlan` 抽取（2026-10-13，13 用例 + 等价性矩阵，flag=off/on 双绿）
- [x] A2 flag 机制（2026-10-13，`-Pkbtv.flags.playbackPlan` + BuildConfig，4 用例，双态验证过）
- [x] A3 `TraceRecorder`（2026-10-13，6 例双绿；设备侧拉取验证并入 A4）
- [ ] A4 androidTest 骨架 + DLNA e2e 三件套：① ✅ ② ✅（编译绿，2026-10-13；实跑待 TV 镜像）③ ✅ 控制点脚本 `app/src/androidTest/assets/controlpoint/dlna_control_point.py`（编译/语法过，实跑待 TV 镜像）
- [x] A5 CI 文件（2026-10-13；Actions 实跑待用户推远端——停止条件）
- [ ] A6 com/zxt + DLNAUtils clean-room 重写（license gate；按 UPnP 规范重写 AVTransport/RenderingControl/ZxtMediaPlayer 表面，A4 e2e 保证行为不回归；完成后 LICENSE-SWITCH.md §4.3 门禁解除）
- [ ] A7 VERIFICATION.md §8.2/8.3/8.6 状态更新

## 4. Stage B 任务（AirPlay，按 §5 SOP：每项先写回归用例再实现）

- [ ] B1 模块骨架：🟡 代码层完成（AirPlayProtocol : CastProtocol + flag 门控 + 服务接入，2026-10-13 三态绿）；设备行为验证待镜像
- [ ] B2 mDNS 广播：🟡 NsdManager 注册代码就位（_airplay/_raop 双类型，TXT 最小子集）；自发现 ≤10s 断言待 androidTest 实跑
- [ ] B3 接收端 HTTP/RTSP 握手解析：✅ 纯函数层完成（AirPlayRequest/AirPlayCommandMapper + 21 用例，2026-10-13 双绿）；服务侧 socket 接线归 B1/B4
- [ ] B4 推送播放：play URL → sink Play(uri)（复用 A1 PlaybackPlan）；e2e：READY+isPlaying
- [ ] B5 镜像：JNI + MediaCodec 硬解 H.264/H.265 → SurfaceView；AAC-ELD/ALAC 音频；canned 流 ≥100 帧；RTP 时间戳 vs 渲染时间探针 p95<150ms；真机体感复核入 SMOKE.md
- [ ] B6 验收 §8.5 e2e 项全绿 + SMOKE.md AirPlay 4 项勾选（用户执行）

## 5. Stage C / D 任务

- [ ] C1 层定位：AVD 上抓 SSDP（M-SEARCH 应答/NOTIFY 节奏）+ device description XML 与主流电视栈字段比对 → `docs/research/baidu-layer-2026-10.md`（含证据：报文/日志摘录）
- [ ] C2 修复：优先配置层（Cling `UpnpServiceConfiguration` 调参：alive 间隔/M-SEARCH 应答/XML 字段对齐）；friendlyName 方案 = DLNA 描述用纯 ASCII（如 "KBTVCaster"）+ UI 显示名不变（「凯机投屏」为界面文案，不进 XML）——**落地前用户确认**；若必须改 vendor 源码 → 触发停止条件
- [ ] D1 [LinkX] 过门：用户提供[厂商]发送端↔接收端 Wireshark 抓包（过滤 `mdns || ssdp`）→ 三节报告；三门任一不过或需法律决策 → 停

## 6. 基础设施事实（跨会话必须知道）

- **Gradle wrapper 不可用**：`gradle-8.7-all` 缓存损坏（0 字节 .part），wrapper 下载超时。**替代**：直接用完整缓存的 8.14.3：
  `cmd //c "C:\Users\kaiji\.gradle\wrapper\dists\gradle-8.14.3-all\57ojdfz2de3j9gyous0kn3uiu\gradle-8.14.3\bin\gradle.bat" <task>`（下称 `$GRADLE`）。
  风险：AGP 8.5 + Kotlin 1.9.24 在 Gradle 8.14.3 属未测组合；若构建失败，回退 = curl 下载 `gradle-8.7-bin.zip` 解压到仓库外直接调用（**不动** `gradle-wrapper.properties`，它不在目标允许范围内）。
- Java 17 在 PATH ✓（AGP 8.5 要求 17+）。
- `kotlin.incremental=false`（Cling 源码编译需要）——改 Cling 相邻代码构建慢属正常。
- 本机 AVD：仅 `Pixel_7`；**无 TV 镜像**（判据 2/4 的前置，用户装）。
- `temp/`、`.goal/` 中的笔记不进发版；`.goal/` 未被 .gitignore 排除（可提交）。

## 7. 依赖所有权（归用户，agent 不得自行动作）

1. Android TV 系统镜像 + AVD 创建（§0 命令）
2. 任何新依赖/JAR（JmDNS、UxPlay 内核引入方式=源码 vendor 也需确认）
3. 真机烟测（iPhone/百度网盘 App/电视）
4. [厂商] Wireshark 抓包会话
5. friendlyName XML ASCII 化确认（§5 C2）
6. 对外发布/发版/推远端
7. 判据放宽（反作弊条款）

## 8. 轮协议与预算

- 每轮 = 一批修改 → 跑当轮判据命令 → 证据（命令+退出码+关键输出）记入 `.goal/PROGRESS.md`。
- 预算：总 60 轮；A≤12（余 11）、B≤25、C≤8、D≤5、缓冲 10。同一思路连续 3 轮失败 → 停。
- 阶段完成时更新本文件对应 checkbox 与 §1 表状态。
