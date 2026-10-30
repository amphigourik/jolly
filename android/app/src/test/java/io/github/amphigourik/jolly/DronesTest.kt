// SPDX-FileCopyrightText: 2026 amphigourik
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.amphigourik.jolly

import org.junit.Assert.*
import org.junit.Test

class DronesTest {
    @Test fun parrotByOui() {
        assertTrue(ParrotDetect.isParrot("MyDrone", "a0:14:3d:12:34:56"))
        assertTrue(ParrotDetect.isParrot("x", "90:03:B7:00:00:01"))
        assertTrue(ParrotDetect.isParrot("x", "00-26-7E-aa-bb-cc"))
        assertFalse(ParrotDetect.isParrot("HomeWifi", "a0:14:3e:12:34:56"))
        assertFalse(ParrotDetect.isParrot("HomeWifi", ""))
    }

    @Test fun parrotBySsidPrefix() {
        assertTrue(ParrotDetect.isParrot("JumpingRace_0123", "11:22:33:44:55:66"))
        assertTrue(ParrotDetect.isParrot("jumpingsumo_x", ""))
        assertTrue(ParrotDetect.isParrot("JumpingNight_1", ""))
        assertTrue(ParrotDetect.isParrot("js_abc", ""))
        assertFalse(ParrotDetect.isParrot("MyJumpingRace", ""))
    }

    @Test fun sorting() {
        val nets = listOf(
            WifiNet("Home", "11:11:11:11:11:11", -40),
            WifiNet("Weak", "22:22:22:22:22:22", -85),
            WifiNet("Drone", "A0:14:3D:00:00:01", -70),
            WifiNet("Drone", "A0:14:3D:00:00:02", -55), // same SSID: strongest kept
            WifiNet("", "33:33:33:33:33:33", -30),      // hidden: dropped
            WifiNet("JumpingRace_1", "44:44:44:44:44:44", -80),
        )
        val sorted = DroneList.sortNets(nets)
        assertEquals(listOf("Drone", "JumpingRace_1", "Home", "Weak"), sorted.map { it.ssid })
        assertEquals(-55, sorted[0].level)
        assertTrue(sorted[0].parrot && sorted[1].parrot && !sorted[2].parrot)
    }

    @Test fun savedListOps() {
        var l = emptyList<String>()
        assertNull(SavedDrones.active(l))
        l = SavedDrones.use(l, "A"); l = SavedDrones.use(l, "B"); l = SavedDrones.use(l, " C ")
        assertEquals(listOf("C", "B", "A"), l)
        assertEquals("C", SavedDrones.active(l))
        l = SavedDrones.use(l, "A")
        assertEquals(listOf("A", "C", "B"), l)
        assertEquals(l, SavedDrones.use(l, "  "))
        assertEquals(listOf("A", "B"), SavedDrones.remove(l, "C"))
        assertEquals(listOf("A", "X", "B"), SavedDrones.rename(l, "C", "X"))
        assertEquals(l, SavedDrones.rename(l, "nope", "X"))
        assertEquals(listOf("A", "C"), SavedDrones.rename(l, "B", "C")) // duplicate collapses
        assertEquals(SavedDrones.MAX, (1..30).fold(emptyList<String>()) { acc, i -> SavedDrones.use(acc, "d$i") }.size)
    }

    @Test fun serialization() {
        val l = listOf("Plain", "with space", "back\\slash", "line\nbreak", "ünï")
        assertEquals(l, SavedDrones.parse(SavedDrones.serialize(l)))
        assertEquals(emptyList<String>(), SavedDrones.parse(null))
        assertEquals(emptyList<String>(), SavedDrones.parse(""))
        assertEquals(listOf("A", "B"), SavedDrones.parse("A\n\nB\nA"))
    }
}
