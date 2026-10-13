# 目标进度与证据日志（.goal/PROGRESS.md）

> 轮协议：每轮一条 Round 记录 = 改动 + 判据命令 + 退出码/关键输出。禁止事后涂改；勘误追加注明。

---

## Round 1（2026-10-13）—— Stage 0 + 文件落盘 【阶段 A 用量 0/12，总 1/60】

**改动：**
1. `HANDOFF.md` ← 复制自 `~/Downloads/KBTVCaster-HANDOFF.md`（按交接文档自述用法入仓）
2. `.goal/SPEC.md` / `.goal/SMOKE.md` / `.goal/PROGRESS.md` 创建
3. **Stage 0 GPLv3 切换完成**：
   - `LICENSE` ← `https://www.gnu.org/licenses/gpl-3.0.txt`（curl；674 行；首尾校验过）
   - `README.md` 徽章 MIT→GPLv3；「📄 许可证」节重写
   - `AGENTS.md` NOTES 增 License 条目；日期 2026-10-13
   - `docs/LICENSE-SWITCH.md` 审计：贡献者=单人 42 commits；Cling=LGPL2.1+/CDDL 取 LGPL 腿；**com/zxt+DLNAUtils 源自 TVRemoteIME/GPL-2.0（风险，Stage A6 重写前不分发）**；libs JAR 审计；javax.annotation(CDDL) 灰区待 A 阶段检查

**判据运行：**
- 判据 0：`grep -m1 "Version 3, 29 June 2007" LICENSE` ✓；`grep GPLv3 README.md` ✓（badge+许可节）；`ls docs/LICENSE-SWITCH.md` ✓ → **判据 0 达成**
- 判据 1 基线：首次 `./gradlew test` 失败——wrapper 8.7-all 缓存损坏（0 字节 .part，10s 超时）。已定位完整缓存 gradle-8.14.3-all，改用 `gradle.bat` 直调重跑中（后台）。结果见 Round 1 补记。

**基础设施发现（已固化进 SPEC §6）：** wrapper 不可用；直调 8.14.3；AGP8.5+Kotlin1.9.24@8.14.3 未测组合有回退预案；无 TV 镜像（判据 2/4 前置，需用户装）。

**待用户（不阻塞后续轮）：** TV 镜像安装（SPEC §0 命令）。

### Round 1 补记（2026-10-13）

- 基线测试（gradle.bat 8.14.3 直调）：`BUILD SUCCESSFUL in 5m 50s`，EXIT_CODE=0，39/39 绿（5 类：HlsDetection 9 / MediaUri 7 / CastProtocol 8 / TransportState 8 / DlnaProtocol 7）。未测组合 AGP8.5+Kotlin1.9.24@Gradle8.14.3 **成立**，仅一条无害警告（`-Xallow-any-scripts-in-source` 不被该编译器支持，原有配置遗留）。

---

## Round 2（2026-10-13）—— Stage A1 + A2 【阶段 A 用量 1/12，总 2/60】

**改动：**
1. 新增 `service/protocol/PlaybackPlan.kt`（纯路由决策：HLS/百度 UA/30s 超时）
2. 新增 `service/protocol/FeatureFlags.kt`（读 `BuildConfig.FLAG_PLAYBACK_PLAN_ROUTING`）
3. `app/build.gradle.kts`：`buildConfigField` + Gradle 属性 `-Pkbtv.flags.playbackPlan`（默认 false=旧行为）
4. `CastCoordinatorService.kt`：`handlePlay` 加 flag 分支；新增 `handlePlayWithPlan`（执行 PlaybackPlan，ExoPlayer 调用与旧分支镜像）；旧分支原样保留
5. 新增测试 `PlaybackPlanTest`（13 例）+ `FeatureFlagsTest`（4 例，含等价性矩阵）

**失败与修正（透明记录）：**
- 失败①：测试反引号名含冒号 → 编译失败（PlaybackPlanTest.kt:76）。改名修复。
- 失败②：等价性 oracle 急切求值 `isHlsStream(uri) to uri.contains("pan.baidu.com")`，对 `pan.baidu.com/*.mp4` 报出旧代码不可能产生的 `(false,true)`——旧代码中 contains 在 HLS 分支**内部**，非 HLS 从不求值。**生产代码无误（新旧语义一致）**，oracle 修正为控制流模型（非 HLS ⇒ (false,false)）。判据本身未变；此修正为测试纠错而非凑绿，特此留痕。

**判据运行（§4 同套件双态）：**
- flag=off：EXIT=0；flag=on（`-Pkbtv.flags.playbackPlan=true`）：EXIT=0
- 56/56（0 failures / 0 errors），7 类。新增 17（39→56），距 ≥35 还差 18。
- 判据 1 部分达成（新用例含 PlaybackPlan/flag ✓，总数未到 35，A3/A4 补）

