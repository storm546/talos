package jumpscan.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import jumpscan.core.Frame
import kotlin.math.min

internal class LiveFormPreview(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private var bitmap: Bitmap? = null
    private var frame: Frame? = null
    private var mirror = false
    private val bone = context.getColor(R.color.signal)

    fun show(image: Bitmap, pose: Frame, flipped: Boolean) {
        bitmap = image
        frame = pose
        mirror = flipped
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(context.getColor(R.color.hero))
        val image = bitmap ?: return
        val zoom = min(width.toFloat() / image.width, height.toFloat() / image.height)
        val drawWidth = zoom * image.width
        val drawHeight = zoom * image.height
        rect.set((width - drawWidth) / 2, (height - drawHeight) / 2, (width + drawWidth) / 2, (height + drawHeight) / 2)
        canvas.save()
        canvas.clipRect(rect)
        if (mirror) canvas.scale(-1f, 1f, width * .5f, height * .5f)
        canvas.drawBitmap(image, null, rect, paint)
        frame?.landmarks?.let { points ->
            val density = resources.displayMetrics.density
            paint.strokeCap = Paint.Cap.ROUND
            fun x(index: Int) = rect.left + points[index].x * drawWidth
            fun y(index: Int) = rect.top + points[index].y * drawHeight
            // A dark under-stroke keeps the accent skeleton visible on light backgrounds.
            for ((width, color) in listOf(6f to Color.argb(150, 16, 31, 26), 3.5f to bone)) {
                paint.strokeWidth = width * density
                paint.color = color
                for ((from, to) in bones) {
                    if (points[from].visibility < .5f || points[to].visibility < .5f) continue
                    canvas.drawLine(x(from), y(from), x(to), y(to), paint)
                }
            }
            paint.color = Color.WHITE
            for (joint in bones.flatMap { listOf(it.first, it.second) }.distinct()) {
                if (points[joint].visibility >= .5f) canvas.drawCircle(x(joint), y(joint), 3.5f * density, paint)
            }
        }
        canvas.restore()
    }

    private companion object {
        val bones = listOf(11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16, 11 to 23, 12 to 24, 23 to 24,
            23 to 25, 25 to 27, 27 to 29, 29 to 31, 24 to 26, 26 to 28, 28 to 30, 30 to 32)
    }
}
