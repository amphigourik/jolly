// SPDX-FileCopyrightText: 2026 amphigourik
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.amphigourik.jolly

/** Axis identifiers. Values >= 100 are "virtual" axes normalized by the activity. */
object PadAxis {
    const val X = 0; const val Y = 1; const val Z = 11; const val RX = 12
    /** Right stick vertical: RZ if the right stick is on Z, otherwise RY. */
    const val RY = 13
    const val HAT_X = 15; const val HAT_Y = 16
    /** Right stick horizontal: Z if the device has it, otherwise RX. */
    const val RIGHT_X = 100
    /** Right trigger: max(AXIS_RTRIGGER, AXIS_GAS). */
    const val R2 = 101
    /** Left trigger: max(AXIS_LTRIGGER, AXIS_BRAKE). */
    const val L2 = 102
    val CAPTURABLE = listOf(X, Y, RIGHT_X, RY, R2, L2, HAT_X, HAT_Y)
}

enum class AxisAction(val labelKey: String, val bipolar: Boolean) {
    SPEED("axis_speed", true), TURN("axis_turn", true), ACCELERATE("axis_accelerate", false), BRAKE("axis_brake", false),
}

data class AxisBinding(val axis: Int, val inverted: Boolean = false)

sealed class ButtonBinding {
    data class Key(val code: Int) : ButtonBinding()
    /** Virtual button: the axis crosses 0.6 in the given direction (released below 0.3). */
    data class AxisBtn(val axis: Int, val positive: Boolean) : ButtonBinding()
}

/** Owner of a binding: an axis action or a button action. */
sealed class Owner {
    data class Axis(val action: AxisAction) : Owner()
    data class Button(val action: PadAction) : Owner()
}

/** Input captured from the gamepad. */
sealed class Captured {
    data class Axis(val axis: Int, val inverted: Boolean) : Captured()
    data class Key(val code: Int) : Captured()
    data class AxisBtn(val axis: Int, val positive: Boolean) : Captured()
}

