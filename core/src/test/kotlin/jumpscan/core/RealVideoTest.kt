package jumpscan.core

import java.util.zip.GZIPInputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RealVideoTest {
    @Test
    fun plantedFeetInRealSquatsAreNotFlights() {
        // Ground truth from visual review of every ffmpeg frame, zero-based at 30000/1001 fps:
        // Frames | Times (ms)       | Last contact before take-off | First landing | Confidence
        // 0–299  | 0–9976.633       | none                         | none          | high: planted shoes
        // There are zero observable jumps; sub-frame flight cannot be excluded at 29.97 fps.
        // Thus there is no last landing or flight duration to compare within 2 frames / 70 ms.
        val rows = GZIPInputStream(javaClass.getResourceAsStream("/squat_jumps_one_person.csv.gz")!!)
            .bufferedReader().use { it.readLines().drop(1).map { line -> line.split(',') } }
        val aspect = rows.first()[1].toFloat()
        val frames = rows.map { row ->
            assertEquals(266, row.size)
            assertEquals(aspect, row[1].toFloat())
            fun landmarks(start: Int) = List(33) { index ->
                val offset = start + index * 4
                Landmark(row[offset].toFloat(), row[offset + 1].toFloat(),
                    row[offset + 2].toFloat(), row[offset + 3].toFloat())
            }
            Frame(row[0].toLong(), landmarks(2), landmarks(134))
        }
        assertEquals(300, frames.size)
        assertEquals(400f / 720f, aspect)
        val metrics = JumpAnalyzer.analyze(frames, "cmj", aspect)
        // No selected flight is equivalent to zero valid flights: analyze always selects the last one.
        assertEquals(listOf("no_flight_detected"), metrics.warnings)
        assertNull(metrics.flightTimeMs)
        assertNull(metrics.jumpHeightCm)
        fun inRange(value: Double, min: Double, max: Double) {
            assertTrue(value.isFinite() && value in min..max, "$value outside $min..$max")
        }
        inRange(metrics.fps, 10.0, 480.0)
        listOf(metrics.kneeFlexionContactL, metrics.kneeFlexionContactR,
            metrics.kneeFlexionPeakL, metrics.kneeFlexionPeakR).forEach { inRange(it, 0.0, 180.0) }
        listOf(metrics.fppaPeakL, metrics.fppaPeakR).forEach { inRange(it, -90.0, 90.0) }
        inRange(metrics.kneeAnkleRatio, 0.0, 5.0)
        inRange(metrics.flexionAsymmetryPct, 0.0, 100.0)
        inRange(metrics.quality, 0.0, 1.0)
    }
}
