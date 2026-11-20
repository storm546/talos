package jumpscan.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import org.json.JSONObject
import kotlin.math.ceil

internal class HeightRule(context: Context, row: JSONObject, private val history: List<Double> = emptyList()) : View(context) {
    private val cm = row.getDouble("jump_height_cm").toFloat()
    private val side = row.optJSONArray("warnings")?.let { list -> (0 until list.length()).any { list.optString(it) == "single_leg_view" } } == true
    private val band = (9.81 / 4 * row.optDouble("flight_time_ms", 0.0) / 1000 * (if (side) .0879 else .0321) * 100).toFloat()
    private val ink = context.getColor(R.color.ink); private val accent = context.getColor(R.color.accent)
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = resources.getFont(R.font.jetbrainsmono_regular) }
    private val value = format(cm); private val unit = context.getString(R.string.ui_cm)
    private val error = "± ${format(band)} $unit"
    private var reference: Float? = null
    private var caption = ""
    private var motion: ValueAnimator? = null
    private var fraction = 1f
    private val density = resources.displayMetrics.density
    private fun format(number: Float) = String.format(resources.configuration.locales[0], "%.1f", number)
    init { baseline(null) }
    fun baseline(height: Double?) {
        reference = height?.takeIf { it.isFinite() }?.toFloat()
        caption = reference?.let { context.getString(R.string.flight_reference, format(it), unit) }.orEmpty()
        contentDescription = "$value $unit, $error. $caption"; invalidate()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (ValueAnimator.areAnimatorsEnabled()) motion = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 530; interpolator = settle; addUpdateListener { fraction = it.animatedValue as Float; invalidate() }; start()
        }
    }
    override fun onDetachedFromWindow() { motion?.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        canvas.scale(density, density); val bottom = height / density - 8; val top = 8f
        val limit = ceil(maxOf(40f, cm + band, reference ?: 0f, history.maxOrNull()?.toFloat() ?: 0f) / 10) * 10
        fun at(heightCm: Float) = bottom - (bottom - top) * heightCm.coerceIn(0f, limit) / limit
        pen.color = ink; pen.strokeWidth = 1f; pen.alpha = 100
        canvas.drawLine(16f, top, 16f, bottom, pen)
        for (mark in 0..limit.toInt() step 5) canvas.drawLine(16f, at(mark.toFloat()), if (mark%10==0) 30f else 23f, at(mark.toFloat()), pen)
        pen.alpha = 75; for (previous in history) canvas.drawLine(35f, at(previous.toFloat()), 70f, at(previous.toFloat()), pen)
        pen.color = accent; pen.alpha = 54; canvas.drawRect(15f, at((cm + band)*fraction), 79f, at((cm-band)*fraction), pen)
        pen.color = accent; pen.strokeWidth = 2f; canvas.drawLine(13f, at(cm*fraction), 81f, at(cm*fraction), pen)
        canvas.drawCircle(81f, at(cm*fraction), 3.5f, pen)
        reference?.let { pen.color = ink; canvas.drawLine(32f, at(it), 60f, at(it), pen); canvas.drawCircle(32f, at(it), 2.5f, pen) }
        pen.color = ink; pen.textSize = 32f; pen.textScaleX = .84f; val middle = (bottom+top)/2
        canvas.drawText(value, 96f, middle, pen); val next = 102 + pen.measureText(value)
        pen.textScaleX = 1f; pen.textSize = 14f; canvas.drawText(unit, next, middle, pen)
        pen.color = context.getColor(R.color.muted); canvas.drawText(error, 96f, middle+19, pen)
        pen.textSize = 11f; canvas.drawText(caption, 96f, bottom, pen)
    }
}