---

## Round 3（2026-10-13）—— Stage A3 + A5 文件 【阶段 A 用量 2/12，总 3/60】

**改动：**
1. 新增 `trace/TraceRecorder.kt`：JSONL 结构化运行记录（手写 JSON 转义，无 org.json → JVM 可测；单 daemon 写线程；1MiB×3 代滚动；debug 写入/release 关闭；写失败静默不伤播放）
2. `CastCoordinatorService.kt` 挂点 10 处：service_created/running/stopped/error、protocol_started/start_failed、command（含 uri）、routing（plan 路径）、play_started（双路径）、playback_state、is_playing、restart_scheduled
3. 新增 `TraceRecorderTest`（6 例：逐行 JSON/ts+sid+event、转义、null 省略、滚动、禁用、控制字符）
4. 新增 `.github/workflows/android.yml`（A5 文件半：JDK17 + test assembleDebug + flag-on 二跑；**Actions 实跑待用户推远端**，停止条件管辖）

**失败与修正：**
- 失败①：漏 `import org.junit.Test`（只导入了 Rule）→ Unresolved reference。补导入。同一思路第 1 次失败。
- 失败②（预防性修正）：控制字符 `\u0001` 字面量在工具写入时丢失成空串，改 `1.toChar()` 程序化构造。

**判据运行：** flag=off EXIT=0；flag=on EXIT=0；**62/62**（0f/0e，8 类；56→62，新增 6）。判据 3 的格式/滚动/禁用逻辑 JVM 侧已验证；**设备侧 adb 拉取验证待 A4 + TV 镜像**。

---

## Round 4（2026-10-13）—— A4 前半：e2e 测试代码 + 编译验证 【阶段 A 用量 3/12，总 4/60】

**改动：**
1. 新增 `app/src/androidTest/java/com/kbtv/caster/e2e/DlnaE2eTest.kt`（项目首个 androidTest）：
   - ① `sink Play → STATE_READY + isPlaying`：测试内生成 3s/440Hz WAV（纯代码 PCM），SingleFileHttpServer 于 127.0.0.1 供流 → ExoPlayer 走真实 HTTP 渐进路径（与手机推 URL 同路径）
   - ② `BOOT_COMPLETED → RUNNING`：直调 `BootReceiver().onReceive`（绕过 protected broadcast），先归一化到 STOPPED 再断言（避免顺序依赖）
2. 自查修正 3 处：HTTP 服务器残留伪代码、双 BufferedReader 交错读、player 可空性

**判据运行：** `:app:compileDebugAndroidTestKotlin` → BUILD SUCCESSFUL in 14s，EXIT=0（编译层验证）。**实跑待 TV AVD（用户侧前置）**。判据 2 未达成（需 connectedDebugAndroidTest 实跑）。

**净状态：** 用例计数 62（JVM）+ 2（androidTest 待跑）；新增累计 24，距 ≥35 差 11。

---

## Round 5（2026-10-13）—— A4 后半（脚本）+ A7 部分（§8.2）【阶段 A 用量 4/12，总 5/60】

**改动：**
1. `dlna/DLNAUtils.java`：onServiceConnected 记录设备 UDN（Log + TraceRecorder 事件 `upnp_registered`）——供宿主机脚本发现 Cling 端口
2. 新增 `app/src/androidTest/assets/controlpoint/dlna_control_point.py`（stdlib-only）：宿主机 WAV 生成+HTTP 服务（10.0.2.2 可达）→ /proc/net/tcp* 枚举目标 uid LISTEN 端口 → adb forward + GET /dev/<UDN>/desc 探测 → 解析 AVTransport controlURL → SOAP SetAVTransportURI+Play → 轮询 `files/trace/cast.jsonl` 断言 `play_started` → PASS/FAIL 退出码
3. `VERIFICATION.md` §8.2 更新为 62 用例 + e2e 目录 + 脚本现状（A7 部分）

**失败与修正（同一思路 2 次失败后成功）：**
- 失败①：Java 调 Kotlin vararg 传单个 Pair → 需 `Pair[]` 数组
- 失败②：companion 成员无 `@JvmStatic` → Java 找不到静态方法；补注解后 `BUILD SUCCESSFUL in 14s`（compileDebugJavaWithJavac + Kotlin + androidTestKotlin 全绿）

**判据运行：** 编译三源集 EXIT=0；`python ast.parse` 脚本语法 ✓。脚本实跑待 TV AVD。

---

## Round 6（2026-10-13）—— B3 前置：AirPlay 纯函数解析层 【阶段 B 用量 1/25，总 6/60】

