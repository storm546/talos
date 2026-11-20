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

internal class RepTarget(context: Context) : android.widget.Button(context) {
    var fraction = 0f
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density; val center = height * .5f; val radius = 10*density
        val left = 14*density; pen.strokeWidth = 2.5f*density; pen.color = context.getColor(R.color.line)
        canvas.drawCircle(left+radius, center, radius, pen); pen.color = context.getColor(R.color.accent)
        canvas.drawArc(left, center-radius, left+2*radius, center+radius, -90f, 360*fraction, false, pen)
    }
}

internal class LiveFormPreview(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private var bitmap: Bitmap? = null
    private var frame: Frame? = null
    private var mirror = false
    private val bone = context.getColor(R.color.signal)
    var jointColor = Color.WHITE
    var scanFraction = -1f

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
        paint.color = Color.WHITE; canvas.drawBitmap(image, null, rect, paint)
        frame?.landmarks?.let { points ->
            val density = resources.displayMetrics.density
            paint.strokeCap = Paint.Cap.ROUND
            fun x(index: Int) = rect.left + points[index].x * drawWidth
            fun y(index: Int) = rect.top + points[index].y * drawHeight
            // A dark under-stroke keeps the accent skeleton visible on light backgrounds.
            for (pass in 0..1) {
                paint.strokeWidth = (if (pass == 0) 5f else 2f) * density
                paint.color = if (pass == 0) Color.argb(180, 20, 22, 25) else jointColor
                for ((from, to) in bones) {
                    if (points[from].visibility < .5f || points[to].visibility < .5f) continue
                    canvas.drawLine(x(from), y(from), x(to), y(to), paint)
                }
            }
            for (joint in joints) if (points[joint].visibility >= .5f) {
                paint.color = jointColor; paint.alpha = 72
                canvas.drawCircle(x(joint), y(joint), 5f * density, paint)
                paint.alpha = 255; canvas.drawCircle(x(joint), y(joint), 2.5f * density, paint)
            }
        }
        if (scanFraction >= 0 && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            val line = rect.top + drawHeight * scanFraction
            paint.color = bone; paint.strokeWidth = 2 * resources.displayMetrics.density
            canvas.drawLine(rect.left, line, rect.right, line, paint)
        }
        canvas.restore()
    }

    private companion object {
        val bones = listOf(11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16, 11 to 23, 12 to 24, 23 to 24,
            23 to 25, 25 to 27, 27 to 29, 29 to 31, 24 to 26, 26 to 28, 28 to 30, 30 to 32)
        val joints = bones.flatMap { listOf(it.first, it.second) }.distinct()
    }
}
