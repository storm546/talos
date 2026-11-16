package jumpscan.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

object JumpAnalyzer {
    private const val LANDMARKS = 33
    private const val MIN_VISIBILITY = 0.5f      // a frame is usable only if all leg landmarks are at least this visible
    private const val MIN_USABLE_FRAMES = 10
    private const val AIRBORNE_FRACTION = 0.025  // feet must be this fraction of leg length above ground; small, so flight is only slightly underestimated
    private const val MIN_AIRBORNE_FRAMES = 2    // a single airborne frame is jitter, not a jump
    private const val WINDOW_MS = 500L           // landing window after initial contact
    private const val MAX_FLIGHT_MS = 1000.0     // a longer airborne run is not a jump (122.6 cm, inside the server's 150 cm limit)
    private const val MAX_GAP_FACTOR = 1.5       // a time step above this many median frame intervals means dropped frames
    private const val PRE_RUN_MS = 500L          // hips of the frames this long before a run give the standing hip height
    private const val LOW_FPS = 24.0
    private const val LOW_QUALITY = 0.7
    private const val G = 9.81
    private val LEG_LANDMARKS = 23..32           // hips, knees, ankles, heels, foot index
    private val FOOT_LANDMARKS = 27..32
    private val LEFT = Leg(hip = 23, knee = 25, ankle = 27)
    private val RIGHT = Leg(hip = 24, knee = 26, ankle = 28)

    private class Leg(val hip: Int, val knee: Int, val ankle: Int)

    /**
     * Landmark positions of one frame. Image poses have x already multiplied by the aspect ratio and z = 0
     * (image plane only); world poses are metres with the real z (depth).
     */
    private class Pose(val x: DoubleArray, val y: DoubleArray, val z: DoubleArray)

    /**
     * [aspect] = image width / height (default 9/16, portrait video). Normalised x and y are not in the
     * same unit, so x is multiplied by [aspect] before any distance or angle is computed.
     * This is the only place the square-pixel assumption is handled.
     */
    fun analyze(frames: List<Frame>, test: String, aspect: Float = 9f / 16f): JumpMetrics {
        require(test == "drop_jump" || test == "cmj") { "test must be drop_jump or cmj" }
        require(aspect > 0f) { "aspect must be positive" }
        require(frames.all { it.landmarks.size == LANDMARKS && it.worldLandmarks.size == LANDMARKS }) {
            "each frame needs $LANDMARKS image and $LANDMARKS world landmarks"
        }
        require(frames.zipWithNext().all { (a, b) -> b.tMs > a.tMs }) { "frame times must increase" }

        val usable = frames.filter { f -> LEG_LANDMARKS.all { f.landmarks[it].visibility >= MIN_VISIBILITY } }
        require(usable.size >= MIN_USABLE_FRAMES) { "need at least $MIN_USABLE_FRAMES usable frames, got ${usable.size}" }
        val n = usable.size
        val t = usable.map { it.tMs }
        val poses = smooth(usable.map { f ->
            Pose(DoubleArray(LANDMARKS) { f.landmarks[it].x * aspect.toDouble() },
                DoubleArray(LANDMARKS) { f.landmarks[it].y.toDouble() },
                DoubleArray(LANDMARKS))
        })
        val world = smooth(usable.map { f ->
            Pose(DoubleArray(LANDMARKS) { f.worldLandmarks[it].x.toDouble() },
                DoubleArray(LANDMARKS) { f.worldLandmarks[it].y.toDouble() },
                DoubleArray(LANDMARKS) { f.worldLandmarks[it].z.toDouble() })
        })

        // Lowest foot point per frame (y points down, so largest y). Ground = 90th percentile of it:
        // feet are on the ground most of the time, and the percentile ignores box and jump frames.
        val footY = poses.map { p -> FOOT_LANDMARKS.maxOf { p.y[it] } }
        val ground = footY.sorted()[((n - 1) * 0.9).toInt()]
        // Body size: median hip-to-ankle distance, averaged over both legs.
        val legLength = median(poses.map { (dist(it, LEFT.hip, LEFT.ankle) + dist(it, RIGHT.hip, RIGHT.ankle)) / 2 })
        val airborne = footY.map { ground - it > AIRBORNE_FRACTION * legLength }

        // Frames dropped for low visibility leave time gaps; flight must never be measured across one.
        val medianStepMs = median(t.zipWithNext { a, b -> (b - a).toDouble() })
        val gapBefore = BooleanArray(n) { it > 0 && t[it] - t[it - 1] > MAX_GAP_FACTOR * medianStepMs }
        val hipY = poses.map { (it.y[LEFT.hip] + it.y[RIGHT.hip]) / 2 }

        // Candidate runs: (first airborne frame, first ground frame), consecutive airborne frames only.
        val candidates = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < n) {
            if (!airborne[i]) { i++; continue }
            var j = i + 1
            while (j < n && airborne[j] && !gapBefore[j]) j++
            if (j < n && j - i >= MIN_AIRBORNE_FRAMES) candidates += i to j
            i = j
        }

