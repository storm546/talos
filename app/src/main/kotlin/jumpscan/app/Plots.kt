package jumpscan.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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

internal fun MainActivity.heightTrace(heights: List<Double>) {
    if (heights.size < 2) return
    val panel = card()
    text(getString(R.string.height_trace), 14, parent = panel)
    panel.addView(object : View(this) {
        val pen = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val inset = dp(4).toFloat()
            val floor = heights.min()
            val range = heights.max() - floor
            val path = Path()
            for ((index, value) in heights.withIndex()) {
                val left = inset + (width - 2 * inset) * index / heights.lastIndex
                val top = inset + (height - 2 * inset) * if (range == 0.0) .5f else (1 - (value-floor)/range).toFloat()
                if (index == 0) path.moveTo(left, top) else path.lineTo(left, top)
                if (index == heights.lastIndex) {
                    pen.style = Paint.Style.FILL; pen.color = getColor(R.color.accent)
                    canvas.drawCircle(left, top, inset, pen)
                }
            }
            pen.style = Paint.Style.STROKE; pen.strokeWidth = dp(2).toFloat(); pen.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(path, pen)
        }
    }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(-1, dp(46)))
}
