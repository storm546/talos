@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package jumpscan.app

import android.content.Intent
import android.app.Activity
import android.app.Fragment
import android.os.Build
import android.provider.MediaStore
import android.widget.LinearLayout
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

internal fun MainActivity.formLabel(choice: FormChoice): String {
    val name = FormExercises[choice.exercise].label(locale.language == "bg")
    return when (choice.side) {
        "L" -> getString(R.string.form_named_side, name, getString(R.string.form_left))
        "R" -> getString(R.string.form_named_side, name, getString(R.string.form_right))
        else -> name
    }
}

private class FormGroup(val label: Int, val icon: String, val ids: Set<String>)

private val formGroups = listOf(
    FormGroup(R.string.ui_group_legs, "legs", setOf("squat", "single_leg_squat", "lunge", "split_squat",
        "lateral_lunge", "step_up", "calf_raise", "wall_sit")),
    FormGroup(R.string.ui_group_hinge, "hinge", setOf("glute_bridge", "romanian_deadlift")),
    FormGroup(R.string.ui_group_push, "push", setOf("push_up", "overhead_press", "dip", "lateral_raise")),
    FormGroup(R.string.ui_group_pull, "pull", setOf("pull_up", "bent_over_row", "biceps_curl")),
    FormGroup(R.string.ui_group_core, "core", setOf("plank", "side_plank")),
    FormGroup(R.string.ui_group_conditioning, "conditioning", emptySet()),
)

/** Exercises missing from the groups above fall into conditioning, so a catalogue addition is never hidden. */
private fun formGroup(id: String) = formGroups.firstOrNull { id in it.ids } ?: formGroups.last()
internal fun formIcon(id: String) = formGroup(id).icon

private fun MainActivity.exerciseFacts(exercise: FormExercise) = listOfNotNull(
    getString(when (exercise.view) { "front" -> R.string.form_front; "side" -> R.string.form_side; else -> R.string.ui_view_either }),
    if (exercise.sided) getString(R.string.ui_one_side) else null).joinToString(" · ")

internal fun MainActivity.unitLabel(unit: String) = unit.replace("torso", getString(R.string.ui_torso))

/** The value already carries its unit, so drop a "(°)" style suffix from the label. */
private fun MainActivity.repLabel(key: String) = getString(repFields.getValue(key)).replace(Regex(" \\((°|%|ms|мс)\\)$"), "")

internal fun MainActivity.formHome(message: String? = null) {
    layout(getString(R.string.form_title), "form")
    message?.let { notice(it, caution = true) }
    hero(getString(R.string.ui_form_hero), getString(R.string.form_modes_guide),
        getString(R.string.ui_form_eyebrow, FormExercises.all.size)).also { panel ->
        val links = row(panel)
        if (token().isNotEmpty()) button(getString(R.string.ui_form_history), parent = links) { techniqueHistory() }
            .layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(12) }
        lastForm?.let { result -> button(getString(R.string.last_result), parent = links) { formResult(result.metrics, result) }
            .layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(12); marginStart = dp(8) } }
    }
    notice(getString(R.string.ui_form_validated_note))
    val replay = BuildConfig.DEBUG && File(filesDir, LIVE_REPLAY_FILE).isFile
    if (replay) text(getString(R.string.ui_replay_hint), 13)
    for (group in formGroups) {
        val members = FormExercises.all.filter { formGroup(it.id) == group }
        if (members.isEmpty()) continue
        section(group.label).setPadding(0, dp(20), 0, 0)
        members.forEach { exerciseCard(it, group.icon, replay) }
    }
    button(getString(R.string.home)) { home() }
}

private fun MainActivity.exerciseCard(exercise: FormExercise, icon: String, replay: Boolean) {
    val panel = card()
    val head = row(panel, center = true)
    iconTile(icon, head)
    val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    head.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
    text(exercise.label(locale.language == "bg"), 18, parent = column).apply {
        instrumentType(display = true)
        setPadding(0, 0, 0, 0)
    }
    chip(getString(if (exercise.mode == "hold") R.string.ui_mode_hold else R.string.ui_mode_reps), row(column))
    text(exerciseFacts(exercise), 13, parent = column).setPadding(0, dp(6), 0, 0)
    var side = if (exercise.sided) formChoice.takeIf { it.exercise == exercise.id }?.side ?: "L" else null
    if (exercise.sided) {
        val sides = row(panel, 4)
        val toggles = listOf("L" to R.string.form_left, "R" to R.string.form_right).mapIndexed { index, (value, label) ->
            button(getString(label), parent = sides) { side = value }.apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(8); if (index > 0) marginStart = dp(8) }
                contentDescription = getString(R.string.ui_side_choice, getString(label))
            } to value
        }
        fun mark() = toggles.forEach { (toggle, value) ->
            toggle.isSelected = value == side
            toggle.background = shape(getColor(if (toggle.isSelected) R.color.hover else R.color.surface), 14,
                getColor(if (toggle.isSelected) R.color.accent else R.color.line))
            toggle.setTextColor(getColor(if (toggle.isSelected) R.color.accent else R.color.ink))
        }
        toggles.forEach { (toggle, value) -> toggle.setOnClickListener { side = value; mark() } }
        mark()
    }
    val actions = row(panel, 4)
    fun choose() {
            formChoice = FormChoice(exercise.id, side)
            getSharedPreferences("account", android.content.Context.MODE_PRIVATE).edit()
                .putString("exercise", exercise.id).putString("exercise_side", side).apply()
        }
    button(getString(R.string.ui_live), primary = true, parent = actions) { choose(); openLiveForm(false) }.apply {
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(10) }
        contentDescription = getString(R.string.ui_live_for, exercise.label(locale.language == "bg"))
        setCompoundDrawablesRelativeWithIntrinsicBounds(lineIcon("form", getColor(R.color.hero)), null, null, null)
        if (replay) setOnLongClickListener { choose(); openLiveForm(true); true }
    }
    button(getString(R.string.ui_from_video), parent = actions) { choose(); formVideo() }.apply {
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(10); marginStart = dp(8) }
        contentDescription = getString(R.string.ui_video_for, exercise.label(locale.language == "bg"))
    }
}

