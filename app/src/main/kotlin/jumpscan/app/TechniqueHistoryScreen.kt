package jumpscan.app

import org.json.JSONObject

internal fun MainActivity.techniqueHistory(offset: Int = 0) {
    val api = featureApi()
    work(getString(R.string.loading_history), {
        val catalogue = try { api.array("/api/form/exercises") }
        catch (error: ApiError) { if (error.status == 404) emptyList() else throw error }
        api.array("/api/form?limit=20&offset=$offset") to catalogue.associateBy { it.getString("id") }
    }, { (sets, catalogue) ->
        layout(getString(R.string.ui_form_history), "technique_history")
        button(getString(R.string.form_title)) { formHome() }
        if (sets.isEmpty()) notice(getString(R.string.form_history_empty))
        for (set in sets) {
            val exercise = catalogue[set.getString("exercise")]
            val panel = card()
            renderSetTitle(set, exercise, panel)
            button(getString(R.string.result), parent = panel) { techniqueDetail(set, exercise, offset) }
        }
        if (offset > 0) button(getString(R.string.feedback_newest)) { techniqueHistory() }
        if (sets.size == 20) button(getString(R.string.form_older)) { techniqueHistory(offset + 20) }
        button(getString(R.string.home)) { home() }
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
    text(name + side, 20, green, panel)
    val view = getString(if (set.optString("view", exercise?.optString("view").orEmpty()) == "front")
        R.string.form_front else R.string.form_side)
    val source = getString(if (set.optString("source") == "live") R.string.ui_live else R.string.ui_video)
    text("$view · $source · ${localDateTime(set.getString("created_at"), locale)}", 13, parent = panel)
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
    button(getString(R.string.ui_form_history)) { techniqueHistory(offset) }
    button(getString(R.string.home)) { home() }
}
