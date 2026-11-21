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
    private val ink = context.getColor(R.color.ink); private val accent = context.getColor(R.color.accent); private val muted = context.getColor(R.color.muted)
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
        canvas.scale(density, density); val bottom = height / density - 10; val top = 10f; val spine = 30f
        val type = resources.configuration.fontScale.coerceIn(1f, 1.3f)
        val limit = ceil(maxOf(40f, cm + band, reference ?: 0f, history.maxOrNull()?.toFloat() ?: 0f) / 10) * 10
        fun at(heightCm: Float) = bottom - (bottom - top) * heightCm.coerceIn(0f, limit) / limit
        pen.style = Paint.Style.FILL; pen.textAlign = Paint.Align.RIGHT; pen.textScaleX = 1f
        pen.color = ink; pen.strokeWidth = 1f; pen.alpha = 110
        canvas.drawLine(spine, top, spine, bottom, pen)
        pen.textSize = 9.5f * type
        for (mark in 0..limit.toInt() step 5) {
            val major = mark % 10 == 0; pen.color = ink; pen.alpha = if (major) 150 else 80
            canvas.drawLine(spine, at(mark.toFloat()), spine + if (major) 12f else 6f, at(mark.toFloat()), pen)
            if (major && mark > 0) { pen.color = muted; pen.alpha = 255; canvas.drawText(mark.toString(), spine - 6f, at(mark.toFloat()) + 3.5f, pen) }
        }
        pen.textAlign = Paint.Align.LEFT
        pen.color = muted; pen.alpha = 120; pen.strokeWidth = 2f
        for (previous in history) canvas.drawLine(spine + 3f, at(previous.toFloat()), spine + 22f, at(previous.toFloat()), pen)
        val level = at(cm * fraction)
        pen.color = accent; pen.alpha = 70; canvas.drawRect(spine - 3f, level, spine + 3f, bottom, pen)
        pen.alpha = 40; canvas.drawRect(spine - 5f, at((cm + band) * fraction), spine + 26f, at((cm - band) * fraction), pen)
        pen.alpha = 255; pen.strokeWidth = 3.5f; canvas.drawLine(spine - 10f, level, spine + 40f, level, pen)
        canvas.drawCircle(spine + 40f, level, 4.5f, pen)
        reference?.let { pen.color = ink; pen.strokeWidth = 1.5f; canvas.drawLine(spine - 8f, at(it), spine + 28f, at(it), pen); canvas.drawCircle(spine - 8f, at(it), 3f, pen) }
        val x = spine + 56f
        pen.color = ink; pen.textSize = 46f * type; pen.textScaleX = .82f
        val base = (bottom + top) / 2 + 10f
        canvas.drawText(value, x, base, pen); val next = x + 6 + pen.measureText(value)
        pen.textScaleX = 1f; pen.textSize = 16f * type; pen.color = muted; canvas.drawText(unit, next, base, pen)
        pen.textSize = 13f * type; canvas.drawText(error, x + 2f, base + 22f * type, pen)
        pen.textSize = 11.5f * type; canvas.drawText(caption, x + 2f, bottom, pen)
    }
}
