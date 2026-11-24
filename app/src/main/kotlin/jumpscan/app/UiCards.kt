package jumpscan.app

import android.content.Context
import android.graphics.Color
import android.view.View
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

internal enum class ActionTone { PRIMARY, SECONDARY, TONAL, TEXT }

internal fun Context.styleButton(button: Button, primary: Boolean, tone: ActionTone = if (primary) ActionTone.PRIMARY else ActionTone.SECONDARY) = button.apply {
    instrumentType(display = true)
    val foreground = when (tone) {
        ActionTone.PRIMARY -> R.color.on_action
        ActionTone.SECONDARY -> R.color.ink
        else -> R.color.accent
    }
    val normal = when (tone) {
        ActionTone.PRIMARY -> getColor(R.color.signal)
        ActionTone.TEXT -> Color.TRANSPARENT
        ActionTone.TONAL -> getColor(R.color.tonal)
        ActionTone.SECONDARY -> getColor(R.color.hover)
    }
    setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(getColor(R.color.muted), getColor(foreground))))
    fun fill(color: Int) = shape(color, 10)
    val states = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), fill(getColor(R.color.hover)))
        addState(intArrayOf(android.R.attr.state_focused), shape(normal, 10, getColor(if (primary) R.color.ink else R.color.accent)))
        addState(intArrayOf(), fill(normal))
    }
    backgroundTintList = null
    background = RippleDrawable(ColorStateList.valueOf(getColor(R.color.accent_pressed)),
        states, null)
    isAllCaps = false
    minHeight = resources.getDimensionPixelSize(R.dimen.touch_target)
    textSize = 15f
    stateListAnimator = null
    pressFeedback()
}

internal fun MainActivity.skeleton(parent: LinearLayout) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    for (width in listOf(172, 108)) addView(View(context).apply { background = shape(getColor(R.color.hover), 4) },
        LinearLayout.LayoutParams(dp(width), dp(7)).apply { topMargin = dp(7) })
    parent.addView(this)
}

internal fun MainActivity.snack(message: String) {
    val shell = (page.parent as? android.widget.ScrollView)?.parent as? LinearLayout ?: return
    shell.findViewWithTag<View>("message")?.let { shell.removeView(it) }
    val messageView = TextView(this).apply {
        tag = "message"
        text = message
        instrumentType()
        textSize = 15f
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = shape(getColor(R.color.hero), 10)
        setTextColor(getColor(R.color.on_hero))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    shell.addView(messageView, 1, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), 0, dp(12), dp(8)) })
    messageView.arrive()
    val timeout = if (android.os.Build.VERSION.SDK_INT >= 29) getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        .getRecommendedTimeoutMillis(4500, android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT) else 4500
    messageView.postDelayed({ shell.removeView(messageView) }, timeout.toLong())
}

internal fun MainActivity.card(parent: LinearLayout = page, hero: Boolean = false): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val pad = resources.getDimensionPixelSize(if (hero) R.dimen.space_feature else R.dimen.space_panel)
    setPadding(pad, pad, pad, pad)
    background = raisedSurface(hero)
    elevation = resources.getDimension(if (hero) R.dimen.lift_feature else R.dimen.lift_panel)
    if (parent === page && parent.childCount < 9) arrive((parent.childCount * 18L).coerceAtMost(90))
    parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = resources.getDimensionPixelSize(R.dimen.space_gap) })
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

