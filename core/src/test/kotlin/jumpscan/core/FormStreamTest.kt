package jumpscan.core

import kotlin.math.cos
import kotlin.math.sin
import java.util.zip.GZIPInputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FormStreamTest {
    private val ranges = mapOf(
        "lunge" to (178.0 to 88.0), "split_squat" to (174.0 to 88.0), "lateral_lunge" to (178.0 to 92.0),
        "step_up" to (95.0 to 177.0), "glute_bridge" to (110.0 to 176.0), "romanian_deadlift" to (178.0 to 90.0),
        "calf_raise" to (0.0 to .22), "push_up" to (177.0 to 75.0), "overhead_press" to (88.0 to 178.0),
        "pull_up" to (178.0 to 74.0), "dip" to (178.0 to 75.0), "bent_over_row" to (176.0 to 84.0),
        "biceps_curl" to (174.0 to 54.0), "lateral_raise" to (7.0 to 93.0), "jumping_jack" to (.30 to 1.70),
    )

    @Test
    fun eachCatalogueCycleReportsCompletePartialAndGapWithBatchParity() {
        val entries = FormExercises.all.filter { !it.signal.legacy && it.mode == "reps" }
        assertEquals(ranges.keys, entries.map { it.id }.toSet())
        assertEquals(FormExercises.all.size, FormExercises.all.map { it.id }.distinct().size)
        for (entry in entries) {
            val (rest, full) = ranges.getValue(entry.id)
            // Adaptive counting judges the excursion from the athlete's own rest: three quarters of min_range is partial.
            val partial = if (entry.signal.adaptive) rest + Math.signum(full - rest) * .75 * entry.signal.minRange
                else (entry.signal.up + entry.signal.down) / 2
            val frames = buildList {
                fun pose(value: Double, missing: Boolean = false) = add(geometry(entry.id, value, size * 50L, missing))
                fun cycle(peak: Double, gap: Boolean = false) {
                    repeat(12) { pose(rest) }
                    for (phase in 1..12) pose(rest + (peak - rest) * phase / 12)
                    repeat(4) { pose(peak, gap) }
                    for (phase in 11 downTo 0) pose(rest + (peak - rest) * phase / 12, gap && phase > 0)
                    repeat(10) { pose(rest) }
                }
                cycle(full); cycle(partial); cycle(full, gap = true)
            }
            val view = if (entry.view == "side") JumpAnalyzer.View.SIDE else JumpAnalyzer.View.FRONT
            val side = if (entry.sided) "L" else null
            val stream = FormStream(entry.id, side, 1f, view)
            val events = frames.flatMap { stream.feed(it) }
            assertEquals(1, stream.progress.complete, entry.id)
            assertEquals(1, stream.progress.partial, entry.id)
            assertEquals(1, stream.progress.unclear, entry.id)
            assertTrue(events.any { it is FormEvent.Start }, entry.id)
            assertTrue(events.any { it is FormEvent.Extremum }, entry.id)
            assertEquals(3, events.count { it is FormEvent.End }, entry.id)
            assertTrue(events.any { it is FormEvent.Cue && it.key == "below_min_depth" }, entry.id)
            val metrics = stream.finish()
            assertEquals(entry.checks.map { it.key }.toSet(), metrics.repetitions.first().checks!!.keys, entry.id)
            assertNull(metrics.repetitions.last().checks, entry.id)
            assertEquals(metrics, FormAnalyzer.analyze(frames, entry.id, side, 1f, view), entry.id)
        }
    }

    @Test
    fun eachHoldMeasuresOnlyObservedIntervalsAndSeparatesInBandTime() {
        for (entry in FormExercises.all.filter { it.mode == "hold" }) {
            val side = if (entry.sided) "R" else null
            val view = if (entry.view == "side") JumpAnalyzer.View.SIDE else JumpAnalyzer.View.FRONT
            val stream = FormStream(entry.id, side, 1f, view)
            val frames = (0..50).map { index ->
                geometry(entry.id, if (index <= 20) 0.0 else 1.0, index * 50L + if (index >= 31) 500 else 0)
            }
            for (frame in frames) stream.feed(frame)
            val result = stream.finish()
            assertEquals(2450L, result.summary.holdMs, entry.id)
            assertEquals(1000L, result.summary.inBandMs, entry.id)
            assertTrue(result.repetitions.isEmpty(), entry.id)
            assertEquals(result, FormAnalyzer.analyze(frames, entry.id, side, 1f, view), entry.id)
        }
    }

    @Test
    fun pushUpUsesPlankViewAndCountsWithOneVisibleWrist() {
        val frames = buildList {
            repeat(10) {
                val frame = geometry("biceps_curl", 174.0, size * 50L)
                val image = frame.landmarks.toMutableList()
                image[12] = image[11].copy(x = image[11].x + .03f)
                image[24] = image[23].copy(x = image[23].x + .03f)
                add(frame.copy(landmarks = image))
            }
            fun plank(noseY: Float) {
                val frame = geometry("push_up", 177.0, size * 50L)
                val image = frame.landmarks.toMutableList()
                image[0] = image[0].copy(y = noseY)
                image[12] = image[11].copy(x = image[11].x + .08f)
                image[24] = image[23].copy(x = image[23].x + .04f)
                image[15] = image[15].copy(y = .62f)
                image[16] = image[16].copy(visibility = 0f)
                add(frame.copy(landmarks = image))
            }
            repeat(10) { plank(.45f) }
            for (step in 1..8) plank(.45f + .015f * step)
            repeat(4) { plank(.57f) }
            for (step in 7 downTo 0) plank(.45f + .015f * step)
            repeat(8) { plank(.45f) }
        }
        assertEquals(JumpAnalyzer.View.SIDE, FormStream.cameraView(frames.take(10), 1f))
        val stream = FormStream("push_up", null, 1f)
        frames.forEach { stream.feed(it) }
        assertEquals(1, stream.progress.complete)
        val result = stream.finish()
        assertEquals("front", result.view)
        assertEquals(result, FormAnalyzer.analyze(frames, "push_up", null, 1f))
        assertEquals(1, FormAnalyzer.analyze(frames.drop(10), "push_up", null, 1f, JumpAnalyzer.View.FRONT).summary.repetitionCount)
    }

    @Test
    fun pushUpFinishesShortSetWithoutPlankViewEvidence() {
        val frames = (0..19).map { index ->
            val frame = geometry("push_up", 177.0, index * 50L)
            val upright = geometry("biceps_curl", 174.0, frame.tMs)
            frame.copy(worldLandmarks = upright.worldLandmarks)
        }
        val result = FormAnalyzer.analyze(frames, "push_up", null, 1f)
        assertEquals(0, result.summary.repetitionCount)
    }

    @Test
    fun eachLimbCountsAlternatingArmsSeparatelyAndBothArmsTogetherOnce() {
        val (rest, full) = ranges.getValue("biceps_curl")
        fun frame(left: Double, right: Double, time: Long): Frame {
            val l = geometry("biceps_curl", left, time)
            val r = geometry("biceps_curl", right, time)
            val points = l.landmarks.mapIndexed { index, point -> if (index >= 12 && index % 2 == 0) r.landmarks[index] else point }
            return Frame(time, points, points)
        }
        val frames = buildList {
            fun cycle(left: Boolean, right: Boolean) {
                val values = List(12) { rest } + List(12) { rest + (full - rest) * (it + 1) / 12 } + List(4) { full } +
                    List(12) { rest + (full - rest) * (11 - it) / 12 } + List(10) { rest }
                for (value in values) add(frame(if (left) value else rest, if (right) value else rest, size * 50L))
            }
            cycle(left = true, right = false); cycle(left = false, right = true); cycle(left = true, right = true)
        }
        val stream = FormStream("biceps_curl", null, 1f, JumpAnalyzer.View.FRONT)
        frames.forEach { stream.feed(it) }
        val result = stream.finish()
        assertEquals(3, result.summary.repetitionCount)
        assertEquals(0, result.summary.unclearCount)
    }

    @Test
    fun adaptiveCountingFollowsTheAthletesOwnRestAndRange() {
        // Lateral raise resting at 45 degrees (above the catalogue's 35-degree rest level) and lifting to 95: the
        // absolute levels never see a rest, the athlete's own rest does. Excursion 50 >= min_range 40.
        fun set(values: List<Double>, id: String = "lateral_raise") = values.mapIndexed { index, value -> geometry(id, value, index * 50L) }
        fun rep(rest: Double, peak: Double) = List(10) { rest } + List(12) { rest + (peak - rest) * (it + 1) / 12 } +
            List(4) { peak } + List(12) { rest + (peak - rest) * (11 - it) / 12 }
        val raised = FormAnalyzer.analyze(set(rep(45.0, 95.0) + rep(45.0, 95.0) + rep(45.0, 95.0) + List(10) { 45.0 }),
            "lateral_raise", null, 1f, JumpAnalyzer.View.FRONT)
        assertEquals(3, raised.summary.repetitionCount)
        assertEquals(0, raised.summary.unclearCount)
        // Excursion 30 (between half and all of min_range) is partial, not a repetition; 15 is no movement at all.
        val short = FormAnalyzer.analyze(set(rep(45.0, 75.0) + rep(45.0, 60.0) + List(10) { 45.0 }), "lateral_raise", null, 1f,
            JumpAnalyzer.View.FRONT)
        assertEquals(listOf("partial"), short.repetitions.map { it.status })
        // A set that begins at the top of a raise: its first movement is a return, reported as incomplete_start.
        val late = FormAnalyzer.analyze(set(List(6) { 95.0 } + List(12) { 95.0 - 50.0 * (it + 1) / 12 } +
            rep(45.0, 95.0) + List(10) { 45.0 }), "lateral_raise", null, 1f, JumpAnalyzer.View.FRONT)
        assertEquals(1, late.summary.repetitionCount)
        assertEquals(listOf("incomplete_start"), late.repetitions.first().reasons)
        // A jump that ends before the athlete is back counts nothing and is reported as incomplete_end.
        val cut = FormAnalyzer.analyze(set(rep(.30, 1.70).dropLast(6), "jumping_jack"), "jumping_jack", null, 1f, JumpAnalyzer.View.FRONT)
        assertEquals(0, cut.summary.repetitionCount)
        assertEquals(listOf("incomplete_end"), cut.repetitions.single().reasons)
    }

    @Test
    fun recountUsesTheSetsOwnRangeAndKeepsMinRangeForDepth() {
        // Lunges from 170 down to 105 degrees with a slow 6-degree wobble at the bottom: one movement each.
        fun set(rest: Double, bottom: Double) = (0 until 3).flatMap {
            List(10) { rest } + List(12) { rest + (bottom - rest) * (it + 1) / 12 } +
                List(8) { bottom + if (it % 4 < 2) 6.0 else 0.0 } + List(12) { bottom + (rest - bottom) * (it + 1) / 12 }
        } + List(10) { rest }
        fun run(values: List<Double>) = FormAnalyzer.analyze(values.mapIndexed { i, v -> geometry("lunge", v, i * 50L) },
            "lunge", "L", 1f, JumpAnalyzer.View.SIDE)
        val deep = run(set(170.0, 105.0))
        assertEquals(3, deep.summary.repetitionCount)
        deep.repetitions.forEach { rep ->
            assertEquals(rep.bottomMs!! - rep.startMs, rep.descentTimeMs)
            assertEquals(rep.endMs - rep.bottomMs!!, rep.ascentTimeMs)
        }
        // 22 degrees of movement is below min_range 35: partial, even though it fills the set's own range.
        val shallow = run(set(170.0, 148.0))
        assertEquals(0, shallow.summary.repetitionCount)
        assertEquals(3, shallow.summary.partialCount)
        assertEquals(listOf("below_min_depth"), shallow.repetitions.first().reasons)
    }

    @Test
    fun bestLimbRowFollowsTheVisibleArmAndAlternatingCurlsCountInProfile() {
        // Row seen from the front: the far (right) arm disappears behind the body while it rows. Averaging both arms
        // would lose every repetition; following the better-seen arm counts all three.
        val (rest, full) = ranges.getValue("bent_over_row")
        val values = (1..3).flatMap { List(10) { rest } + List(12) { i -> rest + (full - rest) * (i + 1) / 12 } + List(4) { full } +
            List(12) { i -> rest + (full - rest) * (11 - i) / 12 } } + List(10) { rest }
        val row = values.mapIndexed { index, value ->
            val frame = geometry("bent_over_row", value, index * 50L)
            val hidden = value < rest - 10
            val image = frame.landmarks.mapIndexed { joint, point ->
                if (hidden && joint in listOf(14, 16)) point.copy(visibility = 0f) else point }
            frame.copy(landmarks = image, worldLandmarks = image)
        }
        assertEquals(3, FormAnalyzer.analyze(row, "bent_over_row", null, 1f, JumpAnalyzer.View.FRONT).summary.repetitionCount)
        // Alternating curls seen in profile still count each arm (5 + 5), not only the near one.
        val (down, up) = ranges.getValue("biceps_curl")
        fun curl() = List(8) { down } + List(10) { down + (up - down) * (it + 1) / 10 } + List(3) { up } +
            List(10) { down + (up - down) * (9 - it) / 10 }
        val arms = (0 until 10).flatMap { rep -> curl().map { value -> if (rep % 2 == 0) value to down else down to value } } +
            List(8) { down to down }
        val curls = arms.mapIndexed { index, (left, right) ->
            val l = geometry("biceps_curl", left, index * 50L)
            val r = geometry("biceps_curl", right, index * 50L)
            val points = l.landmarks.mapIndexed { joint, point -> if (joint >= 12 && joint % 2 == 0) r.landmarks[joint] else point }
            Frame(index * 50L, points, points)
        }
        assertEquals(10, FormAnalyzer.analyze(curls, "biceps_curl", null, 1f, JumpAnalyzer.View.SIDE).summary.repetitionCount)
    }

    @Test
    fun experimentalExercisesExplainWhyInBothLanguages() {
        for (entry in FormExercises.all) {
            assertEquals(entry.experimental, !entry.reasonEn.isNullOrBlank(), entry.id)
            assertEquals(entry.experimental, !entry.reasonBg.isNullOrBlank(), entry.id)
        }
        assertEquals(FormExercises.all.filter { !it.experimental }, FormExercises.validated)
    }

    @Test
    fun liveCadenceWithDroppedAndLowVisibilityFramesKeepsSquatCount() {
        // Recorded at about 30 fps; live delivers frames irregularly (about 15 per s, some intervals doubled)
        // and loses a joint now and then. Neither may split a repetition into unclear halves.
        val (aspect, frames) = GZIPInputStream(checkNotNull(javaClass.getResourceAsStream("/squat_jumps_one_person.csv.gz")))
            .bufferedReader().use(::readLandmarkCsv)
        val live = frames.filterIndexed { index, _ -> index % 2 == 0 && index % 14 != 0 }.mapIndexed { index, frame ->
            if (index % 25 == 3) frame.copy(landmarks = frame.landmarks.map { it.copy(visibility = 0f) }) else frame }
        val recorded = FormAnalyzer.analyze(frames, "squat", null, aspect)
        val stream = FormStream("squat", null, aspect)
        live.forEach { stream.feed(it) }
        val result = stream.finish()
        assertEquals(recorded.summary.repetitionCount, result.summary.repetitionCount)
        assertTrue(result.repetitions.none { "tracking_gap" in it.reasons })
    }

    @Test
    fun legacyVideoAndLiveProduceIdenticalFinalJson() {
        val (aspect, frames) = GZIPInputStream(checkNotNull(javaClass.getResourceAsStream("/squat_jumps_one_person.csv.gz")))
            .bufferedReader().use(::readLandmarkCsv)
        for ((id, side) in listOf("squat" to null, "single_leg_squat" to "L", "single_leg_squat" to "R")) {
            val stream = FormStream(id, side, aspect)
            val events = frames.flatMap { stream.feed(it) }
            assertTrue(events.any { it is FormEvent.End && it.repetition.status == "complete" }, id)
            assertEquals(FormAnalyzer.analyze(frames, id, side, aspect).toJson(), stream.finish().toJson(), id)
        }
    }

    private fun geometry(id: String, value: Double, time: Long, missing: Boolean = false): Frame {
        val points = MutableList(33) { Landmark(.5f, .12f, 0f, if (missing) 0f else 1f) }
        for (side in 0..1) {
            val offset = side * .16
            fun place(joint: Int, x: Double, y: Double) {
                points[joint + side] = Landmark((x + offset).toFloat(), y.toFloat(), 0f, if (missing) 0f else 1f)
            }
            fun limb(joint: Int, x: Double, y: Double, angle: Double, length: Double = .14) {
                val radians = Math.toRadians(angle)
                place(joint, x + length * sin(radians), y - length * cos(radians))
            }
            place(11, .40, .32); place(13, .40, .46); place(15, .40, .60); place(23, .40, .58)
            place(25, .40, .73); place(27, .40, .88); place(29, .39, .90); place(31, .44, .90)
            when (id) {
                "lunge", "split_squat", "lateral_lunge", "step_up" -> limb(27, .40, .73, value)
                "glute_bridge", "romanian_deadlift" -> limb(25, .40, .58, value)
                "calf_raise" -> place(29, .39, .90 - .26 * value)
                "push_up" -> {
                    place(11, .24, .46); place(13, .27, .58); place(23, .49, .49); place(27, .76, .54)
                    limb(15, .27, .58, Math.toDegrees(kotlin.math.atan2(-.03, .12)) + value, .11)
                }
                "overhead_press" -> {
                    place(13, .51, .27)
                    limb(15, .51, .27, Math.toDegrees(kotlin.math.atan2(-.11, -.05)) + value)
                }
                "pull_up" -> { place(13, .40, .18); limb(15, .40, .18, value - 180, .125) }
                "dip", "biceps_curl" -> limb(15, .40, .46, value)
                "bent_over_row" -> {
                    place(11, .21, .51); place(13, .21, .65)
                    limb(15, .21, .65, value)
                }
                "lateral_raise" -> limb(13, .40, .32, 180 - value)
                "jumping_jack" -> place(27, .40 + (side * 2 - 1) * ((value * .26 - .16) / 2), .88)
                "wall_sit" -> {
                    place(23, .33, .58); place(25, .50, .58); place(27, .50, .80)
                    place(11, .33 - .22 * value, .34 + .16 * value)
                }
                "plank", "side_plank" -> {
                    place(11, .20, .45); place(23, .44, .46 + .13 * value); place(27, .72, .47); place(15, .25, .68)
                }
                else -> error(id)
            }
        }
        return Frame(time, points, points)
    }
}
