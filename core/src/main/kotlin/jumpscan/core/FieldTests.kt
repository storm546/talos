package jumpscan.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure calculations of the wave-3 field tools (SPEC 9.3): range of motion, balance, line timing and GPS. */

private fun Landmark.seen() = visibility.isFinite() && visibility >= JumpAnalyzer.MIN_VISIBILITY && x.isFinite() && y.isFinite()

private fun fieldJson(fields: List<Pair<String, Any?>>): String = fields.joinToString(",", "{", "}") { (key, value) ->
    "\"$key\":" + when (value) {
        null -> "null"
        is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is Double -> { require(value.isFinite()); (Math.round(value * 100) / 100.0).toString() }
        is List<*> -> value.joinToString(",", "[", "]") { item ->
            if (item is String) "\"$item\"" else (Math.round((item as Double) * 100) / 100.0).toString() }
        else -> value.toString()
    }
}

object RangeOfMotion {
    /** Joint angles from three landmarks (left-side indices; the right side is +1). */
    enum class Joint(val key: String, val view: JumpAnalyzer.View, val points: IntArray, val flexion: Boolean, val validated: Boolean) {
        KNEE_FLEXION("knee_flexion", JumpAnalyzer.View.SIDE, intArrayOf(23, 25, 27), true, true),
        HIP_FLEXION("hip_flexion", JumpAnalyzer.View.SIDE, intArrayOf(11, 23, 25), true, false),
        SHOULDER_FLEXION("shoulder_flexion", JumpAnalyzer.View.SIDE, intArrayOf(23, 11, 13), false, false),
        SHOULDER_ABDUCTION("shoulder_abduction", JumpAnalyzer.View.FRONT, intArrayOf(23, 11, 13), false, false);

        companion object { fun of(key: String) = entries.first { it.key == key } }
    }

    /** Side-view knee image RMSE from core/VALIDATION.md (10.4 degrees pooled), rounded. */
    const val ERROR_DEG = 10.0
    const val STILL_MS = 1000L
    const val STILL_SPAN_DEG = 5.0
    private const val STILL_FRAMES = 5

    data class Result(val joint: Joint, val view: String, val leftDeg: Double?, val rightDeg: Double?,
                      val quality: Double, val warnings: List<String>) {
        fun toJson() = fieldJson(listOf("kind" to "rom", "joint" to joint.key, "view" to view, "left_deg" to leftDeg,
            "right_deg" to rightDeg, "error_deg" to ERROR_DEG, "quality" to quality, "warnings" to warnings))
    }

    /** The joint angle of one side (0 left, 1 right), or null when a landmark is not visible. */
    fun angle(frame: Frame, aspect: Float, joint: Joint, side: Int): Double? {
        val (a, b, c) = joint.points.map { frame.landmarks[it + side] }
        if (!a.seen() || !b.seen() || !c.seen()) return null
        val ax = (a.x - b.x) * aspect.toDouble(); val ay = (a.y - b.y).toDouble()
        val cx = (c.x - b.x) * aspect.toDouble(); val cy = (c.y - b.y).toDouble()
        val length = hypot(ax, ay) * hypot(cx, cy)
        if (length <= 1e-12) return null
        val inner = acos(((ax * cx + ay * cy) / length).coerceIn(-1.0, 1.0)) * 180 / PI
        return if (joint.flexion) 180 - inner else inner
    }

    /** Median of the still window with the largest median, or null when the angle was never held still. */
    fun held(samples: List<Pair<Long, Double>>): Double? {
        var best: Double? = null
        var start = 0
        for (end in samples.indices) {
            // Shrink from the left until the window is still; any still window ending here starts at or after `start`.
            while (start < end && samples.subList(start, end + 1).let { w -> w.maxOf { it.second } - w.minOf { it.second } } > STILL_SPAN_DEG) start++
            val window = samples.subList(start, end + 1)
            if (window.size >= STILL_FRAMES && window.last().first - window.first().first >= STILL_MS) {
                val median = JumpAnalyzer.median(window.map { it.second })
                if (best == null || median > best) best = median
            }
        }
        return best
    }

