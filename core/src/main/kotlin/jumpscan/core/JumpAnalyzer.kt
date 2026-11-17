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
    private const val MIN_VISIBILITY = 0.5f      // minimum visibility of each measured joint or observed foot point
    private const val MIN_USABLE_FRAMES = 10
    private const val AIRBORNE_FRACTION = 0.025  // minimum displacement as a fraction of the local leg length
    private const val MIN_AIRBORNE_FRAMES = 2    // a single airborne frame is jitter, not a jump
    private const val WINDOW_MS = 500L           // landing window after initial contact
    private const val MAX_FLIGHT_MS = 1000.0     // a longer airborne run is not a jump (122.6 cm, inside the server's 150 cm limit)
    private const val MAX_GAP_FACTOR = 1.5       // a time step above this many median frame intervals means dropped frames
    private const val MAX_TRACKING_GAP_MS = 150L // about four 25-fps intervals; longer gaps can hide a separate contact
    private const val LOW_FPS = 24.0
    private const val LOW_QUALITY = 0.7
    private const val G = 9.81
    private val LEFT = Leg(hip = 23, knee = 25, ankle = 27)
    private val RIGHT = Leg(hip = 24, knee = 26, ankle = 28)

    private class Leg(val hip: Int, val knee: Int, val ankle: Int)

    enum class View { AUTO, FRONT, SIDE }

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
    fun analyze(frames: List<Frame>, test: String, aspect: Float = 9f / 16f, view: View = View.AUTO,
        takeoffMs: Long? = null, landingMs: Long? = null): JumpMetrics =
        analyzeWithLanding(frames, test, aspect, view, takeoffMs, landingMs).metrics

    data class Analysis(val metrics: JumpMetrics, val contactMs: Long?, val flightCount: Int,
        val flightTimeMinMs: Long? = null, val flightTimeMaxMs: Long? = null, val takeoffMs: Long? = null)

    fun analyzeWithLanding(frames: List<Frame>, test: String, aspect: Float = 9f / 16f, view: View = View.AUTO,
        takeoffMs: Long? = null, landingMs: Long? = null): Analysis =
        analyzeFrames(frames, test, aspect, view, takeoffMs, landingMs, useLearned = true)

    /** Retained rules for same-input validation and the learned detector's cross-check. */
    internal fun analyzeRulesWithLanding(frames: List<Frame>, test: String, aspect: Float = 9f / 16f,
        view: View = View.AUTO): Analysis = analyzeFrames(frames, test, aspect, view, null, null, useLearned = false)

    private fun analyzeFrames(frames: List<Frame>, test: String, aspect: Float, view: View,
        takeoffMs: Long?, landingMs: Long?, useLearned: Boolean): Analysis {
        require(test == "drop_jump" || test == "cmj") { "test must be drop_jump or cmj" }
        require(aspect > 0f) { "aspect must be positive" }
        require(frames.all { it.landmarks.size == LANDMARKS && it.worldLandmarks.size == LANDMARKS }) {
            "each frame needs $LANDMARKS image and $LANDMARKS world landmarks"
        }
        require(frames.zipWithNext().all { (a, b) -> b.tMs > a.tMs }) { "frame times must increase" }

        require((takeoffMs == null) == (landingMs == null)) { "provide both flight edges or neither" }
        val manual = takeoffMs != null
        if (takeoffMs != null && landingMs != null) {
            require(frames.any { it.tMs == takeoffMs } && frames.any { it.tMs == landingMs }) {
                "flight edges must be frame times in the clip"
            }
            require(landingMs > takeoffMs && landingMs - takeoffMs in 1..MAX_FLIGHT_MS.toLong()) {
                "flight must be positive and at most 1000 ms"
            }
        }

        val sideView = view == View.SIDE || (view == View.AUTO && isSideView(frames, aspect))
        // Profile views overlap the legs; the leg with more complete joint-and-foot observations is measured.
        val legs = if (sideView) listOf(listOf(LEFT, RIGHT).maxBy { leg -> frames.count { f ->
            listOf(leg.hip, leg.knee, leg.ankle).all { f.landmarks[it].visibility >= MIN_VISIBILITY } &&
                listOf(leg.ankle + 2, leg.ankle + 4).any { f.landmarks[it].visibility >= MIN_VISIBILITY }
        } }) else listOf(LEFT, RIGHT)
        val joints = legs.flatMap { listOf(it.hip, it.knee) }
        val measuredLandmarks = legs.flatMap { listOf(it.hip, it.knee, it.ankle) }
        fun angleUsable(f: Frame) = measuredLandmarks.all { f.landmarks[it].visibility >= MIN_VISIBILITY }
        require(frames.count(::angleUsable) >= MIN_USABLE_FRAMES) { "need at least $MIN_USABLE_FRAMES angle frames" }
        // Contact needs observed foot points; hidden knees must not erase a visible contact.
        val usable = if (manual) frames.filter(::angleUsable) else frames.filter { f ->
            legs.all { leg -> listOf(leg.ankle + 2, leg.ankle + 4).any {
                f.landmarks[it].visibility >= MIN_VISIBILITY } }
        }
        require(usable.size >= MIN_USABLE_FRAMES) { "need at least $MIN_USABLE_FRAMES contact frames, got ${usable.size}" }
        val n = usable.size
        val t = usable.map { it.tMs }
        val rawPoses = usable.map { f ->
            Pose(DoubleArray(LANDMARKS) { f.landmarks[it].x * aspect.toDouble() },
                DoubleArray(LANDMARKS) { f.landmarks[it].y.toDouble() }, DoubleArray(LANDMARKS))
        }
        val poses = smooth(rawPoses)
        val rejected = BooleanArray(n)

        // Each foot has its own ground height: perspective can put planted feet at different image y values.
        fun foot(leg: Leg) = if (manual) emptyList() else poses.mapIndexed { index, p -> listOf(leg.ankle, leg.ankle + 2, leg.ankle + 4)
            .filter { usable[index].landmarks[it].visibility >= MIN_VISIBILITY }.maxOf { p.y[it] } }
        val footL = foot(legs.first())
        val footR = if (legs.size == 1) footL else foot(RIGHT)
        val hipY = poses.mapIndexed { i, p -> if (legs.all {
            usable[i].landmarks[it.hip].visibility >= MIN_VISIBILITY }) legs.map { p.y[it.hip] }.average() else Double.NaN }
        val legLength = poses.mapIndexed { i, p -> if (legs.all {
            usable[i].landmarks[it.hip].visibility >= MIN_VISIBILITY &&
                usable[i].landmarks[it.ankle].visibility >= MIN_VISIBILITY })
            legs.map { dist(p, it.hip, it.ankle) }.average() else Double.NaN }
        // A gap greater than 1.5 median frame intervals means contact or flight may have been missed.
        val medianStepMs = median(frames.zipWithNext { a, b -> (b.tMs - a.tMs).toDouble() })

        data class Standing(val end: Int, val left: Double, val right: Double, val hip: Double, val leg: Double, val segments: List<Double>)
        val standing = if (manual) emptyList() else (0 until n).mapNotNull { end ->
            val start = (0..end).first { t[it] >= t[end] - WINDOW_MS / 2 }
            // A stance spans half the landing window (250 ms), allowing one frame of endpoint quantisation.
            if (end - start < 2 || t[end] - t[start] < WINDOW_MS / 2 - medianStepMs) return@mapNotNull null
            val window = start..end
            if (window.any { !hipY[it].isFinite() || !legLength[it].isFinite() }) return@mapNotNull null
            val leg = median(window.map { legLength[it] })
            // Planted feet establish ground over 250 ms even while the hips move through a countermovement.
            if (listOf(footL, footR).any { signal ->
                    window.maxOf { signal[it] } - window.minOf { signal[it] } > AIRBORNE_FRACTION * leg
                }) return@mapNotNull null
            Standing(end, median(window.map { footL[it] }), median(window.map { footR[it] }),
                median(window.map { hipY[it] }), leg, joints.map { joint ->
                    median(window.filter { angleUsable(usable[it]) }.map { dist(rawPoses[it], joint, joint + 2) }
                        .ifEmpty { listOf(leg / 2) })
                })
        }

        val stableAt = BooleanArray(n)
        standing.forEach { stableAt[it.end] = true }
        data class Flight(val firstAir: Int, val contact: Int, val lastAir: Int, val lastGround: Int)
        val candidates = mutableListOf<Flight>()
        var i = 0
        while (!manual && i < n) {
            // Use the tallest stable stance within flight limit + landing window (1.5 s), excluding the approach.
            val recent = standing.filter { it.end < i && t[i] - t[it.end] <= MAX_FLIGHT_MS + WINDOW_MS }
            val latest = recent.lastOrNull()
            // Compare standing hips only at the latest floor level, so a drop box cannot become rebound standing height.
            val reference = recent.filter { latest != null &&
                abs(it.left - latest.left) <= AIRBORNE_FRACTION * latest.leg &&
                abs(it.right - latest.right) <= AIRBORNE_FRACTION * latest.leg
            }.minByOrNull { it.hip }
                // Only an explicitly requested drop landing may start airborne; CMJ needs observed ground before take-off.
                ?: if (test == "drop_jump" && i == 0 && t[0] == frames.first().tMs && angleUsable(usable[0])) Standing(0,
                    footL.sorted()[((n - 1) * 0.9).toInt()], footR.sorted()[((n - 1) * 0.9).toInt()],
                    hipY[0], median(legLength.filter { it.isFinite() }),
                    joints.map { joint -> median(rawPoses.indices.filter { angleUsable(usable[it]) }
                        .map { dist(rawPoses[it], joint, joint + 2) }) }) else null
            if (reference == null) { i++; continue }
            // Both feet must clear their own standing levels by 2.5% of the local hip-to-ankle length.
            fun airborne(index: Int) = reference.left - footL[index] > AIRBORNE_FRACTION * reference.leg &&
                reference.right - footR[index] > AIRBORNE_FRACTION * reference.leg
            if (!airborne(i) || (i > 0 && airborne(i - 1))) { i++; continue }
            fun plausible(index: Int): Boolean {
                if (!angleUsable(usable[index])) return true // Missing knees cannot disprove observed feet.
                val p = rawPoses[index]
                if (!upright(p, reference.leg, legs)) return false
                // Projection may shorten a bent limb; it cannot double its standing segment length at fixed distance.
                return joints.indices.all { dist(p, joints[it], joints[it] + 2) <= 2 * reference.segments[it] }
            }
            val observed = mutableListOf<Int>()
            var j = i
            var previous = max(0, i - 1)
            // Freeze the stance through flight. Reject impossible poses before they can supply ascent evidence.
            while (j < n) {
                if (airborne(j) && !plausible(j)) { rejected[j] = true; j++; continue }
                if (t[j] - t[previous] > MAX_TRACKING_GAP_MS || !airborne(j) ||
                    (i > 0 && stableAt[j])) break
                observed += j
                previous = j
                j++
            }
            if (j < n && !airborne(j) && observed.size >= MIN_AIRBORNE_FRAMES &&
                t[j] - t[previous] <= MAX_TRACKING_GAP_MS && t[j] - t[i] <= MAX_FLIGHT_MS) {
                val apexHip = observed.map { hipY[it] }.filter { it.isFinite() }.minOrNull()
                // Hips must rise above standing and the preceding ground frame, excluding settled sway.
                if (i == 0 || (apexHip != null && min(reference.hip, hipY[i - 1]) - apexHip >= AIRBORNE_FRACTION * reference.leg))
                    candidates += Flight(observed.first(), j, observed.last(), max(0, i - 1))
            }
            i = if (j < n && airborne(j) && stableAt[j]) j + 1 else max(i + 1, j)
        }

        // Retain the last rule flight for fallback; the learned proposal is evaluated below.
        val landing = candidates.lastOrNull()
        val rulesUncertain = landing?.let { flight ->
            t[flight.firstAir] - t[flight.lastGround] > MAX_GAP_FACTOR * medianStepMs ||
                t[flight.contact] - t[flight.lastAir] > MAX_GAP_FACTOR * medianStepMs
        } == true
        // With a missing take-off edge, use the last observed ground frame: this is an upper timing bound.
        fun ruleStart(flight: Flight) =
            t[if (t[flight.firstAir] - t[flight.lastGround] > MAX_GAP_FACTOR * medianStepMs)
                flight.lastGround else flight.firstAir]
        val learned = if (useLearned && !manual) LearnedFlight.predict(frames, aspect) else null
        val learnedFlight = learned?.edges
        val uncertainTiming = timingUncertain(learned, candidates.map {
            LearnedFlight.Edges(ruleStart(it), t[it.contact]) }, rulesUncertain)
        val flightStart = takeoffMs ?: learnedFlight?.takeoffMs ?: landing?.let(::ruleStart)
        val initialContact = landingMs ?: learnedFlight?.contactMs ?: landing?.let { t[it.contact] }
        val flightMs = flightStart?.let { (initialContact!! - it).toDouble() }
        // Observations bracket each edge; dropped frames and frame-rate quantisation do not imply exact contact.
        val flightMin = if (learnedFlight != null) frames.lastOrNull { it.tMs < initialContact!! }
            ?.let { maxOf(0L, it.tMs - flightStart!!) } else landing?.let { t[it.lastAir] - t[it.firstAir] }
        val flightMax = if (learnedFlight != null) initialContact!! -
            (frames.lastOrNull { it.tMs < flightStart!! }?.tMs ?: flightStart!!)
            else landing?.let { t[it.contact] - t[it.lastGround] }
        val rebound = if (learnedFlight != null) flightStart!! > frames.first().tMs else
            landing != null && landing.firstAir > 0 && (test == "cmj" || candidates.indexOf(landing) > 0)
        val heightCm = if (flightMs != null && (manual || rebound)) G * (flightMs / 1000).pow(2) / 8 * 100 else null

        // Angles need hips, knees and ankles, regardless of heel or toe visibility.
        val rejectedTimes = rejected.indices.filter { rejected[it] }.map { t[it] }.toSet()
        val metricFrames = frames.filter { angleUsable(it) && it.tMs !in rejectedTimes }
        require(metricFrames.size >= MIN_USABLE_FRAMES) { "need at least $MIN_USABLE_FRAMES angle frames" }
        val metricPoses = smooth(metricFrames.map { f ->
            Pose(DoubleArray(LANDMARKS) { f.landmarks[it].x * aspect.toDouble() },
                DoubleArray(LANDMARKS) { f.landmarks[it].y.toDouble() }, DoubleArray(LANDMARKS))
        })
        val world = smooth(metricFrames.map { f ->
            Pose(DoubleArray(LANDMARKS) { f.worldLandmarks[it].x.toDouble() },
                DoubleArray(LANDMARKS) { f.worldLandmarks[it].y.toDouble() },
                DoubleArray(LANDMARKS) { f.worldLandmarks[it].z.toDouble() })
        })
        val contactTime = initialContact ?: metricFrames.first().tMs
        val window = metricFrames.indices.filter { metricFrames[it].tMs in contactTime..(contactTime + WINDOW_MS) }
        require(window.isNotEmpty()) { "no usable angles in landing window" }
        // Profile flexion lies in the image plane; frontal flexion needs the estimated world depth.
        val kneePoses = if (sideView) metricPoses else world
        val flexL = window.map { deviation(kneePoses[it], legs.first()) }
        val flexR = if (legs.size == 1) flexL else window.map { deviation(kneePoses[it], RIGHT) }
        val peakL = flexL.indices.maxBy { flexL[it] }
        val peakR = flexR.indices.maxBy { flexR[it] }
        val peakBoth = flexL.indices.maxBy { flexL[it] + flexR[it] }

        // Knee separation / ankle separation (horizontal) at the frame of greatest combined flexion.
        val p = metricPoses[window[peakBoth]]
        val ankleSep = abs(p.x[LEFT.ankle] - p.x[RIGHT.ankle])
        val ratio = if (ankleSep < 1e-6) 5.0 else (abs(p.x[LEFT.knee] - p.x[RIGHT.knee]) / ankleSep).coerceIn(0.0, 5.0)

        val peakFlexL = flexL[peakL]
        val peakFlexR = flexR[peakR]
        // Asymmetry of peak flexion: |l - r| / max(l, r) * 100.
        val asymmetry = if (max(peakFlexL, peakFlexR) == 0.0) 0.0 else abs(peakFlexL - peakFlexR) / max(peakFlexL, peakFlexR) * 100

        // Quality: mean visibility of hips, knees, ankles over all input frames of the window, skipped ones included.
        val windowFrames = frames.filter { it.tMs in contactTime..(contactTime + WINDOW_MS) }
        val quality = windowFrames.flatMap { f -> measuredLandmarks.map { f.landmarks[it].visibility.toDouble() } }.average()

        val fps = (frames.size - 1) * 1000.0 / (frames.last().tMs - frames.first().tMs)
        val warnings = buildList {
            if (fps < LOW_FPS) add("low_fps")
            if (quality < LOW_QUALITY) add("low_visibility")
            if (!manual && initialContact == null) add("no_flight_detected")
            if (uncertainTiming) add("uncertain_timing")
            if (manual) add("manual_edges")
            if (legs.size == 1) add("single_leg_view")
        }

        val metrics = JumpMetrics(
            test = test,
            fps = fps,
            flightTimeMs = flightMs,
            jumpHeightCm = heightCm,
            kneeFlexionContactL = if (LEFT in legs) flexL[0] else null,
            kneeFlexionContactR = if (RIGHT in legs) flexR[0] else null,
            kneeFlexionPeakL = if (LEFT in legs) peakFlexL else null,
            kneeFlexionPeakR = if (RIGHT in legs) peakFlexR else null,
            fppaPeakL = if (sideView) null else fppa(metricPoses[window[peakL]], LEFT),
            fppaPeakR = if (sideView) null else fppa(metricPoses[window[peakR]], RIGHT),
            kneeAnkleRatio = if (sideView) null else ratio,
            flexionAsymmetryPct = if (legs.size == 1) null else asymmetry,
            quality = quality,
            warnings = warnings,
        )
        return Analysis(metrics, initialContact, if (learnedFlight != null) 1 else candidates.size,
            flightMin, flightMax, flightStart)
    }

    internal fun timingUncertain(learned: LearnedFlight.Prediction?, rules: List<LearnedFlight.Edges>,
        rulesUncertain: Boolean): Boolean {
        if (learned == null) return rulesUncertain
        val flight = learned.edges ?: return rules.isNotEmpty()
        return rules.none { abs(it.takeoffMs - flight.takeoffMs) <= 100 && abs(it.contactMs - flight.contactMs) <= 100 }
    }

    private fun isSideView(frames: List<Frame>, aspect: Float): Boolean {
        val widths = frames.mapNotNull { f ->
            val p = f.landmarks
            if (listOf(11, 12, 23, 24).any { p[it].visibility < MIN_VISIBILITY }) return@mapNotNull null
            fun distance(a: Int, b: Int) = hypot((p[a].x - p[b].x) * aspect.toDouble(), (p[a].y - p[b].y).toDouble())
            val torso = hypot((p[11].x + p[12].x - p[23].x - p[24].x) * aspect / 2.0,
                (p[11].y + p[12].y - p[23].y - p[24].y) / 2.0)
            if (torso <= 0) null else distance(11, 12) / torso to distance(23, 24) / torso
        }
        if (widths.size < MIN_USABLE_FRAMES) return false
        // Near profile foreshortens shoulders below half the torso and hips below roughly a third; require both.
        return median(widths.map { it.first }) < 0.5 && median(widths.map { it.second }) < 0.35
    }

    /** Median of three neighbouring frames per coordinate: removes jitter spikes, keeps landing edges sharp. */
    private fun smooth(raw: List<Pose>): List<Pose> = raw.indices.map { i ->
        if (i == 0 || i == raw.lastIndex) raw[i]
        else Pose(DoubleArray(LANDMARKS) { median3(raw[i - 1].x[it], raw[i].x[it], raw[i + 1].x[it]) },
            DoubleArray(LANDMARKS) { median3(raw[i - 1].y[it], raw[i].y[it], raw[i + 1].y[it]) },
            DoubleArray(LANDMARKS) { median3(raw[i - 1].z[it], raw[i].z[it], raw[i + 1].z[it]) })
    }

    // Allow 10% of leg length for projection/noise; hips further below knees are not an upright stance or jump.
    private fun upright(p: Pose, legLength: Double, legs: List<Leg>) = legs.all {
        p.y[it.hip] - p.y[it.knee] <= 0.1 * legLength
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