/** A status pill whose fill and foreground are colour resources chosen by the caller. */
internal fun MainActivity.statusChip(value: String, fill: Int, foreground: Int, parent: LinearLayout) = TextView(this).apply {
    text = value
    textSize = 12f
    instrumentType(display = true)
    setTextColor(getColor(foreground))
    background = shape(getColor(fill), 10)
    setPadding(dp(9), dp(4), dp(9), dp(4))
    gravity = Gravity.CENTER
    parent.addView(this, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
}

/** "How is this measured?": a row that expands in place to show the method and its limits, which stay on the screen. */
internal fun MainActivity.howMeasured(vararg notes: String, parent: LinearLayout = page) {
    val head = row(parent, center = true).apply {
        minimumHeight = dp(48)
        isClickable = true
        isFocusable = true
        foreground = RippleDrawable(ColorStateList.valueOf(getColor(R.color.hover)), null, shape(Color.TRANSPARENT, 0))
    }
    val label = text(getString(R.string.how_measured), 14, getColor(R.color.accent), head).apply {
        instrumentType(display = true)
        compoundDrawablePadding = dp(10)
        setCompoundDrawablesRelativeWithIntrinsicBounds(lineIcon("info", currentTextColor), null, null, null)
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    }
    val chevron = android.widget.ImageView(this).apply {
        setImageDrawable(lineIcon("chevron", getColor(R.color.muted)))
        rotation = 90f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    head.addView(chevron, LinearLayout.LayoutParams(dp(18), dp(18)))
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    parent.addView(body, LinearLayout.LayoutParams(-1, -2))
    notes.forEach { text(it, 13, parent = body) }
    fun state() {
        val open = body.visibility == View.VISIBLE
        chevron.animate().rotation(if (open) -90f else 90f).setDuration(180).start()
        if (android.os.Build.VERSION.SDK_INT >= 30)
            head.stateDescription = getString(if (open) R.string.expanded else R.string.collapsed)
    }
    head.contentDescription = label.text
    state()
    head.setOnClickListener { body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE; state(); body.arrive() }
}

/** A horizontal row. Centred rows must not give children top margins; LinearLayout then clips their bottoms. */
internal fun MainActivity.row(parent: LinearLayout = page, top: Int = 0, center: Boolean = false) = LinearLayout(this).apply {
    if (center) gravity = Gravity.CENTER_VERTICAL
    isBaselineAligned = false
    parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
}

internal fun MainActivity.timelineEntry(index: Int, last: Boolean): LinearLayout {
    val line = row()
    val rail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    line.addView(rail, LinearLayout.LayoutParams(dp(36), -1))
    rail.addView(TextView(this).apply {
        text = String.format(locale, "%02d", index + 1)
        instrumentType(numeric = true)
        gravity = Gravity.CENTER
        setTextColor(getColor(R.color.accent))
        background = shape(getColor(R.color.tonal), 8)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, dp(32), dp(32))
    if (!last) rail.addView(View(this).apply { setBackgroundColor(getColor(R.color.line)) }, dp(1), -1)
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), 0, 0, dp(16))
        line.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
    }
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

/** A rounded surface that stacks settings rows, like Hermes Companion's modules list. */
internal fun MainActivity.settingsGroup(parent: LinearLayout = page) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = raisedSurface()
    parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
}

/** A hairline between grouped rows, indented past the leading icon. */
internal fun MainActivity.divider(parent: LinearLayout, inset: Boolean = true) = View(this).apply {
    background = shape(getColor(R.color.line), 0)
    alpha = if (inset) 1f else .55f
    parent.addView(this, LinearLayout.LayoutParams(-1, dp(1)).apply { if (inset) { marginStart = dp(54); marginEnd = dp(12) } })
}

private class SettingRow(val row: LinearLayout, val value: TextView)

private fun MainActivity.settingRowCore(icon: String, title: String, parent: LinearLayout,
                                        subtle: Boolean, action: () -> Unit): SettingRow {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(12), dp(12))
        minimumHeight = dp(60)
        isClickable = true
        isFocusable = true
        foreground = RippleDrawable(ColorStateList.valueOf(getColor(R.color.hover)), null, shape(Color.TRANSPARENT, 0))
        background = null
        setOnClickListener { action() }
        pressFeedback()
    }
    row.addView(android.widget.ImageView(this).apply {
        setImageDrawable(lineIcon(icon, getColor(if (subtle) R.color.error else R.color.accent)))
        scaleType = android.widget.ImageView.ScaleType.CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(14) })
    val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    row.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
    text(title, 16, getColor(if (subtle) R.color.error else R.color.ink), column).apply {
        instrumentType(display = true)
        setPadding(0, 0, 0, 0)
    }
    val value = text("", 13, getColor(R.color.muted), column).apply {
        setPadding(0, dp(3), 0, 0)
        setSingleLine(true)
        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        visibility = View.GONE
    }
    row.addView(android.widget.ImageView(this).apply {
        setImageDrawable(lineIcon("chevron", getColor(R.color.muted)))
        scaleType = android.widget.ImageView.ScaleType.CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(10) })
    parent.addView(row, LinearLayout.LayoutParams(-1, -2))
    return SettingRow(row, value)
}

