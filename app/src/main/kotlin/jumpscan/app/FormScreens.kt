@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package jumpscan.app

import android.content.Intent
import android.app.Activity
import android.app.AlertDialog
import android.app.Fragment
import android.os.Build
import android.provider.MediaStore
import jumpscan.core.FormExercise
import jumpscan.core.FormExercises
import org.json.JSONObject
import java.io.File
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
    val exercise = FormExercises[body.getString("exercise")]
    if (body.has("mode")) require(body.getString("mode") == exercise.mode)
    if (body.has("source")) require(body.getString("source") in setOf("live", "video"))
    val hold = if (exercise.mode == "hold") setOf("hold_ms", "in_band_ms") else emptySet()
    require(body.getJSONObject("summary").keys().asSequence().toSet() == summary + hold)
    val rows = body.getJSONArray("repetitions").objects()
    val checks = exercise.checks.map { it.key }.toSet()
    require(rows.size <= 100 && rows.all { row ->
        val keys = row.keys().asSequence().toSet()
        keys == rep || (keys == rep + "checks" && row.getJSONObject("checks").let { values ->
            values.keys().asSequence().all { it in checks && (values.isNull(it) || values.get(it) is Number) }
        })
    })
}

internal fun FormExercise.label(bg: Boolean) = if (bg) nameBg else nameEn

private fun MainActivity.formLabel(choice: FormChoice): String {
    val name = FormExercises[choice.exercise].label(locale.language == "bg")
    return when (choice.side) {
        "L" -> getString(R.string.form_named_side, name, getString(R.string.form_left))
        "R" -> getString(R.string.form_named_side, name, getString(R.string.form_right))
        else -> name
    }
}

internal fun MainActivity.formHome(message: String? = null) {
    layout(getString(R.string.form_title), "form")
    featureError(message)
    val exercise = FormExercises[formChoice.exercise]
    val bg = locale.language == "bg"
    text(getString(R.string.form_modes_guide))
    button(getString(R.string.form_change_exercise, exercise.label(bg))) {
        val rows = FormExercises.all
        AlertDialog.Builder(this).setTitle(R.string.form_choose_exercise)
            .setSingleChoiceItems(rows.map { it.label(bg) }.toTypedArray(), rows.indexOf(exercise)) { dialog, index ->
                val selected = rows[index]
                formChoice = FormChoice(selected.id, if (selected.sided) formChoice.side ?: "L" else null)
                dialog.dismiss()
                formHome()
            }.setNegativeButton(R.string.cancel, null).show()
    }
    if (exercise.sided) {
        button(formLabel(formChoice)) {
            formChoice = formChoice.copy(side = if (formChoice.side == "L") "R" else "L")
            formHome()
        }
    }
    text(getString(exercise.viewGuide()), 14)
    if (!exercise.signal.legacy) text(getString(R.string.form_experimental), 14)
    button(getString(R.string.form_live), primary = true) { openLiveForm(false) }
    if (BuildConfig.DEBUG && File(filesDir, LIVE_REPLAY_FILE).isFile) {
        button(getString(R.string.form_replay_debug)) { openLiveForm(true) }
    }
    text(getString(R.string.form_clip_limit), 14)
    button(getString(R.string.record)) {
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

private fun MainActivity.formCounts(summary: JSONObject) = if (summary.has("hold_ms")) getString(R.string.form_hold_counts,
    summary.getLong("hold_ms") / 1000.0, summary.getLong("in_band_ms") / 1000.0) else getString(R.string.form_counts,
        summary.getInt("repetition_count"), summary.getInt("partial_count"), summary.getInt("unclear_count"))

internal fun MainActivity.formResult(metrics: JSONObject, result: FormResult? = null, error: String? = null) {
    layout(getString(R.string.form_result), if (result == null) "form_detail" else "form_result")
    val exercise = FormExercises[metrics.getString("exercise")]
    text(formLabel(FormChoice(exercise.id, if (metrics.isNull("side")) null else metrics.getString("side"))), 18, green)
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
        if (metrics.getJSONArray("repetitions").length() > 100) text(getString(R.string.form_set_too_long), 14)
        else if (result.status !in listOf(R.string.feature_saved, R.string.saved_pending)) {
            button(getString(if (token().isEmpty()) R.string.sign_in_upload else R.string.upload), primary = true) {
                saveFormResult(result) { status, message ->
                    result.status = status
                    formResult(metrics, result, if (status == null) message else null)
                }
            }
        }
        if (result.status == R.string.saved_pending) pendingControls()
        text(getString(R.string.no_video_upload), 14)
    }
    if (metrics.has("created_at")) text(localDateTime(metrics.getString("created_at"), locale), 14)
    if (!summary.isNull("worst_repetition")) {
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
        if (rep.getString("status") != "unclear") {
            if (exercise.signal.legacy) formMeasurements(rep)
            if (!exercise.signal.legacy) rep.optJSONObject("checks")?.let { checks ->
                for (check in exercise.checks) {
                    val label = if (locale.language == "bg") check.labelBg else check.labelEn
                    val value = if (checks.isNull(check.key)) getString(R.string.not_available)
                        else "${number(checks, check.key)} ${check.unit}"
                    text(getString(R.string.feature_value, label, value), 14)
                }
            }
        }
        if (!rep.isNull("hip_below_knee")) text(getString(if (rep.getBoolean("hip_below_knee"))
            R.string.form_hip_below else R.string.form_hip_not_below), 14)
        val reasons = rep.getJSONArray("reasons")
        for (index in 0 until reasons.length()) text(getString(when (reasons.getString(index)) {
            "below_min_depth" -> if (exercise.signal.legacy) R.string.form_reason_depth else R.string.form_reason_range
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

private fun MainActivity.saveFormResult(result: FormResult, done: (Int?, String) -> Unit) {
    if (token().isEmpty()) return login()
    val body = JSONObject(result.metrics.toString())
    if (body.optString("source") != "live") body.remove("mode")
    saveFeature(FeatureWrite.FORM, body, clientId = result.clientId, done = done)
}

@Suppress("DEPRECATION")
private fun MainActivity.openLiveForm(replay: Boolean) {
    val tag = "live_form_result"
    val bridge = fragmentManager.findFragmentByTag(tag) as? FormResultBridge ?: FormResultBridge().also {
        fragmentManager.beginTransaction().add(it, tag).commitNow()
    }
    bridge.startActivityForResult(Intent(this, LiveFormActivity::class.java).putExtra("exercise", formChoice.exercise)
        .putExtra("side", formChoice.side).putExtra("replay", replay), 1)
}

@Suppress("DEPRECATION")
class FormResultBridge : Fragment() {
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val host = activity as? MainActivity ?: return
        val json = data?.getStringExtra("metrics")
        if (requestCode == 1 && resultCode == Activity.RESULT_OK && json != null) {
            val result = FormResult(JSONObject(json))
            host.lastForm = result
            host.formResult(result.metrics, result)
        } else host.formHome()
    }
}

internal fun FormExercise.viewGuide() = when (view) {
    "front" -> R.string.form_camera_front
    "side" -> R.string.form_camera_side
    else -> R.string.form_camera_either
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
