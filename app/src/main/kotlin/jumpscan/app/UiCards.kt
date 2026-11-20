package jumpscan.app

import android.content.Context
import android.content.res.ColorStateList
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

internal fun Context.styleButton(button: Button, primary: Boolean) = button.apply {
    instrumentType(display = true)
    setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(getColor(R.color.muted), getColor(if (primary) R.color.hero else R.color.ink))))
    fun fill(color: Int) = shape(color, 10)
    val states = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), fill(getColor(R.color.hover)))
        addState(intArrayOf(android.R.attr.state_focused), shape(getColor(R.color.hover), 14, getColor(R.color.accent)))
        addState(intArrayOf(), fill(getColor(if (primary) R.color.signal else R.color.hover)))
    }
    backgroundTintList = null
    background = RippleDrawable(ColorStateList.valueOf(getColor(if (primary) R.color.accent_pressed else R.color.hover)),
        states, null)
    isAllCaps = false
    minHeight = px(48)
    textSize = 15f
    stateListAnimator = null
}

internal fun MainActivity.card(parent: LinearLayout = page, hero: Boolean = false): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val pad = dp(if (hero) 20 else 16)
    setPadding(pad, pad, pad, pad)
    background = raisedSurface(hero)
    elevation = dp(if (hero) 3 else 1).toFloat()
    parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
}

internal fun MainActivity.section(label: Int, parent: LinearLayout = page) = text(getString(label), 20, parent = parent).apply {
    instrumentType(display = true)
    if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
}

internal fun MainActivity.hero(title: Int, subtitle: Int, eyebrow: String? = null) =
    hero(getString(title), getString(subtitle), eyebrow)

internal fun MainActivity.hero(title: String, subtitle: String, eyebrow: String? = null): LinearLayout = card(hero = true).also { panel ->
    val signal = getColor(R.color.signal)
    if (eyebrow != null) text(eyebrow, 12, signal, panel).apply { letterSpacing = .09f }
    text(title, 29, android.graphics.Color.WHITE, panel).apply { instrumentType(display = true) }
    text(subtitle, 14, getColor(R.color.on_hero), panel)
}

/** Small rounded label for exercise facts such as mode, camera view and side. */
internal fun MainActivity.chip(value: String, parent: LinearLayout, caution: Boolean = false) = TextView(this).apply {
    text = value
    textSize = 12f
    instrumentType(display = true)
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
        instrumentType(display = true)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) }
    }
}

/** A 48 dp rounded tile holding one line icon. */
internal fun MainActivity.iconTile(icon: String, parent: LinearLayout, dark: Boolean = false) = android.widget.ImageView(this).apply {
    setImageDrawable(lineIcon(icon, getColor(if (dark) R.color.signal else R.color.accent)))
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
                setCompoundDrawablesRelativeWithIntrinsicBounds(lineIcon(tile.icon, green), null, null, null)
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
        text(measurement, 32, getColor(R.color.ink), this).apply {
            instrumentType(numeric = true)
            if (value == "—") contentDescription = getString(R.string.not_available)
        }
    }

internal fun MainActivity.targetBar(label: String, value: String, fraction: Double?, parent: LinearLayout) {
    text(label, 13, parent = parent)
    text(value, 20, green, parent).instrumentType(display = true)
    if (fraction != null) parent.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = (fraction.coerceIn(0.0, 1.0) * 100).toInt()
        progressTintList = ColorStateList.valueOf(green)
        progressBackgroundTintList = ColorStateList.valueOf(getColor(R.color.hover))
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, LinearLayout.LayoutParams(-1, dp(12)).apply { bottomMargin = dp(10) })
}

internal fun Context.lineIcon(name: String, tint: Int) = getDrawable(when (name) {
    "form" -> R.drawable.ic_form
    "training" -> R.drawable.ic_training
    "food" -> R.drawable.ic_food
    "assistant" -> R.drawable.ic_assistant
    "club" -> R.drawable.ic_club
    "checkin" -> R.drawable.ic_checkin
    "profile" -> R.drawable.ic_profile
    "legs" -> R.drawable.ic_legs
    "hinge" -> R.drawable.ic_hinge
    "push" -> R.drawable.ic_push
    "pull" -> R.drawable.ic_pull
    "core" -> R.drawable.ic_core
    "conditioning" -> R.drawable.ic_conditioning
    "home" -> R.drawable.ic_home
    "index" -> R.drawable.ic_index
    "more" -> R.drawable.ic_more
    else -> R.drawable.ic_history
})!!.mutate().apply { setTint(tint) }