/** A tappable settings row: line icon, title, optional current value and a chevron. */
internal fun MainActivity.settingRow(icon: String, title: String, value: String?, parent: LinearLayout,
                                     subtle: Boolean = false, action: () -> Unit): LinearLayout =
    settingRowCore(icon, title, parent, subtle, action).also { core ->
        if (value != null) { core.value.text = value; core.value.visibility = View.VISIBLE }
    }.row

/** A settings row whose current value is filled in later; returns the value view. */
internal fun MainActivity.statusRow(icon: String, title: String, parent: LinearLayout, action: () -> Unit): TextView =
    settingRowCore(icon, title, parent, false, action).value

internal fun MainActivity.notice(value: String, caution: Boolean = false) = text(value, 14,
    getColor(if (caution) R.color.warning else R.color.muted)).apply {
    background = shape(getColor(if (caution) R.color.notice else R.color.surface), 14)
    compoundDrawablePadding = dp(10)
    setCompoundDrawablesRelativeWithIntrinsicBounds(lineIcon(if (caution) "notice" else "info", currentTextColor), null, null, null)
    setPadding(dp(14), dp(12), dp(14), dp(12))
    accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
    (layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(8); bottomMargin = dp(8) }
}

internal data class UiTile(val label: Int, val icon: String, val action: () -> Unit)

/** The kit switch: a rounded track that fills with the accent and a solid thumb, no stock tint. */
internal fun android.widget.Switch.instrumentSwitch() {
    val density = resources.displayMetrics.density
    fun pill(color: Int, width: Int, height: Int, inset: Int = 0) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = height * density / 2; setColor(color)
        setSize((width * density).toInt(), (height * density).toInt())
        if (inset > 0) setStroke((inset * density).toInt(), Color.TRANSPARENT)
    }
    thumbTintList = null; trackTintList = null
    thumbDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_checked), pill(context.getColor(R.color.on_action), 30, 30, 4))
        addState(intArrayOf(), pill(context.getColor(R.color.muted), 30, 30, 5))
    }
    trackDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_checked), pill(context.getColor(R.color.accent), 54, 30))
        addState(intArrayOf(), pill(context.getColor(R.color.hover), 54, 30))
    }
}