data class PadMapping(
    val axes: Map<AxisAction, AxisBinding> = emptyMap(),
    val buttons: Map<PadAction, List<ButtonBinding>> = emptyMap(),
) {
    fun withAxis(a: AxisAction, b: AxisBinding?) = copy(axes = if (b == null) axes - a else axes + (a to b))
    fun withButtons(a: PadAction, l: List<ButtonBinding>) = copy(buttons = if (l.isEmpty()) buttons - a else buttons + (a to l))

    private fun overlaps(c: Captured, b: AxisBinding) = when (c) {
        is Captured.Axis -> c.axis == b.axis
        is Captured.AxisBtn -> c.axis == b.axis
        is Captured.Key -> false
    }

    private fun overlaps(c: Captured, b: ButtonBinding) = when (c) {
        is Captured.Key -> b is ButtonBinding.Key && b.code == c.code
        is Captured.AxisBtn -> b is ButtonBinding.AxisBtn && b.axis == c.axis && b.positive == c.positive
        is Captured.Axis -> b is ButtonBinding.AxisBtn && b.axis == c.axis
    }

    /** Another owner of the same physical input, or null. */
    fun findConflict(target: Owner, input: Captured): Owner? {
        for ((a, b) in axes) if (Owner.Axis(a) != target && overlaps(input, b)) return Owner.Axis(a)
        for ((a, l) in buttons) if (Owner.Button(a) != target && l.any { overlaps(input, it) }) return Owner.Button(a)
        return null
    }

    private fun toAxisBinding(c: Captured) = (c as? Captured.Axis)?.let { AxisBinding(it.axis, it.inverted) }
    private fun toButton(c: Captured): ButtonBinding? = when (c) {
        is Captured.Key -> ButtonBinding.Key(c.code)
        is Captured.AxisBtn -> ButtonBinding.AxisBtn(c.axis, c.positive)
        is Captured.Axis -> null
    }

    /**
     * Assigns [input] to [target]. If another action already uses it, [swap] exchanges them: the previous
     * owner receives [target]'s old binding (if of the same kind), otherwise it is unbound.
     * Without [swap] and on conflict, the mapping is returned unchanged.
     */
    fun assign(target: Owner, input: Captured, swap: Boolean): PadMapping {
        val conflict = findConflict(target, input)
        if (conflict != null && !swap) return this
        var m = this
        if (conflict != null) {
            val oldAxis = (target as? Owner.Axis)?.let { axes[it.action] }
            val oldButtons = (target as? Owner.Button)?.let { buttons[it.action] }.orEmpty()
            m = when (conflict) {
                is Owner.Axis -> m.withAxis(conflict.action, oldAxis)
                is Owner.Button -> m.withButtons(conflict.action,
                    buttons[conflict.action].orEmpty().filterNot { overlaps(input, it) } + oldButtons)
            }
        }
        return when (target) {
            is Owner.Axis -> toAxisBinding(input)?.let { m.withAxis(target.action, it) } ?: m
            is Owner.Button -> toButton(input)?.let { m.withButtons(target.action, listOf(it)) } ?: m
        }
    }

    fun unbind(target: Owner) = when (target) {
        is Owner.Axis -> withAxis(target.action, null)
        is Owner.Button -> withButtons(target.action, emptyList())
    }

    fun invert(a: AxisAction): PadMapping = axes[a]?.let { withAxis(a, it.copy(inverted = !it.inverted)) } ?: this

    // --- Text serialization ---------------------------------------------------
    fun serialize(): String {
        val sb = StringBuilder("JPAD1\n")
        for (a in AxisAction.values()) axes[a]?.let { sb.append("A ${a.name} ${it.axis} ${if (it.inverted) 1 else 0}\n") }
        for (a in PadAction.values()) buttons[a]?.let { l ->
            sb.append("B ${a.name} ").append(l.joinToString(",") {
                when (it) { is ButtonBinding.Key -> "K${it.code}"; is ButtonBinding.AxisBtn -> "X${it.axis}${if (it.positive) "+" else "-"}" }
            }).append('\n')
        }
        return sb.toString()
    }

    companion object {
        /** Null if the text is invalid (unknown format, unknown action, unreadable value). */
        fun deserialize(text: String?): PadMapping? {
            if (text == null) return null
            return try {
                val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (lines.firstOrNull() != "JPAD1") return null
                var m = PadMapping()
                for (line in lines.drop(1)) {
                    val p = line.split(' ')
                    when (p[0]) {
                        "A" -> {
                            require(p.size == 4)
                            val inv = when (p[3]) { "1" -> true; "0" -> false; else -> error("inv") }
                            m = m.withAxis(AxisAction.valueOf(p[1]), AxisBinding(p[2].toInt(), inv))
                        }
                        "B" -> {
                            require(p.size == 3)
                            val l = p[2].split(',').map { t ->
                                when (t[0]) {
                                    'K' -> ButtonBinding.Key(t.substring(1).toInt())
                                    'X' -> {
                                        val dir = t.last()
                                        require(dir == '+' || dir == '-')
                                        ButtonBinding.AxisBtn(t.substring(1, t.length - 1).toInt(), dir == '+')
                                    }
                                    else -> error("binding")
                                }
                            }
                            m = m.withButtons(PadAction.valueOf(p[1]), l)
                        }
                        else -> return null
                    }
                }
                m
            } catch (_: Exception) {
                null
            }
        }
    }
}

object Presets {
    private val NO_REPEAT_COMMON = mapOf(
        PadAction.JUMP_LONG to listOf<ButtonBinding>(ButtonBinding.Key(GamepadLogic.BUTTON_A)),
        PadAction.JUMP_HIGH to listOf<ButtonBinding>(ButtonBinding.Key(GamepadLogic.BUTTON_B)),
        PadAction.PHOTO to listOf<ButtonBinding>(ButtonBinding.Key(GamepadLogic.BUTTON_X)),
        PadAction.RECORD to listOf<ButtonBinding>(ButtonBinding.Key(GamepadLogic.BUTTON_Y)),
        PadAction.POSTURE_NEXT to listOf(ButtonBinding.Key(GamepadLogic.DPAD_UP), ButtonBinding.AxisBtn(PadAxis.HAT_Y, false)),
        PadAction.POSTURE_PREV to listOf(ButtonBinding.Key(GamepadLogic.DPAD_DOWN), ButtonBinding.AxisBtn(PadAxis.HAT_Y, true)),
        PadAction.TOGGLE_CONNECT to listOf<ButtonBinding>(ButtonBinding.Key(GamepadLogic.BUTTON_START)),
        PadAction.TOGGLE_SETTINGS to listOf<ButtonBinding>(
            ButtonBinding.Key(GamepadLogic.BUTTON_SELECT), ButtonBinding.Key(GamepadLogic.BUTTON_MODE)),
    )

