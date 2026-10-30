// SPDX-FileCopyrightText: 2026 amphigourik
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.amphigourik.jolly

import org.junit.Assert.*
import org.junit.Test

class GamepadLogicTest {
    private val eps = 1e-5f
    private var clock = 0L
    private fun t(): Long { clock += 1000; return clock }
    private fun joy(m: PadMapping = Presets.JOYSTICKS) = GamepadLogic(9, m)
    private fun race() = GamepadLogic(9, Presets.COURSE)
    private fun snap(vararg p: Pair<Int, Float>) = AxisSnapshot(mapOf(*p))
    private fun GamepadLogic.axes(vararg p: Pair<Int, Float>) = onAxes(snap(*p), t())

    @Test fun deadzoneAndRescale() {
        assertEquals(0f, GamepadLogic.deadzone(0.05f, 0f), eps)
        assertEquals(0f, GamepadLogic.deadzone(0.12f, 0f), eps)
        assertEquals(0f, GamepadLogic.deadzone(-0.10f, 0.05f), eps)
        assertEquals(1f, GamepadLogic.deadzone(1f, 0f), eps)
        assertEquals(-1f, GamepadLogic.deadzone(-1f, 0f), eps)
        assertEquals(0.5f, GamepadLogic.deadzone(0.56f, 0.12f), eps)
        assertEquals(0f, GamepadLogic.deadzone(0.2f, 0.25f), eps)
        assertTrue(GamepadLogic.deadzone(0.3f, 0.25f) > 0f)
    }

    @Test fun combinePrecedence() {
        assertEquals(0.4f, GamepadLogic.combine(0.4f, 0.9f), eps)
        assertEquals(0.9f, GamepadLogic.combine(0f, 0.9f), eps)
    }

    // --- Preset JOYSTICKS ---
    @Test fun joysticksPreset() {
        val l = joy()
        val r = l.axes(PadAxis.X to 1f, PadAxis.Y to -1f, PadAxis.RIGHT_X to 1f, PadAxis.L2 to 1f)
        assertEquals(1f, r.speed, eps) // up = forward; left X and the L2 trigger are ignored
        assertEquals(SpeedSource.STICK, r.source)
        assertEquals(1f, r.turn, eps)
        assertEquals(100, ControlMapping.map(r.speed, 100, false))
        assertEquals(60, ControlMapping.map(r.turn, 60, false))
        val z = l.axes(PadAxis.Y to 0.05f, PadAxis.RIGHT_X to -0.05f)
        assertEquals(0f, z.speed, eps); assertEquals(0f, z.turn, eps)
        assertEquals(SpeedSource.NONE, z.source)
        assertEquals(-1f, l.axes(PadAxis.Y to 1f).speed, eps)
    }

    @Test fun joysticksR2LaunchEdge() {
        val l = joy()
        assertTrue(l.axes(PadAxis.R2 to 0.3f).actions.isEmpty())
        assertEquals(listOf(PadAction.ANIM_LAUNCH), l.axes(PadAxis.R2 to 0.7f).actions)
        assertTrue(l.axes(PadAxis.R2 to 0.9f).actions.isEmpty())
        assertTrue(l.axes(PadAxis.R2 to 0.4f).actions.isEmpty()) // not yet released below 0.3
        assertTrue(l.axes(PadAxis.R2 to 0f).actions.isEmpty())
        assertEquals(listOf(PadAction.ANIM_LAUNCH), l.axes(PadAxis.R2 to 1f).actions)
        // 400 ms debounce between sources: key right after the axis
        assertNull(l.onKey(GamepadLogic.BUTTON_THUMBR, clock + 100))
        assertEquals(PadAction.ANIM_LAUNCH, l.onKey(GamepadLogic.BUTTON_THUMBR, clock + 1000))
    }

    @Test fun keyOnlyR2LaunchesInJoysticks() {
        val l = joy()
        l.onTriggerKey(GamepadLogic.BUTTON_R2, true)
        assertEquals(listOf(PadAction.ANIM_LAUNCH), l.current(t()).actions)
        l.onTriggerKey(GamepadLogic.BUTTON_R2, false)
        assertTrue(l.current(t()).actions.isEmpty())
    }

