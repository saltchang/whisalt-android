package com.saltchang.whisalt

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.log10
import kotlin.math.sqrt

/** Scrolling bars of recent microphone loudness, newest on the right, so the user sees they are heard. */
class LevelMeterView(context: Context) : View(context) {

    private val levels = FloatArray(BARS)
    private var next = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }

    /** Main thread only. */
    fun push(level: Float) {
        levels[next] = level
        next = (next + 1) % BARS
        invalidate()
    }

    fun clear() {
        levels.fill(0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val slot = width.toFloat() / BARS
        val barWidth = slot * 0.6f
        val minHeight = barWidth
        val midY = height / 2f
        for (i in 0 until BARS) {
            val level = levels[(next + i) % BARS]
            val half = maxOf(minHeight, level * height) / 2
            val x = i * slot + (slot - barWidth) / 2
            canvas.drawRoundRect(x, midY - half, x + barWidth, midY + half, barWidth / 2, barWidth / 2, paint)
        }
    }

    companion object {
        private const val BARS = 32
        private const val FLOOR_DB = -50.0 // room noise stays flat
        private const val CEIL_DB = -10.0  // loud speech fills the bar

        /** Loudness of 16-bit little-endian PCM as 0..1 on a dB scale. */
        fun level(pcm: ByteArray, length: Int): Float {
            val count = length / 2
            if (count == 0) return 0f
            var sum = 0.0
            for (i in 0 until count) {
                val s = ((pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)) / 32768.0
                sum += s * s
            }
            val rms = sqrt(sum / count)
            if (rms <= 0.0) return 0f
            val db = 20 * log10(rms)
            return ((db - FLOOR_DB) / (CEIL_DB - FLOOR_DB)).coerceIn(0.0, 1.0).toFloat()
        }
    }
}
