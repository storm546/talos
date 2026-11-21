package jumpscan.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.roundToInt

private val joints = intArrayOf(0, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28, 31, 32)
private val links = intArrayOf(1,2, 1,3, 3,5, 2,4, 4,6, 1,7, 2,8, 7,8, 7,9, 9,11, 8,10, 10,12, 11,13, 12,14)

internal class FlightStrip(val heightCm: Double, val durationMs: Long, val samples: Array<IntArray>) {
    fun encode() = JSONObject().put("cm", heightCm).put("ms", durationMs).put("frames",
        JSONArray().apply { samples.forEach { put(JSONArray(it)) } }).toString()

    companion object {
        fun read(value: String?) = value?.let { runCatching {
            val json = JSONObject(it)
            val frames = json.getJSONArray("frames")
            require(frames.length() in 2..32)
            FlightStrip(json.getDouble("cm"), json.getLong("ms"), Array(frames.length()) { index ->
                val frame = frames.getJSONArray(index)
                IntArray(joints.size * 2) { frame.getInt(it) }
            })
        }.getOrNull() }

        fun from(run: Analysis): FlightStrip? {
            val takeoff = run.landing.takeoffMs ?: return null
            val landing = run.landing.contactMs ?: return null
            val height = run.metrics.jumpHeightCm ?: return null
            if (landing <= takeoff || !height.isFinite() || height <= 0 || run.frames.isEmpty()) return null
            val count = minOf(32, run.frames.count { it.tMs in takeoff..landing })
            if (count < 2) return null
            val samples = Array(count) { index ->
                val time = takeoff + index * (landing - takeoff) / (count - 1)
                val found = run.frames.binarySearchBy(time) { it.tMs }
                val after = (if (found < 0) -found - 1 else found).coerceIn(run.frames.indices)
                val before = (after - 1).coerceAtLeast(0)
                val pose = run.frames[if (time - run.frames[before].tMs < run.frames[after].tMs - time) before else after].landmarks
                IntArray(joints.size * 2) { coord ->
                    val point = pose[joints[coord / 2]]
                    if (point.visibility < .5f || !point.x.isFinite() || !point.y.isFinite()) Int.MIN_VALUE
                    else ((if (coord % 2 == 0) point.x * run.aspect else point.y) * 1000).roundToInt()
                }
            }
            return FlightStrip(height, landing - takeoff, samples)
        }
    }
}

/**
 * The jump stage: a height ruler on the left, the athlete's keyframes rising off a ground line by the
 * measured height on the ruler's scale, and the flight drawn as a height-over-time arc. Without a
 * measured jump it plays a demonstration figure and shows no number.
 */
