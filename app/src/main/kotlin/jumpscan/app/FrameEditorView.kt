package jumpscan.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import jumpscan.core.Frame

/**
 * Frame scrubber used by the frame editor. Shows the dashed flight arc across all frames, the
 * take-off and landing markers the athlete has set so far (kept in frame space), the peak-flexion
 * fallback marker and a scrubbing needle. Dragging scrubs; the caller owns the current index.
 */
internal class FrameEditorView(context: Context) : View(context) {
    var frames: List<Float> = emptyList()  // normalised height lagging the arc from 0..1, beyond the current index
        set(value) { field = value; invalidate() }
    var takeoff: Int = -1
        set(value) { field = value; invalidate() }
    var landing: Int = -1
        set(value) { field = value; invalidate() }
    var peak: Int = -1
        set(value) { field = value; invalidate() }
    var index: Int = 0
        set(value) { field = value; invalidate() }
    /** Invoked with the next index when the needle is dragged. */
    var onScrub: ((Int) -> Unit)? = null
    /** Draw a vertical skeleton above the runway, given the needle x, ground y, ceiling y and density. */
    var landmark: ((Canvas, Float, Float, Float, Float) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val ink = context.getColor(R.color.ink)
    private val muted = context.getColor(R.color.muted)
    private val accent = context.getColor(R.color.accent)
    private val ready = context.getColor(R.color.ready)

    private val runwayPx get() = (34 * resources.displayMetrics.density).toInt()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0) return
        val density = resources.displayMetrics.density
        val count = maxOf(frames.size, index + 1, 1)
        val left = runwayPx.toFloat()
        val usable = (width - 2 * runwayPx).toFloat()
        val span = maxOf(1, count - 1)
        val rowH = minOf(height / 2f, 44 * density)
        val base = height - 8 * density
        fun x(i: Int) = left + usable * i / span

        // Flight arc: dashed height-over-time sweeping the full run.
        if (frames.size > 1) {
            val max = frames.maxOrNull()?.takeIf { it > 0f } ?: 1f
            paint.color = muted
            paint.strokeWidth = 1.5f * density
            paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(4 * density, 5 * density), 0f)
            val path = android.graphics.Path()
            var started = false
            frames.forEachIndexed { i, v ->
                val px = x(i)
                val py = base - (base - 6 * density) * (v / max)
                if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
            }
            if (started) canvas.drawPath(path, paint)
            paint.pathEffect = null
        }

        fun marker(selected: Int, color: Int, up: Boolean, thick: Boolean) {
            if (selected < 0 || selected >= count) return
            val px = x(selected)
            val cy = base - rowH / 2
            paint.color = color
            paint.strokeWidth = 2.5f * density
            canvas.drawLine(px, base - rowH, px, base, paint)
            paint.strokeWidth = (if (thick) 9f else 3f) * density
            canvas.drawLine(px - rowH / 2, cy, px + rowH / 2, cy, paint)
            paint.strokeWidth = 3f * density
            canvas.drawLine(px, cy - rowH / 4, px, cy + rowH / 4, paint)
            if (up) canvas.drawLine(px, cy - rowH / 4, px, cy - rowH / 4 - 7 * density, paint)
        }
        marker(peak, accent, up = false, thick = false)
        marker(takeoff, if (takeoff >= 0) ready else accent, up = true, thick = true)
        marker(landing, if (landing >= 0) ready else accent, up = false, thick = true)

        landmark?.invoke(canvas, x(index.coerceIn(0, count - 1)), base, base - rowH, density)

        // Scrubbing needle with a soft touch well.
        val px = x(index.coerceIn(0, count - 1))
        paint.shader = android.graphics.LinearGradient(px - 8 * density, 0f, px + 8 * density, 0f,
            android.graphics.Color.TRANSPARENT, (ink and 0xffffff) or (0x28 shl 24), android.graphics.Shader.TileMode.CLAMP)
        paint.strokeWidth = 16 * density
        paint.color = ink
        canvas.drawLine(px, 0f, px, height.toFloat(), paint)
        paint.shader = null
        paint.strokeWidth = 2.5f * density
        canvas.drawLine(px, 0f, px, height.toFloat(), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            val count = maxOf(frames.size, index + 1, 1)
            if (count > 1) {
                val left = runwayPx
                val usable = (width - 2 * runwayPx)
                val fraction = ((event.x - left) / usable).coerceIn(0f, 1f)
                val next = (fraction * (count - 1)).toInt().coerceIn(0, count - 1)
                if (next != index) {
                    index = next
                    onScrub?.invoke(next)
                    if (android.animation.ValueAnimator.areAnimatorsEnabled())
                        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
            }
        }
        return true
    }
}

/** Draw the pose skeleton with a dark under-stroke, mapping landmarks through the frame-space functions. */
internal fun drawSkeleton(canvas: Canvas, frame: Frame, px: (Int) -> Float, py: (Int) -> Float,
    paint: Paint, density: Float, jointColor: Int = android.graphics.Color.WHITE) {
    for (pass in 0..1) {
        paint.strokeWidth = (if (pass == 0) 7f else 3.5f) * density
        paint.color = if (pass == 0) android.graphics.Color.argb(170, 20, 22, 25) else jointColor
        for ((from, to) in poseBones) {
            if (frame.landmarks[from].visibility < .5f || frame.landmarks[to].visibility < .5f) continue
            canvas.drawLine(px(from), py(from), px(to), py(to), paint)
        }
    }
    for (joint in poseJoints) if (frame.landmarks[joint].visibility >= .5f) {
        paint.color = jointColor
        paint.alpha = 40; canvas.drawCircle(px(joint), py(joint), 13f * density, paint)
        paint.alpha = 90; canvas.drawCircle(px(joint), py(joint), 7.5f * density, paint)
        paint.alpha = 255; canvas.drawCircle(px(joint), py(joint), 4f * density, paint)
        paint.color = android.graphics.Color.argb(200, 20, 22, 25)
        canvas.drawCircle(px(joint), py(joint), 1.6f * density, paint)
        paint.color = jointColor
    }
}