    val JOYSTICKS = PadMapping(
        axes = mapOf(
            AxisAction.SPEED to AxisBinding(PadAxis.Y, inverted = true), // up = forward
            AxisAction.TURN to AxisBinding(PadAxis.RIGHT_X),
        ),
        buttons = NO_REPEAT_COMMON + mapOf(
            PadAction.ANIM_PREV to listOf<ButtonBinding>(ButtonBinding.Key(GamepadLogic.BUTTON_L1)),
            PadAction.ANIM_NEXT to listOf<ButtonBinding>(ButtonBinding.Key(GamepadLogic.BUTTON_R1)),
            PadAction.ANIM_LAUNCH to listOf(ButtonBinding.AxisBtn(PadAxis.R2, true), ButtonBinding.Key(GamepadLogic.BUTTON_THUMBR)),
        ),
    )

    val COURSE = PadMapping(
        axes = mapOf(
            AxisAction.TURN to AxisBinding(PadAxis.X),
            AxisAction.ACCELERATE to AxisBinding(PadAxis.R2),
            AxisAction.BRAKE to AxisBinding(PadAxis.L2),
        ),
        buttons = NO_REPEAT_COMMON + mapOf(
            PadAction.ANIM_PREV to listOf(ButtonBinding.Key(GamepadLogic.BUTTON_L1), ButtonBinding.AxisBtn(PadAxis.RIGHT_X, false)),
            PadAction.ANIM_NEXT to listOf(ButtonBinding.Key(GamepadLogic.BUTTON_R1), ButtonBinding.AxisBtn(PadAxis.RIGHT_X, true)),
            PadAction.ANIM_LAUNCH to listOf<ButtonBinding>(ButtonBinding.Key(GamepadLogic.BUTTON_THUMBR)),
        ),
    )
}

enum class PadProfile(val labelKey: String) { JOYSTICKS("profile_joysticks"), COURSE("profile_course"), PERSO("profile_custom") }

object PadProfiles {
    fun preset(p: PadProfile) = when (p) { PadProfile.COURSE -> Presets.COURSE; else -> Presets.JOYSTICKS }

    /** Active profile + CUSTOM mapping from the preferences; any invalid data falls back to JOYSTICKS. */
    fun resolve(profileName: String?, persoText: String?): Triple<PadProfile, PadMapping, PadMapping> {
        val perso = PadMapping.deserialize(persoText)
        val profile = PadProfile.values().firstOrNull { it.name == profileName } ?: PadProfile.JOYSTICKS
        val persoOrDefault = perso ?: Presets.JOYSTICKS
        return if (profile == PadProfile.PERSO && perso == null) Triple(PadProfile.JOYSTICKS, Presets.JOYSTICKS, persoOrDefault)
        else Triple(profile, if (profile == PadProfile.PERSO) persoOrDefault else preset(profile), persoOrDefault)
    }
}

/** Input capture: an axis moving more than 0.5 away from its resting value, or a key. */
class CaptureSession(private val wantAxis: Boolean, private var baseline: Map<Int, Float>?) {
    /** Without a known [baseline], the first measurement is used as the reference. */
    fun onAxes(values: Map<Int, Float>): Captured? {
        val base = baseline
        if (base == null) { baseline = values; return null }
        var best: Int? = null
        var bestDelta = 0f
        for ((axis, v) in values) {
            val d = v - (base[axis] ?: 0f)
            if (Math.abs(d) >= THRESHOLD && Math.abs(d) > Math.abs(bestDelta)) { best = axis; bestDelta = d }
        }
        val axis = best ?: return null
        return if (wantAxis) Captured.Axis(axis, inverted = bestDelta < 0) else Captured.AxisBtn(axis, positive = bestDelta > 0)
    }

    fun onKey(code: Int): Captured? = if (wantAxis) null else Captured.Key(code)

    companion object { const val THRESHOLD = 0.5f }
}

/** Labels: resolved by key through [Texts] (string resources). */
object PadLabels {
    private val ACTION_KEYS = mapOf(
        PadAction.JUMP_LONG to "act_jump_long", PadAction.JUMP_HIGH to "act_jump_high", PadAction.PHOTO to "act_photo",
        PadAction.RECORD to "act_record", PadAction.ANIM_PREV to "act_anim_prev", PadAction.ANIM_NEXT to "act_anim_next",
        PadAction.ANIM_LAUNCH to "act_anim_launch", PadAction.POSTURE_NEXT to "act_posture_next",
        PadAction.POSTURE_PREV to "act_posture_prev", PadAction.TOGGLE_CONNECT to "act_connect",
        PadAction.TOGGLE_SETTINGS to "act_settings",
    )
    val BUTTON_ACTIONS = PadAction.values().toList()

