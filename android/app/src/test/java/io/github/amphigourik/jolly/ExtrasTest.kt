// SPDX-FileCopyrightText: 2026 amphigourik
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.amphigourik.jolly

import org.junit.Assert.*
import org.junit.Test

class ExtrasTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun ev(p: Int, k: Int, c: Int, vararg b: Int) =
        byteArrayOf(p.toByte(), k.toByte(), (c and 0xFF).toByte(), (c shr 8).toByte()) + ByteArray(b.size) { b[it].toByte() }

    @Test fun nameValidation() {
        assertTrue(NameValidator.isValid("DRONE"))
        assertTrue(NameValidator.isValid("Jumping_Race-2"))
        assertTrue(NameValidator.isValid("a".repeat(32)))
        assertFalse(NameValidator.isValid(""))
        assertFalse(NameValidator.isValid("a".repeat(33)))
        assertFalse(NameValidator.isValid("my drone"))
        assertFalse(NameValidator.isValid("café"))
        assertFalse(NameValidator.isValid("a.b"))
    }

    @Test fun commandEncoding() {
        assertEquals("00020200" + "44524f4e4500", hex(Protocol.productName("DRONE")))
        assertEquals("030c0000" + "32", hex(Protocol.audioVolume(50)))
        assertEquals("030c0000" + "64", hex(Protocol.audioVolume(500))) // clamped
        assertEquals("030c0100" + "02000000", hex(Protocol.audioTheme(2)))
    }

    @Test fun decodesAudioAndNetworkEvents() {
        var s = DroneState()
        s = DroneStateDecoder.apply(s, ev(3, 13, 0, 0x0b))
        assertEquals(11, s.audioVolume)
        s = DroneStateDecoder.apply(s, ev(3, 13, 1, 3, 0, 0, 0))
        assertEquals(3, s.audioTheme)
        s = DroneStateDecoder.apply(s, ev(3, 9, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0x0a)) // observed
        assertEquals(0, s.wifiBand); assertEquals(10, s.wifiChannel)
        s = DroneStateDecoder.apply(s, ev(3, 11, 4, 5))
        assertEquals(5, s.linkQuality)
        s = DroneStateDecoder.apply(s, ev(3, 19, 0, 1, 0, 0, 0))
        assertEquals(1, s.videoEnabled)
        val before = s
        assertSame(before, DroneStateDecoder.apply(before, ev(3, 9, 0, 1, 0))) // truncated
        assertSame(before, DroneStateDecoder.apply(before, ev(3, 13, 1)))
        assertSame(before, DroneStateDecoder.apply(before, ev(3, 99, 99, 1)))
    }

    @Test fun rumbleOnTransitionsOnly() {
        val none = DroneState()
        assertEquals(listOf(Rumble.STUCK), RumbleLogic.onState(none, DroneState(posture = 3)))
        assertTrue(RumbleLogic.onState(DroneState(posture = 3), DroneState(posture = 3)).isEmpty())
        assertTrue(RumbleLogic.onState(none, DroneState(posture = 0)).isEmpty())
        assertEquals(listOf(Rumble.BATTERY), RumbleLogic.onState(none, DroneState(alert = 2)))
        assertEquals(listOf(Rumble.BATTERY), RumbleLogic.onState(DroneState(alert = 2), DroneState(alert = 1)))
        assertTrue(RumbleLogic.onState(DroneState(alert = 2), DroneState(alert = 2)).isEmpty())
        assertTrue(RumbleLogic.onState(DroneState(alert = 2), DroneState(alert = 0)).isEmpty())
        assertEquals(listOf(Rumble.STUCK, Rumble.BATTERY), RumbleLogic.onState(none, DroneState(posture = 3, alert = 1)))
        assertEquals(Rumble.JUMP, RumbleLogic.onJump())
        assertEquals(Rumble.LINK_LOST, RumbleLogic.onLinkLost())
        assertEquals(60L, Rumble.JUMP.timings[1]); assertEquals(400L, Rumble.STUCK.timings[1])
        assertEquals(6, Rumble.BATTERY.timings.size); assertEquals(4, Rumble.LINK_LOST.timings.size)
    }

    @Test fun jumpPolicy() {
        assertTrue(JumpPolicy.blocksAnimation(true, 6))
        assertFalse(JumpPolicy.blocksAnimation(true, 1))
        assertFalse(JumpPolicy.blocksAnimation(false, 6))
    }

    @Test fun kidModeToggle() {
        val start = PilotSettings(80, false)
        val (kid, applied) = KidState().toggle(start)
        assertTrue(kid.active)
        assertEquals(PilotSettings(40, true), applied)
        val (off, restored) = kid.toggle(applied)
        assertFalse(off.active)
        assertEquals(start, restored)
        // jumps already disabled before: restored as disabled
        val (k2, _) = KidState().toggle(PilotSettings(100, true))
        assertEquals(PilotSettings(100, true), k2.toggle(PilotSettings(40, true)).second)
    }
}
