package jumpscan.core

import kotlin.math.abs
import kotlin.math.hypot

sealed interface FormEvent {
    data class Start(val timeMs: Long) : FormEvent
    data class Extremum(val timeMs: Long, val top: Boolean) : FormEvent
    data class End(val repetition: FormRepetition) : FormEvent
    data class Cue(val key: String?) : FormEvent
}

data class FormProgress(val complete: Int = 0, val partial: Int = 0, val unclear: Int = 0,
    val holdMs: Long = 0, val inBandMs: Long = 0, val cue: String? = null)

class FormStream(exercise: String, private val side: String?, private val aspect: Float,
    private val view: JumpAnalyzer.View = JumpAnalyzer.View.AUTO) {
    private val entry = FormExercises[exercise].also { it.validateSide(side) }
    private var signal = if (view == JumpAnalyzer.View.FRONT) entry.signalFront ?: entry.signal else entry.signal
    private val legacyFrames = mutableListOf<Frame>()
    private val reps = mutableListOf<FormRepetition>()
    private val orientation = mutableListOf<Frame>()
    private val plankOrientation = mutableListOf<Frame>()
    private val usePlankView = entry.id == "push_up"
    private val direction get() = if (signal.down > signal.up) 1 else -1
    private val isHold = entry.mode == "hold"
    private var sideView = if (view == JumpAnalyzer.View.AUTO) null else view == JumpAnalyzer.View.SIDE
        set(value) { field = value; if (value == false) entry.signalFront?.let { signal = it } }
    private var selected = if (side == "R") 1 else 0
    private var first = -1L
    private var last = -1L
    private var count = 0
    private var usable = 0
    private var trackingLost = false
    private var quality = 0.0
    private var previous = -1L
    private var lastValid = -1L
    private var lastGate = false
    private var lastBand = false
    private var gapStart: Long? = null
    /** One repetition tracker per counted limb: a single one fed with the averaged signal, or one per side when the
     *  catalogue counts each limb (limbs = "each") and both are in view. */
    private inner class Limb {
        val sample = ArrayDeque<Double>(3)
        var rest: Long? = null
        var restValue = 0.0
        var cycle: Cycle? = null
        /** Adaptive counting only: highest value and first time since the last rest, and whether a rest was seen. */
        var high = 0.0
        var since = 0L
        var rested = false
        fun clear() { cycle = null; rest = null; sample.clear(); rested = false }
    }
    private val limbs = listOf(Limb(), Limb())
    private val cycle get() = limbs.firstNotNullOfOrNull { it.cycle }
    /** Completed or partial repetitions per limb, to merge one movement seen on both sides. */
    private val limbReps = mutableListOf<Pair<Int, LongRange>>()
    private val eachLimb get() = signal.eachLimb && side == null && (sideView == false || signal.adaptive)
    /** One side drives the count: a requested side, the profile side, or the better-seen side (limbs = "best"). */
    private val oneSide get() = side != null || ((sideView == true || signal.bestLimb) && !eachLimb)
    private var legacyTick = Long.MIN_VALUE / 2
    private var legacyReported = 0
    private var finished: FormMetrics? = null
    /** Per frame: time and the driving value of each side, kept only for the end-of-set recount. */
    private val trace = mutableListOf<Triple<Long, Double?, Double?>>()
    var progress = FormProgress()
        private set

    init { require(aspect.isFinite() && aspect > 0f) { "aspect must be finite and positive" } }

    companion object {
        /** Longest unobserved stretch between usable frames that a repetition or hold may bridge. */
        const val MAX_GAP_MS = 300L
        /** Adaptive counting, as fractions of min_range or of the repetition's own excursion (tuned on training sets). */
        private const val ADAPTIVE_START = 0.5
        private const val ADAPTIVE_RETURN = 0.6
        private const val ADAPTIVE_LEAVE = 0.2
        fun cameraView(frames: List<Frame>, aspect: Float): JumpAnalyzer.View = cameraView(frames, aspect, .5)

        private fun cameraView(frames: List<Frame>, aspect: Float, shoulderCutoff: Double): JumpAnalyzer.View {
            val ratios = frames.mapNotNull { frame ->
                val points = frame.landmarks
                val (leftShoulder, rightShoulder, leftHip, rightHip) = listOf(11, 12, 23, 24).map(points::get)
                if (listOf(leftShoulder, rightShoulder, leftHip, rightHip).any {
                    !it.x.isFinite() || !it.y.isFinite() || !it.visibility.isFinite() || it.visibility < .2f
                }) return@mapNotNull null
                val torso = hypot((leftShoulder.x + rightShoulder.x - leftHip.x - rightHip.x) * aspect / 2,
                    (leftShoulder.y + rightShoulder.y - leftHip.y - rightHip.y) / 2)
                if (torso <= 1e-6) return@mapNotNull null
                fun width(first: Landmark, second: Landmark) =
                    (hypot((first.x - second.x) * aspect, first.y - second.y) / torso).toDouble()
                width(leftShoulder, rightShoulder) to width(leftHip, rightHip)
            }
            if (ratios.size < 5) return JumpAnalyzer.View.AUTO
            return if (JumpAnalyzer.median(ratios.map { it.first }) < shoulderCutoff && JumpAnalyzer.median(ratios.map { it.second }) < .35)
                JumpAnalyzer.View.SIDE else JumpAnalyzer.View.FRONT
        }

        private fun plankPosture(frame: Frame): Boolean {
            val world = frame.worldLandmarks
            val torso = listOf(world[11], world[12], world[23], world[24])
            if (torso.any { !it.visibility.isFinite() || it.visibility < .2f }) return false
            val horizontal = hypot((world[11].x + world[12].x - world[23].x - world[24].x).toDouble(),
                (world[11].z + world[12].z - world[23].z - world[24].z).toDouble())
            val vertical = abs(world[11].y + world[12].y - world[23].y - world[24].y)
            val image = frame.landmarks
            return horizontal.isFinite() && horizontal > 1.7 * vertical && (0..1).any { side ->
                image[15 + side].visibility >= .2f && image[15 + side].y > image[11 + side].y
            }
        }
    }

    private data class Cycle(val start: Long, val open: Boolean, val base: Double, var peak: Double,
        var extreme: Long, var checks: Map<String, Double?>, var turned: Boolean = false)

    fun feed(frame: Frame, report: Boolean = true): List<FormEvent> {
        check(finished == null) { "form stream already finished" }
        require(frame.landmarks.size == 33 && frame.worldLandmarks.size == 33) { "each frame needs 33 image and 33 world landmarks" }
        require(frame.tMs >= 0 && frame.tMs > last) { "frame times must increase" }
        if (count == 0) first = frame.tMs
        last = frame.tMs
        count++
        if (signal.legacy) {
            legacyFrames += frame
            return if (report) legacyUpdate(frame) else emptyList()
        }
        val events = mutableListOf<FormEvent>()
        if (sideView == null) {
            orientation += frame
            if (usePlankView && plankPosture(frame)) plankOrientation += frame
            if (orientation.size < JumpAnalyzer.MIN_USABLE_FRAMES) return events
            val detected = if (usePlankView) {
                val plankView = cameraView(plankOrientation, aspect, .25)
                if (plankView == JumpAnalyzer.View.AUTO && last - first < 2000 && orientation.size < 60) return events
                plankView
            } else cameraView(orientation, aspect)
            resolveView(detected, events)
        } else advance(frame, events)
        return events
    }

    private fun resolveView(detected: JumpAnalyzer.View, events: MutableList<FormEvent>) {
        val resolved = if (detected == JumpAnalyzer.View.AUTO && usePlankView)
            cameraView(orientation.take(JumpAnalyzer.MIN_USABLE_FRAMES), aspect) else detected
        sideView = resolved == JumpAnalyzer.View.SIDE || (resolved == JumpAnalyzer.View.AUTO && entry.view == "side")
        orientation.forEach { advance(it, events) }
        orientation.clear()
        plankOrientation.clear()
    }

    private fun legacyUpdate(frame: Frame): List<FormEvent> = buildList {
        val profile = view == JumpAnalyzer.View.SIDE || (view == JumpAnalyzer.View.AUTO &&
            JumpAnalyzer.isSideView(legacyFrames.takeLast(12), aspect))
        val geometry = (if (side == null) listOf(0, 1) else listOf(selected)).map { FormGeometry(frame, aspect, it) }
        val bent = geometry.any { (it.measure(entry.checks.first().band.metric) ?: 0.0) >= checkNotNull(signal.openFlexion) }
        val inward = bent && !profile && geometry.any { measure -> entry.checks.last().band.let { band ->
            measure.measure(band.metric)?.let { !band.contains(it) } == true
        } }
        setCue(if (inward) "knee_alignment" else if (formInFrame(frame)) progress.cue.takeUnless { it in listOf("knee_alignment", "tracking_gap") }
            else "tracking_gap", this)
        if (count < JumpAnalyzer.MIN_USABLE_FRAMES || last - legacyTick < 400) return@buildList
        legacyTick = last
        val snapshot = try { FormAnalyzer.legacy(legacyFrames, entry, side, aspect, view) }
            catch (_: IllegalArgumentException) { return@buildList }
        val closed = snapshot.repetitions.filter { "incomplete_end" !in it.reasons }
        for (rep in closed.drop(legacyReported)) {
            add(FormEvent.Start(rep.startMs))
            rep.bottomMs?.let { add(FormEvent.Extremum(it, false)) }
            if (rep.status != "unclear") add(FormEvent.Extremum(rep.endMs, true))
            add(FormEvent.End(rep))
            if (rep.status == "partial") setCue("knee_flexion_peak", this)
            if ("too_fast" in rep.reasons) setCue("too_fast", this)
        }
        legacyReported = maxOf(legacyReported, closed.size)
        progress = progress.copy(complete = closed.count { it.status == "complete" }, partial = closed.count { it.status == "partial" },
            unclear = closed.count { it.status == "unclear" })
    }

    private fun advance(frame: Frame, events: MutableList<FormEvent>) {
        val time = frame.tMs
        if (lastValid >= 0 && time - lastValid > MAX_GAP_MS) lost(lastValid, events)
        val left = FormGeometry(frame, aspect, 0)
        val right = FormGeometry(frame, aspect, 1)
        val signalSides = if (signal.minVisibility == JumpAnalyzer.MIN_VISIBILITY) listOf(left, right)
            else listOf(FormGeometry(frame, aspect, 0, signal.minVisibility), FormGeometry(frame, aspect, 1, signal.minVisibility))
        fun confidence(geometry: FormGeometry) = geometry.required(signal.metric).minOf {
            it.visibility.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        }
        if (side == null && oneSide && cycle == null && !lastGate) {
            selected = if (confidence(left) >= confidence(right)) 0 else 1
        }
        val geometry = if (oneSide) listOf(if (selected == 0) left else right) else listOf(left, right)
        val signalGeometry = if (oneSide) listOf(signalSides[selected]) else signalSides
        fun measure(metric: String, requireAll: Boolean = true, from: List<FormGeometry> = geometry): Double? {
            if (metric == "fppa" && sideView == true) return null
            val values = from.mapNotNull { it.measure(metric) }
            if (values.isEmpty() || (requireAll && values.size < from.size)) return null
            return if (metric == "fppa") values.maxOrNull() else values.average()
        }
        if (signal.recount != null && !isHold) trace += Triple(time, signalSides[0].measure(signal.metric), signalSides[1].measure(signal.metric))
        quality += geometry.map(::confidence).average()
        val raws = if (eachLimb) signalGeometry.map { it.measure(signal.metric) }
            else listOf(measure(signal.metric, requireAll = !signal.metric.startsWith("rise:"), from = signalGeometry))
        val raw = raws.filterNotNull().takeIf { it.isNotEmpty() }?.average()
        // A dropped or low-visibility frame is skipped; only an unobserved stretch longer than MAX_GAP_MS
        // since the last usable frame interrupts the repetition or hold.
        if (raw == null) { previous = time; return }
        val checks = entry.checks.associate { it.key to measure(it.band.metric, requireAll = false) }
        fun offBand(values: Map<String, Double?>) = entry.checks.firstOrNull { check ->
            values[check.key]?.let { !check.band.contains(it) } == true
        }?.key
        val gates = signal.gates.map { measure(it.metric, from = signalGeometry) }
        val gated = signal.gates.zip(gates).all { (band, value) -> band.contains(value) }
        if (gates.any { it == null }) { previous = time; return }
        usable++
        val prior = lastValid
        lastValid = time
        closeGap(time, events)
        if (isHold) {
            val threshold = if (lastBand) signal.up else signal.down
            val inBand = gated && direction * (raw - threshold) >= 0 && entry.checks.all { it.band.contains(checks[it.key]) }
            if (gated && lastGate && prior >= 0) {
                val step = time - prior
                progress = progress.copy(holdMs = progress.holdMs + step,
                    inBandMs = progress.inBandMs + if (inBand && lastBand) step else 0)
            }
            lastGate = gated
            lastBand = inBand
            setCue(if (gated) offBand(checks) else "hold_position", events)
        } else if (!gated) {
            cycle?.let { unclear(it.start, previous.coerceAtLeast(it.start), "incomplete_end", events) }
            reset()
            setCue("ready_position", events)
        } else {
            raws.forEachIndexed { index, limbRaw -> if (limbRaw != null) {
                if (signal.adaptive) adaptiveStep(limbs[index], index, time, limbRaw, checks, events, ::offBand)
                else step(limbs[index], index, time, limbRaw, checks, events, ::offBand)
            } }
        }
        previous = time
    }

    private fun step(limb: Limb, index: Int, time: Long, raw: Double, checks: Map<String, Double?>,
        events: MutableList<FormEvent>, offBand: (Map<String, Double?>) -> String?) {
        run {
            if (limb.sample.size == 3) limb.sample.removeFirst()
            limb.sample.addLast(raw * direction)
            val value = limb.sample.sorted()[limb.sample.size / 2]
            if (value <= signal.up * direction) {
                limb.cycle?.let { active ->
                    val reason = when {
                        active.open -> "incomplete_start"
                        active.extreme - active.start < signal.minPhaseMs || time - active.extreme < signal.minPhaseMs -> "too_fast"
                        else -> null
                    }
                    if (reason != null) { if (counted(index, active.start, time)) unclear(active.start, time, reason, events) }
                    else {
                        val full = active.peak >= signal.down * direction && active.peak - active.base >= signal.minRange
                        val reasons = if (full) emptyList() else listOf("below_min_depth")
                        if (!active.turned) events += FormEvent.Extremum(active.extreme, signal.peakAtTop)
                        if (counted(index, active.start, time)) record(FormRepetition(reps.size + 1, if (full) "complete" else "partial", reasons, active.start,
                            active.extreme, time, null, null, null, null, null, null, active.extreme - active.start,
                            time - active.extreme, null, active.checks), events)
                        setCue(if (full) offBand(active.checks) else "below_min_depth", events)
                    }
                }
                limb.cycle = null
                limb.rest = time
                limb.restValue = value
                if (progress.cue in listOf("tracking_gap", "ready_position")) setCue(null, events)
            } else {
                val rise = value - signal.up * direction
                val epsilon = abs(signal.up - signal.down) * 0.22
                if (limb.cycle == null && rise >= epsilon) {
                    val start = limb.rest
                    limb.cycle = Cycle(start ?: time, start == null, if (start == null) signal.up * direction else limb.restValue,
                        value, time, checks)
                    events += FormEvent.Start(start ?: time)
                    setCue(null, events)
                }
                limb.cycle?.let { active ->
                    if (value > active.peak) {
                        active.peak = value; active.extreme = time; active.checks = checks
                    } else if (!active.turned && active.peak - value >= epsilon) {
                        active.turned = true
                        events += FormEvent.Extremum(active.extreme, signal.peakAtTop)
                    }
                    if (value >= signal.down * direction) setCue(offBand(checks), events)
                }
            }
        }
    }

    /** A repetition is a rise of at least half of min_range above the lowest value since the last rest, ended by a
     *  return of [ADAPTIVE_RETURN] of its own excursion; it is complete when the excursion reaches min_range. The rest
     *  level is the athlete's own, so a set that never reaches the catalogue's absolute up/down levels still counts.
     *  A stream whose first movement is a return began mid-repetition and reports incomplete_start. */
    private fun adaptiveStep(limb: Limb, index: Int, time: Long, raw: Double, checks: Map<String, Double?>,
        events: MutableList<FormEvent>, offBand: (Map<String, Double?>) -> String?) {
        if (limb.sample.size == 3) limb.sample.removeFirst()
        limb.sample.addLast(raw * direction)
        val value = limb.sample.sorted()[limb.sample.size / 2]
        val range = signal.minRange
        val active = limb.cycle
        if (active == null) {
            if (limb.rest == null) { limb.rest = time; limb.restValue = value; limb.high = value; limb.since = time }
            limb.high = maxOf(limb.high, value)
            limb.restValue = minOf(limb.restValue, value)
            if (value <= limb.restValue + ADAPTIVE_LEAVE * range) {
                limb.rest = time
                if (progress.cue in listOf("tracking_gap", "ready_position")) setCue(null, events)
            }
            if (!limb.rested && limb.high - value >= ADAPTIVE_RETURN * range) {
                limb.rested = true
                if (counted(index, limb.since, time)) unclear(limb.since, time, "incomplete_start", events)
            }
            if (value - limb.restValue >= ADAPTIVE_START * range) {
                val start = checkNotNull(limb.rest)
                limb.cycle = Cycle(start, false, limb.restValue, value, time, checks)
                events += FormEvent.Start(start)
                setCue(null, events)
            }
            return
        }
        if (value > active.peak) { active.peak = value; active.extreme = time; active.checks = checks }
        val excursion = active.peak - active.base
        if (!active.turned && active.peak - value >= 0.22 * range) {
            active.turned = true
            events += FormEvent.Extremum(active.extreme, signal.peakAtTop)
        }
        if (active.peak - value < ADAPTIVE_RETURN * excursion) return
        if (active.extreme - active.start < signal.minPhaseMs || time - active.extreme < signal.minPhaseMs) {
            if (counted(index, active.start, time)) unclear(active.start, time, "too_fast", events)
        } else {
            val full = excursion >= range
            if (!active.turned) events += FormEvent.Extremum(active.extreme, signal.peakAtTop)
            if (counted(index, active.start, time)) record(FormRepetition(reps.size + 1, if (full) "complete" else "partial",
                if (full) emptyList() else listOf("below_min_depth"), active.start, active.extreme, time, null, null, null,
                null, null, null, active.extreme - active.start, time - active.extreme, null, active.checks), events)
            setCue(if (full) offBand(active.checks) else "below_min_depth", events)
        }
        limb.cycle = null
        limb.rest = time; limb.restValue = value; limb.high = value; limb.since = time; limb.rested = true
    }

    /** True unless the other limb already recorded a repetition overlapping at least half of this one (both arms
     *  or legs moving together are one repetition; alternating limbs count separately). */
    private fun counted(index: Int, start: Long, end: Long): Boolean {
        if (!eachLimb) return true
        val span = (end - start).coerceAtLeast(1)
        val same = limbReps.any { (other, range) -> other != index &&
            minOf(end, range.last) - maxOf(start, range.first) >= minOf(span, range.last - range.first) / 2 }
        if (!same) limbReps += index to (start..end)
        return !same
    }

    private fun setCue(key: String?, events: MutableList<FormEvent>) {
        if (key != progress.cue) {
            progress = progress.copy(cue = key)
            events += FormEvent.Cue(key)
        }
    }

    private fun reset() { limbs.forEach { it.clear() }; lastGate = false; lastBand = false }

    private fun lost(time: Long, events: MutableList<FormEvent>) {
        trackingLost = true
        if (gapStart == null) gapStart = cycle?.start ?: time
        reset()
        setCue("tracking_gap", events)
    }

    private fun closeGap(time: Long, events: MutableList<FormEvent>) {
        gapStart?.let { if (!isHold) unclear(it, time, "tracking_gap", events) }
        gapStart = null
    }

    private fun unclear(start: Long, end: Long, reason: String, events: MutableList<FormEvent>) {
        record(FormRepetition(reps.size + 1, "unclear", listOf(reason), start, null, end,
            null, null, null, null, null, null, null, null, null), events)
        if (reason == "too_fast") setCue(reason, events)
    }

    private fun record(rep: FormRepetition, events: MutableList<FormEvent>) {
        reps += rep
        progress = when (rep.status) {
            "complete" -> progress.copy(complete = progress.complete + 1)
            "partial" -> progress.copy(partial = progress.partial + 1)
            else -> progress.copy(unclear = progress.unclear + 1)
        }
        events += FormEvent.End(rep)
    }

    fun finish(): FormMetrics = finished ?: finalizeMetrics().also { finished = it }

    private fun finalizeMetrics(): FormMetrics {
        if (signal.legacy) return FormAnalyzer.legacy(legacyFrames, entry, side, aspect, view).also {
            progress = progress.copy(complete = it.summary.repetitionCount, partial = it.summary.partialCount,
                unclear = it.summary.unclearCount)
        }
        val events = mutableListOf<FormEvent>()
        if (usePlankView && sideView == null) resolveView(JumpAnalyzer.View.AUTO, events)
        require(usable >= JumpAnalyzer.MIN_USABLE_FRAMES) { "need at least ${JumpAnalyzer.MIN_USABLE_FRAMES} visible form frames" }
        closeGap(last, events)
        limbs.forEachIndexed { index, limb -> limb.cycle?.let {
            if (counted(index, it.start, last)) unclear(it.start, last, if (it.open) "incomplete_start" else "incomplete_end", events) } }
        signal.recount?.let { recount(it) }?.let { rebuilt ->
            reps.clear(); reps += rebuilt
            progress = progress.copy(complete = rebuilt.count { it.status == "complete" },
                partial = rebuilt.count { it.status == "partial" },
                unclear = rebuilt.count { it.status == "unclear" })
        }
        val summary = FormSummary(progress.complete, progress.partial, progress.unclear, null, null, null, null, null,
            null, null, null, null, null, progress.holdMs.takeIf { isHold }, progress.inBandMs.takeIf { isHold })
        return FormMetrics(entry.id, side, if (sideView == true) "side" else "front", (count - 1) * 1000.0 / (last - first),
            quality / count, reps.toList(), summary, buildList {
                if (quality / count < 0.7 || trackingLost) add("low_visibility")
                if (progress.unclear > 0) add("unclear_reps")
            }, mode = entry.mode)
    }

    /** The whole set counted against its own range (see [FormRecount]); null keeps the live result: too few usable
     *  frames, a range under half of min_range, or no repetition found. Checks come from the overlapping live repetition. */
    private fun recount(r: FormRecount): List<FormRepetition>? {
        if (trace.size < 8) return null
        val t = trace.map { it.first.toDouble() }
        val sides = listOf(trace.map { it.second }, trace.map { it.third })
        fun percentile(values: List<Double>, p: Double): Double {
            val v = values.sorted(); val x = (v.size - 1) * p / 100.0; val i = x.toInt()
            return if (i + 1 < v.size) v[i] + (v[i + 1] - v[i]) * (x - i) else v[i]
        }
        // The requested side, else the side with the larger usable range (the left one on a tie).
        val ranges = sides.map { v -> v.filterNotNull().let { if (it.size > 0.3 * t.size) percentile(it, 95.0) - percentile(it, 5.0) else 0.0 } }
        val pick = if (side != null) selected else if (ranges[1] > ranges[0]) 1 else 0
        val raw = sides[pick]
        val known = raw.indices.filter { raw[it] != null }
        if (known.size < 0.4 * raw.size || known.size < 8) return null
        // Gaps filled linearly in time, constant beyond the first and last seen value.
        val y = DoubleArray(raw.size) { i ->
            raw[i] ?: run {
                val after = known.firstOrNull { it > i }; val before = known.lastOrNull { it < i }
                when {
                    before == null -> raw[after!!]!!
                    after == null -> raw[before]!!
                    else -> raw[before]!! + (raw[after]!! - raw[before]!!) * (t[i] - t[before]) / (t[after] - t[before])
                }
            }
        }
        val steps = t.zipWithNext { a, b -> b - a }.sorted()
        val dt = (if (steps.size % 2 == 1) steps[steps.size / 2] else (steps[steps.size / 2 - 1] + steps[steps.size / 2]) / 2)
            .takeIf { it > 0 } ?: 66.0
        val k = maxOf(1, Math.round(r.windowMs / dt).toInt())
        val smooth = if (k == 1) y else DoubleArray(y.size) { i ->
            (0 until k).sumOf { j -> y[(i - k / 2 + j).coerceIn(0, y.size - 1)] } / k
        }
        val z0 = smooth.map { it * direction }
        val lo = percentile(z0, 5.0); val hi = percentile(z0, 95.0)
        if (hi - lo < 0.5 * signal.minRange || hi - lo <= 0) return null
        val z = z0.map { (it - lo) / (hi - lo) }
        val out = mutableListOf<FormRepetition>()
        val times = trace.map { it.first }
        // Checks come from the live repetition that overlaps most; an unclear repetition carries no measurements.
        fun checks(start: Long, end: Long) = reps.filter { it.status != "unclear" }
            .maxByOrNull { minOf(it.endMs, end) - maxOf(it.startMs, start) }
            ?.takeIf { minOf(it.endMs, end) > maxOf(it.startMs, start) }?.checks
        fun add(status: String, reasons: List<String>, start: Long, bottom: Long, end: Long) {
            out += if (status == "unclear") FormRepetition(out.size + 1, status, reasons, start, null, end,
                null, null, null, null, null, null, null, null, null)
            else FormRepetition(out.size + 1, status, reasons, start, bottom, end, null, null, null, null, null, null,
                bottom - start, end - bottom, null, checks(start, end))
        }
        var state: Boolean? = null  // true = far from rest
        var startedFar = false; var leave = times[0]; var peak = times[0]; var peakZ = Double.NEGATIVE_INFINITY
        var restZ = Double.POSITIVE_INFINITY
        for (i in z.indices) {
            val v = z[i]
            if (state == null) {
                state = if (v < r.low) false else if (v > r.high) true else null
                startedFar = state == true; leave = times[i]; restZ = v; continue
            }
            if (state == true && v > peakZ) { peakZ = v; peak = times[i] }
            if (state == false && v < r.low) leave = times[i]
            if (state == false) restZ = minOf(restZ, v)
            if (state == false && v > r.high) { state = true; peakZ = v; peak = times[i] }
            else if (state == true && v < r.low) {
                if (startedFar && out.isEmpty()) add("unclear", listOf("incomplete_start"), times[0], peak, times[i])
                // Measured against the set's own range, so a shallow set still needs min_range of real movement.
                else if ((peakZ - restZ) * (hi - lo) >= signal.minRange) add("complete", emptyList(), leave, peak, times[i])
                else add("partial", listOf("below_min_depth"), leave, peak, times[i])
                state = false; leave = times[i]; peakZ = Double.NEGATIVE_INFINITY; restZ = v
            }
        }
        if (state == true && out.isNotEmpty()) add("unclear", listOf("incomplete_end"), leave, peak, times.last())
        return out.takeIf { it.isNotEmpty() }
    }
}
