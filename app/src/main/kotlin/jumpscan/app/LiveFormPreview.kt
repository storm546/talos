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

/** Cycles the repetition target; the ring beside it shows progress. */
internal class RepTarget(context: Context) : android.widget.Button(context) {
    var fraction = 0f
}

/** A ring that fills toward the repetition target, one tick per repetition. */
internal class RepRing(context: Context) : View(context) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private var shown = 0f
    private var target = 0
    private var fill: android.animation.ValueAnimator? = null
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun show(fraction: Float, reps: Int) {
        target = reps
        if (fraction == shown) { invalidate(); return }
        fill?.cancel()
        if (!android.animation.ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow) { shown = fraction; invalidate(); return }
        fill = android.animation.ValueAnimator.ofFloat(shown, fraction).apply {
            duration = 320; interpolator = settle
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }; start()
        }
    }
    override fun onDetachedFromWindow() { fill?.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val stroke = 9 * density; val radius = min(width, height) / 2f - stroke
        val cx = width / 2f; val cy = height / 2f
        pen.strokeWidth = stroke; pen.color = context.getColor(R.color.ink); pen.alpha = 40
        canvas.drawCircle(cx, cy, radius, pen)
        pen.color = context.getColor(R.color.accent); pen.alpha = 255
        if (shown > 0) canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, -90f, 360 * shown, false, pen)
        pen.strokeWidth = 2 * density; pen.color = context.getColor(R.color.paper); pen.alpha = 230
        for (tick in 0 until target) {
            val angle = Math.toRadians(-90.0 + 360.0 * tick / target)
            val inner = radius - stroke / 2; val outer = radius + stroke / 2
            canvas.drawLine(cx + inner * kotlin.math.cos(angle).toFloat(), cy + inner * kotlin.math.sin(angle).toFloat(),
                cx + outer * kotlin.math.cos(angle).toFloat(), cy + outer * kotlin.math.sin(angle).toFloat(), pen)
        }
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
    /** Crop the camera image to fill the view instead of letterboxing it. */
    var fill = false

    fun show(image: Bitmap, pose: Frame, flipped: Boolean) {
        bitmap = image
        frame = pose
        mirror = flipped
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(context.getColor(R.color.hero))
        val image = bitmap ?: return
        val zoom = if (fill) kotlin.math.max(width.toFloat() / image.width, height.toFloat() / image.height)
            else min(width.toFloat() / image.width, height.toFloat() / image.height)
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
            // A dark under-stroke keeps the skeleton visible on light backgrounds; joints glow in the cue colour.
            for (pass in 0..1) {
                paint.strokeWidth = (if (pass == 0) 7f else 3.5f) * density
                paint.color = if (pass == 0) Color.argb(170, 20, 22, 25) else jointColor
                for ((from, to) in poseBones) {
                    if (points[from].visibility < .5f || points[to].visibility < .5f) continue
                    canvas.drawLine(x(from), y(from), x(to), y(to), paint)
                }
            }
            for (joint in poseJoints) if (points[joint].visibility >= .5f) {
                paint.color = jointColor
                paint.alpha = 40; canvas.drawCircle(x(joint), y(joint), 13f * density, paint)
                paint.alpha = 90; canvas.drawCircle(x(joint), y(joint), 7.5f * density, paint)
                paint.alpha = 255; canvas.drawCircle(x(joint), y(joint), 4f * density, paint)
                paint.color = Color.argb(200, 20, 22, 25); canvas.drawCircle(x(joint), y(joint), 1.6f * density, paint)
            }
        }
        if (scanFraction >= 0 && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            val density = resources.displayMetrics.density
            val line = rect.top + drawHeight * scanFraction
            paint.shader = android.graphics.LinearGradient(0f, line - 64 * density, 0f, line,
                Color.TRANSPARENT, (bone and 0xffffff) or (0x55 shl 24), android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRect(rect.left, line - 64 * density, rect.right, line, paint); paint.shader = null
            paint.color = bone; paint.strokeWidth = 2 * density
            canvas.drawLine(rect.left, line, rect.right, line, paint)
        }
        if (scanFraction >= 0) {
            // Viewfinder corners mark the frame being read.
            val density = resources.displayMetrics.density; val arm = 22 * density; val inset = 10 * density
            paint.color = bone; paint.strokeWidth = 3 * density; paint.strokeCap = Paint.Cap.SQUARE
            for ((cx, cy) in listOf(rect.left + inset to rect.top + inset, rect.right - inset to rect.top + inset,
                    rect.left + inset to rect.bottom - inset, rect.right - inset to rect.bottom - inset)) {
                val dx = if (cx < rect.centerX()) arm else -arm; val dy = if (cy < rect.centerY()) arm else -arm
                canvas.drawLine(cx, cy, cx + dx, cy, paint); canvas.drawLine(cx, cy, cx, cy + dy, paint)
            }
        }
        canvas.restore()
    }
}

