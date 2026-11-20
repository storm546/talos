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
    typeface = resources.getFont(when {
        numeric -> R.font.jetbrainsmono_regular
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
).apply { cornerRadius = resources.displayMetrics.density * 14 }
