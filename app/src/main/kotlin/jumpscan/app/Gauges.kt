package jumpscan.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** Food day gauge: a thick calorie arc with the three macros as thinner arcs inside; it sweeps in and the number counts up. */
internal class FuelGauge(context: Context, private val arcs: List<Pair<Double?, Int>>, private val over: Boolean,
                         private val big: Double?, private val bigText: String, private val label: String) : View(context) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val locale = resources.configuration.locales[0]
    private var time = 1f
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); time = 0f; sweepIn { time = it } }
    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val cx = width / 2f; val cy = height * .56f
        var r = minOf(width / 2f, height * .52f) - 10 * d
        val start = 150f; val sweep = 240f
        arcs.forEachIndexed { i, (fraction, color) ->
            val stroke = (if (i == 0) 12 else 6) * d
            val box = RectF(cx - r, cy - r, cx + r, cy + r)
            pen.style = Paint.Style.STROKE; pen.strokeCap = Paint.Cap.ROUND; pen.strokeWidth = stroke; pen.shader = null
            pen.color = context.getColor(R.color.hover)
            canvas.drawArc(box, start, sweep, false, pen)
            if (fraction != null && fraction > 0) {
                val end = (sweep * fraction.coerceIn(0.0, 1.0) * time).toFloat()
                val tint = if (i == 0 && over) context.getColor(R.color.warning) else color
                // The arc with a light-to-full gradient along its sweep.
                pen.shader = SweepGradient(cx, cy, intArrayOf(lighter(tint), tint, tint), floatArrayOf(0f, .6f, 1f))
                    .apply { setLocalMatrix(android.graphics.Matrix().apply { setRotate(start - 4, cx, cy) }) }
                canvas.drawArc(box, start, end, false, pen)
                pen.shader = null
                if (i == 0) { // a bright dot rides the tip of the calorie arc
                    val a = Math.toRadians((start + end).toDouble())
                    pen.style = Paint.Style.FILL; pen.color = Color.WHITE
                    canvas.drawCircle(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), stroke * .28f, pen)
                }
            }
            r -= if (i == 0) 18 * d else 11 * d
        }
        pen.style = Paint.Style.FILL; pen.shader = null; pen.textAlign = Paint.Align.CENTER
        pen.typeface = resources.getFont(R.font.spacegrotesk_bold)
        pen.color = context.getColor(if (over) R.color.warning else R.color.ink)
        pen.textSize = 38 * d
        val shown = if (big == null || time >= 1f) bigText else metricNumber(big * time, "kcal", locale)
        canvas.drawText(shown, cx, cy + 6 * d, pen)
        pen.typeface = resources.getFont(R.font.spacegrotesk_regular); pen.color = context.getColor(R.color.muted); pen.textSize = 12 * d
        canvas.drawText(label, cx, cy + 24 * d, pen)
    }
}

/** Gym streak: one ring segment per planned workout this week, filled one after another; weeks in a row in the middle. */
internal class StreakRing(context: Context, private val weeks: Int, private val done: Int, private val target: Int) : View(context) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private var time = 1f
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); time = 0f; sweepIn { time = it } }
    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val r = minOf(width, height) / 2f - 7 * d
        val box = RectF(width / 2f - r, height / 2f - r, width / 2f + r, height / 2f + r)
        val n = target.coerceIn(1, 14); val gap = if (n > 1) 14f else 0f; val each = 360f / n - gap
        pen.style = Paint.Style.STROKE; pen.strokeWidth = 8 * d; pen.strokeCap = Paint.Cap.ROUND
        for (i in 0 until n) {
            val from = -90f + gap / 2 + i * (each + gap)
            pen.color = context.getColor(R.color.hover); canvas.drawArc(box, from, each, false, pen)
            val fill = ((time * n - i).coerceIn(0f, 1f)) // segments fill in turn
            if (i < done && fill > 0) { pen.color = context.getColor(R.color.accent); canvas.drawArc(box, from, each * fill, false, pen) }
        }
        pen.style = Paint.Style.FILL; pen.textAlign = Paint.Align.CENTER; pen.typeface = resources.getFont(R.font.spacegrotesk_bold)
        pen.color = context.getColor(R.color.ink); pen.textSize = r * .9f
        canvas.drawText("${(weeks * time).toInt().coerceAtMost(weeks)}", width / 2f, height / 2f + pen.textSize * .35f, pen)
    }
}

