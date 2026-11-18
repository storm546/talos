package jumpscan.app

import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import org.json.JSONObject
import java.util.UUID

internal data class FormChoice(val exercise: String, val side: String?)
internal data class FormResult(val metrics: JSONObject, val clientId: String = UUID.randomUUID().toString(),
    var status: Int? = null)

private val repFields = linkedMapOf("depth" to R.string.form_depth,
    "knee_flexion_peak_l" to R.string.knee_flexion_peak_l, "knee_flexion_peak_r" to R.string.knee_flexion_peak_r,
    "fppa_l" to R.string.fppa_peak_l, "fppa_r" to R.string.fppa_peak_r, "flexion_asymmetry_pct" to R.string.flexion_asymmetry_pct,
    "descent_time_ms" to R.string.form_descent, "ascent_time_ms" to R.string.form_ascent)

internal fun validateFormFields(body: JSONObject) {
    val summary = setOf("repetition_count", "partial_count", "unclear_count", "hip_below_knee_count", "worst_repetition") +
        repFields.keys.map { "median_$it" }
    val rep = setOf("index", "status", "reasons", "start_ms", "bottom_ms", "end_ms", "hip_below_knee") + repFields.keys
    require(body.getJSONObject("summary").keys().asSequence().toSet() == summary)
    val rows = body.getJSONArray("repetitions").objects()
    require(rows.size <= 100 && rows.all { it.keys().asSequence().toSet() == rep })
}

private fun MainActivity.formLabel(choice: FormChoice) = getString(when (choice.side) {
    "L" -> R.string.form_single_left
    "R" -> R.string.form_single_right
    else -> R.string.form_squat
})

internal fun MainActivity.formHome(message: String? = null) {
    layout(getString(R.string.form_title), "form")
    featureError(message)
    text(getString(R.string.form_guide))
    text(getString(R.string.form_clip_limit), 14)
    for (choice in listOf(FormChoice("squat", null), FormChoice("single_leg_squat", "L"), FormChoice("single_leg_squat", "R"))) {
        button(formLabel(choice), formChoice == choice) { formChoice = choice; formHome() }
    }
    button(getString(R.string.record), primary = true) {
        launchVideo(Intent(MediaStore.ACTION_VIDEO_CAPTURE).putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1)
            .putExtra(MediaStore.EXTRA_DURATION_LIMIT, 60), 12)
    }
    button(getString(R.string.pick_video)) {
        val pick = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES)
            else Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        launchVideo(pick.setType("video/*"), 13)
    }
    lastForm?.let { result -> button(getString(R.string.last_result)) { formResult(result.metrics, result) } }
    if (token().isNotEmpty()) button(getString(R.string.form_history)) { formHistory() }
    button(getString(R.string.home)) { home() }
}

private fun MainActivity.formCounts(summary: JSONObject) = getString(R.string.form_counts,
    summary.getInt("repetition_count"), summary.getInt("partial_count"), summary.getInt("unclear_count"))

internal fun MainActivity.formResult(metrics: JSONObject, result: FormResult? = null, error: String? = null) {
    layout(getString(R.string.form_result), if (result == null) "form_detail" else "form_result")
    text(formLabel(FormChoice(metrics.getString("exercise"), if (metrics.isNull("side")) null else metrics.getString("side"))), 18, green)
    val summary = metrics.getJSONObject("summary")
    text(formCounts(summary), 18)
    text(getString(if (metrics.getString("view") == "front") R.string.form_front else R.string.form_side), 14)
    for (warning in metrics.getJSONArray("warnings").let { array -> List(array.length()) { array.getString(it) } }) {
        text(getString(when (warning) {
            "unclear_reps" -> R.string.form_unclear_warning
            "single_leg_view" -> R.string.form_one_leg_warning
            "low_visibility" -> R.string.low_visibility
            else -> R.string.unknown_warning
        }), 14, getColor(R.color.warning))
    }
    if (result != null) {
        featureError(error)
        result.status?.let { text(getString(it), 15, if (it == R.string.feature_rejected) getColor(R.color.error) else green) }
        if (result.status !in listOf(R.string.feature_saved, R.string.saved_pending)) {
            button(getString(if (token().isEmpty()) R.string.sign_in_upload else R.string.upload), primary = true) {
                saveFeature(FeatureWrite.FORM, metrics, clientId = result.clientId) { status, message ->
                    result.status = status
                    formResult(metrics, result, if (status == null) message else null)
                }
            }
        }
        if (result.status == R.string.saved_pending) pendingControls()
        text(getString(R.string.no_video_upload), 14)
    }
    if (metrics.has("created_at")) text(localDateTime(metrics.getString("created_at"), locale), 14)
    if (summary.getInt("repetition_count") > 0) {
        text(getString(R.string.form_medians), 18, green)
        formMeasurements(summary, "median_")
        text(getString(R.string.form_shallowest, summary.getInt("worst_repetition")), 14)
    }
    for (rep in metrics.getJSONArray("repetitions").objects()) {
        val status = getString(when (rep.getString("status")) {
            "complete" -> R.string.form_complete
            "partial" -> R.string.form_partial
            else -> R.string.form_unclear
        })
        text(getString(R.string.form_rep, rep.getInt("index"), status), 18, green).setPadding(0, dp(14), 0, dp(4))
        if (rep.getString("status") != "unclear") formMeasurements(rep)
        if (!rep.isNull("hip_below_knee")) text(getString(if (rep.getBoolean("hip_below_knee"))
            R.string.form_hip_below else R.string.form_hip_not_below), 14)
        val reasons = rep.getJSONArray("reasons")
        for (index in 0 until reasons.length()) text(getString(when (reasons.getString(index)) {
            "below_min_depth" -> R.string.form_reason_depth
            "incomplete_start" -> R.string.form_reason_start
            "incomplete_end" -> R.string.form_reason_end
            "tracking_gap" -> R.string.form_reason_gap
            "too_fast" -> R.string.form_reason_fast
            else -> R.string.form_reason_geometry
        }), 14)
    }
    button(getString(if (result == null) R.string.form_history else R.string.form_title)) {
        if (result == null) formHistory() else formHome()
    }
    button(getString(R.string.home)) { home() }
}

private fun MainActivity.formMeasurements(values: JSONObject, prefix: String = "") {
    for ((key, label) in repFields) {
        val value = number(values, prefix + key)
        val unit = when {
            key.endsWith("_ms") -> getString(R.string.unit_ms)
            key == "depth" -> "°"
            else -> ""
        }
        text(getString(R.string.feature_value, getString(label), if (unit.isEmpty() || values.isNull(prefix + key)) value else "$value $unit"), 14)
    }
}

private fun MainActivity.formHistory(offset: Int = 0) {
    val api = featureApi()
    work(getString(R.string.loading_history), { api.array("/api/form?limit=20&offset=$offset") }, { rows ->
        layout(getString(R.string.form_history), "form_history")
        button(getString(R.string.form_title)) { formHome() }
        if (rows.isEmpty()) text(getString(R.string.form_history_empty))
        for (item in rows) {
            val exercise = formLabel(FormChoice(item.getString("exercise"), item.optString("side")))
            val date = localDateTime(item.getString("created_at"), locale)
            button(getString(R.string.feature_value, exercise, date)) { formResult(item) }
            text(formCounts(item.getJSONObject("summary")), 14)
        }
        if (offset > 0) button(getString(R.string.feedback_newest)) { formHistory() }
        if (rows.size == 20) button(getString(R.string.form_older)) { formHistory(offset + 20) }
    }, { formHome(it) })
}
