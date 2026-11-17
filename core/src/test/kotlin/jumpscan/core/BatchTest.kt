package jumpscan.core

import java.io.File
import java.util.zip.GZIPOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BatchTest {
    private val aspect = 9.0 / 16.0

    /** Straight-legged person facing the camera at 60 fps for 2 s; feet leave the ground 0.8 s in, for [flight] s (0 = none). */
    private fun csv(flight: Double): String {
        val rows = (0 until 120).map { k ->
            val t = k / 60.0
            val s = t - 0.8
            val lift = if (flight > 0 && s in 0.0..flight) 9.81 * s * (flight - s) / 2 / 1.8 else 0.0
            val image = Array(33) { doubleArrayOf(0.5, 0.5) }
            val world = Array(33) { doubleArrayOf(0.0, 0.0) }
            for ((hip, side) in listOf(23 to 1.0, 24 to -1.0)) {
                val x = 0.5 * aspect + side * 0.08
                val ankleY = 0.81 - lift
                image[hip] = doubleArrayOf(x / aspect, ankleY - 0.5)
                image[hip + 2] = doubleArrayOf(x / aspect, ankleY - 0.25)
                for (i in intArrayOf(4, 6, 8)) image[hip + i] = doubleArrayOf(x / aspect, ankleY + (i - 4) * 0.01)
                world[hip + 2] = doubleArrayOf(side * 0.09, 0.4)
                for (i in intArrayOf(4, 6, 8)) world[hip + i] = doubleArrayOf(side * 0.09, 0.8)
                world[hip] = doubleArrayOf(side * 0.09, 0.0)
            }
            val cols = listOf((k * 1000L / 60).toString(), aspect.toString()) +
                image.flatMap { listOf(it[0], it[1], 0.0, 1.0) }.map { it.toString() } +
                world.flatMap { listOf(it[0], it[1], 0.0, 1.0) }.map { it.toString() }
            cols.joinToString(",")
        }
        return "header\n" + rows.joinToString("\n") + "\n"
    }

    @Test
    fun scoresClipsAndSurvivesMalformedFile(@TempDir dir: File) {
        File(dir, "jump_in_place").mkdirs()
        File(dir, "still_pose").mkdirs()
        File(dir, "jump_in_place/a.csv").writeText(csv(0.5))
        GZIPOutputStream(File(dir, "still_pose/b.csv.gz").outputStream()).use { it.write(csv(0.0).toByteArray()) }
        File(dir, "still_pose/bad.csv").writeText("header\n1,2,3\n")
        File(dir, "still_pose/notes.txt").writeText("ignored")
        val out = File(dir, "out/result.csv")

        val summary = runBatch(dir, out)

        val lines = out.readLines()
        val header = lines.first().split(',')
        assertEquals(3, lines.size - 1)
        val rows = lines.drop(1).map { line -> header.zip(line.split(',')).toMap() }.associateBy { it["clip"] }
        val jump = rows.getValue("a.csv")
        assertEquals("jump_in_place", jump["class"])
        assertEquals("120", jump["frames"])
        assertEquals("1983", jump["duration_ms"])
        assertEquals("ok", jump["status"])
        assertEquals("true", jump["flight_detected"])
        val (jumpAspect, frames) = csv(0.5).reader().buffered().use(::readLandmarkCsv)
        val expected = JumpAnalyzer.analyzeWithLanding(frames, "cmj", jumpAspect)
        assertEquals(expected.metrics.flightTimeMs!!, jump["flight_time_ms"]!!.toDouble(), 0.01)
        assertEquals(expected.contactMs.toString(), jump.getValue("contact_ms"))
        val still = rows.getValue("b.csv.gz")
        assertEquals("still_pose", still["class"])
        assertEquals("ok", still["status"])
        assertEquals("false", still["flight_detected"])
        assertEquals("", still["flight_time_ms"])
        assertEquals("", still["contact_ms"])
        assertEquals("no_flight_detected", still["warnings"])
        val bad = rows.getValue("bad.csv")
        assertEquals("error", bad["status"])
        assertTrue(bad["error"]!!.isNotEmpty() && ',' !in bad["error"]!!)
        assertEquals("false", bad["flight_detected"])
        assertTrue(summary.contains("jump_in_place: clips=1 errors=0 detected=1"), summary)
        assertTrue(summary.contains("still_pose: clips=2 errors=1 detected=0"), summary)
    }
}