internal fun MainActivity.formVideo() {
    val exercise = FormExercises[formChoice.exercise]
    layout(getString(R.string.form_title), "form_video")
    hero(formLabel(formChoice), getString(exercise.viewGuide()), getString(R.string.ui_from_video).uppercase(locale)).also { panel ->
        button(getString(R.string.record), primary = true, parent = panel) {
            launchVideo(Intent(MediaStore.ACTION_VIDEO_CAPTURE).putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1)
                .putExtra(MediaStore.EXTRA_DURATION_LIMIT, 60), 12)
        }
        button(getString(R.string.pick_video), parent = panel) {
            val pick = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES)
                else Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            launchVideo(pick.setType("video/*"), 13)
        }
    }
    if (!exercise.signal.legacy) notice(getString(R.string.form_experimental), caution = true)
    text(getString(R.string.form_clip_limit), 14)
    text(getString(R.string.no_video_upload), 14)
    button(getString(R.string.ui_all_exercises)) { formHome() }
}

private fun median(values: List<Double>) = values.sorted().let { if (it.isEmpty()) null
    else if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }

internal fun MainActivity.formResult(metrics: JSONObject, result: FormResult? = null, error: String? = null) {
    layout(getString(if (result == null) R.string.form_result else R.string.ui_set_done), if (result == null) "form_detail" else "form_result")
    val exercise = FormExercises[metrics.getString("exercise")]
    val bg = locale.language == "bg"
    val head = row(page, 2, center = true)
    iconTile(formIcon(exercise.id), head)
    val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    head.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
    text(formLabel(FormChoice(exercise.id, if (metrics.isNull("side")) null else metrics.getString("side"))), 20, parent = column)
        .instrumentType(display = true)
    text(listOfNotNull(getString(if (metrics.getString("view") == "front") R.string.form_front else R.string.form_side),
        when (metrics.optString("source")) { "live" -> getString(R.string.ui_live); "video" -> getString(R.string.ui_video); else -> null },
        if (metrics.has("created_at")) localDateTime(metrics.getString("created_at"), locale) else null)
        .joinToString(" · "), 13, parent = column)
    val summary = metrics.getJSONObject("summary")
    if (exercise.mode == "hold") statGrid(listOf("hold_ms" to R.string.ui_hold, "in_band_ms" to R.string.ui_in_band).map { (key, label) ->
        Triple(getString(label), if (summary.isNull(key)) "—" else metricNumber(summary.getLong(key) / 1000.0, key, locale), getString(R.string.ui_seconds))
    }) else statGrid(listOf(Triple(getString(R.string.ui_complete), "${summary.getInt("repetition_count")}", ""),
        Triple(getString(R.string.ui_partial), "${summary.getInt("partial_count")}", ""),
        Triple(getString(R.string.ui_unclear), "${summary.getInt("unclear_count")}", "")), columns = 3)
    val warnings = metrics.getJSONArray("warnings").let { array -> List(array.length()) { array.getString(it) } }
    if (warnings.isNotEmpty()) notice(getString(R.string.ui_reviews) + "\n" + warnings.joinToString("\n") { getString(when (it) {
        "unclear_reps" -> R.string.form_unclear_warning
        "single_leg_view" -> R.string.form_one_leg_warning
        "low_visibility" -> R.string.low_visibility
        else -> R.string.unknown_warning
    }) }, caution = true)
    if (result != null) {
        error?.let { notice(it, caution = true) }
        result.status?.let { notice(getString(it), caution = it == R.string.feature_rejected) }
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
        text(getString(R.string.no_video_upload), 13)
    }
    val reps = metrics.getJSONArray("repetitions").objects()
    val medians = if (exercise.signal.legacy) repFields.keys.filter { !summary.isNull("median_$it") }.map { key ->
        Triple(repLabel(key), number(summary, "median_$key"), when {
            key.endsWith("_ms") -> getString(R.string.unit_ms)
            key == "flexion_asymmetry_pct" -> "%"
            else -> "°"
        })
    } else exercise.checks.mapNotNull { check ->
        median(reps.filter { it.getString("status") == "complete" }.mapNotNull { rep ->
            rep.optJSONObject("checks")?.takeIf { !it.isNull(check.key) }?.getDouble(check.key) })
            ?.let { Triple(if (bg) check.labelBg else check.labelEn, metricNumber(it, check.key, locale), unitLabel(check.unit)) }
    }
    if (medians.isNotEmpty()) {
        section(R.string.form_medians).setPadding(0, dp(20), 0, 0)
        statGrid(medians)
        if (!summary.isNull("worst_repetition")) text(getString(R.string.form_shallowest, summary.getInt("worst_repetition")), 13)
    }
    if (reps.isNotEmpty()) section(R.string.ui_repetitions).setPadding(0, dp(20), 0, 0)
    for (rep in reps) {
        val panel = card()
        val title = row(panel, center = true)
        text(getString(R.string.ui_rep_number, rep.getInt("index")), 17, parent = title).apply {
            instrumentType(display = true)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val status = rep.getString("status")
        chip(getString(when (status) {
            "complete" -> R.string.form_complete
            "partial" -> R.string.form_partial
            else -> R.string.form_unclear
        }), title, caution = status != "complete").apply { (layoutParams as LinearLayout.LayoutParams).topMargin = 0 }
        if (status != "unclear") {
            if (exercise.signal.legacy) for (key in repFields.keys) {
                if (!rep.isNull(key)) valueRow(repLabel(key), number(rep, key) + when {
                    key.endsWith("_ms") -> " " + getString(R.string.unit_ms)
                    key == "flexion_asymmetry_pct" -> "%"
                    else -> "°"
                }, panel)
            } else rep.optJSONObject("checks")?.let { checks ->
                for (check in exercise.checks) valueRow(if (bg) check.labelBg else check.labelEn,
                    if (checks.isNull(check.key)) getString(R.string.not_available)
                    else "${number(checks, check.key)} ${unitLabel(check.unit)}".replace(" °", "°"), panel)
            }
        }
        if (!rep.isNull("hip_below_knee")) text(getString(if (rep.getBoolean("hip_below_knee"))
            R.string.form_hip_below else R.string.form_hip_not_below), 13, parent = panel)
        val reasons = rep.getJSONArray("reasons")
        for (index in 0 until reasons.length()) text(getString(when (reasons.getString(index)) {
            "below_min_depth" -> if (exercise.signal.legacy) R.string.form_reason_depth else R.string.form_reason_range
            "incomplete_start" -> R.string.form_reason_start
            "incomplete_end" -> R.string.form_reason_end
            "tracking_gap" -> R.string.form_reason_gap
            "too_fast" -> R.string.form_reason_fast
            else -> R.string.form_reason_geometry
        }), 13, parent = panel)
    }
    if (result != null) button(getString(R.string.ui_another_set)) { formHome() }
    if (token().isNotEmpty()) button(getString(R.string.ui_form_history)) { techniqueHistory() }
    button(getString(R.string.home)) { home() }
}

private fun MainActivity.saveFormResult(result: FormResult, done: (Int?, String) -> Unit) {
    if (token().isEmpty()) return login()
    val body = JSONObject(result.metrics.toString())
    if (body.optString("source") != "live") body.remove("mode")
    saveFeature(FeatureWrite.FORM, body, clientId = result.clientId, done = done)
}

@Suppress("DEPRECATION")
internal fun MainActivity.openLiveForm(replay: Boolean, trial: PhoneTrial? = null) {
    val tag = "live_form_result"
    val bridge = fragmentManager.findFragmentByTag(tag) as? FormResultBridge ?: FormResultBridge().also {
        fragmentManager.beginTransaction().add(it, tag).commitNow()
    }
    val choice = if (trial == null) formChoice else FormChoice("squat", null)
    bridge.startActivityForResult(Intent(this, LiveFormActivity::class.java).putExtra("exercise", choice.exercise)
        .putExtra("side", choice.side).putExtra("replay", replay).putExtra("phone_trial", trial?.number ?: 0),
        if (trial == null) 1 else 2)
}

@Suppress("DEPRECATION")
class FormResultBridge : Fragment() {
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val host = activity as? MainActivity ?: return
        val json = data?.getStringExtra("metrics")
        if (resultCode == Activity.RESULT_OK && json != null) {
            val result = FormResult(JSONObject(json))
            host.lastForm = result
            if (requestCode != 2) host.formResult(result.metrics, result)
        } else if (requestCode != 2) host.formHome()
        if (requestCode == 2) host.diagnostics()
    }
}

internal fun FormExercise.viewGuide() = when (view) {
    "front" -> R.string.form_camera_front
    "side" -> R.string.form_camera_side
    else -> R.string.form_camera_either
}