    // --- Preset COURSE ---
    @Test fun coursePreset() {
        val l = race()
        assertEquals(0f, l.axes(PadAxis.R2 to 0.04f).speed, eps)
        assertEquals(1f, l.axes(PadAxis.R2 to 1f).speed, eps)
        assertEquals(SpeedSource.TRIGGERS, l.axes(PadAxis.R2 to 1f).source)
        assertEquals(-1f, l.axes(PadAxis.L2 to 1f).speed, eps)
        assertEquals(0f, l.axes(PadAxis.R2 to 1f, PadAxis.L2 to 1f).speed, eps)
        assertEquals(0.5f, l.axes(PadAxis.R2 to 1f, PadAxis.L2 to 0.525f).speed, eps)
        assertEquals(25, ControlMapping.map(l.axes(PadAxis.R2 to 0.525f).speed, 100, true))
        val r = l.axes(PadAxis.X to 1f, PadAxis.Y to -1f)
        assertEquals(0f, r.speed, eps) // Y ignored
        assertEquals(1f, r.turn, eps)
        assertEquals(0f, l.axes(PadAxis.X to 0.05f).turn, eps)
        assertEquals(0f, l.axes(PadAxis.RIGHT_X to 1f).turn, eps)
    }

    @Test fun courseR2DoesNotLaunch() {
        val l = race()
        assertTrue(l.axes(PadAxis.R2 to 1f).actions.isEmpty())
        assertEquals(PadAction.ANIM_LAUNCH, l.onKey(GamepadLogic.BUTTON_THUMBR, t()))
    }

    @Test fun courseRightFlickNoAutoRepeat() {
        val l = race()
        fun f(x: Float) = l.axes(PadAxis.RIGHT_X to x).actions
        assertTrue(f(0.5f).isEmpty())
        assertEquals(listOf(PadAction.ANIM_NEXT), f(0.7f))
        assertEquals(1, l.animIndex)
        assertTrue(f(1f).isEmpty())
        assertTrue(f(0.5f).isEmpty())
        assertTrue(f(0.1f).isEmpty()) // re-armed
        assertEquals(listOf(PadAction.ANIM_PREV), f(-0.8f))
        assertEquals(0, l.animIndex)
        assertTrue(f(-1f).isEmpty())
        f(0f)
        assertEquals(listOf(PadAction.ANIM_PREV), f(-0.8f))
        assertEquals(8, l.animIndex)
    }

    // --- Triggers: analog vs keys ---
    @Test fun triggerKeysOnlyPads() {
        val l = race()
        assertTrue(l.onTriggerKey(GamepadLogic.BUTTON_R2, true))
        assertEquals(1f, l.current(t()).speed, eps)
        l.onTriggerKey(GamepadLogic.BUTTON_L2, true)
        assertEquals(0f, l.current(t()).speed, eps)
        l.onTriggerKey(GamepadLogic.BUTTON_R2, false)
        assertEquals(-1f, l.current(t()).speed, eps)
        l.onTriggerKey(GamepadLogic.BUTTON_L2, false)
        assertEquals(SpeedSource.NONE, l.current(t()).source)
        assertFalse(l.onTriggerKey(GamepadLogic.BUTTON_A, true))
    }

    @Test fun analogTriggersIgnoreKeys() {
        val l = race()
        l.axes(PadAxis.R2 to 0.3f)
        l.onTriggerKey(GamepadLogic.BUTTON_R2, true) // the pad also sends the key
        val s = l.current(t()).speed
        assertEquals(GamepadLogic.triggerDeadzone(0.3f), s, eps)
        assertTrue(s < 0.3f)
    }

    // --- Arbitrary mappings ---
    @Test fun customSpeedOnRightStickInverted() {
        val m = PadMapping().withAxis(AxisAction.SPEED, AxisBinding(PadAxis.RY, inverted = true))
            .withAxis(AxisAction.TURN, AxisBinding(PadAxis.X, inverted = true))
        val l = joy(m)
        val r = l.axes(PadAxis.RY to -1f, PadAxis.X to 1f, PadAxis.Y to -1f)
        assertEquals(1f, r.speed, eps)
        assertEquals(-1f, r.turn, eps)
    }

