package jumpscan.core

import jumpscan.core.JumpAnalyzer.LEFT
import jumpscan.core.JumpAnalyzer.RIGHT
import jumpscan.core.JumpAnalyzer.MIN_USABLE_FRAMES
import jumpscan.core.JumpAnalyzer.MIN_VISIBILITY
import jumpscan.core.JumpAnalyzer.Pose
import jumpscan.core.JumpAnalyzer.deviation
import jumpscan.core.JumpAnalyzer.dist
import jumpscan.core.JumpAnalyzer.fppa
import jumpscan.core.JumpAnalyzer.isSideView
import jumpscan.core.JumpAnalyzer.median
import jumpscan.core.JumpAnalyzer.smooth
import kotlin.math.abs
import kotlin.math.max

/** All sides are anatomical. Unavailable measurements are null, including every unclear candidate's metrics. */
data class FormRepetition(
    val index: Int,
    val status: String,
    val reasons: List<String>,
    val startMs: Long,
    val bottomMs: Long?,
    val endMs: Long,
    val depth: Double?,
    val kneeFlexionPeakL: Double?,
    val kneeFlexionPeakR: Double?,
    val fppaL: Double?,
    val fppaR: Double?,
    val flexionAsymmetryPct: Double?,
    val descentTimeMs: Long?,
    val ascentTimeMs: Long?,
    val hipBelowKnee: Boolean?,
    val checks: Map<String, Double?>? = null,
)

data class FormSummary(
    val repetitionCount: Int,
    val partialCount: Int,
    val unclearCount: Int,
    val medianDepth: Double?,
    val medianKneeFlexionPeakL: Double?,
    val medianKneeFlexionPeakR: Double?,
    val medianFppaL: Double?,
    val medianFppaR: Double?,
    val medianFlexionAsymmetryPct: Double?,
    val medianDescentTimeMs: Double?,
    val medianAscentTimeMs: Double?,
    val hipBelowKneeCount: Int?,
    val worstRepetition: Int?,
    val holdMs: Long? = null,
    val inBandMs: Long? = null,
)

data class FormMetrics(
    val exercise: String,
    val side: String?,
    val view: String,
    val fps: Double,
    val quality: Double,
    val repetitions: List<FormRepetition>,
    val summary: FormSummary,
    val warnings: List<String>,
    val mode: String? = null,
    val source: String? = null,
)

object FormAnalyzer {
    // Above standing-pose bias (about 10–17 degrees in our jump validation) and small knee bends.
    // This is a counting threshold, not a standard for adequate squat depth.
    const val MIN_DEPTH_DEGREES = 45.0

    fun analyze(frames: List<Frame>, exercise: String, side: String? = null,
        aspect: Float = 9f / 16f, view: JumpAnalyzer.View = JumpAnalyzer.View.AUTO): FormMetrics =
        FormStream(exercise, side, aspect, view).apply { frames.forEach { feed(it, report = false) } }.finish()