**改动（顺序说明：TV 镜像未装，A4 实跑/A6 阻塞，先行无依赖的 B3 纯逻辑层；§5 合规——本层即「先写回归」）：**
1. 新增 `airplay/AirPlayRequest.kt`：RTSP/HTTP 风格请求解析（method/path/query/小写化 headers/body）+ Apple text-plist 扁平 dict 解析（XXE 防护；binary plist 明确不支持，留给镜像原生层）；畸形输入 → null/空 map，绝不抛
2. 新增 `airplay/AirPlayCommandMapper.kt`：`/play /rate /stop /scrub` → `AirPlayIntent` 密封类（**刻意不含 android.net.Uri**，服务侧接线轮再转 PlaybackCommand）
3. 新增测试 `AirPlayRequestTest`（9）+ `AirPlayCommandMapperTest`（12）

**判据运行：** flag=off EXIT=0；flag=on EXIT=0；**83/83**（0f/0e，10 类）。新增累计 44（39→83）。

**🎯 判据 1 达成**：`$GRADLE test` 退出码 0 + 新增用例 44 ≥ 35。

## 阶段盘点（截至 Round 6，总 6/60）

| 判据 | 状态 |
|---|---|
| 0 GPLv3 | ✅ |
| 1 JVM 回归 ≥35 新增 | ✅（83/83 双态） |
| 2 e2e connectedDebugAndroidTest | ⏸ 代码+编译就绪，待 TV 镜像 |
| 3 JSONL trace adb 拉取 | ⏸ 格式 JVM 已证，待设备拉取（随判据 2） |
| 4 AirPlay e2e | ⏸ 解析层就绪，待镜像 + B1/B2/B4/B5 接线 |
| 5 真机烟测 | ⏸ 待用户（iPhone） |
| 6 百度网盘 | ⏸ 待 C1/C2 |
| 7 [LinkX] 过门报告 | ⏸ 待用户抓包 |
| 8 CI + §8.3 | 🟡 文件全就位（含 §8.2/8.3 更新）；Actions 实跑待推送 |

---

## Round 7（2026-10-13）—— B1/B4 代码层：AirPlay 接线 【阶段 B 用量 2/25，总 7/60】

**改动：**
1. `app/build.gradle.kts`：新增 flag `kbtv.flags.airplay` → `FLAG_AIRPLAY_PROTOCOL`（默认 false）
2. 新增 `airplay/AirPlayServer.kt`：纯 java.net HTTP 面（URL 推送子集）：accept 循环 + Content-Length 读取 → AirPlayRequest/Mapper → intent 回调；200/404/400 响应；异常不出界
3. 新增 `airplay/AirPlayProtocol.kt` : CastProtocol：启动 AirPlayServer + NsdManager 注册 `_airplay._tcp`/`_raop._tcp`（TXT 最小诚实子集：deviceid/srcvers/model；保真度留给 B2 设备验收调）；intent→PlaybackCommand 转换；trace 事件 5 种（started/registered/registration_failed/start_failed/stopped/intent）
4. `CastCoordinatorService.protocols`：flag 开时加入 AirPlayProtocol（关=原行为）
5. 新增 `AirPlayServerTest`（6 例，JVM 真 socket 回环：play→PlayUrl+200、rate→Pause、stop→Stop、未知路径 404、垃圾输入 400 且不杀服务器、stop 关监听）

**失败与修正：** 尾随 lambda 绑错参数（`onIntent` 在前、带默认值的 `executor` 在后）→ 改具名参数 `AirPlayServer(onIntent = {...})`，主代码与测试同步修。

**判据运行：** 三态 DEFAULT=0 / PLAN_ON=0 / AIRPLAY_ON=0，**89/89**（0f/0e，11 类）。新增累计 50。
判据 4 进展：代码层推送链路闭环（JVM 证）；mDNS 自发现/READY+isPlaying/重启恢复**待 TV 镜像实跑**。

## 净状态（截至 Round 7）
- 待用户：**TV 镜像安装（关键路径）**、git 提交确认、（后续）推远端/烟测/抓包
- 待镜像解锁：判据 2/3/4 实跑 → A6 重写 → B 设备验收 → C1/C2
- B5 镜像解码（canned H.264/p95 探针）：设备可用前不写不可验证的原生层代码（纪律）

---

## Round 8（2026-10-13）—— 判据 4 androidTest + C1 预分析 【阶段 B 用量 3/25，总 8/60】

**改动：**
1. 新增 `e2e/TestMediaServer.kt`（共享：运行时 WAV 生成 + localhost HTTP）
2. 新增 `e2e/AirPlayE2eTest.kt` 三用例，`Assume` 门控于 `FeatureFlags.airplayProtocol`——**默认 flag 跳过**（保判据 2 的默认运行绿）、`-Pkbtv.flags.airplay=true` 时执行：①`_airplay._tcp`+`_raop._tcp` NsdManager 自发现 ≤10s ②URL 推送（真 HTTP POST /play plist）→ ExoPlayer READY+isPlaying（端口从 JSONL trace 解析）③服务重启后 ≤10s 再发现
3. 新增 `docs/research/baidu-layer-2026-10.md`（C1 预分析）：friendlyName=「凯机投屏」CJK（DLNAUtils.java:73-75）、model 冒牌 "MSI MediaRenderer"、modelURL 指向 4thline（Utils.java:18-25）等静态证据 + 设备侧取证清单 + 修复优先级（ASCII 化需用户确认）

