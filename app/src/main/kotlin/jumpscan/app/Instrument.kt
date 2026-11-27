package jumpscan.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.widget.TextView

internal fun Context.instrumentTheme(): Context {
    val choice = getSharedPreferences("instrument", Context.MODE_PRIVATE).getString("appearance", "dark")
    if (choice == "system") return this
    return createConfigurationContext(Configuration(resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (choice == "light") Configuration.UI_MODE_NIGHT_NO else Configuration.UI_MODE_NIGHT_YES
    })
}

internal fun TextView.instrumentType(display: Boolean = false, numeric: Boolean = false) {
    // Mono only for large readouts; small inline numbers and sentences read better in Space Grotesk with tabular figures.
    val readout = numeric && textSize >= 24 * resources.displayMetrics.scaledDensity
    typeface = resources.getFont(when {
        readout -> R.font.jetbrainsmono_regular
        display -> R.font.spacegrotesk_bold
        else -> R.font.spacegrotesk_regular
    })
    if (numeric) fontFeatureSettings = "tnum"
    if (display) letterSpacing = -.025f
    breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
}

internal fun Context.raisedSurface(hero: Boolean = false) = GradientDrawable(
    GradientDrawable.Orientation.TOP_BOTTOM,
    intArrayOf(getColor(if (hero) R.color.hero_top else R.color.surface_top), getColor(if (hero) R.color.hero else R.color.surface))
).apply { cornerRadius = resources.getDimension(R.dimen.radius_panel) }

internal fun TextView.typeSize(size: Int) {
    val role = when (size) {
        11, 12 -> R.dimen.type_label
        13, 14 -> R.dimen.type_caption
        16, 17 -> R.dimen.type_body
        28, 29 -> R.dimen.type_title
        40 -> R.dimen.type_display
        else -> { textSize = size.toFloat(); return }
    }
    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(role))
}