    /**
     * Fixed camera, complete body, upright start/end. AUTO reuses the jump view heuristic.
     * Side squats measure the best-observed leg; a single-leg squat always measures the requested leg.
     * Visibility or timestamp gaps split observations; they never supply an inferred down/up cycle.
     */
    internal fun legacy(frames: List<Frame>, exercise: FormExercise, side: String?,
        aspect: Float, view: JumpAnalyzer.View): FormMetrics {
        val minHipExcursion = exercise.signal.down
        val topTolerance = exercise.signal.up
        val depthMinimum = checkNotNull(exercise.signal.depthMin)
        val openFlexion = checkNotNull(exercise.signal.openFlexion)
        val sideView = view == JumpAnalyzer.View.SIDE || (view == JumpAnalyzer.View.AUTO && isSideView(frames, aspect))
        fun visible(f: Frame, ids: List<Int>) = ids.all { i ->
            val p = f.landmarks[i]
            p.visibility.isFinite() && p.visibility >= MIN_VISIBILITY && p.x.isFinite() && p.y.isFinite()
        }
        val legs = when {
            side != null -> listOf(if (side == "L") LEFT else RIGHT)
            sideView -> listOf(listOf(LEFT, RIGHT).maxBy { leg -> frames.count {
                visible(it, listOf(leg.hip, leg.knee, leg.ankle))
            } })
            else -> listOf(LEFT, RIGHT)
        }
        val joints = legs.flatMap { listOf(it.hip, it.knee, it.ankle) }
        // FPPA sign requires both hips to locate the midline, even for a single-leg squat.
        val required = (joints + if (sideView) emptyList() else listOf(23, 24)).distinct()
        fun usable(f: Frame): Boolean = visible(f, required) && (sideView || joints.all { i ->
            val p = f.worldLandmarks[i]
            p.visibility.isFinite() && p.visibility >= MIN_VISIBILITY &&
                p.x.isFinite() && p.y.isFinite() && p.z.isFinite()
        })
        require(frames.count(::usable) >= MIN_USABLE_FRAMES) { "need at least $MIN_USABLE_FRAMES visible form frames" }
        val step = median(frames.zipWithNext { a, b -> (b.tMs - a.tMs).toDouble() })
        val quality = frames.flatMap { f -> required.map { i ->
            val image = f.landmarks[i].visibility.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
            if (sideView || i !in joints) image.toDouble() else minOf(image,
                f.worldLandmarks[i].visibility.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f).toDouble()
        } }.average()
        val reps = mutableListOf<FormRepetition>()
        fun unclear(start: Long, end: Long, reasons: List<String>) {
            reps += FormRepetition(reps.size + 1, "unclear", reasons, start, null, end,
                null, null, null, null, null, null, null, null, null)
        }
        fun segment(fs: List<Frame>, leftGap: Boolean, rightGap: Boolean) {
            if (fs.isEmpty()) return
            fun pose(f: Frame, world: Boolean): Pose {
                val ps = if (world) f.worldLandmarks else f.landmarks
                return Pose(DoubleArray(33) { ps[it].x.toDouble() * if (world) 1.0 else aspect.toDouble() },
                    DoubleArray(33) { ps[it].y.toDouble() }, DoubleArray(33) { if (world) ps[it].z.toDouble() else 0.0 })
            }
            val images = smooth(fs.map { pose(it, false) })
            val angles = if (sideView) images else smooth(fs.map { pose(it, true) })
            // Zero-length segments do not define an angle. Do not report the jump helper's fallback 0 degrees.
            if (images.any { p -> legs.any { dist(p, it.hip, it.knee) < 1e-6 || dist(p, it.knee, it.ankle) < 1e-6 } } ||
                angles.any { p -> legs.any { leg ->
                    fun norm(a: Int, b: Int) = (p.x[a] - p.x[b]) * (p.x[a] - p.x[b]) +
                        (p.y[a] - p.y[b]) * (p.y[a] - p.y[b]) + (p.z[a] - p.z[b]) * (p.z[a] - p.z[b])
                    norm(leg.hip, leg.knee) < 1e-12 || norm(leg.knee, leg.ankle) < 1e-12
                } }) {
                unclear(fs.first().tMs, fs.last().tMs, listOf("degenerate_geometry")); return
            }
            val hip = images.map { p -> legs.map { p.y[it.hip] }.average() }
            val length = median(images.map { p -> legs.map { dist(p, it.hip, it.knee) + dist(p, it.knee, it.ankle) }.average() })
            val flex = angles.map { p -> legs.map { deviation(p, it) } }
            // Track each local top rather than requiring every repetition to reach the set's tallest pose.
            var top = hip.first()
            var start = 0
            var active = false
            var initialOpen = flex.first().average() > openFlexion
            var incompleteStart = false
            var bottom = 0
            var recovery = -1
            fun finish(end: Int, incompleteEnd: Boolean) {
                val range = start..end
                if (range.maxOf { hip[it] } - top < minHipExcursion * length &&
                    !incompleteStart && !incompleteEnd) return
                val reasons = buildList {
                    if (incompleteStart) add(if (leftGap) "tracking_gap" else "incomplete_start")
                    if (incompleteEnd) add(if (rightGap) "tracking_gap" else "incomplete_end")
                    if (!incompleteStart && !incompleteEnd &&
                        (fs[bottom].tMs - fs[start].tMs < exercise.signal.minPhaseMs || fs[end].tMs - fs[bottom].tMs < exercise.signal.minPhaseMs)) add("too_fast")
                }
                if (reasons.isNotEmpty()) { unclear(fs[start].tMs, fs[end].tMs, reasons); return }
                val peaks = legs.indices.map { l -> range.maxOf { flex[it][l] } }
                val depth = peaks.average()
                // Deepest = largest mean flexion; timing bottom = lowest smoothed hip. These can differ.
                val deepest = range.maxBy { flex[it].average() }
                val l = if (LEFT in legs) peaks[legs.indexOf(LEFT)] else null
                val r = if (RIGHT in legs) peaks[legs.indexOf(RIGHT)] else null
                val asymmetry = if (l == null || r == null) null else if (max(l, r) == 0.0) 0.0 else abs(l - r) / max(l, r) * 100
                val partial = depth < depthMinimum
                reps += FormRepetition(reps.size + 1, if (partial) "partial" else "complete",
                    if (partial) listOf("below_min_depth") else emptyList(), fs[start].tMs, fs[bottom].tMs, fs[end].tMs,
                    depth, l, r, if (sideView || l == null) null else fppa(images[deepest], LEFT),
                    if (sideView || r == null) null else fppa(images[deepest], RIGHT), asymmetry,
                    fs[bottom].tMs - fs[start].tMs, fs[end].tMs - fs[bottom].tMs,
                    if (sideView) range.any { i -> legs.any { images[i].y[it.hip] > images[i].y[it.knee] } } else null)
            }
            for (i in 1..fs.lastIndex) {
                if (!active) {
                    if (hip[i] <= top) top = hip[i]
                    if (hip[i] <= top + topTolerance * length) start = i
                    else if (hip[i] - top >= minHipExcursion * length) {
                        if (initialOpen && hip.first() - top >= minHipExcursion * length)
                            unclear(fs.first().tMs, fs[start].tMs, listOf(if (leftGap) "tracking_gap" else "incomplete_start"))
                        else incompleteStart = initialOpen
                        initialOpen = false
                        active = true; bottom = i; recovery = -1
                    }
                } else {
                    if (hip[i] > hip[bottom]) { bottom = i; recovery = -1 }
                    if (hip[bottom] - hip[i] >= minHipExcursion * length &&
                        (recovery < 0 || hip[i] <= hip[recovery])) recovery = i
                    if (hip[i] <= top + topTolerance * length) {
                        finish(i, false)
                        active = false; incompleteStart = false; start = i; top = hip[i]
                    } else if (recovery >= 0 && hip[i] - hip[recovery] >= minHipExcursion * length) {
                        // A new descent bounds the previous ascent even when the local top has drifted.
                        finish(recovery, false)
                        start = recovery; top = hip[start]; bottom = i; recovery = -1; incompleteStart = false
                    }
                }
            }
            if (active) finish(fs.lastIndex, true)
            else if (initialOpen) unclear(fs.first().tMs, fs.last().tMs,
                listOf(if (leftGap) "tracking_gap" else "incomplete_start"))
        }
        var runStart = -1
        var leftGap = false
        var hadGap = false
        for (i in frames.indices) {
            val gap = i > 0 && frames[i].tMs - frames[i - 1].tMs > minOf(150.0, step * 1.5)
            if (!usable(frames[i]) || gap) {
                if (runStart >= 0) segment(frames.subList(runStart, i), leftGap, true)
                runStart = -1; leftGap = true; hadGap = true
            }
            if (usable(frames[i]) && runStart < 0) runStart = i
        }
        if (runStart >= 0) segment(frames.subList(runStart, frames.size), leftGap, false)
        // An unobserved interval can hide entire repetitions even when both surrounding poses are upright.
        if (hadGap && reps.none { "tracking_gap" in it.reasons })
            unclear(frames.first().tMs, frames.last().tMs, listOf("tracking_gap"))
        val complete = reps.filter { it.status == "complete" }
        fun med(value: (FormRepetition) -> Double?): Double? {
            val xs = complete.mapNotNull(value).sorted()
            return if (xs.isEmpty()) null else (xs[(xs.size - 1) / 2] + xs[xs.size / 2]) / 2
        }
        // "Worst" means shallowest complete repetition, tie resolved by earliest index. No clinical score.
        val summary = FormSummary(complete.size, reps.count { it.status == "partial" }, reps.count { it.status == "unclear" },
            med { it.depth }, med { it.kneeFlexionPeakL }, med { it.kneeFlexionPeakR }, med { it.fppaL }, med { it.fppaR },
            med { it.flexionAsymmetryPct }, med { it.descentTimeMs?.toDouble() }, med { it.ascentTimeMs?.toDouble() },
            if (sideView) complete.count { it.hipBelowKnee == true } else null, complete.minByOrNull { it.depth!! }?.index)
        return FormMetrics(exercise.id, side, if (sideView) "side" else "front",
            (frames.size - 1) * 1000.0 / (frames.last().tMs - frames.first().tMs), quality, reps, summary,
            buildList {
                if (quality < 0.7 || frames.any { !usable(it) }) add("low_visibility")
                if (summary.unclearCount > 0) add("unclear_reps")
                if (legs.size == 1) add("single_leg_view")
            })
    }
}

