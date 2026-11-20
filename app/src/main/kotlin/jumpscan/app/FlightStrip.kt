package jumpscan.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator
import org.json.JSONArray
import org.json.JSONObject
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

internal class FlightView(context: Context, private val flight: FlightStrip?) : View(context) {
    private val signal = context.getColor(R.color.signal)
    private val chalk = context.getColor(R.color.on_hero)
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val unit = context.getString(R.string.ui_cm)
    private val heading = context.getString(if (flight == null) R.string.flight_demo else R.string.flight_last)
    private val caption = context.getString(R.string.flight_replay)
    private val digits = resources.getFont(R.font.jetbrainsmono_regular)
    private val label = resources.getFont(R.font.spacegrotesk_bold)
    private val values = Array(101) { String.format(resources.configuration.locales[0], "%.1f", (flight?.heightCm ?: 0.0) * it / 100) }
    private val demo = intArrayOf(50,17, 44,26, 56,25, 35,35, 67,32, 31,25, 72,19, 46,48, 54,48, 40,62, 61,62, 42,78, 58,78, 36,80, 65,80)
    private val samples = flight?.samples ?: arrayOf(demo)
    private val coords = samples.flatMap { it.asList().chunked(2) }.filter { it[0] != Int.MIN_VALUE }
    private val minX = coords.minOfOrNull { it[0] } ?: 0
    private val maxX = coords.maxOfOrNull { it[0] } ?: 1
    private val minY = coords.minOfOrNull { it[1] } ?: 0
    private val maxY = coords.maxOfOrNull { it[1] } ?: 1
    private var phase = 1f
    private var motion: ValueAnimator? = null

    init {
        contentDescription = listOfNotNull(heading, flight?.let { values.last() + " " + unit }, caption).joinToString(". ")
        isFocusable = true
        setOnClickListener { replay(true) }
    }
    private fun replay(tactile: Boolean = false) {
        motion?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) { phase = 1f; invalidate(); return }
        motion = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ((flight?.durationMs ?: 550L) * 3).coerceIn(900, 2400)
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (tactile && flight != null && phase < 1f && it.animatedFraction == 1f) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                phase = it.animatedFraction; invalidate()
            }; start()
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); replay() }
    override fun onDetachedFromWindow() { motion?.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        val scale = minOf(width / 320f, height / 156f)
        canvas.translate((width - 320 * scale) / 2, (height - 156 * scale) / 2); canvas.scale(scale, scale)
        pen.color = chalk; pen.typeface = label; pen.textSize = 13f
        canvas.drawText(heading, 146f, 28f, pen)
        val airborne = 4 * phase * (1 - phase)
        val pose = samples[(phase * (samples.size - 1)).roundToInt()]
        val fit = minOf(110f / (maxX - minX).coerceAtLeast(1), (if (flight == null) 86f else 116f) / (maxY - minY).coerceAtLeast(1))
        fun x(index: Int) = 65f + (pose[2 * index] - (minX + maxX) / 2f) * fit
        fun y(index: Int) = 133f - (maxY - pose[2 * index + 1]) * fit - if (flight == null) 26 * airborne else 0f
        pen.strokeWidth = 1f; pen.alpha = 90; canvas.drawLine(12f, 136f, 121f, 136f, pen)
        pen.color = chalk; pen.strokeWidth = 3.6f
        for (index in links.indices step 2) {
            val start = links[index]; val end = links[index + 1]
            if (pose[start*2] != Int.MIN_VALUE && pose[end*2] != Int.MIN_VALUE) canvas.drawLine(x(start), y(start), x(end), y(end), pen)
        }
        pen.color = signal
        for (index in joints.indices) if (pose[index*2] != Int.MIN_VALUE) canvas.drawCircle(x(index), y(index), if (index == 0) 6f else 2.4f, pen)
        if (flight != null) {
            val value = values[if (phase >= .5f) 100 else (100 * airborne).roundToInt()]
            pen.typeface = digits; pen.textScaleX = .82f; pen.textSize = 43f
            canvas.drawText(value, 146f, 76f, pen); val next = 151 + pen.measureText(value)
            pen.textScaleX = 1f; pen.textSize = 15f; canvas.drawText(unit, next, 75f, pen)
        }
        val rise = if (flight == null) 45f else (20f + flight.heightCm.toFloat() * .6f).coerceIn(20f, 42f)
        val ground = if (flight == null) 101f else 127f
        pen.strokeWidth = 1f; pen.color = chalk; pen.alpha = 85; canvas.drawLine(146f, ground, 302f, ground, pen)
        pen.color = signal; pen.strokeWidth = 2.3f
        var prevX = 146f; var prevY = ground
        for (index in 1..32) {
            val fraction = minOf(phase, index/32f); val nextX = 146 + 156 * fraction; val nextY = ground - rise * 4 * fraction * (1 - fraction)
            canvas.drawLine(prevX, prevY, nextX, nextY, pen); prevX = nextX; prevY = nextY
            if (fraction >= phase) break
        }
        canvas.drawCircle(prevX, prevY, 3.4f, pen); pen.color = chalk; pen.textSize = 12f; pen.typeface = label
        canvas.drawText(caption, 146f, 151f, pen)
    }
}
