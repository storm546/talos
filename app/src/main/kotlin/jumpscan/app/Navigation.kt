package jumpscan.app

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView

internal fun MainActivity.navigation(destinations: List<UiTile>, selected: Int, previous: Int) = LinearLayout(this).also { bar ->
    bar.setBackgroundColor(getColor(R.color.surface))
    bar.setPadding(dp(6), dp(4), dp(6), dp(4))
    destinations.forEachIndexed { index, target ->
        val active = index == selected
        val tint = getColor(if (active) R.color.accent else R.color.muted)
        val tab = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        bar.addView(tab, LinearLayout.LayoutParams(0, -2, 1f))
        val mark = View(this).apply { background = shape(tint, 2); alpha = if (active) 1f else 0f }
        tab.addView(mark, LinearLayout.LayoutParams(dp(24), dp(3)))
        val control = styleButton(Button(this), false).apply {
            text = getString(target.label)
            textSize = 12f
            setTextColor(tint)
            background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(getColor(R.color.hover)), null, shape(Color.WHITE, 10))
            setPadding(0, dp(5), 0, dp(4))
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, lineIcon(target.icon, tint), null, null)
            isSelected = active
            setOnClickListener { if (!active) target.action() }
        }
        tab.addView(control, LinearLayout.LayoutParams(-1, -2))
        if (active && previous in destinations.indices && previous != index) mark.post {
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                mark.translationX = ((previous - index) * tab.width).toFloat()
                mark.animate().translationX(0f).setDuration(240).setInterpolator(settle).start()
            }
        }
        tab.clipChildren = false
    }
    bar.clipChildren = false
}

internal fun RadioButton.segment(inverse: Boolean = false) {
    val selected = context.getColor(if (inverse) R.color.segment_selected else R.color.accent)
    val rest = context.getColor(if (inverse) R.color.hero else R.color.hover)
    val density = resources.displayMetrics.density
    buttonDrawable = null
    background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_checked), context.shape(selected, 8))
        addState(intArrayOf(android.R.attr.state_focused), context.shape(rest, 8, selected))
        addState(intArrayOf(), context.shape(rest, 8))
    }
    setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(
        context.getColor(if (inverse) R.color.signal else R.color.paper), context.getColor(if (inverse) R.color.on_hero else R.color.ink))))
    minHeight = (48 * density).toInt()
    minWidth = 0
    gravity = Gravity.CENTER
    instrumentType(display = true)
    pressFeedback()
}

internal fun MainActivity.appearance() {
    val settings = getSharedPreferences("instrument", android.content.Context.MODE_PRIVATE)
    val options = listOf("dark" to R.string.dark_mode, "light" to R.string.light_mode, "system" to R.string.system_mode)
    val selected = settings.getString("appearance", "dark")
    button(getString(R.string.appearance_value, getString(options.first { it.first == selected }.second))) {
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(16), dp(22), dp(24))
            background = raisedSurface()
        }
        section(R.string.appearance, panel)
        options.forEach { (key, label) ->
            button(getString(label), primary = key == selected, parent = panel) {
                dialog.dismiss()
                if (key != selected) { settings.edit().putString("appearance", key).apply(); recreate() }
            }.isSelected = key == selected
        }
        dialog.setContentView(ScrollView(this).apply { addView(panel) })
        dialog.setTitle(R.string.appearance)
        dialog.window?.apply { setGravity(Gravity.BOTTOM); setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(-1, -2) }
        dialog.show()
        panel.arrive()
    }
}
