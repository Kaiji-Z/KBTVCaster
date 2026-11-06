# Version 0.5.0

**Release Date**: 2026-11-06  
**Status**: Released  
**Previous Version**: v0.4.1

## What's New

Phase-3 行为对齐与镜像会话稳定性 —— 聚焦快速切换不冻结、冷启动出画面、断开重连即回。

### 镜像会话稳定性（四类会话杀手连修）

- **每次断开后的 5 秒全栈重建**：连接关闭后排定的栈重建会在快速切换（<2s 间隔）时落到下一个会话中途、杀死正在投屏的会话——新会话开始时现在会解除这颗定时炸弹（6e5dd88）
- **wifi churn 杀活会话**：电视 ConnectivityService 每 30 余秒冒出新网络代理，每次都重启 AirPlay 栈、掐断正在进行的投屏；活跃会话不再受网络回调扰动（c749c34）
- **过期解码器错误误计**：会话中途 Surface 到位退役旧解码器后，迟到的异常被误判为 surface 模式饿死、把整场会话钉死在永不渲染的降级档——现按实例归因（ff48196）
- **JNI 线程退出杀进程**：镜像原生线程退役时未解绑 VM，Android 6 直接终止整个应用——现随线程退出自动解绑（466ab15）

### 冷启动与重试节奏（乐播行为 clean-room 移植）

- **冷启动解码器耐心**：电视断电重启后 MStar 解码器在长时间窗口内不发放输入缓冲，现在按乐播的方式等待而不是重启烧预算（ffc2bbd）
- **失败重试 500ms 节流**：按解码器实例（而非累计）判断初始化失败，失败重启不再 170ms 连击解码器（32d1b5a）
- **连接中提示**：镜像建立期间显示「正在连接投屏…」加载动画，首帧渲染后隐藏——对齐乐播播放器先行体验（67484f4）
- 网络看门狗去噪：注册回执与重复网络代理不再触发栈重启（4f66afb）

### 验证体系（不再依赖人工投屏测试）

- **headless 镜像注入器**（`scripts/probe/mirror_inject.py`）：合成 AirPlay 发送端驱动完整接收管线（RTSP/镜像数据通道/解码/渲染），支持真机直连、adb 转发与模拟器（QEMU redir）三种注入路径；调试旗门控明文旁路，release 永不启用（d92cfe3、ec4dcc2）
- **机器门成绩**（SHARP/Android 6 真机）：断电冷启动直投 353 帧 p95=115ms；六轮快速会话每轮 353 帧零中断 p95≈6.7ms；断开重连锤 5/5（恢复 3-6ms）；assert_session 判 PASS
- **模拟器双旗 e2e**：注入器给模拟器喂会话取证 dump，7 个用例复活；airplay on/off 两旗失败集完全一致且均为真机专属豁免；JVM 全量三旗 EXIT=0
- 乐播放投屏行为提取事实账本（F1-F30）与逐轮记录见 `.goal/`（仅存档，不含任何反编译产物）

### 注意事项

- AirPlay 屏幕镜像仍为实验性：需 `-Pkbtv.flags.airplay=true` 构建启用；DLNA 投屏不受影响
- DRM 保护内容（爱奇艺等 App 的加密流）镜像为黑帧属系统限制，非本应用缺陷

## Recent Commits

```
ffca316 Record Round 32: teardown-rebuild bomb defused, G1 machine gates green
6e5dd88 Cancel the teardown-rebuild when a new mirror session starts
d87439a Record M5: emulator dual-flag e2e reverified via injector-fed dump
ec4dcc2 Support emulator and adb-serial injection targets in mirror injector
9053996 Record injector wire-format and field facts F26-F30
7cc0d74 Record Round 31: headless injector closed loop, three session-kill fixes
5b0a7b1 Ignore Python bytecode caches under scripts
c749c34 Never restart the AirPlay stack on network churn mid-session
32d1b5a Key the init-retry pacing off per-codec-instance frames
466ab15 Auto-detach JNI-attached native callback threads on exit
d92cfe3 Add headless mirror injector probe with debug-only plaintext bypass
ffc2bbd Wait out the cold-boot MStar input refusal instead of aborting into the dead tier
d1af9e8 Record Round 30: full behavior-port inventory complete
67484f4 Port the Lebo connecting-state UX and init-retry pacing
3727acb Record Round 29: watchdog fix, G4 knob table closes extraction, init retry detail
4f66afb Ignore network registration receipts and duplicate agents in the mirror watchdog
772ad72 Record Round 28: combined surface-race and reaper landing
ff48196 Stop stale codec errors and surface swaps from pinning sessions to the buffer tier
5f64ebd Revert "Roll back the async reaper to the morning-known-good receiver"
c7067df Record the rollback decision and open forensics debt
7e97a65 Roll back the async reaper to the morning-known-good receiver
2175468 Record the v2 regression lesson in Round 27 notes
9ca9bac Keep the mirror session-start path synchronous with a generation guard on teardown
7f632f4 Record Round 27: async reaper fix, storm probe evidence, restart-chain backlog
ba430c6 Add the same-connection SETUP storm probe
c9f834d Reap retired RAOP streams asynchronously instead of joining on the httpd thread
a76ba5d Record the mirror-player tuning knob inventory from strings
60c8581 Destroy previous stream instances on re-SETUP instead of leaking them
f9bb473 Round 25: forensics base, G1 first fix, gates green
d2bdaf4 Add the clean-room facts ledger seeded with extracted history
d1f7d7c Write Phase-3 clean-room behavior-alignment spec
4c5bdfb Record the sender-freeze closing state and the clean-room extraction plan
```

## Rollback

```bash
git checkout v0.4.1
```
