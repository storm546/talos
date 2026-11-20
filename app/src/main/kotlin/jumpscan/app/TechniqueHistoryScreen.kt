package jumpscan.app

import jumpscan.core.FormExercises
import org.json.JSONObject

internal fun MainActivity.techniqueHistory(offset: Int = 0) {
    val api = featureApi()
    work(getString(R.string.loading_history), {
        val catalogue = try { api.array("/api/form/exercises") }
        catch (error: ApiError) { if (error.status == 404) emptyList() else throw error }
        api.array("/api/form?limit=20&offset=$offset") to catalogue.associateBy { it.getString("id") }
    }, { (sets, catalogue) ->
        layout(getString(R.string.ui_form_history), "technique_history")
        if (sets.isEmpty()) notice(getString(R.string.form_history_empty))
        for (set in sets) {
            val exercise = catalogue[set.getString("exercise")]
            val panel = card()
            val line = row(panel, center = true)
            iconTile(formIcon(set.getString("exercise")), line)
            val column = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
            line.addView(column, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            renderSetTitle(set, exercise, column)
            val summary = set.optJSONObject("summary") ?: JSONObject()
            val hold = set.optString("mode", exercise?.optString("mode").orEmpty()) == "hold" || !summary.isNull("hold_ms")
            val total = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.END
            }
            line.addView(total, android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
            text(if (hold) metricNumber(summary.optLong("hold_ms") / 1000.0, "hold_ms", locale)
                else summary.optInt("repetition_count").toString(), 24, green, total).apply {
                instrumentType(numeric = true)
                gravity = android.view.Gravity.END
                setPadding(0, 0, 0, 0)
            }
            text(getString(if (hold) R.string.ui_seconds else R.string.ui_reps_unit), 12, parent = total).apply {
                gravity = android.view.Gravity.END
                setPadding(0, 0, 0, 0)
            }
            panel.pressFeedback()
            panel.isClickable = true
            panel.isFocusable = true
            panel.foreground = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(getColor(R.color.hover)), null, shape(getColor(R.color.surface), 20))
            panel.setOnClickListener {
                if (FormExercises.all.any { it.id == set.getString("exercise") }) formResult(set)
                else techniqueDetail(set, exercise, offset)
            }
        }
        if (offset > 0) button(getString(R.string.feedback_newest)) { techniqueHistory() }
        if (sets.size == 20) button(getString(R.string.form_older)) { techniqueHistory(offset + 20) }
    }, { home(it) })
}

private fun MainActivity.localized(entry: JSONObject?, key: String) = entry?.optString(
    if (locale.language == "bg") "${key}_bg" else "${key}_en")?.takeIf { it.isNotBlank() }

private fun MainActivity.renderSetTitle(set: JSONObject, exercise: JSONObject?, panel: android.widget.LinearLayout) {
    val id = set.getString("exercise")
    val name = localized(exercise, "name") ?: when (id) {
        "squat" -> getString(R.string.form_squat)
        "single_leg_squat" -> getString(when (set.optString("side")) {
            "L" -> R.string.form_single_left
            "R" -> R.string.form_single_right
            else -> R.string.ui_single_leg
        })
        else -> id
    }
    val side = if (exercise?.optBoolean("sided") == true && !set.isNull("side")) {
        when (set.optString("side")) {
            "L", "left" -> " · " + getString(R.string.ui_left)
            "R", "right" -> " · " + getString(R.string.ui_right)
            else -> ""
        }
    } else ""
    text(name + side, 17, parent = panel).apply {
        instrumentType(display = true)
        setPadding(0, 0, 0, dp(2))
    }
    val view = getString(if (set.optString("view", exercise?.optString("view").orEmpty()) == "front")
        R.string.form_front else R.string.form_side)
    val source = getString(if (set.optString("source") == "live") R.string.ui_live else R.string.ui_video)
    text("$view · $source\n${localDateTime(set.getString("created_at"), locale)}", 13, parent = panel).setPadding(0, 0, 0, 0)
}

private fun MainActivity.techniqueDetail(set: JSONObject, exercise: JSONObject?, offset: Int) {
    layout(getString(R.string.ui_form_history), "technique_detail")
    renderSetTitle(set, exercise, card())
    val summary = set.optJSONObject("summary") ?: JSONObject()
    val checks = exercise?.optJSONArray("checks")?.objects().orEmpty()
    if (set.optString("mode", exercise?.optString("mode").orEmpty()) == "hold") {
        for ((key, label) in listOf("hold_ms" to R.string.ui_hold, "in_band_ms" to R.string.ui_in_band)) {
            val value = if (!set.isNull(key)) set.optDouble(key) else summary.optDouble(key)
            stat(getString(label), if (value.isFinite()) metricNumber(value / 1000, key, locale) else "—", getString(R.string.ui_seconds))
        }
    } else {
        notice(getString(R.string.form_counts, summary.optInt("repetition_count"),
            summary.optInt("partial_count"), summary.optInt("unclear_count")))
    }
    for (rep in set.optJSONArray("repetitions")?.objects().orEmpty()) {
        val panel = card()
        val status = getString(when (rep.optString("status")) {
            "complete" -> R.string.form_complete
            "partial" -> R.string.form_partial
            else -> R.string.form_unclear
        })
        text(getString(R.string.form_rep, rep.optInt("index"), status), 18, green, panel)
        val values = rep.optJSONObject("checks") ?: rep
        val fields = if (checks.isNotEmpty()) checks.map { check ->
            val key = check.getString("key")
            val unit = check.optString("unit")
            key to listOf(localized(check, "label") ?: key, unit).filter { it.isNotBlank() }.joinToString(" · ")
        } else listOf("depth" to R.string.form_depth, "descent_time_ms" to R.string.form_descent,
            "ascent_time_ms" to R.string.form_ascent, "fppa_l" to R.string.fppa_peak_l,
            "fppa_r" to R.string.fppa_peak_r, "knee_flexion_peak_l" to R.string.knee_flexion_peak_l,
            "knee_flexion_peak_r" to R.string.knee_flexion_peak_r,
            "flexion_asymmetry_pct" to R.string.flexion_asymmetry_pct).map { (key, label) ->
            key to (getString(label) + when {
                key.endsWith("_ms") -> " (${getString(R.string.ui_ms)})"
                key == "depth" -> " (°)"
                else -> ""
            })
        }
        fields.forEach { (key, label) ->
            if (values.has(key)) text(getString(R.string.feature_value, label,
                if (values.isNull(key)) "—" else number(values, key)), 14, parent = panel)
        }
    }
}