        fun isFlight(s: Int, e: Int): Boolean {
            if (gapBefore[s] || gapBefore[e]) return false                  // cut by a gap: start or landing not seen
            if (t[e] - t[s] > MAX_FLIGHT_MS) return false                    // too long to be a jump
            val before = (0 until s).filter { t[it] >= t[s] - PRE_RUN_MS }
            if (before.isEmpty()) return true                                // clip starts airborne (drop landing): nothing to compare
            // The body must rise: highest mid-hip in the run vs median mid-hip just before it (y points down).
            val rise = median(before.map { hipY[it] }) - (s until e).minOf { hipY[it] }
            return rise >= AIRBORNE_FRACTION * legLength
        }

        // Analysed landing: the last valid airborne-to-ground transition. No valid one (including rejected
        // candidates) means no_flight_detected; then analyse from the first frame instead of guessing.
        val landing = candidates.lastOrNull { (s, e) -> isFlight(s, e) }
        val contact = landing?.second ?: 0

        // Flight time: from first airborne frame to first ground frame of the landing run.
        val flightMs = landing?.let { (s, e) -> (t[e] - t[s]).toDouble() }
        // Height from flight time: h = g * t^2 / 8 (rise and fall take t/2 each). Not available when take-off
        // was not seen (run starts with the clip) or, for a drop jump, when no earlier landing exists to rebound from.
        val rebound = landing != null && landing.first > 0 && (test == "cmj" || candidates.indexOf(landing) > 0)
        val heightCm = if (flightMs != null && rebound) G * (flightMs / 1000).pow(2) / 8 * 100 else null

        val window = (contact until n).takeWhile { t[it] <= t[contact] + WINDOW_MS }
        // Knee flexion from the 3D world landmarks: the knee bends towards a frontal camera, so the image plane cannot see it.
        val flexL = window.map { deviation(world[it], LEFT) }
        val flexR = window.map { deviation(world[it], RIGHT) }
        val peakL = flexL.indices.maxBy { flexL[it] }
        val peakR = flexR.indices.maxBy { flexR[it] }
        val peakBoth = flexL.indices.maxBy { flexL[it] + flexR[it] }

        // Knee separation / ankle separation (horizontal) at the frame of greatest combined flexion.
        val p = poses[window[peakBoth]]
        val ankleSep = abs(p.x[LEFT.ankle] - p.x[RIGHT.ankle])
        val ratio = if (ankleSep < 1e-6) 5.0 else (abs(p.x[LEFT.knee] - p.x[RIGHT.knee]) / ankleSep).coerceIn(0.0, 5.0)

        val peakFlexL = flexL[peakL]
        val peakFlexR = flexR[peakR]
        // Asymmetry of peak flexion: |l - r| / max(l, r) * 100.
        val asymmetry = if (max(peakFlexL, peakFlexR) == 0.0) 0.0 else abs(peakFlexL - peakFlexR) / max(peakFlexL, peakFlexR) * 100

        // Quality: mean visibility of hips, knees, ankles over all input frames of the window, skipped ones included.
        val windowFrames = frames.filter { it.tMs in t[contact]..(t[contact] + WINDOW_MS) }
        val quality = windowFrames.flatMap { f -> (23..28).map { f.landmarks[it].visibility.toDouble() } }.average()

