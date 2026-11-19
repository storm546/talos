package jumpscan.app

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

internal fun MainActivity.shape(fill: Int, radius: Int, border: Int? = null) = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = dp(radius).toFloat()
    border?.let { setStroke(dp(1), it) }
}

internal fun MainActivity.card(parent: LinearLayout = page, hero: Boolean = false): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val pad = dp(if (hero) 20 else 16)
    setPadding(pad, pad, pad, pad)
    background = if (hero) TrackCard(getColor(R.color.hero), dp(24).toFloat()) else
        shape(getColor(R.color.surface), 20, getColor(R.color.line))
    parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
}

internal fun MainActivity.section(label: Int, parent: LinearLayout = page) = text(getString(label), 20, parent = parent).apply {
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
}

internal fun MainActivity.hero(title: Int, subtitle: Int, eyebrow: String? = null): LinearLayout = card(hero = true).also { panel ->
    val signal = getColor(R.color.signal)
    if (eyebrow != null) text(eyebrow, 12, signal, panel).apply { letterSpacing = .09f }
    text(getString(title), 29, android.graphics.Color.WHITE, panel).apply { setTypeface(null, Typeface.BOLD) }
    text(getString(subtitle), 14, getColor(R.color.on_hero), panel)
}

internal fun MainActivity.notice(value: String, caution: Boolean = false) = text(value, 14,
    getColor(if (caution) R.color.warning else R.color.accent)).apply {
    background = shape(getColor(if (caution) R.color.notice else R.color.hover), 14)
    setPadding(dp(14), dp(12), dp(14), dp(12))
    accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
    (layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(8); bottomMargin = dp(8) }
}

internal data class UiTile(val label: Int, val icon: String, val action: () -> Unit)

internal fun MainActivity.tiles(vararg entries: UiTile, parent: LinearLayout = page) {
    entries.toList().chunked(2).forEach { pair ->
        val row = LinearLayout(this)
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        pair.forEachIndexed { index, tile ->
            button(getString(tile.label), parent = row, action = tile.action).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                minHeight = dp(66)
                compoundDrawablePadding = dp(8)
                setCompoundDrawablesRelativeWithIntrinsicBounds(UiIcon(tile.icon, green, dp(21)), null, null, null)
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply { if (index > 0) marginStart = dp(8) }
            }
        }
    }
}

internal fun MainActivity.stat(label: String, value: String, unit: String = "", parent: LinearLayout = page) =
    card(parent).apply {
        text(label, 13, parent = this)
        val measurement = when {
            value == "—" || unit.isEmpty() -> value
            unit == "°" || unit == "%" -> "$value$unit"
            else -> "$value $unit"
        }
        text(measurement, 26, green, this).apply {
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            if (value == "—") contentDescription = getString(R.string.not_available)
        }
    }

internal fun MainActivity.targetBar(label: String, value: String, fraction: Double?, parent: LinearLayout) {
    text(label, 13, parent = parent)
    text(value, 20, green, parent).setTypeface(null, Typeface.BOLD)
    if (fraction != null) parent.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = (fraction.coerceIn(0.0, 1.0) * 100).toInt()
        progressTintList = ColorStateList.valueOf(green)
        progressBackgroundTintList = ColorStateList.valueOf(getColor(R.color.hover))
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, LinearLayout.LayoutParams(-1, dp(12)).apply { bottomMargin = dp(10) })
}

private class TrackCard(private val fill: Int, private val radius: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val box = RectF(bounds)
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawRoundRect(box, radius, radius, paint)
        canvas.save()
        canvas.clipRect(box.left + radius, box.top, box.right - radius, box.bottom)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius / 16
        paint.color = 0xff394e34.toInt()
        repeat(5) { lane ->
            val width = radius * (2 + lane * .58f)
            val edge = box.right + radius * .84f
            canvas.drawOval(edge - width, box.top - width, edge + width, box.top + width, paint)
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

private class UiIcon(private val name: String, color: Int, private val size: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        fun path(vararg points: Int) {
            for (index in 2 until points.size step 2) canvas.drawLine(points[index - 2].toFloat(),
                points[index - 1].toFloat(), points[index].toFloat(), points[index + 1].toFloat(), paint)
        }
        when (name) {
            "form" -> { canvas.drawCircle(12f, 4f, 2f, paint); path(4, 10, 12, 9, 20, 10); path(12, 9, 12, 15, 7, 21); path(12, 15, 17, 21) }
            "training" -> { path(6, 12, 18, 12); path(6, 7, 6, 17); path(18, 7, 18, 17); path(3, 9, 3, 15); path(21, 9, 21, 15) }
            "food" -> { path(4, 3, 4, 10, 10, 10, 10, 3); path(7, 3, 7, 21); path(19, 21, 19, 3, 15, 9, 15, 13, 19, 13) }
            "assistant" -> { path(12, 3, 14, 10, 21, 12, 14, 14, 12, 21, 10, 14, 3, 12, 10, 10, 12, 3) }
            "club" -> { path(4, 5, 20, 5, 20, 21, 4, 21, 4, 5); path(4, 10, 20, 10); path(8, 3, 8, 7); path(16, 3, 16, 7) }
            "checkin" -> { canvas.drawCircle(12f, 12f, 8.5f, paint); path(8, 12, 11, 15, 16, 9) }
            "profile" -> { canvas.drawCircle(12f, 7f, 3.5f, paint); canvas.drawArc(5f, 13f, 19f, 26f, 180f, 180f, false, paint) }
            else -> { path(3, 19, 21, 19); path(4, 15, 9, 10, 13, 13, 20, 5); path(15, 5, 20, 5, 20, 10) }
        }
        canvas.restore()
    }
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
