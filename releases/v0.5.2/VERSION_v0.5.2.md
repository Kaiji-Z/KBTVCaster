# Version 0.5.2

**Release Date**: 2026-11-26  
**Status**: Released  
**Previous Version**: v0.5.1

## What's New

**修复：播放器菜单「画面翻转」无效**（v0.5.1 menu 变体的缺陷，SHARP 真机确认）。

- **根因**：`applyFlip` 按 `playerMenu.flipped` 反推当前播放面，但状态机在进入该方法前已翻转此 flag，导致 `from === to` 早退——开/关两个方向都永远空转，画面纹丝不动。
- **修复**：改用显式追踪的 `attachedPlayerView`（当前实际挂播放器的视图）作为唯一事实来源；服务重绑时画面回默认面、翻转意图归零（`PlayerMenu.resetFlip`，含新增 JVM 用例）。
- **真机机器验证**（SHARP Android 6 / v7a，debug+trace 包）：SSDP 发现→SOAP 推流→adb 按键驱动→截屏比对——翻转后画面 180° 倒置（烧录时码从左上角移到右下角倒置）、色条顺序反转、`player_menu` trace 事件链完整（open/flip on/close/flip off/close）。已知小瑕疵：换面瞬间解码器重渲染会偏移一帧（时码 61→60），不影响使用。
- 遥控器 MENU 键在 SHARP 遥控器上确认可用；菜单浮层在真机渲染正常（选中态/档位值显示正确）。

## Recent Commits

```
5d3bb69 Fix flip toggle never switching player surface
```

## Rollback

```bash
git checkout v0.5.1
```