        val fps = (frames.size - 1) * 1000.0 / (frames.last().tMs - frames.first().tMs)
        val warnings = buildList {
            if (fps < LOW_FPS) add("low_fps")
            if (quality < LOW_QUALITY) add("low_visibility")
            if (landing == null) add("no_flight_detected")
        }

        return JumpMetrics(
            test = test,
            fps = fps,
            flightTimeMs = flightMs,
            jumpHeightCm = heightCm,
            kneeFlexionContactL = flexL[0],
            kneeFlexionContactR = flexR[0],
            kneeFlexionPeakL = peakFlexL,
            kneeFlexionPeakR = peakFlexR,
            fppaPeakL = fppa(poses[window[peakL]], LEFT),
            fppaPeakR = fppa(poses[window[peakR]], RIGHT),
            kneeAnkleRatio = ratio,
            flexionAsymmetryPct = asymmetry,
            quality = quality,
            warnings = warnings,
        )
    }

    /** Median of three neighbouring frames per coordinate: removes jitter spikes, keeps landing edges sharp. */
    private fun smooth(raw: List<Pose>): List<Pose> = raw.indices.map { i ->
        if (i == 0 || i == raw.lastIndex) raw[i]
        else Pose(DoubleArray(LANDMARKS) { median3(raw[i - 1].x[it], raw[i].x[it], raw[i + 1].x[it]) },
            DoubleArray(LANDMARKS) { median3(raw[i - 1].y[it], raw[i].y[it], raw[i + 1].y[it]) },
            DoubleArray(LANDMARKS) { median3(raw[i - 1].z[it], raw[i].z[it], raw[i + 1].z[it]) })
    }

    private fun median3(a: Double, b: Double, c: Double) = max(min(a, b), min(max(a, b), c))

    private fun median(values: List<Double>) = values.sorted()[values.size / 2]

    private fun dist(p: Pose, a: Int, b: Int) = hypot(p.x[a] - p.x[b], p.y[a] - p.y[b])

    /**
     * Angle in degrees between thigh (hip to knee) and shank (knee to ankle); 0 = straight leg.
     * On a world pose this is knee flexion (180 - 3D hip-knee-ankle angle); on an image pose (z = 0) it is the
     * image-plane angle that FPPA is built from.
     */
    private fun deviation(p: Pose, leg: Leg): Double {
        val tx = p.x[leg.knee] - p.x[leg.hip]
        val ty = p.y[leg.knee] - p.y[leg.hip]
        val tz = p.z[leg.knee] - p.z[leg.hip]
        val sx = p.x[leg.ankle] - p.x[leg.knee]
        val sy = p.y[leg.ankle] - p.y[leg.knee]
        val sz = p.z[leg.ankle] - p.z[leg.knee]
        val norms = sqrt(tx * tx + ty * ty + tz * tz) * sqrt(sx * sx + sy * sy + sz * sz)
        if (norms < 1e-9) return 0.0
        return Math.toDegrees(acos(((tx * sx + ty * sy + tz * sz) / norms).coerceIn(-1.0, 1.0)))
    }

    /**
     * Signed FPPA: [deviation] is positive when the knee lies on the body-midline side of the hip-ankle line.
     * The midline is the midpoint of the hips, so the sign is right for both legs whatever the left/right labelling.
     */
    private fun fppa(p: Pose, leg: Leg): Double {
        val hx = p.x[leg.hip]
        val dy = p.y[leg.ankle] - p.y[leg.hip]
        val lineX = if (abs(dy) < 1e-9) hx else hx + (p.x[leg.ankle] - hx) * (p.y[leg.knee] - p.y[leg.hip]) / dy
        val midX = (p.x[LEFT.hip] + p.x[RIGHT.hip]) / 2
        val angle = deviation(p, leg)
        val signed = if ((p.x[leg.knee] - lineX) * (midX - hx) >= 0) angle else -angle
        return signed.coerceIn(-90.0, 90.0)
    }
}
