package com.kbtv.caster.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放器菜单状态机纯逻辑测试。
 * 键码用原始 Int（与 android.view.KeyEvent 编译期内联值一致），
 * 避免 JVM 单测触碰 Android 运行时。
 */
class PlayerMenuTest {

    // KeyEvent 编译期常量值（PlayerMenu 内联引用，测试用字面量镜像）
    private val dpadUp = 19
    private val dpadDown = 20
    private val dpadLeft = 21
    private val dpadRight = 22
    private val dpadCenter = 23
    private val enter = 66
    private val back = 4
    private val escape = 111
    private val menu = 82
    private val volumeUp = 24
    private val mediaPlay = 126

    @Test
    fun `should move selection down and wrap around`() {
        val m = PlayerMenu()
        assertEquals(PlayerMenu.Item.FLIP, m.items[m.selectedIndex])

        assertTrue(m.handleKey(dpadDown) is PlayerMenu.Effect.SelectionMoved)
        assertEquals(PlayerMenu.Item.SPEED, m.items[m.selectedIndex])

        m.handleKey(dpadDown)
        m.handleKey(dpadDown)
        assertEquals(PlayerMenu.Item.ASPECT, m.items[m.selectedIndex])

        m.handleKey(dpadDown) // wrap
        assertEquals(PlayerMenu.Item.FLIP, m.items[m.selectedIndex])

        m.handleKey(dpadUp) // wrap back
        assertEquals(PlayerMenu.Item.ASPECT, m.items[m.selectedIndex])
    }

    @Test
    fun `should toggle flip on activation regardless of direction`() {
        val m = PlayerMenu() // FLIP selected by default

        val on = m.handleKey(dpadCenter) as PlayerMenu.Effect.FlipChanged
        assertTrue(on.flipped)

        val off = m.handleKey(dpadLeft) as PlayerMenu.Effect.FlipChanged
        assertTrue(!off.flipped)
    }

    @Test
    fun `should cycle speeds forward and backward with wrap`() {
        val m = PlayerMenu()
        m.handleKey(dpadDown) // select SPEED

        val order = listOf(1.0f, 1.25f, 1.5f, 2.0f)
        order.drop(1).forEach { expected ->
            val e = m.handleKey(dpadRight) as PlayerMenu.Effect.SpeedChanged
            assertEquals(expected, e.speed)
        }
        val wrap = m.handleKey(dpadRight) as PlayerMenu.Effect.SpeedChanged
        assertEquals(1.0f, wrap.speed)

        val down = m.handleKey(dpadLeft) as PlayerMenu.Effect.SpeedChanged
        assertEquals(2.0f, down.speed)
    }

    @Test
    fun `ok key activates selected speed item too`() {
        val m = PlayerMenu()
        m.handleKey(dpadDown) // select SPEED

        val e = m.handleKey(enter) as PlayerMenu.Effect.SpeedChanged
        assertEquals(1.25f, e.speed)
    }

    @Test
    fun `should toggle repeat`() {
        val m = PlayerMenu()
        m.handleKey(dpadDown)
        m.handleKey(dpadDown) // select REPEAT

        val on = m.handleKey(dpadCenter) as PlayerMenu.Effect.RepeatChanged
        assertTrue(on.repeatAll)

        val off = m.handleKey(dpadCenter) as PlayerMenu.Effect.RepeatChanged
        assertTrue(!off.repeatAll)
    }

    @Test
    fun `should cycle aspect fit fill zoom with wrap`() {
        val m = PlayerMenu()
        m.handleKey(dpadDown)
        m.handleKey(dpadDown)
        m.handleKey(dpadDown) // select ASPECT

        assertEquals(PlayerMenu.AspectMode.FILL, (m.handleKey(dpadRight) as PlayerMenu.Effect.AspectChanged).mode)
        assertEquals(PlayerMenu.AspectMode.ZOOM, (m.handleKey(dpadRight) as PlayerMenu.Effect.AspectChanged).mode)
        assertEquals(PlayerMenu.AspectMode.FIT, (m.handleKey(dpadRight) as PlayerMenu.Effect.AspectChanged).mode)
        assertEquals(PlayerMenu.AspectMode.ZOOM, (m.handleKey(dpadLeft) as PlayerMenu.Effect.AspectChanged).mode)
    }

    @Test
    fun `should close on back escape or menu key`() {
        val m = PlayerMenu()
        assertTrue(m.handleKey(back) is PlayerMenu.Effect.Close)
        assertTrue(m.handleKey(escape) is PlayerMenu.Effect.Close)
        assertTrue(m.handleKey(menu) is PlayerMenu.Effect.Close)
    }

    @Test
    fun `should return null for non-menu keys so they reach the legacy handler`() {
        val m = PlayerMenu()
        assertNull(m.handleKey(volumeUp))
        assertNull(m.handleKey(mediaPlay))
    }

    @Test
    fun `labels should reflect current values`() {
        val m = PlayerMenu()
        assertEquals("关", m.labelFor(PlayerMenu.Item.FLIP))
        assertEquals("1.0x", m.labelFor(PlayerMenu.Item.SPEED))
        assertEquals("关", m.labelFor(PlayerMenu.Item.REPEAT))
        assertEquals("适合", m.labelFor(PlayerMenu.Item.ASPECT))

        m.handleKey(dpadCenter) // flip on
        m.handleKey(dpadDown)   // SPEED, keep 1.0 — 先开翻转
        m.handleKey(dpadRight)  // 1.25x
        m.handleKey(dpadDown)   // REPEAT
        m.handleKey(dpadCenter) // on
        m.handleKey(dpadDown)   // ASPECT
        m.handleKey(dpadRight)  // FILL

        assertEquals("开", m.labelFor(PlayerMenu.Item.FLIP))
        assertEquals("1.25x", m.labelFor(PlayerMenu.Item.SPEED))
        assertEquals("开", m.labelFor(PlayerMenu.Item.REPEAT))
        assertEquals("铺满", m.labelFor(PlayerMenu.Item.ASPECT))
    }

    @Test
    fun `syncFromPlayer should snap menu display to live player state`() {
        val m = PlayerMenu()
        m.syncFromPlayer(speed = 2.0f, repeatAll = true)
        assertEquals(2.0f, m.speed)
        assertTrue(m.repeatAll)

        // 播放器速度不在档位表内时回落到 1.0，不越界
        m.syncFromPlayer(speed = 3.7f, repeatAll = false)
        assertEquals(1.0f, m.speed)
        assertTrue(!m.repeatAll)
    }
}