    /** True when the last [STILL_MS] of samples stayed inside the still band (live "held" cue). */
    fun stillNow(samples: List<Pair<Long, Double>>): Boolean {
        if (samples.isEmpty()) return false
        val tail = samples.takeLastWhile { samples.last().first - it.first <= STILL_MS }
        if (tail.size < STILL_FRAMES || tail.last().first - tail.first().first < STILL_MS * 9 / 10) return false
        return tail.maxOf { it.second } - tail.minOf { it.second } <= STILL_SPAN_DEG
    }

    /** In side view only the side with more visible joint observations is measured (left on a tie). */
    fun measuredSides(frames: List<Frame>, joint: Joint, view: JumpAnalyzer.View): List<Int> {
        if (view == JumpAnalyzer.View.FRONT) return listOf(0, 1)
        fun seen(side: Int) = frames.sumOf { frame -> joint.points.count { frame.landmarks[it + side].seen() } }
        return listOf(if (seen(1) > seen(0)) 1 else 0)
    }

    fun analyze(frames: List<Frame>, aspect: Float, joint: Joint): Result {
        val view = FormStream.cameraView(frames, aspect)
        if (view != joint.view) return Result(joint, view.name.lowercase(), null, null, 0.0, listOf("wrong_view"))
        val values = arrayOfNulls<Double>(2)
        val visibility = mutableListOf<Double>()
        for (side in measuredSides(frames, joint, view)) {
            val samples = frames.mapNotNull { frame -> angle(frame, aspect, joint, side)?.let { frame.tMs to it } }
            values[side] = held(samples)
            frames.forEach { frame -> joint.points.forEach { visibility += frame.landmarks[it + side].visibility.toDouble() } }
        }
        val quality = if (visibility.isEmpty()) 0.0 else visibility.average().coerceIn(0.0, 1.0)
        val warnings = listOfNotNull("low_visibility".takeIf { quality < 0.7 }, "not_validated".takeIf { !joint.validated })
        return Result(joint, view.name.lowercase(), values[0], values[1], quality, warnings)
    }

    /** Joins two side-view holds (one per side) into one left/right result; the later value wins per side. */
    fun merge(first: Result, second: Result): Result = second.copy(leftDeg = second.leftDeg ?: first.leftDeg,
        rightDeg = second.rightDeg ?: first.rightDeg, quality = minOf(first.quality, second.quality),
        warnings = (first.warnings + second.warnings).distinct().filter { it != "wrong_view" })

    /** True when the left/right difference is smaller than the measuring error and should not be read as a difference. */
    fun withinError(left: Double, right: Double) = abs(left - right) < ERROR_DEG
}

object Balance {
    const val LIFT = 0.08            // lifted ankle above the stance ankle, fraction of stance leg length
    const val TOUCH = 0.04           // below this the lifted foot is down
    const val LIFT_MS = 300L
    const val TOUCH_MS = 200L
    const val REFERENCE_MS = 500L
    const val SWAY_LIMIT = 0.25      // hip midpoint excursion that ends the stance, fraction of leg length
    const val LOST_MS = 300L
    const val LIMIT_MS = 60_000L
    const val STANCE_KNEE_MAX = 30.0 // the stance starts only on a nearly straight standing leg (a squat shortens the leg)
    const val NOISE_MS = 1000L       // two-footed still window that measures the pose jitter (noise floor)
    const val NOISE_RADIUS = 0.03    // the hips stay within this fraction of leg length of the window's first position

    /**
     * [swayVelocityPct] is the hip midpoint's path length per second over the stance, in % of leg length per second;
     * [noiseFloorPct] is the same speed over the first still two-footed second before the lift (pose jitter alone).
     * Both stay on the phone: the server's balance fields are only those of [toJson].
     */
    data class Result(val leg: String, val stanceMs: Long, val swayPct: Double?, val endedBy: String, val fps: Double, val quality: Double,
                      val swayVelocityPct: Double? = null, val noiseFloorPct: Double? = null) {
        fun toJson() = fieldJson(listOf("kind" to "balance", "leg" to leg, "stance_ms" to stanceMs, "sway_pct" to swayPct,
            "ended_by" to endedBy, "fps" to fps, "quality" to quality))

        /** Sway velocity above the measured noise floor (never below zero); null without both measurements. */
        val swayAboveNoisePct: Double? get() = if (swayVelocityPct == null || noiseFloorPct == null) null
            else (swayVelocityPct - noiseFloorPct).coerceAtLeast(0.0)
    }

