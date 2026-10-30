// SPDX-FileCopyrightText: 2026 amphigourik
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.amphigourik.jolly

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Launcher-icon palette (from logo/jolly-icon.svg). */
object Pal {
    val WHITE = 0xFFE8EAED.toInt()
    val LIME = 0xFFD4FF1E.toInt()
    val INK = 0xFF1E2126.toInt()
    val RED = 0xFFE3202F.toInt()
    val GREY = 0xFF5D626A.toInt()
    val LIGHT_GREY = 0xFFA9AEB6.toInt()
    val PANEL = 0xFF2A2E35.toInt()
    val ORANGE = 0xFFFF9800.toInt()
}

fun Context.px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

fun shape(color: Int, radius: Float, oval: Boolean = false, ring: Int = 0, ringWidth: Int = 0) = GradientDrawable().apply {
    shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
    setColor(color)
    if (!oval) cornerRadius = radius
    if (ring != 0) setStroke(ringWidth, ring)
}

/** Ink disc with a vector glyph at 2/3 of its size. `on` = false greys it out (taps ignored by the caller, long press still works). */
class IconBtn(c: Context, icon: Int, label: String) : ImageView(c) {
    var on = true
        set(v) { field = v; refresh() }
    var dim = false
        set(v) { field = v; refresh() }
    /** Ring colour when the button shows an active state (recording, panel open), 0 = none. */
    var ring = 0
        set(v) { field = v; refresh() }
    private var size = 0

    init {
        setImageResource(icon)
        scaleType = ScaleType.FIT_CENTER
        contentDescription = label
        elevation = c.px(3).toFloat()
        refresh()
    }

    fun setIcon(res: Int) = setImageResource(res)

    fun size(px: Int) { size = px; val p = px / 6; setPadding(p, p, p, p); refresh() }

    private fun refresh() {
        background = shape(Pal.INK, 0f, oval = true, ring = ring, ringWidth = if (ring != 0) context.px(2) else 0)
        alpha = if (!on) 0.35f else if (dim) 0.4f else 1f
    }
}

/** Rounded text button, dimmed when disabled. */
class PillButton(c: Context, text: String, accent: Boolean = false, red: Boolean = false) : Button(c) {
    init {
        this.text = text
        isAllCaps = false
        textSize = 14f
        minWidth = 0; minHeight = 0; minimumWidth = 0; minimumHeight = 0
        setTextColor(if (red) Color.WHITE else if (accent) Pal.INK else Pal.WHITE)
        background = shape(if (red) Pal.RED else if (accent) Pal.LIME else Pal.PANEL, c.px(18).toFloat())
        setPadding(c.px(14), c.px(8), c.px(14), c.px(8))
        stateListAnimator = null
    }
    override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); alpha = if (enabled) 1f else 0.4f }
}

/** Selectable chip: lime when selected. */
fun chip(c: Context, text: String, selected: Boolean = false): TextView = TextView(c).apply {
    this.text = text
    textSize = 13f
    gravity = Gravity.CENTER
    setTextColor(if (selected) Pal.INK else Pal.WHITE)
    background = shape(if (selected) Pal.LIME else Pal.PANEL, c.px(16).toFloat())
    setPadding(c.px(12), c.px(6), c.px(12), c.px(6))
    isClickable = true
}

fun styleSwitch(sw: Switch) {
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    sw.thumbTintList = ColorStateList(states, intArrayOf(Pal.LIME, Pal.LIGHT_GREY))
    sw.trackTintList = ColorStateList(states, intArrayOf(0x80D4FF1E.toInt(), Pal.GREY))
    sw.setTextColor(Pal.WHITE)
}

fun styleSeek(sb: SeekBar) {
    sb.progressTintList = ColorStateList.valueOf(Pal.LIME)
    sb.thumbTintList = ColorStateList.valueOf(Pal.LIME)
    sb.progressBackgroundTintList = ColorStateList.valueOf(Pal.GREY)
}

/** Wraps its children onto several rows, left to right. */
class FlowLayout(c: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(c) {
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val maxW = MeasureSpec.getSize(wSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v.visibility == GONE) continue
            v.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            if (x > 0 && x + v.measuredWidth > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            x += v.measuredWidth + hGap
            rowH = maxOf(rowH, v.measuredHeight)
        }
        setMeasuredDimension(resolveSize(maxW + paddingLeft + paddingRight, wSpec), y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v.visibility == GONE) continue
            if (x > 0 && x + v.measuredWidth > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            v.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + v.measuredWidth, paddingTop + y + v.measuredHeight)
            x += v.measuredWidth + hGap
            rowH = maxOf(rowH, v.measuredHeight)
        }
    }
}