    fun actionName(a: PadAction, tx: Texts) = tx.t(ACTION_KEYS.getValue(a))
    fun axisActionName(a: AxisAction, tx: Texts) = tx.t(a.labelKey)
    fun owner(o: Owner, tx: Texts) = when (o) { is Owner.Axis -> axisActionName(o.action, tx); is Owner.Button -> actionName(o.action, tx) }

    /** For an axis action: the arrow shows the direction that gives a positive value. */
    fun axis(b: AxisBinding?, tx: Texts): String {
        if (b == null) return "—"
        val neg = b.inverted
        return when (b.axis) {
            PadAxis.R2 -> tx.t(if (neg) "in_r2_inv" else "in_r2")
            PadAxis.L2 -> tx.t(if (neg) "in_l2_inv" else "in_l2")
            PadAxis.Y -> tx.t(if (neg) "in_left_up" else "in_left_down")
            PadAxis.RY -> tx.t(if (neg) "in_right_up" else "in_right_down")
            PadAxis.HAT_Y -> tx.t(if (neg) "in_dpad_up" else "in_dpad_down")
            PadAxis.X -> tx.t(if (neg) "in_left_left" else "in_left_right")
            PadAxis.RIGHT_X -> tx.t(if (neg) "in_right_left" else "in_right_right")
            PadAxis.HAT_X -> tx.t(if (neg) "in_dpad_left" else "in_dpad_right")
            else -> tx.t(if (neg) "in_axis_n_inv" else "in_axis_n", b.axis)
        }
    }

    private val KEY_KEYS = mapOf(
        GamepadLogic.BUTTON_A to "key_a", GamepadLogic.BUTTON_B to "key_b", GamepadLogic.BUTTON_X to "key_x",
        GamepadLogic.BUTTON_Y to "key_y", GamepadLogic.BUTTON_L1 to "key_l1", GamepadLogic.BUTTON_R1 to "key_r1",
        GamepadLogic.BUTTON_L2 to "key_l2", GamepadLogic.BUTTON_R2 to "key_r2", GamepadLogic.BUTTON_THUMBR to "key_r3",
        106 to "key_l3", GamepadLogic.BUTTON_START to "key_start", GamepadLogic.BUTTON_SELECT to "key_select",
        GamepadLogic.BUTTON_MODE to "key_mode", GamepadLogic.DPAD_UP to "in_dpad_up", GamepadLogic.DPAD_DOWN to "in_dpad_down",
        21 to "in_dpad_left", 22 to "in_dpad_right",
    )

    fun button(b: ButtonBinding, tx: Texts): String = when (b) {
        is ButtonBinding.Key -> KEY_KEYS[b.code]?.let(tx::t) ?: tx.t("key_unknown", b.code)
        is ButtonBinding.AxisBtn -> when (b.axis) {
            PadAxis.R2 -> tx.t("in_r2")
            PadAxis.L2 -> tx.t("in_l2")
            PadAxis.HAT_Y -> tx.t(if (b.positive) "in_dpad_down" else "in_dpad_up")
            PadAxis.HAT_X -> tx.t(if (b.positive) "in_dpad_right" else "in_dpad_left")
            PadAxis.Y -> tx.t("in_flick", tx.t(if (b.positive) "in_left_down" else "in_left_up"))
            PadAxis.RY -> tx.t("in_flick", tx.t(if (b.positive) "in_right_down" else "in_right_up"))
            PadAxis.X -> tx.t("in_flick", tx.t(if (b.positive) "in_left_right" else "in_left_left"))
            PadAxis.RIGHT_X -> tx.t("in_flick", tx.t(if (b.positive) "in_right_right" else "in_right_left"))
            else -> tx.t("in_axis_n", b.axis)
        }
    }

    fun buttons(l: List<ButtonBinding>?, tx: Texts) =
        if (l.isNullOrEmpty()) "—" else l.joinToString(" / ") { button(it, tx) }

    /** Full legend generated from the mapping. */
    fun legend(m: PadMapping, tx: Texts): String {
        val moves = AxisAction.values().joinToString(" · ") { "${axisActionName(it, tx)}: ${axis(m.axes[it], tx)}" }
        val actions = BUTTON_ACTIONS.joinToString("\n") { "${actionName(it, tx)}: ${buttons(m.buttons[it], tx)}" }
        return "$moves\n$actions"
    }
}