/** Fixed snake_case JSON contract; two decimal places at most, like JumpMetrics. */
fun FormMetrics.toJson(): String {
    fun num(v: Number?): String = if (v == null) "null" else {
        require(v.toDouble().isFinite()) { "metric is not finite" }
        (Math.round(v.toDouble() * 100) / 100.0).toString()
    }
    fun str(v: String?) = if (v == null) "null" else "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    fun strings(v: List<String>) = v.joinToString(",", "[", "]") { str(it) }
    fun obj(vararg fields: Pair<String, String?>) = fields.filter { it.second != null }
        .joinToString(",", "{", "}") { (k, v) -> "${str(k)}:$v" }
    val s = summary
    return obj("exercise" to str(exercise), "side" to str(side), "view" to str(view), "fps" to num(fps), "quality" to num(quality),
        "repetitions" to repetitions.joinToString(",", "[", "]") { r -> obj(
            "index" to r.index.toString(), "status" to str(r.status), "reasons" to strings(r.reasons),
            "start_ms" to r.startMs.toString(), "bottom_ms" to (r.bottomMs?.toString() ?: "null"), "end_ms" to r.endMs.toString(),
            "depth" to num(r.depth), "knee_flexion_peak_l" to num(r.kneeFlexionPeakL), "knee_flexion_peak_r" to num(r.kneeFlexionPeakR),
            "fppa_l" to num(r.fppaL), "fppa_r" to num(r.fppaR), "flexion_asymmetry_pct" to num(r.flexionAsymmetryPct),
            "descent_time_ms" to (r.descentTimeMs?.toString() ?: "null"), "ascent_time_ms" to (r.ascentTimeMs?.toString() ?: "null"),
            "hip_below_knee" to (r.hipBelowKnee?.toString() ?: "null"),
            "checks" to r.checks?.let { obj(*it.map { (key, value) -> key to num(value) }.toTypedArray()) }) },
        "summary" to obj("repetition_count" to s.repetitionCount.toString(), "partial_count" to s.partialCount.toString(),
            "unclear_count" to s.unclearCount.toString(), "median_depth" to num(s.medianDepth),
            "median_knee_flexion_peak_l" to num(s.medianKneeFlexionPeakL), "median_knee_flexion_peak_r" to num(s.medianKneeFlexionPeakR),
            "median_fppa_l" to num(s.medianFppaL), "median_fppa_r" to num(s.medianFppaR),
            "median_flexion_asymmetry_pct" to num(s.medianFlexionAsymmetryPct),
            "median_descent_time_ms" to num(s.medianDescentTimeMs), "median_ascent_time_ms" to num(s.medianAscentTimeMs),
            "hip_below_knee_count" to (s.hipBelowKneeCount?.toString() ?: "null"),
            "worst_repetition" to (s.worstRepetition?.toString() ?: "null"),
            "hold_ms" to s.holdMs?.toString(), "in_band_ms" to s.inBandMs?.toString()), "warnings" to strings(warnings),
        "mode" to mode?.let(::str), "source" to source?.let(::str))
}