internal val poseBones = listOf(11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16, 11 to 23, 12 to 24, 23 to 24,
    23 to 25, 25 to 27, 27 to 29, 29 to 31, 24 to 26, 26 to 28, 28 to 30, 30 to 32)
internal val poseJoints = poseBones.flatMap { listOf(it.first, it.second) }.distinct()

/** Framing overlay for the jump capture camera: live skeleton, a target bracket and the countdown stage. */
internal class FramingGuide(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val muted = context.getColor(R.color.muted)
    private val ready = context.getColor(R.color.ready)
    private val signal = context.getColor(R.color.signal)
    private var pose: Frame? = null
    private var aspect = 16f / 9f
    private var locked = false
    private var number = 0

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun show(pose: Frame?, imageAspect: Float) {
        this.pose = pose
        if (imageAspect > 0f) aspect = imageAspect
        invalidate()
    }

    fun locked(value: Boolean) { if (locked != value) { locked = value; invalidate() } }

    fun countdown(value: Int) { number = value; invalidate() }

    fun clearCountdown() { number = 0; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val density = resources.displayMetrics.density
        // The camera image fills the view, centred; landmarks map onto that crop.
        val viewAspect = width.toFloat() / height
        val drawW: Float; val drawH: Float; val ox: Float; val oy: Float
        if (viewAspect > aspect) {
            drawW = width.toFloat(); drawH = drawW / aspect; ox = 0f; oy = (height - drawH) / 2f
        } else {
            drawH = height.toFloat(); drawW = drawH * aspect; ox = (width - drawW) / 2f; oy = 0f
        }
        val accent = if (locked) ready else muted
        val inset = 18 * density
        val arm = 20 * density
        paint.color = accent
        paint.strokeWidth = 2.5f * density
        for ((cx, cy) in listOf(ox + inset to oy + inset, ox + drawW - inset to oy + inset,
                ox + inset to oy + drawH - inset, ox + drawW - inset to oy + drawH - inset)) {
            val dx = if (cx < ox + drawW / 2) arm else -arm
            val dy = if (cy < oy + drawH / 2) arm else -arm
            canvas.drawLine(cx, cy, cx + dx, cy, paint)
            canvas.drawLine(cx, cy, cx, cy + dy, paint)
        }
        paint.strokeWidth = 1.5f * density
        paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6 * density, 6 * density), 0f)
        canvas.drawLine(ox + inset, oy + inset, ox + drawW - inset, oy + inset, paint)
        paint.pathEffect = null
        pose?.landmarks?.let { points ->
            fun x(i: Int) = ox + points[i].x * drawW
            fun y(i: Int) = oy + points[i].y * drawH
            val joint = if (locked) ready else Color.WHITE
            for (pass in 0..1) {
                paint.strokeWidth = (if (pass == 0) 7f else 3.5f) * density
                paint.color = if (pass == 0) Color.argb(170, 20, 22, 25) else joint
                for ((from, to) in poseBones) {
                    if (points[from].visibility < .5f || points[to].visibility < .5f) continue
                    canvas.drawLine(x(from), y(from), x(to), y(to), paint)
                }
            }
            for (i in poseJoints) if (points[i].visibility >= .5f) {
                paint.color = joint
                paint.alpha = 40; canvas.drawCircle(x(i), y(i), 13f * density, paint)
                paint.alpha = 90; canvas.drawCircle(x(i), y(i), 7.5f * density, paint)
                paint.alpha = 255; canvas.drawCircle(x(i), y(i), 4f * density, paint)
                paint.color = Color.argb(200, 20, 22, 25); canvas.drawCircle(x(i), y(i), 1.6f * density, paint)
            }
        }
        if (number > 0) {
            canvas.drawColor(Color.argb(150, 20, 22, 25))
            paint.typeface = resources.getFont(R.font.spacegrotesk_bold)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 104 * density
            paint.color = signal
            paint.alpha = 255
            canvas.drawText(number.toString(), width / 2f, height / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
        }
    }
}
