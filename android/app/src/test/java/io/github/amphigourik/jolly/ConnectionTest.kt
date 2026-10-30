// SPDX-FileCopyrightText: 2026 amphigourik
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.amphigourik.jolly

import org.junit.Assert.*
import org.junit.Test

class ConnectionTest {
    private fun connected(m: ConnectionMachine = ConnectionMachine()): ConnectionMachine {
        m.onEvent(ConnEvent.UserConnect, 0)
        m.onEvent(ConnEvent.ConnectSucceeded, 0)
        return m
    }

    @Test fun userConnectFlow() {
        val m = ConnectionMachine()
        assertEquals(listOf(ConnAction.StartConnect), m.onEvent(ConnEvent.UserConnect, 0))
        assertEquals(ConnState.CONNECTING, m.state)
        assertTrue(m.onEvent(ConnEvent.UserConnect, 0).isEmpty())
        assertTrue(m.onEvent(ConnEvent.ConnectSucceeded, 10).isEmpty())
        assertEquals(ConnState.CONNECTED, m.state)
    }

    @Test fun firstConnectFailureShowsError() {
        val m = ConnectionMachine()
        m.onEvent(ConnEvent.UserConnect, 0)
        assertEquals(listOf(ConnAction.Teardown(true), ConnAction.ShowError), m.onEvent(ConnEvent.ConnectFailed, 5))
        assertEquals(ConnState.DISCONNECTED, m.state)
    }

    @Test fun linkLostReconnects() {
        val m = connected()
        val a = m.onEvent(ConnEvent.LinkLost, 1000)
        assertEquals(listOf(ConnAction.Teardown(false), ConnAction.ScheduleAttempt(2000)), a)
        assertEquals(ConnState.RECONNECTING, m.state)
        assertEquals(1, m.attempt)
        assertFalse(m.droneShutdown)
        // an attempt fails, another is scheduled
        assertEquals(listOf(ConnAction.ScheduleAttempt(2000)), m.onEvent(ConnEvent.ConnectFailed, 5000))
        assertEquals(2, m.attempt)
        // success
        m.onEvent(ConnEvent.ConnectSucceeded, 8000)
        assertEquals(ConnState.CONNECTED, m.state)
        assertEquals(0, m.attempt)
    }

    @Test fun givesUpAfterSixtySeconds() {
        val m = connected()
        m.onEvent(ConnEvent.LinkLost, 1000)
        assertEquals(listOf(ConnAction.ScheduleAttempt(2000)), m.onEvent(ConnEvent.ConnectFailed, 58_000))
        assertEquals(listOf(ConnAction.Teardown(true), ConnAction.GiveUp), m.onEvent(ConnEvent.ConnectFailed, 60_000))
        assertEquals(ConnState.DISCONNECTED, m.state)
        // no more retries afterwards
        assertTrue(m.onEvent(ConnEvent.ConnectFailed, 62_000).isEmpty())
    }

    @Test fun droneShutdownReconnectsWithFlag() {
        val m = connected()
        assertEquals(listOf(ConnAction.Teardown(false), ConnAction.ScheduleAttempt(2000)), m.onEvent(ConnEvent.DroneShutdown, 0))
        assertTrue(m.droneShutdown)
        assertEquals(ConnState.RECONNECTING, m.state)
        // a later link loss does not change anything
        assertTrue(m.onEvent(ConnEvent.LinkLost, 100).isEmpty())
    }

    @Test fun userDisconnectNeverReconnects() {
        val m = connected()
        assertEquals(listOf(ConnAction.Teardown(true)), m.onEvent(ConnEvent.UserDisconnect, 0))
        assertEquals(ConnState.DISCONNECTED, m.state)
        assertTrue(m.onEvent(ConnEvent.LinkLost, 1).isEmpty())
        assertTrue(m.onEvent(ConnEvent.UserDisconnect, 2).isEmpty())
        // while reconnecting, the user can stop it
        val r = connected()
        r.onEvent(ConnEvent.LinkLost, 0)
        assertEquals(listOf(ConnAction.Teardown(true)), r.onEvent(ConnEvent.UserDisconnect, 100))
        assertTrue(r.onEvent(ConnEvent.ConnectFailed, 200).isEmpty())
    }

    @Test fun finishingNeverReconnects() {
        val m = connected()
        assertEquals(listOf(ConnAction.Teardown(true)), m.onEvent(ConnEvent.Finishing, 0))
        assertTrue(m.onEvent(ConnEvent.LinkLost, 1).isEmpty())
        assertTrue(m.onEvent(ConnEvent.UserConnect, 2).isEmpty())
        assertEquals(ConnState.DISCONNECTED, m.state)
    }

    @Test fun linkLostWhileConnectingIsAFailure() {
        val m = ConnectionMachine()
        m.onEvent(ConnEvent.UserConnect, 0)
        assertEquals(listOf(ConnAction.Teardown(true), ConnAction.ShowError), m.onEvent(ConnEvent.LinkLost, 1))
    }

    @Test fun watchdogFiresOnceAndRearms() {
        val w = LinkWatchdog(3000)
        w.reset(0)
        assertFalse(w.check(2999))
        assertTrue(w.check(3000))
        assertFalse(w.check(4000)) // only once
        w.onPacket(5000)
        assertFalse(w.check(7000))
        assertTrue(w.check(8000))
    }
}
