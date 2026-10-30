# FACTS · Clean-room 提取台账（Phase 3）

> 合规记录：本文件只记**事实条目**（行为/参数/序列/应答形状）与其在我方的落点。
> 乐播反编译产物（代码/资源/字符串表）一行不进仓库——铁律见 .goal/SPEC.md §0。
> 每轮提取后追加；G 编号对应 SPEC §3。

## 历史已提取（Phase 2 期间，散见 git 历史，此处归档）

| # | 事实 | 来源 | 我方落点 | 状态 |
|---|---|---|---|---|
| F1 | 乐播镜像音频 AAC-ELD 走 fdk-aac **软件解码**（aacDecoder_Open/ConfigRaw/Fill/DecodeFrame 符号），非平台 OMX AAC | HPPlay 8.2.04 原生库字符串/符号 | `cpp/audio_decoder.h` FfmpegAacDecoder 软解优先、AMediaCodec 兜底 | ✅ 已落地（audio_pipeline 探针 131/131） |
| F2 | 按机型切换低延迟键（nLowlatencyType / bLowlatency 每设备键名） | 乐播机型配置逻辑 | 解码器/音频引擎配置路径（SHARP 键值待 G4 补全） | ◐ 部分 |
| F3 | csd-0 参数集 blob（机型相关）注入解码器配置 | 同上 | MirrorVideoRenderer csd 路径（本机 blob 待 G4 提取） | ◐ 部分 |
| F4 | 渲染架构 = SurfaceTexture + set_surface（解码→纹理→上屏，非直接 surface 模式） | liblelink 字符串 | `airplay/MirrorGlDisplay.kt`（GL 直通管线，真机 60fps p95 34-71ms） | ✅ 已落地 |
| F5 | nohold 接管语义（新连接驱逐旧连接） | 乐播行为观察 | UxPlay `raop.c` nohold=true + `nativeInit` 参数 | ✅ 已落地 |

## 2026-10-30 M0 会话日志实证（运行时观察，非反编译）

| # | 事实 | 观察 | 我方落点 | 状态 |
|---|---|---|---|---|
| F6 | TCP×3 通道（控制/数据/事件），无 UDP 镜像流 | `netstat -un` 空 + 三条 ESTABLISHED | 传输层与我们同构——**排除传输差异假设** | ✅ 结案用 |
| F7 | `mirror_frame_store` 帧仓库：逐帧 nNetDelay/nDeltaPts/nDeltaClock 追踪 | hpplay-base 日志 | G3 候选（仅当 G1 需要） | 待 G1 判定 |
| F8 | `ScreenStream` 每秒心跳 | 同上 | G1 候选 | 待 G1 判定 |
| F9 | **stopPrevious**：内容切换时内部会话拆掉重建（stop/dying/latest 三态） | hpplay-java Dispatcher 日志 | G1 主嫌疑——我方会话切换语义对齐目标 | 待提取细节 |
| F10 | 音频 ~170KB 缓冲超限进丢帧模式（drop frame mode） | mirror_audio 日志 | 音频引擎已有自适应缓冲；丢帧模式待比对 | G4/G3 候选 |

## 结案性证据（勿重查，引用即可）

- iOS 870.14.1 发送端在密集流内切换后整体冻结（四信道同秒变哑）；接收端五信道无罪
  （复现器 12 连翻全绿 / RTSP 全 200 / NTP 52-167ms 零校正 / 音频零解码错 / 对 UxPlay
  master 无协议差异）——2026-10-30 取证，详见 git log 2026-10-30 与 VERIFICATION.md §10 #6
- MStar 流内切换的 BadParameter/nBufferCountActual 拒绝是噪音（解码器无视照跑）
- MStar 冷拒只拦合成流；真实流可用（warmup cold ≠ 会话失败）

## Phase 3 新提取（G1/G2/G4 进行中）

### Round 25（2026-10-31 深夜，strings 级，Ghidra 待深挖）

| # | 事实 | 来源（~/rea-work，仓库外） | 我方落点 | 状态 |
|---|---|---|---|---|
| F11 | HPPlay 8.2.04 APK **只带 armeabi-v7a** 32 位库（lib 清单实证）；真电视以 32 位 ABI 跑 v7a liblelink.so | `~/rea-work/app.apk` lib/ 清单 | 分析对象选型：v7a 即真机实跑版本 | ✅ |
| F12 | 乐播 AirPlay 栈 = **UxPlay/RAOP 族分支**：`./lelink/sink/rtp_stream/raop_buffer.c`（raop_buffer_queue/handle_resends/flush 同名 API）+ lighttpd 系 httpdserver（fdevent/request/response/server）+ libplist + 自研 ScreenStream/mirror_frame_store/mirror_mediacodec_wrapper | liblelink.so `__FILE__` 串全集 | G1 策略改为分支差异比对 | ✅ |
| F13 | 解码器包装器：init 失败 **sleep 后重试**（"sleep, and will try init_decoder again"）；`use fixed resolution: %dx%d->%dx%d` 尺寸重映射；厂商解码器名表（OMX.allwinner.video.decoder.avc 等）；low-latency 键名 `low-latency`/`lowlatency` | liblelink.so 串邻域（init_decoder 函数区） | G2 重试策略 / G4 键名对照 | 待 Ghidra 深挖 |
| F14 | /info **displays 数组**模板（width/height/rotation:false/widthPhysical:false/widthPixels/heightPixels/refreshRate 60/maxFPS %d）——与我方 vendored raop_handlers.h:215-234 同构 | liblelink.so plist 模板 0x450978 区 | 排除"displays 缺失"差异假设（双方都有） | ✅ 结案 |
| F15 | 会话类型矩阵：ios5.0 airplay screen/audio、lelink screen/audio、itunes airplay audio、unknown airplay screen | liblelink.so 串（0x44be0b 区） | 会话语义对照背景 | ✅ |

**乐播源文件地图**（`__FILE__` 全集）：common/libplist、common/libxml2、httpdserver/（lighttpd 系 11 文件）、sink/mirror_player/mediacodec_wrapper.c、sink/rtp_stream/{netutils,raop_buffer}.c、sink/sdp.c。