    /** Path length per second of (t, x, y) points, in % of [leg]; null for fewer than two points or no elapsed time. */
    fun speedPct(path: List<Triple<Long, Double, Double>>, leg: Double): Double? {
        if (path.size < 2 || path.last().first <= path.first().first || leg <= 1e-9) return null
        val length = path.zipWithNext { a, b -> hypot(b.second - a.second, b.third - a.third) }.sum()
        return length / leg * 100 / ((path.last().first - path.first().first) / 1000.0)
    }

    /** Feed frames in time order; [result] is set once the stance ends. */
    class Tracker(private val aspect: Float) {
        private var liftSince: Long? = null
        private var liftedSide = -1   // 0 left ankle lifted, 1 right
        var startMs: Long? = null; private set
        private var lowSince: Long? = null
        private var lastSeen: Long? = null
        private val stance = mutableListOf<Triple<Long, Double, Double>>() // t, hip midpoint x, y
        private val legs = mutableListOf<Double>()
        private val visibility = mutableListOf<Double>()
        private val times = mutableListOf<Long>()
        var result: Result? = null; private set
        /** Latest lift of the raised foot as a fraction of leg length (for the live readout). */
        var lift = 0.0; private set
        private val still = mutableListOf<Triple<Long, Double, Double>>() // current two-footed still run before the lift
        private val stillLegs = mutableListOf<Double>()
        /** Hip speed over the first still two-footed second, % of leg length per second; null until measured. */
        var noiseFloorPct: Double? = null; private set

        val running get() = startMs != null && result == null

        fun feed(frame: Frame): Result? {
            if (result != null) return result
            times += frame.tMs
            val p = frame.landmarks
            val joints = listOf(23, 24, 27, 28).map(p::get)
            if (joints.any { !it.seen() }) {
                val seen = lastSeen
                if (startMs != null && seen != null && frame.tMs - seen > LOST_MS) end(seen, "lost_tracking")
                if (startMs == null) { liftSince = null; still.clear(); stillLegs.clear() }
                return result
            }
            lastSeen = frame.tMs
            fun leg(side: Int) = hypot((p[23 + side].x - p[27 + side].x) * aspect.toDouble(), (p[23 + side].y - p[27 + side].y).toDouble())
            if (startMs == null) {
                val raised = if (p[27].y < p[28].y) 0 else 1
                val length = leg(1 - raised)
                lift = if (length > 1e-6) abs(p[27].y - p[28].y) / length else 0.0
                if (noiseFloorPct == null) noise(frame.tMs, (p[23].x + p[24].x) * aspect / 2.0, (p[23].y + p[24].y) / 2.0, length)
                val knee = RangeOfMotion.angle(frame, aspect, RangeOfMotion.Joint.KNEE_FLEXION, 1 - raised)
                if (lift <= LIFT || knee == null || knee > STANCE_KNEE_MAX) { liftSince = null; return null }
                if (liftSince == null || raised != liftedSide) { liftSince = frame.tMs; liftedSide = raised }
                if (frame.tMs - liftSince!! < LIFT_MS) return null
                startMs = liftSince
            }
            val start = startMs!!
            val length = leg(1 - liftedSide)
            if (length <= 1e-6) return null
            lift = (p[27 + 1 - liftedSide].y - p[27 + liftedSide].y) / length
            val x = (p[23].x + p[24].x) * aspect / 2.0
            val y = (p[23].y + p[24].y) / 2.0
            stance += Triple(frame.tMs, x, y)
            legs += length
            visibility += joints.map { it.visibility.toDouble() }.average()
            if (frame.tMs - start >= LIMIT_MS) return end(start + LIMIT_MS, "time_limit")
            if (lift < TOUCH) {
                if (lowSince == null) lowSince = frame.tMs
                if (frame.tMs - lowSince!! >= TOUCH_MS) return end(lowSince!!, "touch_down")
            } else lowSince = null
            val reference = stance.filter { it.first - start <= REFERENCE_MS }
            if (frame.tMs - start > REFERENCE_MS) {
                val rx = reference.map { it.second }.average(); val ry = reference.map { it.third }.average()
                if (hypot(x - rx, y - ry) > SWAY_LIMIT * JumpAnalyzer.median(legs)) return end(frame.tMs, "sway")
            }
            return null
        }