    @Test fun speedFallsBackToTriggersWhenStickCentered() {
        val m = PadMapping().withAxis(AxisAction.SPEED, AxisBinding(PadAxis.Y, true))
            .withAxis(AxisAction.ACCELERATE, AxisBinding(PadAxis.R2))
        val l = joy(m)
        assertEquals(SpeedSource.TRIGGERS, l.axes(PadAxis.Y to 0.05f, PadAxis.R2 to 1f).source)
        assertEquals(SpeedSource.STICK, l.axes(PadAxis.Y to -1f, PadAxis.R2 to 1f).source)
    }

    @Test fun customL2AxisButtonAndHatJump() {
        val m = PadMapping()
            .withButtons(PadAction.ANIM_LAUNCH, listOf(ButtonBinding.AxisBtn(PadAxis.L2, true)))
            .withButtons(PadAction.JUMP_HIGH, listOf(ButtonBinding.AxisBtn(PadAxis.HAT_Y, false)))
        val l = joy(m)
        assertEquals(listOf(PadAction.ANIM_LAUNCH), l.axes(PadAxis.L2 to 0.8f).actions)
        assertTrue(l.axes(PadAxis.L2 to 0.9f).actions.isEmpty())
        assertTrue(l.axes(PadAxis.L2 to 0f).actions.isEmpty())
        assertEquals(listOf(PadAction.JUMP_HIGH), l.axes(PadAxis.HAT_Y to -1f).actions)
        assertTrue(l.axes(PadAxis.HAT_Y to -1f).actions.isEmpty())
        assertTrue(l.axes(PadAxis.HAT_Y to 1f).actions.isEmpty()) // wrong direction
        assertNull(l.onKey(GamepadLogic.BUTTON_A, t())) // no longer bound
    }

    @Test fun unboundAxisActionGivesZero() {
        val l = joy(PadMapping())
        val r = l.axes(PadAxis.Y to -1f, PadAxis.RIGHT_X to 1f, PadAxis.R2 to 1f)
        assertEquals(0f, r.speed, eps); assertEquals(0f, r.turn, eps)
    }

    @Test fun keysAndAnimationCycle() {
        val l = joy()
        assertEquals(PadAction.JUMP_LONG, l.onKey(GamepadLogic.BUTTON_A, t()))
        assertEquals(PadAction.JUMP_HIGH, l.onKey(GamepadLogic.BUTTON_B, t()))
        assertEquals(PadAction.PHOTO, l.onKey(GamepadLogic.BUTTON_X, t()))
        assertEquals(PadAction.RECORD, l.onKey(GamepadLogic.BUTTON_Y, t()))
        assertEquals(PadAction.TOGGLE_CONNECT, l.onKey(GamepadLogic.BUTTON_START, t()))
        assertEquals(PadAction.TOGGLE_SETTINGS, l.onKey(GamepadLogic.BUTTON_SELECT, t()))
        assertEquals(PadAction.TOGGLE_SETTINGS, l.onKey(GamepadLogic.BUTTON_MODE, t()))
        assertEquals(PadAction.POSTURE_NEXT, l.onKey(GamepadLogic.DPAD_UP, t()))
        assertEquals(PadAction.POSTURE_PREV, l.onKey(GamepadLogic.DPAD_DOWN, t()))
        assertNull(l.onKey(4, t()))
        l.onKey(GamepadLogic.BUTTON_L1, t()); assertEquals(8, l.animIndex)
        l.onKey(GamepadLogic.BUTTON_R1, t()); assertEquals(0, l.animIndex)
        repeat(10) { l.onKey(GamepadLogic.BUTTON_R1, t()) }; assertEquals(1, l.animIndex)
    }

    @Test fun hatAndDpadKeyDebounced() {
        val l = joy()
        assertEquals(listOf(PadAction.POSTURE_NEXT), l.onAxes(snap(PadAxis.HAT_Y to -1f), 5000).actions)
        assertNull(l.onKey(GamepadLogic.DPAD_UP, 5050)) // same press, second source
        assertEquals(PadAction.POSTURE_NEXT, l.onKey(GamepadLogic.DPAD_UP, 6000))
        assertEquals(listOf(PadAction.POSTURE_PREV), l.axes(PadAxis.HAT_Y to 1f).actions)
    }
}
