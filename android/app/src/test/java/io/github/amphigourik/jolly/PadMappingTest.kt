// SPDX-FileCopyrightText: 2026 amphigourik
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.amphigourik.jolly

import org.junit.Assert.*
import org.junit.Test

class PadMappingTest {
    @Test fun roundTripPresetsAndCustom() {
        for (m in listOf(Presets.JOYSTICKS, Presets.COURSE, PadMapping())) {
            assertEquals(m, PadMapping.deserialize(m.serialize()))
        }
        val c = Presets.COURSE.withAxis(AxisAction.SPEED, AxisBinding(PadAxis.RY, true))
            .withButtons(PadAction.PHOTO, listOf(ButtonBinding.Key(106), ButtonBinding.AxisBtn(PadAxis.HAT_X, false)))
        assertEquals(c, PadMapping.deserialize(c.serialize()))
    }

    @Test fun invalidDataIsRejected() {
        assertNull(PadMapping.deserialize(null))
        assertNull(PadMapping.deserialize(""))
        assertNull(PadMapping.deserialize("garbage"))
        assertNull(PadMapping.deserialize("JPAD1\nA NOPE 1 0"))
        assertNull(PadMapping.deserialize("JPAD1\nA SPEED x 0"))
        assertNull(PadMapping.deserialize("JPAD1\nB PHOTO Z9"))
        assertNull(PadMapping.deserialize("JPAD1\nB PHOTO X1?"))
        assertNull(PadMapping.deserialize("JPAD2\nA SPEED 1 0"))
    }

    @Test fun profilesFallbackToJoysticks() {
        val bad = PadProfiles.resolve("PERSO", "garbage")
        assertEquals(PadProfile.JOYSTICKS, bad.first); assertEquals(Presets.JOYSTICKS, bad.second)
        assertEquals(PadProfile.JOYSTICKS, PadProfiles.resolve("???", null).first)
        assertEquals(PadProfile.COURSE, PadProfiles.resolve("COURSE", null).first)
        assertEquals(Presets.COURSE, PadProfiles.resolve("COURSE", "garbage").second)
        val perso = Presets.COURSE.withButtons(PadAction.PHOTO, emptyList())
        val ok = PadProfiles.resolve("PERSO", perso.serialize())
        assertEquals(PadProfile.PERSO, ok.first); assertEquals(perso, ok.second); assertEquals(perso, ok.third)
    }

    @Test fun conflictDetection() {
        val m = Presets.JOYSTICKS
        // A is already JUMP_LONG
        assertEquals(Owner.Button(PadAction.JUMP_LONG), m.findConflict(Owner.Button(PadAction.PHOTO), Captured.Key(GamepadLogic.BUTTON_A)))
        assertNull(m.findConflict(Owner.Button(PadAction.JUMP_LONG), Captured.Key(GamepadLogic.BUTTON_A))) // itself
        assertNull(m.findConflict(Owner.Button(PadAction.PHOTO), Captured.Key(106)))
        // left stick Y is SPEED: an axis action or a virtual button on that axis conflicts
        assertEquals(Owner.Axis(AxisAction.SPEED), m.findConflict(Owner.Axis(AxisAction.TURN), Captured.Axis(PadAxis.Y, false)))
        assertEquals(Owner.Axis(AxisAction.SPEED), m.findConflict(Owner.Button(PadAction.PHOTO), Captured.AxisBtn(PadAxis.Y, false)))
        // R2 trigger as virtual button: LAUNCH
        assertEquals(Owner.Button(PadAction.ANIM_LAUNCH), m.findConflict(Owner.Button(PadAction.PHOTO), Captured.AxisBtn(PadAxis.R2, true)))
        assertNull(m.findConflict(Owner.Button(PadAction.PHOTO), Captured.AxisBtn(PadAxis.R2, false))) // other direction
    }

    @Test fun assignWithoutConflictAndCancel() {
        val m = Presets.JOYSTICKS
        val a = m.assign(Owner.Button(PadAction.PHOTO), Captured.Key(106), false)
        assertEquals(listOf<ButtonBinding>(ButtonBinding.Key(106)), a.buttons[PadAction.PHOTO])
        // conflict + cancel => unchanged
        assertEquals(m, m.assign(Owner.Button(PadAction.PHOTO), Captured.Key(GamepadLogic.BUTTON_A), false))
    }

    @Test fun swapButtons() {
        val m = Presets.JOYSTICKS
        val s = m.assign(Owner.Button(PadAction.PHOTO), Captured.Key(GamepadLogic.BUTTON_A), true)
        assertEquals(listOf<ButtonBinding>(ButtonBinding.Key(GamepadLogic.BUTTON_A)), s.buttons[PadAction.PHOTO])
        assertEquals(listOf<ButtonBinding>(ButtonBinding.Key(GamepadLogic.BUTTON_X)), s.buttons[PadAction.JUMP_LONG])
    }

    @Test fun swapAxisActions() {
        val m = Presets.JOYSTICKS
        val s = m.assign(Owner.Axis(AxisAction.TURN), Captured.Axis(PadAxis.Y, false), true)
        assertEquals(AxisBinding(PadAxis.Y, false), s.axes[AxisAction.TURN])
        assertEquals(AxisBinding(PadAxis.RIGHT_X, false), s.axes[AxisAction.SPEED])
    }