**判据运行：** `:app:compileDebugAndroidTestKotlin -Pkbtv.flags.airplay=true` → BUILD SUCCESSFUL in 5s，EXIT=0。
**设备实跑命令记录**（镜像就绪后）：判据 2 = `connectedDebugAndroidTest`（默认 flag，AirPlay 用例跳过）；判据 4 = `connectedDebugAndroidTest -Pkbtv.flags.airplay=true` + `python dlna_control_point.py`。

### Round 8 补记（lint 抓真 bug）

- 首次 lint（EXIT=0 非阻塞）：3 errors / 115 warnings。其中 **`AirPlayRequest.kt:56` NewApi 为真 bug**——`URLDecoder.decode(String, Charset)` 需 API 33，minSdk 23 设备会崩；JVM 测试绿是假象（桌面 JDK 支持该重载）。已改 `decode(s, "UTF-8")` + catch UnsupportedEncodingException 回退原文。
- 另 2 errors 为 vendored Cling `RemoteDevice.java` 既有缩进问题（vendor 只读政策，记录不修）。
- 修复后复跑：DEFAULT=0 / AIRPLAY_ON=0，**89/89 全绿**；lint → **2 errors**（均为 vendor）/ 115 warnings。

### Round 8 补记 2（控制点脚本干跑）

- 无设备干跑暴露子串误判：`"device" not in adb("get-state")` 被报错串「no **devices**/emulators found」绕过 → 误通过后又空等 30s。改为取输出最后一行与 `"device"` 精确比对。
- 复验：`REAL_EXIT=${PIPESTATUS[0]}`（修正此前管道吞退出码的测量错误）→ 无设备路径快速退出，**exit 2**，消息 `[-] no adb device` ✓。设备路径逻辑未动。

---

## Round 9（2026-10-13）—— Pixel_7 AVD 实机装置验证（重大）【阶段 B 用量 4/25，总 9/60】

**思路**：本机已有 Pixel_7 AVD，启动它不装任何新东西（不触发停止条件）——在手机 AVD 上先行验证 e2e 装置，消灭真机才会爆的雷。明确：**不满足判据 2 的「TV 模拟器」字面要求**，结果如实分层记录。

**连环排雷（4 个真 bug，全部当轮修复复验）：**
1. **D8 拒绝反引号名中的空格**（`Space characters in SimpleName ... prior to DEX version 040`，minSdk 23 无法用 040）→ androidTest 五个用例改驼峰反引号（`sinkPlayShouldDrive...`），文件留 NOTE
2. **`InetAddress.getLoopbackAddress()` 在 API 36 模拟器返回 ::1**：ServerSocket 绑 `[::1]`，所有 `127.0.0.1` 客户端 ECONNREFUSED——这是「播放器连不上」三连败的真根因（自探测判别法定位：`from /::` 暴露 IPv6）。改显式 `InetAddress.getByName("127.0.0.1")`（DlnaE2eTest 内嵌服务器 + TestMediaServer 两处）
3. 排查过程自伤若干（`Socket(String)` 构造不存在、内嵌方法未限定、缺 isRunning、管道吞退出码、陈旧 XML 误导）——每处当轮修正，教训已入 PROGRESS
4. 控制点脚本：UDN 发现改为**优先读 trace**（logcat 缓冲被冲掉时不失效）；`get-state` 精确匹配（见 R8 补记 2）

**实机结果（Pixel_7 / API 36 镜像）：**
| 项 | 结果 | 证据 |
|---|---|---|
| DlnaE2eTest（sink→READY+isPlaying、boot→RUNNING） | ✅ 2/2 | XML `tests=2 failures=0`，time 9.97s |
| 全套件 connectedDebugAndroidTest（默认 flag） | ✅ **EXIT=0** | 5 pass + 3 skip（AirPlay Assume 门控正确） |
| **AirPlay URL 推送→READY+isPlaying（flag-on）** | ✅ **真机通过** | flag 变体中该用例不在失败名单 |
| mDNS 自发现 ×2 | ❌ 环境限制 | 系统 `MdnsAdvertiser` 已接受双服务注册（含 TXT），但 `hostname: null`、回调未达、自发现 10s 超时——**该镜像 mDNS 环境限制**；测试保持严格（不放宽），TV AVD/真机复核 |
| Cling 流服务器 | ❌ 环境限制 | `NetworkAddressFactoryImpl: No usable network interface`（goldfish 网卡 supportsMulticast=false 被过滤；活跃网络=假 MOBILE[NR]）→ 无监听端口 → 控制点脚本止步端口扫描。**真机无此问题**（README 场景即真机电视） |
| **判据 3 trace 实机拉取** | ✅ **达成**（手机 AVD 层） | `adb shell run-as com.kbtv.caster cat files/trace/cast.jsonl` ✓；事件：service_created/protocol_started×2/airplay_started:39971/service_running/upnp_registered(UDN)；`grep '"event"'` 可数 |

