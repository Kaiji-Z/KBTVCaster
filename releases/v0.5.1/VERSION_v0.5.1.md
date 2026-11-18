# Version 0.5.1

**Release Date**: 2026-11-18  
**Status**: Released  
**Previous Version**: v0.5.0

## What's New

**播放器菜单（实验性，`app-release-menu.apk` 变体启用）**：DLNA 播放中按遥控器 **MENU** 键呼出画面内菜单，方向键导航、OK/左右切换：

- **画面翻转 180°** —— 特殊视频临时倒转观看；翻转期经 TextureView 渲染，关闭即回默认合成路径
- **播放速度** —— 1.0 / 1.25 / 1.5 / 2.0x 档位循环
- **循环播放** —— 关/开
- **画面比例** —— 适合 / 铺满 / 放大

菜单开启时上下键移动选择、左右键调值、OK 确认、BACK/MENU 关闭；音量与媒体键不受影响。菜单关闭或未启用时所有键位维持原有行为。决策逻辑为纯状态机（`ui/PlayerMenu.kt`），10 个 JVM 用例覆盖；菜单操作写入 trace（`player_menu` 事件）。默认 flag 关闭（`kbtv.flags.playerMenu`），本版以独立 APK 变体 `app-release-menu.apk` 分发；真机（Android 6 / v7a）遥控器 MENU 键与 TextureView 流畅度待现场复核。

**CI**：release workflow 新增第三个签名 APK 变体（默认 / airplay 镜像 / player menu），均过 apksigner 校验。

## Recent Commits

```
8ee4bc8 Build player-menu APK variant in release workflow
4cfe6b6 Register playerMenu flag in verification docs
6fa2775 Wire on-player menu to remote keys in MainActivity
a88561d Add playerMenu feature flag
d8e29f8 Add PlayerMenu pure state machine with JVM tests
c57cd54 Record v0.5.0 release
```

## Rollback

```bash
git checkout v0.5.0
```