    @Test fun swapMixedKindsUnbindsOwner() {
        val m = Presets.JOYSTICKS
        val s = m.assign(Owner.Button(PadAction.PHOTO), Captured.AxisBtn(PadAxis.Y, false), true)
        assertNull(s.axes[AxisAction.SPEED])
        assertEquals(listOf<ButtonBinding>(ButtonBinding.AxisBtn(PadAxis.Y, false)), s.buttons[PadAction.PHOTO])
    }

    @Test fun invertAndUnbind() {
        val m = Presets.JOYSTICKS.invert(AxisAction.SPEED)
        assertFalse(m.axes[AxisAction.SPEED]!!.inverted)
        assertNull(m.unbind(Owner.Axis(AxisAction.TURN)).axes[AxisAction.TURN])
        assertNull(m.unbind(Owner.Button(PadAction.PHOTO)).buttons[PadAction.PHOTO])
    }

    // --- Capture ---
    @Test fun captureAxisDirectionSetsInverted() {
        val c = CaptureSession(wantAxis = true, baseline = mapOf(PadAxis.Y to 0f, PadAxis.X to 0f))
        assertNull(c.onAxes(mapOf(PadAxis.Y to -0.4f)))            // below the threshold
        assertEquals(Captured.Axis(PadAxis.Y, true), c.onAxes(mapOf(PadAxis.Y to -0.8f)))  // up => inverted
        assertEquals(Captured.Axis(PadAxis.X, false), CaptureSession(true, mapOf(PadAxis.X to 0f)).onAxes(mapOf(PadAxis.X to 0.9f)))
    }

    @Test fun captureIgnoresRestingNonZeroAxes() {
        val base = mapOf(PadAxis.R2 to -1f, PadAxis.Y to 0f)
        val c = CaptureSession(true, base)
        assertNull(c.onAxes(mapOf(PadAxis.R2 to -1f, PadAxis.Y to 0.1f)))
        assertEquals(Captured.Axis(PadAxis.Y, true), c.onAxes(mapOf(PadAxis.R2 to -1f, PadAxis.Y to -0.9f)))
    }

    @Test fun captureFirstSampleAsBaseline() {
        val c = CaptureSession(true, null)
        assertNull(c.onAxes(mapOf(PadAxis.R2 to -1f)))
        assertNull(c.onAxes(mapOf(PadAxis.R2 to -1f)))
        assertEquals(Captured.Axis(PadAxis.R2, false), c.onAxes(mapOf(PadAxis.R2 to 0.0f, PadAxis.Y to 0f)).let { it ?: c.onAxes(mapOf(PadAxis.R2 to 0.5f)) })
    }

    @Test fun captureStrongestAxisWins() {
        val c = CaptureSession(true, mapOf())
        assertEquals(Captured.Axis(PadAxis.Y, false), c.onAxes(mapOf(PadAxis.X to 0.6f, PadAxis.Y to 0.9f)))
    }

    @Test fun captureButtonKinds() {
        val c = CaptureSession(false, mapOf())
        assertEquals(Captured.Key(96), c.onKey(96))
        assertEquals(Captured.AxisBtn(PadAxis.HAT_Y, false), c.onAxes(mapOf(PadAxis.HAT_Y to -1f)))
        assertEquals(Captured.AxisBtn(PadAxis.R2, true), CaptureSession(false, mapOf(PadAxis.R2 to 0f)).onAxes(mapOf(PadAxis.R2 to 1f)))
        assertNull(CaptureSession(true, mapOf()).onKey(96)) // an axis action ignores keys
    }

    private val tx = TestTexts()

    @Test fun allKeysUsedByLabelsExist() {
        for (a in AxisAction.values()) tx.t(a.labelKey)
        for (p in PadProfile.values()) tx.t(p.labelKey)
        for (a in PadAction.values()) PadLabels.actionName(a, tx)
        for (axis in PadAxis.CAPTURABLE) { PadLabels.axis(AxisBinding(axis, true), tx); PadLabels.axis(AxisBinding(axis, false), tx)
            PadLabels.button(ButtonBinding.AxisBtn(axis, true), tx); PadLabels.button(ButtonBinding.AxisBtn(axis, false), tx) }
        for (k in DroneStateDecoder.POSTURE_KEYS + DroneStateDecoder.ALERT_KEYS) tx.t(k)
        assertEquals("unknown (9)", DisplayText.alertName(9, tx))
        assertEquals("stuck", DisplayText.postureName(3, tx))
        assertEquals("Camera off", tx.t("cam_off_note"))
        assertFalse(tx.t("video_stream_sub").contains("saves", ignoreCase = true))
    }

    @Test fun labelsAndLegend() {
        assertEquals("Left stick ↑", PadLabels.axis(AxisBinding(PadAxis.Y, true), tx))
        assertEquals("Left stick ↓", PadLabels.axis(AxisBinding(PadAxis.Y, false), tx))
        assertEquals("Right trigger", PadLabels.axis(AxisBinding(PadAxis.R2), tx))
        assertEquals("Button A", PadLabels.button(ButtonBinding.Key(96), tx))
        assertEquals("D-pad ↑", PadLabels.button(ButtonBinding.AxisBtn(PadAxis.HAT_Y, false), tx))
        assertEquals("Right stick → (flick)", PadLabels.button(ButtonBinding.AxisBtn(PadAxis.RIGHT_X, true), tx))
        assertEquals("—", PadLabels.axis(null, tx))
        val legend = PadLabels.legend(Presets.JOYSTICKS, tx)
        assertTrue(legend.contains("Speed: Left stick ↑"))
        assertTrue(legend.contains("Long jump: Button A"))
        assertTrue(legend.contains("Accelerate: —"))
    }
}