        /** Feeds the first still two-footed window; any frame with a lifted foot or moved hips restarts it. */
        private fun noise(t: Long, x: Double, y: Double, length: Double) {
            val first = still.firstOrNull()
            if (lift >= TOUCH || length <= 1e-6 || (first != null && hypot(x - first.second, y - first.third) > NOISE_RADIUS * length)) {
                still.clear(); stillLegs.clear()
                if (lift >= TOUCH || length <= 1e-6) return
            }
            still += Triple(t, x, y); stillLegs += length
            if (still.size >= 5 && t - still.first().first >= NOISE_MS) noiseFloorPct = speedPct(still, JumpAnalyzer.median(stillLegs))
        }

        /** Ends a running stance at the last frame; null when no stance started. */
        fun stop(): Result? = result ?: if (startMs == null) null else end(stance.lastOrNull()?.first ?: startMs!!, "stopped")

        private fun end(at: Long, reason: String): Result {
            val start = startMs!!
            val path = stance.filter { it.first in start..at }
            val sway = if (path.size < 2) null else {
                val mx = path.map { it.second }.average(); val my = path.map { it.third }.average()
                sqrt(path.map { (it.second - mx) * (it.second - mx) + (it.third - my) * (it.third - my) }.average()) /
                    JumpAnalyzer.median(legs) * 100
            }
            val fps = if (times.size < 2 || times.last() == times.first()) 0.0 else (times.size - 1) * 1000.0 / (times.last() - times.first())
            return Result(if (liftedSide == 1) "L" else "R", (at - start).coerceIn(0, LIMIT_MS), sway?.coerceIn(0.0, 100.0),
                reason, fps, if (visibility.isEmpty()) 0.0 else visibility.average().coerceIn(0.0, 1.0),
                if (path.size < 2) null else speedPct(path, JumpAnalyzer.median(legs)), noiseFloorPct).also { result = it }
        }
    }
}

object LineCrossing {
    const val BAND = 0.08          // width of the timing band at the frame centre, fraction of frame width
    const val PIXEL_CHANGE = 24    // luminance levels (0-255)
    const val TRIGGER = 0.12
    const val QUIET = 0.04
    const val QUIET_MS = 300L
    const val MOTION = 0.03        // whole-frame activity that counts as a reaction
    const val REACTION_MAX_MS = 1500L

    /** Fraction of samples whose luminance changed by more than [PIXEL_CHANGE] (unsigned bytes). */
    fun activity(previous: ByteArray, current: ByteArray): Double {
        require(previous.size == current.size && current.isNotEmpty())
        var changed = 0
        for (i in current.indices) if (abs((current[i].toInt() and 0xff) - (previous[i].toInt() and 0xff)) > PIXEL_CHANGE) changed++
        return changed.toDouble() / current.size
    }

    /**
     * Fires once per passage: the first busy frame after a quiet stretch. [crossingMs] is then the sub-frame estimate:
     * activity is interpolated linearly between the previous frame and the busy frame to where it reaches [TRIGGER].
     */
    class Detector {
        private var quietSince: Long? = null
        private var previous: Pair<Long, Double>? = null
        var armed = false; private set
        /** Interpolated time of the last crossing (between the previous and the busy frame); NaN before the first. */
        var crossingMs = Double.NaN; private set

        fun feed(tMs: Long, activity: Double): Long? {
            val before = previous
            previous = tMs to activity
            if (activity < QUIET) {
                if (quietSince == null) quietSince = tMs
                if (tMs - quietSince!! >= QUIET_MS) armed = true
            } else quietSince = null
            if (armed && activity >= TRIGGER) {
                armed = false
                crossingMs = if (before == null) tMs.toDouble() else interpolate(before.first, before.second, tMs, activity, TRIGGER)
                return tMs
            }
            return null
        }
    }

    /** Time at which a quantity rising from [a0] at [t0] to [a1] at [t1] reaches [level], clamped to the interval. */
    fun interpolate(t0: Long, a0: Double, t1: Long, a1: Double, level: Double): Double =
        if (a1 <= a0) t1.toDouble() else t0 + (t1 - t0) * ((level - a0) / (a1 - a0)).coerceIn(0.0, 1.0)