/** The colour mixed a third of the way to white, for highlights. */
internal fun lighter(color: Int, amount: Float = .35f) = Color.rgb(
    (Color.red(color) + (255 - Color.red(color)) * amount).toInt(),
    (Color.green(color) + (255 - Color.green(color)) * amount).toInt(),
    (Color.blue(color) + (255 - Color.blue(color)) * amount).toInt())

/** This week Monday to Sunday: trained days are filled circles that pop in one after another, today is ringed. */
internal class WeekStrip(context: Context, private val days: List<Boolean>, private val today: Int) : View(context) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letters = java.time.DayOfWeek.entries.map {
        it.getDisplayName(java.time.format.TextStyle.SHORT, resources.configuration.locales[0]).take(2) }
    private var time = 1f
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); time = 0f; sweepIn { time = it } }
    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val slot = width / 7f; val r = minOf(slot / 2 - 6 * d, 17 * d); val cy = r + 7 * d
        val accent = context.getColor(R.color.accent)
        for (i in 0 until 7) {
            val cx = slot * i + slot / 2
            // Each trained day pops (slight overshoot) a little after the one before it.
            val t = ((time * 7 - i * .6f) / 1.5f).coerceIn(0f, 1f)
            val pop = if (t < 1f) t * (1.18f - .18f * t) else 1f
            pen.shader = null; pen.style = Paint.Style.FILL
            if (days.getOrElse(i) { false } && pop > 0) {
                pen.shader = android.graphics.LinearGradient(cx, cy - r, cx, cy + r, lighter(accent), accent, android.graphics.Shader.TileMode.CLAMP)
                canvas.drawCircle(cx, cy, r * pop, pen)
                pen.shader = null; pen.color = context.getColor(R.color.on_action); pen.style = Paint.Style.STROKE
                pen.strokeWidth = 2.4f * d; pen.strokeCap = Paint.Cap.ROUND; pen.strokeJoin = Paint.Join.ROUND
                if (pop > .7f) canvas.drawPath(android.graphics.Path().apply { // a tick
                    moveTo(cx - r * .36f, cy); lineTo(cx - r * .08f, cy + r * .3f); lineTo(cx + r * .4f, cy - r * .3f) }, pen)
            } else {
                pen.color = context.getColor(R.color.hover); canvas.drawCircle(cx, cy, r, pen)
            }
            if (i == today) {
                pen.shader = null; pen.style = Paint.Style.STROKE; pen.strokeWidth = 2 * d; pen.color = context.getColor(R.color.ink)
                canvas.drawCircle(cx, cy, r + 3.5f * d, pen)
            }
            pen.style = Paint.Style.FILL; pen.textAlign = Paint.Align.CENTER; pen.textSize = 11 * d
            pen.typeface = resources.getFont(R.font.spacegrotesk_regular)
            pen.color = context.getColor(if (i == today) R.color.ink else R.color.muted)
            canvas.drawText(letters[i], cx, cy + r + 17 * d, pen)
        }
    }
}

/** A round start button: accent gradient, a play triangle and a soft glow drawn under it; it bounces when pressed. */
internal class PlayButton(context: Context, description: String, start: () -> Unit) : View(context) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent = context.getColor(R.color.accent)
    init {
        contentDescription = description; isClickable = true; isFocusable = true
        setOnClickListener { start() }; pressFeedback()
    }
    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val r = width * .33f; val cy = height / 2f - r * .1f
        pen.style = Paint.Style.FILL
        pen.shader = android.graphics.RadialGradient(cx, cy + r * .15f, r * 1.25f, intArrayOf(Color.argb(110, Color.red(accent),
            Color.green(accent), Color.blue(accent)), Color.TRANSPARENT), floatArrayOf(.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy + r * .15f, r * 1.25f, pen)
        pen.shader = android.graphics.LinearGradient(cx, cy - r, cx, cy + r, lighter(accent), accent, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, pen)
        pen.shader = null; pen.color = context.getColor(R.color.on_action)
        val s = r * .38f; val tx = cx + s * .18f
        canvas.drawPath(android.graphics.Path().apply { moveTo(tx - s * .8f, cy - s); lineTo(tx + s, cy); lineTo(tx - s * .8f, cy + s); close() }, pen)
    }
}
