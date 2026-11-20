package jumpscan.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.TextView

internal val settle = PathInterpolator(.2f, 0f, 0f, 1f)

internal fun View.pressFeedback() = setOnTouchListener { view, event ->
    if (view.isEnabled) {
        val down = event.actionMasked == MotionEvent.ACTION_DOWN
        if (down) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (ValueAnimator.areAnimatorsEnabled() && (down || event.actionMasked in listOf(MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP))) {
            val scale = if (down) .97f else 1f
            view.animate().scaleX(scale).scaleY(scale).setStartDelay(0).setDuration(if (down) 75 else 220)
                .setInterpolator(if (down) settle else OvershootInterpolator(.85f)).start()
        }
    }
    false
}

internal fun View.arrive(delay: Long = 0) {
    if (!ValueAnimator.areAnimatorsEnabled()) return
    alpha = .55f
    translationY = 7 * resources.displayMetrics.density
    animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(210).setInterpolator(settle).start()
}

internal fun View.bump() {
    if (!ValueAnimator.areAnimatorsEnabled()) return
    scaleX = 1.12f
    scaleY = 1.12f
    animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(settle).start()
}

internal class Measurement(context: Context) : TextView(context) {
    private var fraction = 1f
    private var roll: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!ValueAnimator.areAnimatorsEnabled() || text.none { it.isDigit() }) return
        roll = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340
            interpolator = settle
            addUpdateListener { fraction = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (fraction >= 1 || layout?.lineCount != 1 || layout.getParagraphDirection(0) < 0) {
            super.onDraw(canvas)
            return
        }
        val height = paint.fontSpacing
        val base = baseline.toFloat()
        var left = compoundPaddingLeft.toFloat()
        paint.color = currentTextColor
        for (character in text) {
            val glyph = character.toString()
            if (character in '0'..'9') {
                canvas.save()
                canvas.clipRect(left, base + paint.ascent(), left + paint.measureText(glyph), base + paint.descent())
                canvas.drawText(((character.digitToInt() + 9) % 10).toString(), left, base - fraction * height, paint)
                canvas.drawText(glyph, left, base + (1 - fraction) * height, paint)
                canvas.restore()
            } else canvas.drawText(glyph, left, base, paint)
            left += paint.measureText(glyph)
        }
    }

    override fun onDetachedFromWindow() {
        roll?.cancel()
        fraction = 1f
        super.onDetachedFromWindow()
    }
}