    /**
     * When the first sample of the tone reached the output, in ns on the timestamp's clock plus [clockOffsetNs]:
     * an AudioTrack timestamp says frame [framePosition] was presented at [presentedNs]; the tone starts at frame
     * [toneFrame]. Frames before or after the timestamp move the time by 1 / [sampleRate] each.
     */
    fun tonePresentedNs(framePosition: Long, presentedNs: Long, toneFrame: Long, sampleRate: Int, clockOffsetNs: Long = 0): Long {
        require(sampleRate > 0)
        return presentedNs + Math.round((toneFrame - framePosition) * 1e9 / sampleRate) + clockOffsetNs
    }

    /** Timing resolution: the median frame interval in ms. */
    fun precisionMs(timestamps: List<Long>): Double =
        if (timestamps.size < 2) 0.0 else JumpAnalyzer.median(timestamps.zipWithNext { a, b -> (b - a).toDouble() })

    sealed class Reaction {
        object FalseStart : Reaction()
        object TooSlow : Reaction()
        data class Time(val ms: Long) : Reaction()
    }

    /** [samples] are (frame time, whole-frame activity) from the "ready" call onwards; [cueMs] on the same clock. */
    fun reaction(cueMs: Long, samples: List<Pair<Long, Double>>): Reaction {
        if (samples.any { it.first <= cueMs && it.second >= MOTION }) return Reaction.FalseStart
        val first = samples.firstOrNull { it.first > cueMs && it.second >= MOTION } ?: return Reaction.TooSlow
        return if (first.first - cueMs > REACTION_MAX_MS) Reaction.TooSlow else Reaction.Time(first.first - cueMs)
    }

    fun json(kind: String, timeMs: Long, precisionMs: Double, fps: Double, extra: List<Pair<String, Any?>> = emptyList()) =
        fieldJson(listOf("kind" to kind) + extra + listOf("time_ms" to timeMs, "precision_ms" to precisionMs, "fps" to fps))
}

object GpsTrack {
    const val MAX_ACCURACY_M = 25f
    const val MIN_STEP_M = 5.0
    const val EARTH_M = 6_371_000.0
    fun maxSpeed(activity: String) = if (activity == "cycle") 25.0 else 12.0

    data class Fix(val tMs: Long, val lat: Double, val lon: Double, val accuracyM: Float)
    data class Summary(val activity: String, val distanceM: Double, val durationS: Long, val paceSPerKm: Double?, val splitsS: List<Double>) {
        fun toJson() = fieldJson(listOf("kind" to "cardio", "activity" to activity, "distance_m" to distanceM,
            "duration_s" to durationS, "avg_pace_s_per_km" to paceSPerKm, "splits_s" to splitsS))
    }

    fun haversine(a: Fix, b: Fix): Double {
        fun rad(d: Double) = d * PI / 180
        val h = sin(rad(b.lat - a.lat) / 2).let { it * it } +
            cos(rad(a.lat)) * cos(rad(b.lat)) * sin(rad(b.lon - a.lon) / 2).let { it * it }
        return 2 * EARTH_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Accumulates accepted fixes from [startMs]; the route stays in [route] on the phone. */
    class Recorder(val activity: String, val startMs: Long) {
        val route = mutableListOf<Fix>()
        var distanceM = 0.0; private set
        private val splitTimes = mutableListOf<Double>() // ms since start at each whole kilometre

        fun add(fix: Fix): Boolean {
            if (!fix.accuracyM.isFinite() || fix.accuracyM > MAX_ACCURACY_M || fix.tMs < startMs) return false
            val last = route.lastOrNull()
            if (last == null) { route += fix; return true }
            if (fix.tMs <= last.tMs) return false
            val step = haversine(last, fix)
            if (step < MIN_STEP_M || step / ((fix.tMs - last.tMs) / 1000.0) > maxSpeed(activity)) return false
            val before = distanceM
            distanceM += step
            var km = splitTimes.size + 1
            while (km * 1000.0 <= distanceM) {
                splitTimes += (last.tMs - startMs) + (km * 1000.0 - before) / step * (fix.tMs - last.tMs)
                km++
            }
            route += fix
            return true
        }

        fun summary(endMs: Long): Summary {
            val duration = ((endMs - startMs) / 1000).coerceAtLeast(1)
            val splits = (listOf(0.0) + splitTimes).zipWithNext { a, b -> (b - a) / 1000 }
            return Summary(activity, distanceM, duration, if (distanceM < 10) null else duration / (distanceM / 1000), splits)
        }
    }
}
