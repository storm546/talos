package jumpscan.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

private fun Context.px(value: Int) = (value * resources.displayMetrics.density).toInt()

internal fun Context.shape(fill: Int, radius: Int, border: Int? = null) = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = px(radius).toFloat()
    border?.let { setStroke(px(1), it) }
}

/** The one button style: accent fill for the primary action, outlined surface otherwise. */
internal fun Context.styleButton(button: Button, primary: Boolean) = button.apply {
    setTypeface(null, Typeface.BOLD)
    setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(getColor(R.color.muted), getColor(if (primary) R.color.hero else R.color.ink))))
    fun fill(color: Int) = shape(color, 14, if (primary) color else getColor(R.color.line))
    val states = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), fill(getColor(R.color.hover)))
        addState(intArrayOf(android.R.attr.state_focused), shape(getColor(R.color.hover), 14, getColor(R.color.accent)))
        addState(intArrayOf(), fill(getColor(if (primary) R.color.signal else R.color.surface)))
    }
    backgroundTintList = null
    background = RippleDrawable(ColorStateList.valueOf(getColor(if (primary) R.color.accent_pressed else R.color.hover)),
        states, null)
    stateListAnimator = null
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

internal fun MainActivity.hero(title: Int, subtitle: Int, eyebrow: String? = null) =
    hero(getString(title), getString(subtitle), eyebrow)

internal fun MainActivity.hero(title: String, subtitle: String, eyebrow: String? = null): LinearLayout = card(hero = true).also { panel ->
    val signal = getColor(R.color.signal)
    if (eyebrow != null) text(eyebrow, 12, signal, panel).apply { letterSpacing = .09f }
    text(title, 29, android.graphics.Color.WHITE, panel).apply { setTypeface(null, Typeface.BOLD) }
    text(subtitle, 14, getColor(R.color.on_hero), panel)
}

/** Small rounded label for exercise facts such as mode, camera view and side. */
internal fun MainActivity.chip(value: String, parent: LinearLayout, caution: Boolean = false) = TextView(this).apply {
    text = value
    textSize = 12f
    setTypeface(null, Typeface.BOLD)
    setTextColor(getColor(if (caution) R.color.warning else R.color.accent))
    background = shape(getColor(if (caution) R.color.notice else R.color.hover), 10)
    setPadding(dp(9), dp(4), dp(9), dp(4))
    parent.addView(this, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6); topMargin = dp(6) })
}

/** A horizontal row. Centred rows must not give children top margins; LinearLayout then clips their bottoms. */
internal fun MainActivity.row(parent: LinearLayout = page, top: Int = 0, center: Boolean = false) = LinearLayout(this).apply {
    if (center) gravity = Gravity.CENTER_VERTICAL
    isBaselineAligned = false
    parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
}

/** Number cards in equal columns, as on the jump result screen. */
internal fun MainActivity.statGrid(items: List<Triple<String, String, String>>, columns: Int = 2, parent: LinearLayout = page) {
    items.chunked(columns).forEach { chunk ->
        val line = row(parent)
        chunk.forEachIndexed { column, (label, value, unit) ->
            stat(label, value, unit, line).layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply {
                topMargin = dp(8)
                if (column > 0) marginStart = dp(8)
            }
        }
        repeat(columns - chunk.size) { line.addView(android.view.View(this), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = dp(8) }) }
    }
}

/** A label and its value on one line, the value right-aligned. */
internal fun MainActivity.valueRow(label: String, value: String, parent: LinearLayout) = row(parent, center = true).apply {
    setPadding(0, dp(4), 0, dp(4))
    text(label, 14, getColor(R.color.muted), this).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    text(value, 15, getColor(R.color.ink), this).apply {
        setTypeface(null, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) }
    }
}

/** A 48 dp rounded tile holding one line icon. */
internal fun MainActivity.iconTile(icon: String, parent: LinearLayout, dark: Boolean = false) = android.widget.ImageView(this).apply {
    setImageDrawable(UiIcon(icon, getColor(if (dark) R.color.signal else R.color.accent), dp(26)))
    scaleType = android.widget.ImageView.ScaleType.CENTER
    background = shape(getColor(if (dark) R.color.hero else R.color.hover), 14)
    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
    parent.addView(this, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) })
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

internal class UiIcon(private val name: String, color: Int, private val size: Int) : Drawable() {
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
            "legs" -> { canvas.drawCircle(8f, 4f, 2.2f, paint); path(8, 7, 10, 13, 17, 13, 16, 21, 19, 21); path(9, 9, 18, 9) }
            "hinge" -> { canvas.drawCircle(19f, 6f, 2.2f, paint); path(17, 8, 8, 12, 9, 21, 12, 21); path(14, 10, 14, 17); path(11, 17, 17, 17) }
            "push" -> { path(4, 5, 20, 5); path(12, 20, 12, 9); path(8, 13, 12, 9, 16, 13) }
            "pull" -> { path(4, 4, 20, 4); path(12, 20, 12, 8); path(8, 12, 12, 8, 16, 12) }
            "core" -> { canvas.drawCircle(19.5f, 11f, 2f, paint); path(3, 19, 21, 19); path(4, 15, 17, 12); path(15, 12, 15, 19) }
            "conditioning" -> { canvas.drawCircle(12f, 4f, 2f, paint); path(12, 7, 12, 14); path(5, 4, 12, 9, 19, 4); path(7, 21, 12, 14, 17, 21) }
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
