package jumpscan.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import jumpscan.core.Frame
import jumpscan.core.Landmark
import kotlin.math.acos
import kotlin.math.sqrt

internal const val LEFT_COLOR = 0xFF168BFF.toInt()
internal const val RIGHT_COLOR = 0xFFFFB13B.toInt()

private const val PREVIEW_VISIBILITY = 0.8f

private fun clearLeg(frame: Frame, side: Int) = listOf(23, 25, 27).all {
    frame.landmarks[it + side].visibility >= PREVIEW_VISIBILITY
}

internal fun unclearPreview(frame: Frame) = (23..32).any {
    frame.landmarks[it].visibility < PREVIEW_VISIBILITY
}

/** Missing joints stay missing. This bitmap exists only in memory and is never part of an upload. */
internal fun landingPreview(raw: Bitmap, frame: Frame): Bitmap {
    val bitmap = raw.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    val width = maxOf(2f, bitmap.width / 180f)
    // Contact can be blurred. Do not draw an uncertain leg as a floating chain of bones.
    fun visible(index: Int) = frame.landmarks[index].visibility >= PREVIEW_VISIBILITY &&
        (index in 23..24 || clearLeg(frame, (index - 23) % 2))
    fun x(index: Int) = frame.landmarks[index].x * bitmap.width
    fun y(index: Int) = frame.landmarks[index].y * bitmap.height
    for ((side, color) in listOf(0 to LEFT_COLOR, 1 to RIGHT_COLOR)) {
        val joints = listOf(23, 25, 27, 29, 31).map { it + side }
        val edges = listOf(23 to 25, 25 to 27, 27 to 29, 29 to 31, 27 to 31)
        for (outline in listOf(true, false)) {
            paint.color = if (outline) Color.BLACK else color
            paint.strokeWidth = width * if (outline) 2f else 1f
            for ((start, end) in edges) {
                val a = start + side; val b = end + side
                if (visible(a) && visible(b)) canvas.drawLine(x(a), y(a), x(b), y(b), paint)
            }
            for (joint in joints) if (visible(joint))
                canvas.drawCircle(x(joint), y(joint), width * if (outline) 2.2f else 1.5f, paint)
        }
    }
    return bitmap
}

internal fun peakFlexionFrame(frames: List<Frame>): Frame {
    fun visible(frame: Frame, side: Int) = listOf(23, 25, 27).all { frame.landmarks[it + side].visibility >= 0.5f }
    val usable = frames.filter { f -> (0..1).any { visible(f, it) } }
    fun flexion(points: List<Landmark>, side: Int): Double {
        val hip = points[23 + side]; val knee = points[25 + side]; val ankle = points[27 + side]
        val a = doubleArrayOf((hip.x - knee.x).toDouble(), (hip.y - knee.y).toDouble(), (hip.z - knee.z).toDouble())
        val b = doubleArrayOf((ankle.x - knee.x).toDouble(), (ankle.y - knee.y).toDouble(), (ankle.z - knee.z).toDouble())
        val norm = sqrt(a.sumOf { it * it } * b.sumOf { it * it })
        return if (norm < 1e-12) 0.0 else 180 - Math.toDegrees(acos((a.indices.sumOf { a[it] * b[it] } / norm).coerceIn(-1.0, 1.0)))
    }
    return usable.maxBy { frame -> (0..1).sumOf { if (visible(frame, it)) flexion(frame.worldLandmarks, it) else 0.0 } }
}
