// SPDX-FileCopyrightText: 2026 amphigourik
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.amphigourik.jolly

import kotlin.math.abs

/** "Button" actions triggered by the gamepad. */
enum class PadAction {
    JUMP_LONG, JUMP_HIGH, PHOTO, RECORD, ANIM_PREV, ANIM_NEXT, ANIM_LAUNCH,
    POSTURE_NEXT, POSTURE_PREV, TOGGLE_CONNECT, TOGGLE_SETTINGS,
}

/** What drives the speed. */
enum class SpeedSource { NONE, STICK, TRIGGERS }

/** Axis measurement (raw values, keys = [PadAxis]) and the device "flat" value per axis. */
class AxisSnapshot(val values: Map<Int, Float> = emptyMap(), val flats: Map<Int, Float> = emptyMap()) {
    fun v(axis: Int) = values[axis] ?: 0f
    fun flat(axis: Int) = flats[axis] ?: 0f
}

/** Pure gamepad logic (no Android dependency), driven by a [PadMapping]. */
class GamepadLogic(private val animationCount: Int, mapping: PadMapping = Presets.JOYSTICKS) {
    class Reading(val raw: Float, val value: Float)

    /** speed: forward is positive; turn: right is positive (-1..1). */
    class AxesResult(val speed: Float, val turn: Float, val source: SpeedSource,
                     val readings: Map<AxisAction, Reading>, val r2: Float, val l2: Float,
                     val actions: List<PadAction>)

    var mapping: PadMapping = mapping
        set(v) { field = v; virtualActive.clear() }

    var animIndex = 0; private set
    private var snap = AxisSnapshot()
    private var r2Key = false
    private var l2Key = false
    private var analogTriggers = false
    private val virtualActive = HashMap<String, Boolean>()
    private val lastFire = HashMap<PadAction, Long>()

    fun onAxes(s: AxisSnapshot, nowMs: Long): AxesResult {
        snap = s
        // Many pads also send BUTTON_R2/L2 as soon as the trigger is pressed slightly:
        // once an analog axis has been seen, ignore those keys to keep acceleration progressive.
        if (s.v(PadAxis.R2) > 0f || s.v(PadAxis.L2) > 0f) analogTriggers = true
        return evaluate(nowMs)
    }

    /** Pads without an analog axis: L2/R2 key down = 1.0, released = 0.0. True if it was one of those keys. */
    fun onTriggerKey(keyCode: Int, down: Boolean): Boolean = when (keyCode) {
        BUTTON_R2 -> { r2Key = down; true }
        BUTTON_L2 -> { l2Key = down; true }
        else -> false
    }

    /** Re-evaluates with the latest measurement (after a trigger key change). */
    fun current(nowMs: Long) = evaluate(nowMs)

    /** Key pressed: returns the bound action, or null. */
    fun onKey(keyCode: Int, nowMs: Long): PadAction? {
        for ((action, list) in mapping.buttons) {
            if (list.any { it is ButtonBinding.Key && it.code == keyCode }) return fire(action, nowMs)
        }
        return null
    }

    private fun axisValue(axis: Int): Float = when (axis) {
        PadAxis.R2 -> if (analogTriggers) snap.v(axis) else if (r2Key) 1f else 0f
        PadAxis.L2 -> if (analogTriggers) snap.v(axis) else if (l2Key) 1f else 0f
        else -> snap.v(axis)
    }

    private fun evaluate(nowMs: Long): AxesResult {
        val readings = HashMap<AxisAction, Reading>()
        for ((action, b) in mapping.axes) {
            val raw = axisValue(b.axis)
            val v = if (action.bipolar) {
                val dz = deadzone(raw, snap.flat(b.axis))
                if (b.inverted) -dz else dz
            } else triggerDeadzone(maxOf(0f, if (b.inverted) -raw else raw))
            readings[action] = Reading(raw, v)
        }
        fun value(a: AxisAction) = readings[a]?.value ?: 0f
        val speed: Float
        val src: SpeedSource
        val stick = value(AxisAction.SPEED)
        val trig = value(AxisAction.ACCELERATE) - value(AxisAction.BRAKE)
        when {
            stick != 0f -> { speed = stick; src = SpeedSource.STICK }
            trig != 0f -> { speed = trig; src = SpeedSource.TRIGGERS }
            else -> { speed = 0f; src = SpeedSource.NONE }
        }

        val actions = ArrayList<PadAction>()
        for ((action, list) in mapping.buttons) {
            for ((i, b) in list.withIndex()) {
                if (b !is ButtonBinding.AxisBtn) continue
                val key = "${action.name}:$i"
                val v = axisValue(b.axis) * (if (b.positive) 1f else -1f)
                val active = virtualActive[key] == true
                if (!active && v > PRESS) { virtualActive[key] = true; fire(action, nowMs)?.let(actions::add) }
                else if (active && v < RELEASE) virtualActive[key] = false
            }
        }
        return AxesResult(speed, value(AxisAction.TURN), src, readings, axisValue(PadAxis.R2), axisValue(PadAxis.L2), actions)
    }

    /** Debounce: the same action can come from two sources (key + axis). */
    private fun fire(action: PadAction, nowMs: Long): PadAction? {
        val gap = when (action) {
            PadAction.ANIM_LAUNCH -> 400L
            PadAction.ANIM_NEXT, PadAction.ANIM_PREV -> 0L
            else -> 150L
        }
        val last = lastFire[action]
        if (last != null && nowMs - last < gap) return null
        lastFire[action] = nowMs
        when (action) {
            PadAction.ANIM_NEXT -> animIndex = (animIndex + 1) % animationCount
            PadAction.ANIM_PREV -> animIndex = (animIndex - 1 + animationCount) % animationCount
            else -> {}
        }
        return action
    }

    companion object {
        const val MIN_DEADZONE = 0.12f
        const val PRESS = 0.6f
        const val RELEASE = 0.3f

        // KeyEvent.KEYCODE_* codes
        const val DPAD_UP = 19; const val DPAD_DOWN = 20
        const val BUTTON_A = 96; const val BUTTON_B = 97; const val BUTTON_X = 99; const val BUTTON_Y = 100
        const val BUTTON_L1 = 102; const val BUTTON_R1 = 103; const val BUTTON_L2 = 104; const val BUTTON_R2 = 105
        const val BUTTON_THUMBR = 107
        const val TRIGGER_DEADZONE = 0.05f
        const val BUTTON_START = 108; const val BUTTON_SELECT = 109; const val BUTTON_MODE = 110

        /** Deadzone max(flat, MIN_DEADZONE), then rescaling: 0 just past the deadzone, ±1 at full travel. */
        fun deadzone(raw: Float, flat: Float): Float {
            val t = maxOf(flat, MIN_DEADZONE).coerceAtMost(0.9f)
            val a = abs(raw)
            if (a <= t) return 0f
            return Math.signum(raw) * ((a - t) / (1f - t)).coerceAtMost(1f)
        }

        fun triggerDeadzone(v: Float): Float =
            if (v <= TRIGGER_DEADZONE) 0f else ((v - TRIGGER_DEADZONE) / (1f - TRIGGER_DEADZONE)).coerceAtMost(1f)

        /** Gamepad axes take precedence per axis when non-zero, otherwise touch. */
        fun combine(pad: Float, touch: Float) = if (pad != 0f) pad else touch
    }
}
