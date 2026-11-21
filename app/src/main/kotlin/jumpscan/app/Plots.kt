package jumpscan.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.LinearLayout
import kotlin.math.cos
import kotlin.math.sin

internal class Dial(context: Context) : View(context) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shown = -1f
    private var target = -1
    private var sweep: ValueAnimator? = null
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun show(percent: Int) {
        if (percent == target) return
        target = percent.coerceIn(0, 100)
        sweep?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) { shown = target.toFloat(); invalidate(); return }
        sweep = ValueAnimator.ofFloat(shown.coerceAtLeast(0f), target.toFloat()).apply {
            duration = 240
            interpolator = settle
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val radius = minOf(width * .35f, height * .42f)
        canvas.translate(width / 2f, height * .53f)
        pen.strokeCap = Paint.Cap.ROUND
        pen.style = Paint.Style.STROKE
        pen.strokeWidth = density * 4
        pen.color = context.getColor(R.color.hover)
        canvas.drawArc(-radius, -radius, radius, radius, 150f, 240f, false, pen)
        pen.color = context.getColor(R.color.accent)
        if (shown > 0) canvas.drawArc(-radius, -radius, radius, radius, 150f, shown * 2.4f, false, pen)
        pen.strokeWidth = density
        pen.color = context.getColor(R.color.muted)
        for (index in 0..20) {
            val angle = Math.toRadians(150.0 + index * 12)
            val inner = radius - density * if (index % 5 == 0) 10 else 7
            val outer = radius - density * 4
            canvas.drawLine(cos(angle).toFloat()*inner, sin(angle).toFloat()*inner, cos(angle).toFloat()*outer, sin(angle).toFloat()*outer, pen)
        }
        pen.style = Paint.Style.FILL
        pen.textAlign = Paint.Align.CENTER
        pen.typeface = resources.getFont(R.font.jetbrainsmono_regular)
        pen.textSize = radius * .45f
        pen.color = context.getColor(R.color.ink)
        canvas.drawText(if (shown < 0) "—" else "${shown.toInt()}%", 0f, pen.textSize * .35f, pen)
    }

    override fun onDetachedFromWindow() { sweep?.cancel(); super.onDetachedFromWindow() }
}

/** One ruler per jump on a shared scale, oldest on the left, so heights compare at a glance. */
internal class RulerWall(context: Context, private val jumps: List<Pair<Long, Double>>, private val baseline: Double?) : View(context) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = resources.getFont(R.font.jetbrainsmono_regular) }
    private val dashes = android.graphics.DashPathEffect(floatArrayOf(5f, 5f), 0f)
    private val locale = resources.configuration.locales[0]
    private var time = 1f
    private var rise: ValueAnimator? = null
    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (ValueAnimator.areAnimatorsEnabled()) rise = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900; addUpdateListener { time = it.animatedValue as Float; invalidate() }; start()
        }
    }
    override fun onDetachedFromWindow() { rise?.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density; canvas.scale(density, density)
        val type = resources.configuration.fontScale.coerceIn(1f, 1.3f)
        val w = width / density; val top = 30f; val bottom = height / density - 24f; val gutter = 26f
        val ink = context.getColor(R.color.ink); val muted = context.getColor(R.color.muted); val accent = context.getColor(R.color.accent)
        val limit = kotlin.math.ceil(maxOf(30.0, jumps.maxOf { it.second } * 1.15, baseline ?: 0.0) / 10).toFloat() * 10
        fun at(cm: Float) = bottom - (bottom - top) * cm.coerceIn(0f, limit) / limit
        pen.textSize = 10f * type; pen.textAlign = Paint.Align.RIGHT; pen.strokeWidth = 1f; pen.pathEffect = null
        for (mark in 0..limit.toInt() step 10) {
            pen.color = ink; pen.alpha = if (mark == 0) 120 else 30; canvas.drawLine(gutter, at(mark.toFloat()), w, at(mark.toFloat()), pen)
            pen.color = muted; pen.alpha = 255; canvas.drawText(mark.toString(), gutter - 6f, at(mark.toFloat()) + 3.5f, pen)
        }
        val column = (w - gutter) / jumps.size
        jumps.forEachIndexed { index, (id, cm) ->
            val newest = index == jumps.lastIndex
            val shown = cm.toFloat() * settle.getInterpolation(((time - index * .5f / jumps.size) / .5f).coerceIn(0f, 1f))
            val x = gutter + column * (index + .5f); val y = at(shown)
            pen.textAlign = Paint.Align.CENTER
            pen.color = ink; pen.alpha = 60; pen.strokeWidth = 1f
            for (mark in 0..limit.toInt() step 5) canvas.drawLine(x - 3f, at(mark.toFloat()), x + 3f, at(mark.toFloat()), pen)
            pen.color = accent; pen.alpha = if (newest) 120 else 55
            canvas.drawRoundRect(x - 4f, y, x + 4f, bottom, 4f, 4f, pen)
            pen.color = if (newest) accent else ink; pen.alpha = if (newest) 255 else 170; pen.strokeWidth = 3f
            val half = minOf(14f, column * .38f); canvas.drawLine(x - half, y, x + half, y, pen)
            pen.color = if (newest) ink else muted; pen.alpha = 255; pen.textSize = (if (newest) 13f else 10.5f) * type
            canvas.drawText(String.format(locale, "%.1f", cm), x, y - 8f, pen)
            pen.color = muted; pen.textSize = 10f * type; canvas.drawText("#$id", x, bottom + 16f, pen)
        }
        baseline?.let { cm ->
            pen.color = ink; pen.alpha = 140; pen.pathEffect = dashes; pen.strokeWidth = 1f
            canvas.drawLine(gutter, at(cm.toFloat()), w, at(cm.toFloat()), pen); pen.pathEffect = null
        }
        pen.textAlign = Paint.Align.LEFT
    }
}

internal fun MainActivity.heightTrace(jumps: List<Pair<Long, Double>>, label: String, baseline: Double?) {
    if (jumps.size < 2) return
    text(getString(R.string.height_trace) + " · " + label, 14).setPadding(0, dp(18), 0, 0)
    baseline?.let { text(getString(R.string.flight_reference, String.format(resources.configuration.locales[0], "%.1f", it), getString(R.string.ui_cm)), 12) }
    page.addView(RulerWall(this, jumps.takeLast(10), baseline), LinearLayout.LayoutParams(-1, dp(250)))
}