internal class FlightView(context: Context, private val flight: FlightStrip?, private val previous: List<Double> = emptyList(),
                          private val band: Double? = null, private val label: String? = null) : View(context) {
    private val accent = context.getColor(R.color.accent)
    private val ink = context.getColor(R.color.ink)
    private val muted = context.getColor(R.color.muted)
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val dots = android.graphics.DashPathEffect(floatArrayOf(.1f, 7f), 0f)
    private val dashes = android.graphics.DashPathEffect(floatArrayOf(5f, 5f), 0f)
    private val locale = resources.configuration.locales[0]
    private val type = resources.configuration.fontScale.coerceIn(1f, 1.3f)
    private val unit = context.getString(R.string.ui_cm)
    private val heading = (label ?: context.getString(if (flight == null) R.string.flight_demo else R.string.flight_last)).uppercase(locale)
    private val caption = context.getString(R.string.flight_replay)
    private val airtime = flight?.let { "${it.durationMs} ${context.getString(R.string.ui_ms)}" }
    private val digits = resources.getFont(R.font.jetbrainsmono_regular)
    private val bold = resources.getFont(R.font.spacegrotesk_bold)
    private val demo = intArrayOf(50,17, 44,26, 56,25, 35,35, 67,32, 31,25, 72,19, 46,48, 54,48, 40,62, 61,62, 42,78, 58,78, 36,80, 65,80)
    private val heightCm = flight?.heightCm?.toFloat() ?: 20f
    private val poses: List<FloatArray>
    private val span: Float
    private var reference: Float? = null
    private val limit get() = ceil(maxOf(40f, heightCm * 1.5f, previous.maxOrNull()?.toFloat() ?: 0f, (reference ?: 0f) * 1.2f) / 10) * 10
    private var phase = 1f
    private var motion: ValueAnimator? = null

    init {
        // Each keyframe is stood on its own feet and centred on its hips, so only the measured height lifts it.
        val raw = flight?.samples ?: arrayOf(demo)
        poses = raw.map { pose ->
            val valid = joints.indices.filter { pose[2 * it] != Int.MIN_VALUE }
            val feet = valid.filter { it >= 11 }.maxOfOrNull { pose[2 * it + 1] } ?: valid.maxOf { pose[2 * it + 1] }
            val hips = valid.filter { it == 7 || it == 8 }.map { pose[2 * it] }.ifEmpty { valid.map { pose[2 * it] } }.average().toFloat()
            FloatArray(joints.size * 2) { coord ->
                val joint = coord / 2
                if (pose[2 * joint] == Int.MIN_VALUE) Float.NaN
                else if (coord % 2 == 0) pose[coord] - hips else (feet - pose[coord]).toFloat()
            }
        }
        span = poses.maxOf { pose -> (1 until pose.size step 2).maxOf { if (pose[it].isNaN()) 0f else pose[it] } }.coerceAtLeast(1f)
        isFocusable = true
        setOnClickListener { replay(true) }
        describe()
    }

    fun baseline(height: Double?) {
        reference = height?.takeIf { it.isFinite() && it > 0 }?.toFloat(); describe(); invalidate()
    }

    private fun format(value: Float) = String.format(locale, "%.1f", value)
    private fun describe() {
        contentDescription = listOfNotNull(heading.lowercase(locale).replaceFirstChar { it.titlecase(locale) },
            flight?.let { format(heightCm) + " " + unit }, band?.let { "± " + format(it.toFloat()) + " " + unit },
            reference?.let { context.getString(R.string.flight_reference, format(it), unit) }, caption.trimStart('↻', ' ')).joinToString(". ")
    }

    private fun replay(tactile: Boolean = false) {
        motion?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) { phase = 1f; invalidate(); return }
        motion = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ((flight?.durationMs ?: 500L) * 4 / .7f).toLong().coerceIn(2000, 3600)
            interpolator = LinearInterpolator()
            var landed = false
            addUpdateListener {
                phase = it.animatedFraction
                if (tactile && !landed && phase >= .82f) { landed = true; performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
                invalidate()
            }; start()
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); replay() }
    override fun onDetachedFromWindow() { motion?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        canvas.scale(density, density)
        val w = width / density; val h = height / density
        val pad = 18f; val ground = h - 40f; val top = 56f; val spine = pad + 26f
        val flightFraction = ((phase - .12f) / .7f).coerceIn(0f, 1f)
        val lift = 4 * flightFraction * (1 - flightFraction)
        val peak = if (flightFraction >= .5f) 1f else lift
        val settled = ((phase - .82f) / .18f).coerceIn(0f, 1f)
        val scale = limit
        fun at(cm: Float) = ground - (ground - top) * cm.coerceIn(0f, scale) / scale
        pen.pathEffect = null; pen.style = Paint.Style.FILL

        // Header: what this is, and how to replay it.
        pen.typeface = bold; pen.textSize = 12f * type; pen.letterSpacing = .08f; pen.color = muted
        canvas.drawText(heading, pad, 22f, pen)
        pen.letterSpacing = 0f; pen.textAlign = Paint.Align.RIGHT
        canvas.drawText(caption, w - pad, 22f, pen); pen.textAlign = Paint.Align.LEFT

        // Ruler: 5 cm ticks, labels every 10 cm, the unit above the spine.
        pen.color = ink; pen.alpha = 110; pen.strokeWidth = 1f
        canvas.drawLine(spine, top, spine, ground, pen)
        pen.typeface = digits; pen.textSize = 10.5f * type; pen.textAlign = Paint.Align.RIGHT
        for (mark in 0..scale.toInt() step 5) {
            val y = at(mark.toFloat()); val major = mark % 10 == 0
            pen.color = ink; pen.alpha = if (major) 150 else 90
            canvas.drawLine(spine, y, spine + if (major) 12f else 6f, y, pen)
            if (major) { pen.color = muted; canvas.drawText(mark.toString(), spine - 7f, y + 4f, pen) }
        }
        pen.color = muted; canvas.drawText(unit, spine + 4f, top - 10f, pen); pen.textAlign = Paint.Align.LEFT

        // Ground line across the stage.
        pen.color = ink; pen.alpha = 120; pen.strokeWidth = 1.5f
        canvas.drawLine(pad, ground, w - pad, ground, pen)

        // Earlier jumps as faint ticks, the starting point as a dashed line across the whole stage.
        pen.color = muted; pen.alpha = 120; pen.strokeWidth = 2f
        for (cm in previous) canvas.drawLine(spine + 3f, at(cm.toFloat()), spine + 20f, at(cm.toFloat()), pen)
        reference?.let { cm ->
            val y = at(cm); pen.color = ink; pen.alpha = 150; pen.strokeWidth = 1f; pen.pathEffect = dashes
            canvas.drawLine(spine, y, w - pad, y, pen); pen.pathEffect = null
            pen.color = muted; pen.typeface = digits; pen.textSize = 11.5f * type
            canvas.drawLine(pad, ground + 18f, pad + 14f, ground + 18f, pen.apply { pathEffect = dashes; strokeWidth = 1.5f }); pen.pathEffect = null
            canvas.drawText(context.getString(R.string.flight_reference, format(cm), unit), pad + 20f, ground + 22f, pen)
        }

        // The athlete: keyframes on the stage, feet lifted on the ruler's scale.
        val left = w * .58f; val right = w - pad; val centre = (left + right) / 2
        val rise = ground - at(heightCm)
        val figure = minOf(h * .42f, ground - top - rise + 14f).coerceAtLeast(80f)
        val fit = figure / span
        val index = when { phase < .12f -> 0; else -> (flightFraction * (poses.size - 1)).roundToInt() }
        val crouch = if (flight == null) (if (phase < .12f) phase / .12f else if (phase > .82f) 1 - settled else 0f) else 0f
        fun drawFigure(pose: FloatArray, feet: Float, bend: Float) {
            fun x(j: Int) = centre + pose[2 * j] * fit + when (j) { 9 -> -6f * bend; 10 -> 6f * bend; else -> 0f }
            fun y(j: Int) = feet - pose[2 * j + 1] * fit + if (j <= 8) 16f * bend else if (j <= 10) 8f * bend else 0f
            fun ok(j: Int) = !pose[2 * j].isNaN()
            pen.color = ink; pen.strokeWidth = 5f
            for (i in links.indices step 2) if (ok(links[i]) && ok(links[i + 1])) canvas.drawLine(x(links[i]), y(links[i]), x(links[i + 1]), y(links[i + 1]), pen)
            if (ok(0)) { pen.style = Paint.Style.STROKE; pen.strokeWidth = 3.5f
                canvas.drawCircle(x(0), y(0) - figure * .03f, figure * .06f, pen); pen.style = Paint.Style.FILL }
            for (j in 1 until joints.size) if (ok(j)) {
                pen.color = accent; pen.alpha = 60; canvas.drawCircle(x(j), y(j), 7f, pen)
                pen.alpha = 255; canvas.drawCircle(x(j), y(j), 3.2f, pen)
            }
        }
        // The flight arc: height over time, ghosted ahead, drawn in as it happens.
        fun arc(fraction: Float) = (left + (right - left) * fraction) to (ground - rise * 4 * fraction * (1 - fraction))
        pen.pathEffect = dots; pen.strokeWidth = 3f; pen.style = Paint.Style.STROKE
        val path = android.graphics.Path(); val done = android.graphics.Path()
        for (step in 0..48) { val (x, y) = arc(step / 48f); if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
            if (step / 48f <= flightFraction) { if (step == 0) done.moveTo(x, y) else done.lineTo(x, y) } }
        pen.color = muted; pen.alpha = 70; canvas.drawPath(path, pen)
        pen.color = accent; pen.alpha = 255; canvas.drawPath(done, pen)
        pen.pathEffect = null; pen.style = Paint.Style.FILL
        if (phase >= .12f) { val (x, y) = arc(flightFraction); pen.alpha = 50; canvas.drawCircle(x, y, 11f, pen); pen.alpha = 255; canvas.drawCircle(x, y, 4.5f, pen) }
        drawFigure(poses[index], ground - rise * lift, crouch)

        // The measurement: a peak-hold marker rising up the ruler with the number riding beside it.
        val marker = at(heightCm * peak)
        pen.color = accent; pen.alpha = 70; canvas.drawRect(spine - 3f, marker, spine + 3f, ground, pen)
        if (band != null && settled > 0) { pen.alpha = (45 * settled).toInt()
            canvas.drawRect(spine - 5f, at(heightCm + band.toFloat()), spine + 22f, at(heightCm - band.toFloat()), pen) }
        pen.alpha = 255; pen.style = Paint.Style.STROKE; pen.strokeWidth = 3.5f
        canvas.drawLine(spine - 10f, marker, spine + 30f, marker, pen); pen.style = Paint.Style.FILL
        canvas.drawCircle(spine + 30f, marker, 4.5f, pen)
        if (flight != null) {
            pen.typeface = digits; pen.textScaleX = .82f; pen.textSize = 60f * type
            val tenths = (heightCm * 10).roundToInt() * peak
            val shown = format(kotlin.math.floor(tenths) / 10)
            val room = left - spine - 50f; while (pen.measureText(shown) > room && pen.textSize > 34f) pen.textSize -= 2f
            val size = pen.textSize
            val base = (marker + size * .34f).coerceIn(top + size * .7f, ground - 22f)
            val x = spine + 42f; val stem = shown.dropLast(1)
            pen.color = ink; canvas.drawText(stem, x, base, pen)
            val digitX = x + pen.measureText(stem); val roll = tenths - kotlin.math.floor(tenths)
            canvas.save(); canvas.clipRect(digitX, base - size * .8f, digitX + pen.measureText("0"), base + size * .2f)
            val digit = shown.last().digitToIntOrNull() ?: 0
            canvas.drawText(shown.last().toString(), digitX, base - roll * size, pen)
            if (roll > 0) canvas.drawText(((digit + 1) % 10).toString(), digitX, base + (1 - roll) * size, pen)
            canvas.restore()
            val after = digitX + pen.measureText("0") + 5f
            pen.textScaleX = 1f; pen.textSize = 17f * type; pen.color = muted; canvas.drawText(unit, after, base, pen)
            if (band != null && settled > 0) { pen.textSize = 13f * type; pen.alpha = (255 * settled).toInt()
                canvas.drawText("± " + format(band.toFloat()) + " " + unit, x + 2f, base + 20f * type, pen) }
        }

        // Under the ground: airtime as an instrument readout.
        airtime?.let { pen.color = muted; pen.alpha = 255; pen.typeface = digits; pen.textSize = 11.5f * type; pen.textAlign = Paint.Align.RIGHT
            canvas.drawText(it, right, ground + 22f, pen); pen.textAlign = Paint.Align.LEFT }
        pen.textScaleX = 1f
    }
}
