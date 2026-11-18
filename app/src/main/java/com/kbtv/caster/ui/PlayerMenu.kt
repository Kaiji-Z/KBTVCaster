package com.kbtv.caster.ui

import android.view.KeyEvent

/**
 * 播放器菜单纯状态机：菜单打开期间由 MainActivity 把遥控器键码喂给
 * [handleKey]，产出对播放器/UI 的副作用决定。UI 层只执行效果，不做决策
 * ——决策留在这里，才可被 JVM 单测直接断言。
 *
 * 键码直接引用 KeyEvent 编译期常量（编译为字面量内联，JUnit 无需 Android
 * 运行时）；非菜单键（媒体键/音量键）返回 null，交还原有按键处理。
 */
class PlayerMenu {

    /** 菜单条目；ordinal 顺序即显示顺序 */
    enum class Item(val label: String) {
        FLIP("画面翻转"),
        SPEED("播放速度"),
        REPEAT("循环播放"),
        ASPECT("画面比例"),
    }

    /** 画面比例档位（映射 PlayerView.resizeMode：FIT/FILL/ZOOM） */
    enum class AspectMode(val label: String) { FIT("适合"), FILL("铺满"), ZOOM("放大") }

    /** 菜单键的处理结果；MainActivity 据此更新画面与播放器 */
    sealed class Effect {
        data object SelectionMoved : Effect()
        data class FlipChanged(val flipped: Boolean) : Effect()
        data class SpeedChanged(val speed: Float) : Effect()
        data class RepeatChanged(val repeatAll: Boolean) : Effect()
        data class AspectChanged(val mode: AspectMode) : Effect()
        data object Close : Effect()
    }

    /** 变速档位（OK/右键正向循环，左键反向） */
    val speeds = listOf(1.0f, 1.25f, 1.5f, 2.0f)

    /** 菜单条目快照（测试与 UI 渲染共用同一顺序来源） */
    val items: List<Item> = Item.entries.toList()

    var selectedIndex = 0
        private set
    var flipped = false
        private set
    var speedIndex = 0
        private set
    var repeatAll = false
        private set
    var aspect = AspectMode.FIT
        private set

    val speed: Float get() = speeds[speedIndex]

    /** 菜单打开期间的按键 → 效果；null = 非菜单键，交还原有处理 */
    fun handleKey(keyCode: Int): Effect? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> {
            moveSelection(-1)
            Effect.SelectionMoved
        }
        KeyEvent.KEYCODE_DPAD_DOWN -> {
            moveSelection(+1)
            Effect.SelectionMoved
        }
        KeyEvent.KEYCODE_DPAD_LEFT -> activate(-1)
        KeyEvent.KEYCODE_DPAD_RIGHT -> activate(+1)
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> activate(+1)
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_MENU -> Effect.Close
        else -> null
    }

    /**
     * Activity/服务绑定重建后，按播放器实际参数对齐菜单显示（速度与循环
     * 挂在播放器实例上跨投屏存续）。翻转是视图层状态，重建即回正，不在此列。
     */
    fun syncFromPlayer(speed: Float, repeatAll: Boolean) {
        speedIndex = speeds.indexOf(speed).takeIf { it >= 0 } ?: 0
        this.repeatAll = repeatAll
    }

    /** 条目右侧的当前值文案 */
    fun labelFor(item: Item): String = when (item) {
        Item.FLIP -> if (flipped) "开" else "关"
        Item.SPEED -> "${formatSpeed(speed)}x"
        Item.REPEAT -> if (repeatAll) "开" else "关"
        Item.ASPECT -> aspect.label
    }

    private fun formatSpeed(value: Float): String =
        if (value == value.toInt().toFloat()) "${value.toInt()}.0" else value.toString()

    private fun moveSelection(delta: Int) {
        selectedIndex = Math.floorMod(selectedIndex + delta, items.size)
    }

    private fun activate(direction: Int): Effect = when (items[selectedIndex]) {
        Item.FLIP -> {
            flipped = !flipped
            Effect.FlipChanged(flipped)
        }
        Item.SPEED -> {
            speedIndex = Math.floorMod(speedIndex + direction, speeds.size)
            Effect.SpeedChanged(speed)
        }
        Item.REPEAT -> {
            repeatAll = !repeatAll
            Effect.RepeatChanged(repeatAll)
        }
        Item.ASPECT -> {
            val modes = AspectMode.entries.toList()
            aspect = modes[Math.floorMod(modes.indexOf(aspect) + direction, modes.size)]
            Effect.AspectChanged(aspect)
        }
    }
}
