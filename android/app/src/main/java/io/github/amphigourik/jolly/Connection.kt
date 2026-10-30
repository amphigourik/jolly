// SPDX-FileCopyrightText: 2026 amphigourik
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.amphigourik.jolly

enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

sealed class ConnEvent {
    /** The user asked to connect. */
    object UserConnect : ConnEvent()
    /** The user asked to disconnect (never followed by a reconnect). */
    object UserDisconnect : ConnEvent()
    /** The app is finishing: tear everything down, never reconnect. */
    object Finishing : ConnEvent()
    /** A connect attempt (network + handshake) succeeded. */
    object ConnectSucceeded : ConnEvent()
    /** A connect attempt failed for good (its own retries are exhausted). */
    object ConnectFailed : ConnEvent()
    /** The link dropped: network lost, I/O error, or silence from the drone. */
    object LinkLost : ConnEvent()
    /** The drone announced its own disconnection (common/Network/Disconnection, 0/1/0). */
    object DroneShutdown : ConnEvent()
}

sealed class ConnAction {
    /** Start the initial connection (network request + handshake). */
    object StartConnect : ConnAction()
    /** Stop the drone link and zero the controls; [releaseNetwork] also drops the Wi-Fi request. */
    data class Teardown(val releaseNetwork: Boolean) : ConnAction()
    /** Try a full connect again after [delayMs]. */
    data class ScheduleAttempt(val delayMs: Long) : ConnAction()
    /** The first connection failed: show the error. */
    object ShowError : ConnAction()
    /** Reconnecting for too long: show "Connection lost". */
    object GiveUp : ConnAction()
}

/** Pure connection state machine: events in, state changes and actions out. */
class ConnectionMachine(private val retryMs: Long = 2000, private val maxReconnectMs: Long = 60_000) {
    var state = ConnState.DISCONNECTED; private set
    var attempt = 0; private set
    /** True when the current reconnection follows a drone-announced shutdown. */
    var droneShutdown = false; private set
    private var reconnectStart = 0L
    private var finishing = false

    fun onEvent(e: ConnEvent, nowMs: Long): List<ConnAction> {
        if (finishing && e !is ConnEvent.Finishing) return emptyList()
        return when (e) {
            ConnEvent.UserConnect ->
                if (state == ConnState.DISCONNECTED) { state = ConnState.CONNECTING; attempt = 0; droneShutdown = false; listOf(ConnAction.StartConnect) }
                else emptyList()
            ConnEvent.UserDisconnect ->
                if (state != ConnState.DISCONNECTED) { state = ConnState.DISCONNECTED; attempt = 0; listOf(ConnAction.Teardown(true)) }
                else emptyList()
            ConnEvent.Finishing -> { finishing = true; state = ConnState.DISCONNECTED; listOf(ConnAction.Teardown(true)) }
            ConnEvent.ConnectSucceeded ->
                if (state == ConnState.CONNECTING || state == ConnState.RECONNECTING) {
                    state = ConnState.CONNECTED; attempt = 0; droneShutdown = false; emptyList()
                } else emptyList()
            ConnEvent.ConnectFailed -> when (state) {
                ConnState.CONNECTING -> { state = ConnState.DISCONNECTED; listOf(ConnAction.Teardown(true), ConnAction.ShowError) }
                ConnState.RECONNECTING ->
                    if (nowMs - reconnectStart + retryMs > maxReconnectMs) {
                        state = ConnState.DISCONNECTED; listOf(ConnAction.Teardown(true), ConnAction.GiveUp)
                    } else { attempt++; listOf(ConnAction.ScheduleAttempt(retryMs)) }
                else -> emptyList()
            }
            ConnEvent.LinkLost -> lose(nowMs, shutdown = false)
            ConnEvent.DroneShutdown -> lose(nowMs, shutdown = true)
        }
    }

    private fun lose(nowMs: Long, shutdown: Boolean): List<ConnAction> = when (state) {
        ConnState.CONNECTED -> {
            state = ConnState.RECONNECTING; attempt = 1; droneShutdown = shutdown; reconnectStart = nowMs
            listOf(ConnAction.Teardown(false), ConnAction.ScheduleAttempt(retryMs))
        }
        ConnState.CONNECTING -> { state = ConnState.DISCONNECTED; listOf(ConnAction.Teardown(true), ConnAction.ShowError) }
        else -> emptyList()
    }
}

/** Detects silence from the drone: fires once when no packet arrived for [timeoutMs], re-arms on the next packet. */
class LinkWatchdog(private val timeoutMs: Long = 3000) {
    private var last = 0L
    private var fired = false

    fun reset(nowMs: Long) { last = nowMs; fired = false }
    fun onPacket(nowMs: Long) { last = nowMs; fired = false }

    fun check(nowMs: Long): Boolean {
        if (fired || nowMs - last < timeoutMs) return false
        fired = true
        return true
    }
}
