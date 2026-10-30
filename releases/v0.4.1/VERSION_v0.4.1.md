# Version 0.4.1

**Release Date**: 2026-10-30  
**Status**: Released  
**Previous Version**: v0.4.0

## What's New

### AirPlay 镜像：断开重连与稳定性攻坚（真机实测驱动）

- **断开后重连修复**：网络接口变化自动重绑原生栈（长跑僵死根因之一）；会话回调全部移出原生线程（httpd 线程不再被收尾工作锁死）；**栈重建仅在真正断连时执行**（换视频的会话关闭不再触发重建）；重建挂死时 12 秒看门狗升级为进程级自愈（Alarm + START_STICKY 自动复活）
- **横屏视频画面比例**：信源遥测在竖横切换瞬间会报出畸形裁剪值（如 1×9 像素），画面会被缩成一个点——已加防御，比例始终跟随解码器真实输出格式
- **停止镜像**：iOS 停止只发暂停信号不发拆链命令，现 3 秒静默即收屏；锁屏恢复自愈重开保留
- **崩溃修复**：API 23 设备上网络监听接口不存在导致的启动崩溃（影响所有 Android 6 电视）
- **可观测性与无头验证体系**（工程）：结构化 trace 跨重启持久化；音频链路五级计数器；探针工具入库（RTSP 健康 / mDNS 可见性 / **回环压测锤** / 会话验收断言，`scripts/probe/`）；分辨率翻转无头复现器（androidTest）——跨会话缺陷从此装机前被机器拦截

### 已知边界

- **同一镜像会话内快速连播切换视频**（竖横快速交替）可能触发 iOS 发送端冻结（约 2-3 次切换后手机停止发送；接收端各信道经逐层取证确认无责，乐播同类接收器行为不同）。**规避**：切换视频间隔约 2 秒；或停止镜像后重投。深度修复（接收端会话协议行为对齐商用接收器）已排期
- 冷启动后首次投屏若黑屏：内置解码器热身器会自动重试（MStar 固件怪癖，通常数分钟内自愈）

## Recent Commits

```
40356db Restore INFO log level for production builds
7c68ab1 Enable raop DEBUG logging and rotate incident dumps
c3bbf17 Add headless resolution-flip repro harness and raise the dump cap to 32MB
08bb01e Revert "Recreate the decoder on real in-band resolution switches"
ae6e5fd Recreate the decoder on real in-band resolution switches
7916d13 Document the no-hammer-during-live-session rule
dc512f9 Rebuild the native stack only when the connection truly dies
0a1bc0d Collect all mDNS answers before judging receiver visibility
52a778c Run stack rebuilds off-main with a process-restart watchdog
032ef89 Add device probe toolkit and session-loop hammer
17d103a Refresh verification diagnosis for the Phase-2 field rounds
ec9657d Rebuild the native AirPlay stack after every mirror session close
30b4280 Run session callbacks off native threads and reject degenerate crop dims
33e0b78 Restart the AirPlay stack when the network interface changes
```

## Rollback

```bash
git checkout v0.4.0
```