**净判定：** 判据 2 的机制（DLNA e2e 双用例）与判据 3 的完整机制在真 Android 运行时验证通过；判据 2 字面（TV 模拟器）+ 控制点脚本实跑 + mDNS 发现 = TV AVD/真机项。gradle 测试结束会卸载应用（trace 被清），实机取证需手动 `installDebug` + 起服务（本轮已验证此路径）。

**Round 9 用量说明**：mDNS/Cling 两个失败各为「环境限制」非「同一思路失败 3 次」（代码侧证据齐全）。

---

## Round 10（2026-10-13）—— 用户授权安装 + TV 模拟器实跑（判据 2/4 达成）【阶段 B 用量 5/25，总 10/60】

**前置**：用户回复「授权安装」并恢复目标。仓库索引中 **API 34+ 无 TV 镜像**（全是手机/车载标签）→ 改用最新可用 `system-images;android-33;android-tv;x86`（32 位 x86，emulator 36 可跑；arm64 在 Windows 主机不可用）。安装 + `KBTV_TV`（tv_1080p）创建成功。

**TV 模拟器实跑结果（Android 13 / android-tv / API 33）：**
| 项 | 结果 | 证据 |
|---|---|---|
| **判据 2**：`connectedDebugAndroidTest`（默认 flag） | ✅ **EXIT=0**（TV 模拟器字面要求达成） | `TEST-emulator-5554 - 13-_app-.xml`：tests=5 f=0 e=0 skipped=3；DlnaE2eTest 双用例绿 |
| **判据 4 ①③**：mDNS 自发现 + 重启恢复 | ✅ | airplay flag 变体：**tests=5 f=0 e=0 skipped=0**（API 33 旧网络栈无 API 36 的 mDNS 限制；含 KBTVCaster 与 -raop 双服务 ≤10s 发现、重启 ≤10s 恢复） |
| 判据 4 ②：AirPlay URL 推送→READY+isPlaying | ✅（TV 模拟器复验） | 同上 |
| 判据 2 第三件：控制点 SOAP 脚本 | ❌ **模拟器不可达**（全镜像通病） | API 33 TV 镜像同样 `NetworkAddressFactoryImpl: No usable network interface`——goldfish 网卡被 Cling 过滤，流服务器永不监听。**唯一路径=真机电视**（README 工作流 `adb connect <电视IP>:5555`，脚本已支持 `--serial`） |

**净判定更新**：判据 0 ✅ / 1 ✅ / 2 ✅（字面达成；控制点件=真机电视路径，待用户执行或裁定口径）/ 3 ✅ / 4 的①②③ ✅（④ B5 剩余）。
**B5 解锁**：TV 模拟器闭环可用（55s 启动+2min 测试），canned H.264 解码与 p95 探针可按 §5 SOP 开发。

---

## Round 11（2026-10-13）—— Cling 配置层修复：判据 2 完全闭环（控制点脚本 PASS）【阶段 B 用量 6/25，总 11/60】

**动因**：用户问「必须我做的能不能不做」→ 验证后走 vendor 自有扩展点修复，消解真机依赖。

**三层根因与修复（KbtvUpnpServiceConfiguration.kt，零 vendor 修改）：**
1. **hostName 反射**（`AndroidNetworkAddressFactory.isUsableAddress` 注入 ICS 时代 DNS workaround，ART 在 API 33+ 拦截 → 每个地址判废）：无反射复刻 stock 判定——**顺带修复 Android 13+ 真机 DLNA 全挂的生产 bug**
2. **放宽回退**：严格过滤为空时重扫（goldfish 无组播网卡场景）
3. **模拟器 loopback 绑定**（goldfish/ranchu 判定）：adb forward 唯一可达 127.0.0.1 → stream server 加 loopback 连接器（真机零变化）
- manifest 服务切换至 `KbtvUpnpServiceImpl`（app/src/ 内，范围内）；DLNAUtils 绑定同步切换

**协议层两谜（脚本侧修复）：**
- 描述符路径 = `/upnp/dev/<udn>/desc`（vendored Namespace basePath 是 `/upnp`，非空！）——DLNAUtils 现把精确路径打进 trace（`upnp_descriptor_path` 事件），脚本原样采用
- **SOAPACTION 头值必须带双引号**（裸值被 Cling 判为缺失 → errorCode 401）

