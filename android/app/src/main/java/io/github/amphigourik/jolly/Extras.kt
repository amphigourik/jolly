// SPDX-FileCopyrightText: 2026 amphigourik
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.amphigourik.jolly

/** Drone (Wi-Fi) name validation: it becomes the SSID. */
object NameValidator {
    private val RE = Regex("^[A-Za-z0-9_-]{1,32}$")
    fun isValid(name: String) = RE.matches(name)
}

/** Vibration patterns (timings for VibrationEffect.createWaveform: off, on, off, on, ...). */
enum class Rumble(val timings: LongArray) {
    JUMP(longArrayOf(0, 60)),
    STUCK(longArrayOf(0, 400)),
    BATTERY(longArrayOf(0, 100, 100, 100, 100, 100)),
    LINK_LOST(longArrayOf(0, 150, 100, 150)),
}

/** Which event triggers which vibration; only on transitions. */
object RumbleLogic {
    fun onState(old: DroneState, new: DroneState): List<Rumble> {
        val out = ArrayList<Rumble>()
        if (new.posture == 3 && old.posture != 3) out.add(Rumble.STUCK)
        val a = new.alert
        if ((a == 1 || a == 2) && a != old.alert) out.add(Rumble.BATTERY)
        return out
    }
    fun onJump() = Rumble.JUMP
    fun onLinkLost() = Rumble.LINK_LOST
}

/** Jumps lock: blocks both jumps and the spin-jump animation. */
object JumpPolicy {
    const val SPINJUMP_ANIMATION_ID = 6
    fun blocksAnimation(disabled: Boolean, animationId: Int) = disabled && animationId == SPINJUMP_ANIMATION_ID
}

data class PilotSettings(val maxSpeed: Int, val jumpsDisabled: Boolean)

/** Kid mode: caps speed and disables jumps, remembering the previous settings. */
data class KidState(val active: Boolean = false, val saved: PilotSettings? = null) {
    /** Returns the new kid state and the settings to apply. */
    fun toggle(current: PilotSettings): Pair<KidState, PilotSettings> =
        if (!active) KidState(true, current) to PilotSettings(KID_MAX_SPEED, true)
        else KidState(false, null) to (saved ?: current)

    companion object { const val KID_MAX_SPEED = 40 }
}