/** A row of instrument keys: a round icon well over a short label, for secondary destinations. */
internal fun MainActivity.toolRow(vararg entries: UiTile, parent: LinearLayout = page) {
    val row = LinearLayout(this)
    parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
    entries.forEachIndexed { index, tile ->
        val key = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
            isClickable = true; isFocusable = true
            contentDescription = getString(tile.label)
            background = RippleDrawable(ColorStateList.valueOf(getColor(R.color.hover)), null, shape(Color.WHITE, 14))
            setOnClickListener { tile.action() }
            pressFeedback()
            arrive(120L + index * 30L)
        }
        key.addView(android.widget.ImageView(this).apply {
            setImageDrawable(lineIcon(tile.icon, green))
            scaleType = android.widget.ImageView.ScaleType.CENTER
            background = raisedSurface().apply { cornerRadius = dp(28).toFloat() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        text(getString(tile.label), 12, getColor(R.color.ink), key).apply {
            gravity = Gravity.CENTER; maxLines = 2; setPadding(dp(2), dp(7), dp(2), 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(key, LinearLayout.LayoutParams(0, -2, 1f))
    }
}

internal fun MainActivity.tiles(vararg entries: UiTile, parent: LinearLayout = page) {
    entries.toList().chunked(2).forEach { pair ->
        val row = LinearLayout(this)
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        pair.forEachIndexed { index, tile ->
            button(getString(tile.label), parent = row, action = tile.action).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                minHeight = dp(66)
                compoundDrawablePadding = resources.getDimensionPixelSize(R.dimen.space_small)
                setCompoundDrawablesRelativeWithIntrinsicBounds(lineIcon(tile.icon, green), null, null, null)
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply { if (index > 0) marginStart = dp(8) }
            }
        }
    }
}

internal fun MainActivity.stat(label: String, value: String, unit: String = "", parent: LinearLayout = page) =
    card(parent).apply {
        text(label, 14, parent = this)
        val measurement = when {
            value == "—" || unit.isEmpty() -> value
            unit == "°" || unit == "%" -> "$value$unit"
            else -> "$value $unit"
        }
        addView(Measurement(this@stat).apply {
            text = measurement
            textSize = 32f
            setTextColor(getColor(R.color.ink))
            instrumentType(numeric = true)
            if (value == "—") contentDescription = getString(R.string.not_available)
        })
    }

/** One line of an instrument readout: label left, measured value right, notes underneath. */
internal fun MainActivity.readoutRow(label: String, value: String, unit: String, parent: LinearLayout) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(14), dp(16), dp(12))
    parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    val line = row(this, center = true)
    text(label, 16, getColor(R.color.ink), line).apply {
        instrumentType(display = true)
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    }
    line.addView(Measurement(this@readoutRow).apply {
        text = when { value == "—" || unit.isEmpty() -> value; unit == "°" || unit == "%" -> "$value$unit"; else -> "$value $unit" }
        textSize = 28f
        setTextColor(getColor(R.color.ink))
        instrumentType(numeric = true)
        if (value == "—") contentDescription = getString(R.string.not_available)
    }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
}

internal fun MainActivity.targetBar(label: String, value: String, fraction: Double?, parent: LinearLayout): TextView {
    text(label, 13, parent = parent)
    val reading = text(value, 20, green, parent).apply { instrumentType(display = true) }
    if (fraction != null) parent.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(
            shape(getColor(R.color.hover), 4),
            android.graphics.drawable.ClipDrawable(shape(getColor(R.color.accent), 4), Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
        )).apply { setId(0, android.R.id.background); setId(1, android.R.id.progress) }
        max = 100
        setProgress((fraction.coerceIn(0.0, 1.0) * 100).toInt(), android.animation.ValueAnimator.areAnimatorsEnabled())
        progressTintList = ColorStateList.valueOf(green)
        progressBackgroundTintList = ColorStateList.valueOf(getColor(R.color.hover))
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, LinearLayout.LayoutParams(-1, dp(6)).apply { bottomMargin = dp(10) })
    return reading
}

internal fun Context.lineIcon(name: String, tint: Int) = getDrawable(when (name) {
    "form" -> R.drawable.ic_form
    "training" -> R.drawable.ic_training
    "food" -> R.drawable.ic_food
    "assistant" -> R.drawable.ic_assistant
    "club" -> R.drawable.ic_club
    "checkin" -> R.drawable.ic_checkin
    "notice" -> R.drawable.ic_notice
    "info" -> R.drawable.ic_info
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
    "overflow" -> R.drawable.ic_overflow
    "back" -> R.drawable.ic_back
    "chevron" -> R.drawable.ic_chevron
    "appearance" -> R.drawable.ic_appearance
    "diagnostics" -> R.drawable.ic_diagnostics
    "server" -> R.drawable.ic_server
    "signout" -> R.drawable.ic_signout
    "scan" -> R.drawable.ic_scan
    "plus" -> R.drawable.ic_plus
    "recipe" -> R.drawable.ic_recipe
    "insights" -> R.drawable.ic_insights
    "torch" -> R.drawable.ic_torch
    "copy" -> R.drawable.ic_copy
    "check" -> R.drawable.ic_check
    "timer" -> R.drawable.ic_timer
    "plates" -> R.drawable.ic_plates
    "route" -> R.drawable.ic_route
    else -> R.drawable.ic_history
})!!.mutate().apply { setTint(tint) }