**结果：**
- 控制点脚本 TV 模拟器 **PASS（exit 0）**：trace 探活 → 宿主机 WAV(10.0.2.2) → /proc 端口发现 + adb forward → 描述符 200 → **SetAVTransportURI 200 → Play 200 → play_started 落 trace**（真实 Cling→zxt→DlnaProtocol→sink→ExoPlayer 全链）
- **🎯 判据 2 完全达成**（三件套齐，零真机依赖）；「真机电视验证」从用户清单永久移除
- 全量回归：JVM=0（89/89）+ CDAT 默认=0 + airplay=0（TV 模拟器）——manifest/Application/DLNAUtils 变更零回归
- 过程失败计数：编译错 4 次（import 包名、Enumeration 链式、`isLoopbackAddress` 用错类×2）全部当轮修复

**用户侧清单更新**：真机电视验证已消解；剩余 = iPhone 烟测（B5 后）/ 百度一分钟 / [厂商]抓包十分钟 / 推送一句话 / git 一句话 / A6·friendlyName 两确认。

---

## Round 12（2026-10-13）—— B5 解码路径闭环（判据 4④ 达成，判据 4 全绿）+ [厂商]三道门文档【阶段 B 用量 7/25，总 12/60】

**B5 根因链（MirrorDecodePathTest）**：
1. 残留 `decoder.configure()` 对已启动 codec 二次调用 → ISE（非缓存问题，栈行号说的是真话）
2. 8KB 定长 chunk ≈ 6-7 帧/AU，一 chunk 一 PTS → `queuedAt.remove` 每 chunk 仅命中一次 → 46 样本假象（实际 outputs≈300）
3. 修复：Annex-B 访问单元切分（slice NAL 边界，SPS/PPS 前缀归属后续 AU）→ 每输入 buffer 恰一帧，PTS 一一对应；加在途窗口（MAX_INFLIGHT_AUS=8）防积压污染延迟
4. 软件解码器显式指定（`c2.android.avc.decoder`，OMX 兜底）——byType 会选 goldfish 硬解（真字节流卡死）

**结果**：`AUs=900 outputs=901 latencies=900`，**p95=76.3-78.5ms < 150ms**，EXIT=0；trace 铁证 `b5_decode_stats frames=900 p95Micros=76323`（am instrument 手动装回 APK 后 run-as 拉取——connectedAndroidTest 跑完自动卸载）。**🎯 判据 4①②③④ 全部达成。**

**D（[厂商]）**：`docs/research/[linkx]-gate-2026-10.md`——三道门判定框架（DLNA 覆盖/AirPlay 覆盖/LeLink 必要性）+ 用户抓包 SOP（60s×2 pcap）+ 净室红线。结论区留白待用户 pcap。

**回归**：JVM 三旗 0 失败；连接测试默认+airplay 双轮 EXIT=0。提交：bd38a30（B5 测试+素材）、09d6f41（[厂商]文档）。

---

## Round 13（2026-10-13）—— A6 净室重写：GPL 层清零 + C1 取证 + javax.annotation 消解【阶段 A 用量 7/12，总 13/60】

**A6（用户已授权「三句话我都同意」）**：
- 新 `dlna/renderer/`（Kotlin，净室）：RendererDevice（LocalDevice 组装 + LastChange 推送线程，volatile 停机旗标语义保留）/ RendererTransport（AVTransport:1，REL_TIME-only seek 走 ModelUtil.fromTimeString）/ RendererControl（Master 通道音量/静音）/ RendererPlayer（传输状态机 + AudioManager STREAM_MUSIC）/ RendererConnectionManager（sink MIME 清单）；`DlnaBootstrap` 替换 DLNAUtils（Timber 化，UDN/descriptor trace 事件原样保留供控制点脚本）
- `com/zxt/dlna/**`（10 文件 GPL-2.0 来源）+ `DLNAUtils.java` 整树删除
- 折叠 C2：friendlyName=ASCII `KBTVCaster`；manufacturer/model 诚实字段（`KBTVCaster Project` / `KBTVCaster DMR` / 版本=versionName），不再冒用 MSI/4thline
- 死代码随重写消失：GstMediaListener、FileUtil/DevMountInfo/UpnpUtil 控制点侧工具、SharedPreferences 名字后缀特性（无调用方）

**验证**：主源编译过（修正：raw `LocalServiceBinder.read` 泛型、UnsignedIntegerFourBytes Long、Kotlin 属性 getter return）；JVM 三旗 89/89×3 全绿；连接测试默认+airplay 双轮 EXIT=0（真实 SOAP 控制点链路穿透新 renderer）。提交 91cf74a。

