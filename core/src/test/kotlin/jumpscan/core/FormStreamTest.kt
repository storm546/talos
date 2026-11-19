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
            val partial = (entry.signal.up + entry.signal.down) / 2
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
