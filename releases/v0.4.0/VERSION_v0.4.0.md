# Version 0.4.0

**Release Date**: 2026-10-28  
**Status**: Released  
**Previous Version**: v0.3.0

## What's New

### AirPlay 镜像真机成熟（MStar / Android 6 实测）

- **GL 直通渲染管线**：解码 → SurfaceTexture → 私有 EGL 上下文 → OES 外部纹理 → 显示 Surface（商用接收端同款架构），等比 letterbox **跟随解码器实际输出格式**（流内分辨率切换实时生效），真机实测 60fps 稳定、数千帧零丢帧、帧延迟 p95 ≈ 18-98ms
- **音频链路打通**：修复线上路径引擎从未配置导致的静默丢包；ffmpeg 补编 AAC 软解（LGPL 子集）；抖动缓冲适配"发送端不重传"的镜像音频（丢包跳洞，10ms 毛刺替代永久静音）
- **停止/断开语义**：适配 iOS 停止镜像的静默行为（仅 `video_pause`、无 TEARDOWN）——暂停后 3 秒静默即结束会话；连接关闭与 TEARDOWN 110 双路触发；空闲看门狗 10s 收尾
- **固件怪癖防御**：surface 失败先原地重试 GL 两次（该固件 CPU 回拷路径无输出）；解码器重启预算 + 60 秒冷却探针（避免重启风暴搅深固件楔死）；**开机解码器热身器**——用内置合成测试流自动趟过 MStar 冷启动拒绝（新鲜安装也带 59KB asset）
- **可观测性**：音频链路五级计数器（收包/同步/出队/引擎/统计）+ 结构化 trace 事件全集，黑盒排障时代结束

### 32 位电视支持

- 新增 **armeabi-v7a** 预制品（OpenSSL + FFmpeg，`-D__ANDROID_API__=23`）并加入 ABI 列表——修复索尼 XR-85X90K 等 32 位电视无法安装的问题（[#5](../../issues/5)）

### 说明

- AirPlay 镜像仍为 **flag 门控**（`-Pkbtv.flags.airplay=true`）：Releases 附 `app-release-airplay.apk`（含镜像）与 `app-release.apk`（仅 DLNA）双包
- 已知边界：个别 MStar 固件冷启动后解码器首次使用会被拒，热身器会自动重试直至解锁；若与厂商内置投屏服务并发使用，后者可能先占解码器

## Recent Commits

```
3ee623d Fall back to a bundled synthetic stream for the decoder warmup
7b057d9 Add armeabi-v7a prebuilts and ABI target for 32-bit TVs
fa8dbcc Drive letterbox from decoder output format and split audio probe counters
819222d Debounce telemetry resolution flapping before tearing down the codec
70ec92f Warm the MStar decoder at protocol start to survive cold-boot refusal
2c41f9d Back off decoder restarts to one probe per minute when budget exhausts
fd06837 Actually retry GL twice before blit fallback (prior commit lost its own edit)
b820d12 Configure audio engine on live path and skip unfilled resend holes
e81d376 Close mirror session on video pause silence and probe the audio sync gate
56542ea End mirror session on connection close/reset and tighten idle reap to 10s
74cf00a Retry GL twice before falling back to the never-outputting blit tier
e9154fe Degrade to blit on any zero-frame surface-mode failure
0eb2ed9 Extend mirror idle timeout to 30s and reopen on lock-screen resume
3a22e16 Make mirror idle-watchdog timestamp volatile across callback threads
d4b1977 Add structure assertion and resolution matrix to decoder tests
2e2bfe8 Close mirror session after 10s idle and harden audio stats timer
9e16099 Add output-stall watchdog, blit degrade, and restart budget refresh
059a23b Fix GL display compositing: UV byte offset, opaque alpha, pre-sized texture, letterbox
c044e53 Ignore local scratch directories
23ddbf2 Rebuild prebuilt OpenSSL without assembly
adf12f3 Fix NTP thread leak and reject unknown FairPlay types in vendored UxPlay
13cfb04 Decode mirror audio in software via ffmpeg and add engine diagnostics
b0359fa Render mirror video through a GL forward tier on the MStar field TV
```

## Rollback

```bash
git checkout v0.3.0
```
