package jumpscan.core

import jumpscan.core.JumpAnalyzer.MIN_VISIBILITY
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot

internal fun Landmark.formVisible() = visibility.isFinite() && visibility >= MIN_VISIBILITY && x.isFinite() && y.isFinite()

fun formInFrame(frame: Frame) = frame.landmarks.let { points ->
    points[0].formVisible() && (0.01f..0.99f).let { band ->
        fun inside(index: Int) = points[index].let { it.formVisible() && it.x in band && it.y in band }
        listOf(0, 11, 13, 15, 23, 25, 27).all(::inside) || listOf(0, 12, 14, 16, 24, 26, 28).all(::inside)
    }
}

private val leftJoints = mapOf("shoulder" to 11, "elbow" to 13, "wrist" to 15, "hip" to 23, "knee" to 25,
    "ankle" to 27, "heel" to 29, "toe" to 31)

internal class FormGeometry(private val frame: Frame, private val aspect: Float, private val side: Int,
    private val minVisibility: Float = MIN_VISIBILITY) {
    private fun Landmark.seen() = visibility.isFinite() && visibility >= minVisibility && x.isFinite() && y.isFinite()
    private fun point(name: String) = if (name == "nose") frame.landmarks[0] else frame.landmarks[leftJoints.getValue(name) + side]
    private fun horizontal(a: Landmark, b: Landmark) = (a.x - b.x) * aspect.toDouble()
    private fun vertical(a: Landmark, b: Landmark) = (a.y - b.y).toDouble()
    private fun distance(a: Landmark, b: Landmark) = hypot(horizontal(a, b), vertical(a, b))
    private fun ratio(value: Double): Double? {
        val shoulder = point("shoulder")
        val hip = point("hip")
        val torso = distance(shoulder, hip)
        return if (shoulder.seen() && hip.seen() && torso > 1e-6) value / torso else null
    }
    private fun angle(a: Landmark, b: Landmark, c: Landmark): Double? {
        val length = distance(a, b) * distance(c, b)
        if (length <= 1e-12) return null
        return acos(((horizontal(a, b) * horizontal(c, b) + vertical(a, b) * vertical(c, b)) / length)
            .coerceIn(-1.0, 1.0)) * 180 / PI
    }
    fun required(metric: String): List<Landmark> = when (metric) {
        "fppa" -> listOf(point("hip"), point("knee"), point("ankle"), frame.landmarks[23], frame.landmarks[24])
        "flexion:knee" -> listOf(point("hip"), point("knee"), point("ankle"))
        "legacy_hip_drop" -> listOf(point("hip"), point("knee"), point("ankle"))
        else -> metric.split(':').drop(1).map(::point) + when {
            metric.startsWith("span:") -> listOf(frame.landmarks[leftJoints.getValue(metric.substringAfter(':')) + 1],
                point("shoulder"), point("hip"))
            metric.startsWith("height:") -> listOf(point("shoulder"), point("hip"))
            metric.startsWith("rise:") -> listOf(frame.landmarks[11], frame.landmarks[12], point("hip"))
            else -> emptyList()
        }
    }
    fun measure(metric: String): Double? {
        if (!required(metric).all { it.seen() }) return null
        if (metric == "fppa") {
            if (angle(point("hip"), point("knee"), point("ankle")) == null) return null
            val pose = JumpAnalyzer.Pose(DoubleArray(33) { frame.landmarks[it].x * aspect.toDouble() },
                DoubleArray(33) { frame.landmarks[it].y.toDouble() }, DoubleArray(33))
            return JumpAnalyzer.fppa(pose, if (side == 0) JumpAnalyzer.LEFT else JumpAnalyzer.RIGHT)
        }
        if (metric == "flexion:knee") return angle(point("hip"), point("knee"), point("ankle"))?.let { 180 - it }
        val parts = metric.split(':')
        val first = point(parts[1])
        if (parts[0] == "span") return ratio(abs(horizontal(frame.landmarks[leftJoints.getValue(parts[1])],
            frame.landmarks[leftJoints.getValue(parts[1]) + 1])))
        val second = point(parts[2])
        return when (parts[0]) {
            "angle" -> angle(first, second, point(parts[3]))
            "height" -> ratio(vertical(first, second))
            // How far the second point is above the first, in units of the larger of shoulder width and torso length:
            // one of the two stays long whether the camera sees a horizontal body from the side or from the front.
            "rise" -> maxOf(distance(frame.landmarks[11], frame.landmarks[12]), distance(point("shoulder"), point("hip")))
                .takeIf { it > 1e-6 }?.let { vertical(first, second) / it }
            "tilt" -> if (distance(first, second) <= 1e-6) null else
                acos((abs(vertical(first, second)) / distance(first, second)).coerceIn(0.0, 1.0)) * 180 / PI
            else -> error("Unknown form metric $metric")
        }
    }
}