**C1 模拟器取证**：描述符 XML 实测（回环流服务 adb forward 拉取，存 temp/descriptor-new.xml）：friendlyName/manufacturer/modelName/modelNumber/modelDescription/X_DLNADOC/X_DLNACAP/UDN 全部达标（§6 表）；UDN 跨进程重启实测稳定。残余变量收窄为 SSDP 节奏（真机抓包）与 _lelink（判据 7）。

**javax.annotation（CDDL）**：唯一引用者为 vendored 死类 ManagedUpnpServiceConfiguration → `compileOnly` 化，APK 不再含 CDDL 字节码；LICENSE-SWITCH §3/§4 更新，分发门禁解除（发布动作仍需用户确认）。

**AGENTS.md**：架构图/结构树/WHERE TO LOOK/GOTCHAS/约定 全部按新现实刷新。

**净判定**：判据 0✅ 1✅ 2✅ 3✅ 4（全四项）✅；5-8 待用户物理动作。agent 可做工作全部完成。

---

## Round 14（2026-10-13）—— 真机电视全链路：ADB 接通 + SOAP 点播出画面 + AirPlay 可见【总 14/60】

**接通过程**：旧记录 IP .49 已失效（DHCP）；用户给新 IP .24。当贝桌面「USB调试」不开 TCP 5555；用户开系统开关后 5555 通（此固件 Android 6.0/MRA58K/arm64，API 23=恰好 minSdk）。全端口扫描定位过 3 个厂商 DMR（B站官方「我的小电视」:9958 / **[厂商] [VendorCast]「[预装投屏]M0」:49152** / 旧版「小盒精灵」:52946——MSI MediaRenderer+modelNumber 1+Cling 路径三重铁证）。

**SSDP 多播坑**：交换机 IGMP snooping 剪枝——必须 IP_ADD_MEMBERSHIP 加组后 M-SEARCH 才有应答（先前成功是泛洪窗口运气）。

