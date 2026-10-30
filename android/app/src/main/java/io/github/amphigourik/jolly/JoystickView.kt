// SPDX-FileCopyrightText: 2026 amphigourik
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.amphigourik.jolly

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/** Two multi-touch virtual joysticks: left = speed (vertical), right = turn (horizontal). */
class JoystickView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    /** speed (up is positive) and turn (right is positive), normalized to -1..1. */
    var onChange: ((speed: Float, turn: Float) -> Unit)? = null

    /** A quick tap (< 300 ms, < 16 dp of movement) on the video area, away from both joysticks. */
    var onTap: (() -> Unit)? = null

    /** A finger lands on a joystick base. */
    var onGrab: (() -> Unit)? = null
    private var tapPointer = -1
    private var tapX = 0f
    private var tapY = 0f
    private var tapTime = 0L
    private fun cancelTap() { tapPointer = -1 }

    private class Stick { var id = -1; var dx = 0f; var dy = 0f }
    private val left = Stick()
    private val right = Stick()
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF; style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 4f }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt(); style = Paint.Style.FILL }

    private val radius get() = min(width / 6f, height / 3f)
    private val leftCx get() = width * 0.15f
    private val rightCx get() = width * 0.85f
    private val cy get() = height * 0.72f

    /** Free horizontal range between the two grab zones (grown by `zone` x radius) over the vertical band [top, bottom], in view px. */
    fun gap(top: Float, bottom: Float, zone: Float = GRAB): Pair<Float, Float> {
        val rr = radius * zone
        val dy = if (cy in top..bottom) 0f else if (cy < top) top - cy else cy - bottom
        val half = if (dy >= rr) 0f else sqrt(rr * rr - dy * dy)
        return (leftCx + half) to (rightCx - half)
    }

    private companion object { const val GRAB = 1.8f }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val isLeft = e.getX(i) < width / 2f
                val s = if (isLeft) left else right
                val cx = if (isLeft) leftCx else rightCx
                // Only grab the stick near its base; elsewhere the touch is on the video (a quick tap reveals the action bar).
                val onBase = hypot(e.getX(i) - cx, e.getY(i) - cy) <= radius * GRAB
                if (onBase) {
                    if (s.id == -1) { s.id = e.getPointerId(i); update(s, e, i) }
                    cancelTap()
                    onGrab?.invoke()
                } else if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    tapPointer = e.getPointerId(i); tapX = e.getX(i); tapY = e.getY(i); tapTime = e.eventTime
                } else cancelTap()
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                if (e.getPointerId(i) == tapPointer && hypot(e.getX(i) - tapX, e.getY(i) - tapY) > 16 * resources.displayMetrics.density) cancelTap()
                val s = stickFor(e.getPointerId(i)) ?: continue
                update(s, e, i)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val id = if (e.actionMasked == MotionEvent.ACTION_CANCEL) -2 else e.getPointerId(e.actionIndex)
                if (id != -2 && id == tapPointer && e.eventTime - tapTime < 300) onTap?.invoke()
                cancelTap()
                for (s in listOf(left, right)) if (id == -2 || s.id == id) { s.id = -1; s.dx = 0f; s.dy = 0f }
            }
        }
        notifyChange()
        invalidate()
        return true
    }

    private fun stickFor(id: Int) = when (id) { left.id -> left; right.id -> right; else -> null }

    private fun update(s: Stick, e: MotionEvent, i: Int) {
        val cx = if (s === left) leftCx else rightCx
        var dx = e.getX(i) - cx
        var dy = e.getY(i) - cy
        val d = hypot(dx, dy)
        if (d > radius) { dx *= radius / d; dy *= radius / d }
        s.dx = dx; s.dy = dy
    }

    private fun notifyChange() {
        val speed = (-left.dy / radius).coerceIn(-1f, 1f)
        val turn = (right.dx / radius).coerceIn(-1f, 1f)
        onChange?.invoke(speed, turn)
    }

    /** Resets both joysticks to zero. */
    fun reset() { left.id = -1; right.id = -1; left.dx = 0f; left.dy = 0f; right.dx = 0f; right.dy = 0f; onChange?.invoke(0f, 0f); invalidate() }

    override fun onDraw(c: Canvas) {
        for ((s, cx) in listOf(left to leftCx, right to rightCx)) {
            c.drawCircle(cx, cy, radius, basePaint)
            c.drawCircle(cx, cy, radius, ringPaint)
            c.drawCircle(cx + s.dx, cy + s.dy, radius * 0.4f, knobPaint)
        }
    }
}