**真机全链路验证（新净室 renderer）**：
- SSDP 发现第四接收端 KBTVCaster（UDN 与 trace 一致）+ 描述符字段核验（与模拟器字节级一致，2130B）
- 真实 SOAP SetAVTransportURI(file:///sdcard/kbtv_test.mp4)+Play → 200/200 → trace: play_started → playback_state=3(READY) → **is_playing=true，电视屏幕实际播放**（ExoPlayer→OMX.MS.AVC.Decoder 硬解+Surface 产品路径）
- **AirPlay 旗变体上真机**：_airplay/_raop 双注册（port 60012），PC 侧 mDNS PTR 查询电视应答 KBTVCaster → **iPhone 隔空列表即见**

**解码测试加固（真机暴露两 bug，已修，017aadf）**：API 23 无 Codec2；OMX 软解吃 900 帧吐 1 帧、MStar 硬解裸 buffer 模式吐 4 帧——但绑 Surface 后正常（产品路径无此问题）。修复：csd-0/1 进 format（OMX 时代需要）+ 喂帧 120s 死线（防在途门空转）+ 真机 byType 优先（产品同路径）。真机 decode-to-buffer 判定：该固件 OMX 不适用，判据 4④ 仍以 TV 模拟器（c2.android.avc.decoder 900 帧 p95 76ms）为准。

**[厂商]门更新（eb8d466）**：电视自带 [vendor] [VendorCast] → 门 3 预判不触发，待用户手机列表一句话确认。

**用户剩余**：iPhone 一按（电视已可见）/ 百度列表一扫 / git push 一句话。

---

## Round 15（2026-10-13）—— 用户真机实测三情报 → 两修复上机【总 15/60】

**用户实测反馈**：①iPhone 只见「[预装投屏]M0」不见 KBTVCaster；②百度列表**可见 KBTVCaster**（发现层修复实证 ✓）但「已连接」后不播，M0 可播；③澄清「小盒精灵」是别的项目非本项目旧版。

**取证（ADB 仍在手）**：trace 显示百度对我们只发 Stop×3、零 SetAVTransportURI；logcat 显示[厂商] SDK 的 SetAVTransportURI×5（540ms 重试风暴）发给了「小盒精灵」(pid 2837, GstAVTransportService 标签=TVRemoteIME 家族)。推送 URI 携带 `protocolInfo="http-get:*:video/mp4:DLNA.ORG_PN=MP3;..."` → [厂商]按 **PN profile 匹配 Sink**，我们的裸 MIME 清单被判「不支持」→ 不推。

**修复一（播放）**：RendererConnectionManager SinkProtocolInfo 裸 MIME → **25 条含 DLNA.ORG_PN profile + 万能通配符 `http-get:*:*:*`**。网络侧 GetProtocolInfo 实测：通配符在列、PN 在列。
**修复二（iPhone 可见）**：AirPlay TXT deviceid 从 UUID → **MAC 格式**（真机读到 wlan0 真 MAC 70:20:84:…；兜底 ANDROID_ID MD5 六字节），补 features=0x77（仅推送不含镜像）+ flags；_raop 补 pw/cn/ch/et。mDNS 实测 TXT 齐备。
**回退记录**：曾把百度 UA 扩到 pcs.baidu.com 渐进式 → 双旗回归测试（FeatureFlagsTest 路由矩阵）逮住与 legacy 分歧 → 按证据驱动原则回退（签名 URL 自带鉴权，先让推送能到达；若复测 403 再加），pcs 行为留回归用例。
**小盒精灵归属修正**：文档全面更正为「另一个 TVRemoteIME 家族项目」。

JVM 三旗全绿；airplay 旗 APK 已上电视（网络侧核验双绿）。**待用户复测**：百度再点一次 KBTVCaster + iPhone 列表。

---

## Round 16（2026-10-13）—— 百度闭环（判据 6 ✅）+ iPhone 可见性四层修复【总 16/60】

**用户复测**：百度网盘选 KBTVCaster **成功播放** —— trace 铁证：`SetAVTransportURI(pan.baidu.com/api/streaming?...type=M3U8_AUTO_1080)` → `play_started` → `state:3` → `is_playing:true`。**正是项目最初要修的 HLS+pan.baidu.com 路径**。🎯 判据 6 达成（真机真 App）。

**iPhone 仍不可见的根因链（四层，逐层剥开）**：
1. mdnsd 静默：Android 6 系统 mDNS 守护进程注册后只应答一次（PC 多轮实测字节级确认）→ [厂商]自养响应器的原因
2. 自建响应器（AirPlayMDns，纯 java.net）：发送通/接收死 → **WiFi 驱动组播过滤，需 MulticastLock**
3. 多网卡坑：networkInterface 需固定主网卡（wlan0>eth0），否则应答从 uap0/p2p0 出去进黑洞
4. 排障烟雾弹：**我的 PC 探针 QDCOUNT=0**（畸形查询）——修好探针后一切正常；「某次成功」实为旁听它应答 iPhone/mdnsd 的正确查询
5. 命名冲突回归：API 33 上 NsdManager+自建响应器同实例双播 → mdnsd 改名 → AirPlayE2eTest 断言原名失败（模拟器回归逮住）→ **API<24 才启用自建响应器**分层，模拟器 3/3 复绿

**终态**：真机响应器 wlan0 上对正确查询应答 ×4（TXT: deviceid=真MAC/features=0x77,0x1E/vv=2/flags=0x4）；模拟器 NsdManager 路径不受影响。提交 77520d2。**待用户 iPhone 复测**（注意指引：看视频 App 播放器的 AirPlay 图标列表；控制中心「屏幕镜像」列表按设计不出现——features 不含镜像位）。

---

## Round 17（2026-10-13）—— Phase 2 开工（M1）：底本 vendor + 首次 native 构建，卡点=宿主 OpenSSL【Phase 2 用量 1/25】

**完成**：
- 底本 jqssun/android-airplay-server（GPLv3）勘察：视频字节流经 `onVideoData` 上抛 Kotlin（接我们 B5 验证过的 MediaCodec 路线）；audio_engine 为独立 C ABI（可桩化）；dnssd shim=NsdManager 后端（接我们双路 mDNS）
- vendor：cpp 整树（UxPlay/libplist/openssl-cmake 子模块 @上游 pinned commit；ffmpeg 子模块不取）+ bridge 三 Kotlin（保留原包名保 JNI 符号）→ `app/src/main/cpp/`、`app/src/main/java/io/github/jqssun/airplay/bridge/`
- 剥离：CMake 去 oboe/ffmpeg；audio_engine_stub.cpp（ABI 同形，M4 换真引擎）
- gradle：externalNativeBuild + NDK 28.2 + arm64-v8a/x86_64 + ANDROID_STL=c++_shared
- 首建 12 分钟：**UxPlay 核心+libplist 全部编过（仅警告）**，卡在 openssl-cmake ExternalProject——`./Configure`+make 需 Unix 宿主，Windows cmd 死路

**宿主 make 获取三连败**（按 SPEC 换路线）：choco（非管理员拒）→ SF/gh（页面/无资产）→ msys2 包提取（mingw32-make.exe 三环境静默零输出，疑似被拦）。**R18 起：WSL(Ubuntu 24.04, make/gcc/perl 齐全) + Linux NDK r28b 一次性预编译 OpenSSL → `cpp/prebuilt/openssl/<abi>/libcrypto.a` 进仓库**（app/libs JAR 同模式），CMake 已切 IMPORTED 目标。脚本 `scripts/build-openssl-wsl.sh`（可复现、来源可溯）。
