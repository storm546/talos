package jumpscan.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.text.InputType
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView

internal fun MainActivity.diagnostics(message: String? = null) {
    val log = DiagnosticLog(this)
    val rows = log.read().asReversed()
    layout(getString(R.string.diagnostics), "diagnostics")
    text(getString(R.string.diag_privacy), 14)
    if (message != null) notice(message, caution = true)
    button(getString(R.string.diag_share), primary = true) {
        try { startActivity(Intent.createChooser(log.share(log.read()), getString(R.string.diag_share))) }
        catch (_: Exception) { diagnostics(getString(R.string.diag_share_error)) }
    }.isEnabled = rows.isNotEmpty()

    val jumps = rows.filter { it.completedJump() }.map { it.getInt("trial") }.toSet()
    val sets = rows.filter { it.completedSquat() && it.has("human_reps") }.map { it.getInt("trial") }.toSet()
    val panel = card()
    section(R.string.diag_phone_test, panel)
    text(getString(R.string.diag_progress, jumps.size, sets.size), 14, parent = panel)
    val next = (1..23).firstOrNull { it !in jumps && it !in sets }
    when (next) {
        null -> text(getString(R.string.diag_done), parent = panel)
        in 1..20 -> {
            val trial = PhoneTrial(checkNotNull(next))
            val view = getString(if (trial.view == "front") R.string.diag_front else R.string.diag_side)
            text(getString(R.string.diag_jump_step, trial.number, view, trial.plannedFps), parent = panel)
            text(getString(R.string.diag_setup), 14, parent = panel)
            text(getString(R.string.diag_jump_instructions), 14, parent = panel)
            rows.firstOrNull { it.optInt("trial") == next && it.has("clip_fps") }?.let { attempt ->
                if (attempt.getString("status") in listOf("ok", "no_flight")) text(
                    getString(R.string.diag_wrong_fps, attempt.getDouble("clip_fps"), attempt.getInt("trial_target_fps")),
                    14, parent = panel)
            }
            button(getString(R.string.diag_pick_fps, trial.plannedFps), primary = true, parent = panel) {
                pickPhoneTrial(trial.copy(targetFps = trial.plannedFps))
            }
            if (trial.plannedFps == 60) button(getString(R.string.diag_fallback), parent = panel) {
                pickPhoneTrial(trial.copy(targetFps = 30))
            }
        }
        else -> {
            val trial = PhoneTrial(next)
            text(getString(R.string.diag_squat_step, trial.number - 20), parent = panel)
            val result = rows.firstOrNull { it.optInt("trial") == next && it.completedSquat() }
            if (result == null) {
                text(getString(R.string.diag_squat_guide), 14, parent = panel)
                button(getString(R.string.diag_start_live), primary = true, parent = panel) { openLiveForm(false, trial) }
                button(getString(R.string.diag_pick_set), parent = panel) { pickPhoneTrial(trial) }
            } else {
                val input = field(getString(R.string.diag_count_label), type = InputType.TYPE_CLASS_NUMBER, parent = panel)
                input.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
                button(getString(R.string.diag_save_count), primary = true, parent = panel) {
                    val count = input.text.toString().trim().toIntOrNull()
                    if (count == null || count < 0) input.error = getString(R.string.diag_count_error)
                    else { log.saveCount(trial.number, count); diagnostics() }
                }
            }
        }
    }

    section(R.string.diag_recent)
    if (rows.isEmpty()) text(getString(R.string.diag_empty), 14)
    else {
        val table = TableLayout(this)
        card().addView(table)
        fun line(values: List<String>, heading: Boolean = false) {
            val row = TableRow(this@diagnostics)
            for ((index, value) in values.withIndex()) row.addView(TextView(this@diagnostics).apply {
                text = value
                textSize = 13f
                setTextColor(getColor(R.color.ink))
                setPadding(dp(3), dp(8), dp(3), dp(8))
                if (heading) setTypeface(null, Typeface.BOLD)
            }, TableRow.LayoutParams(0, -2, if (index == 0) 1.25f else 1f))
            if (!heading && table.childCount % 2 == 1) row.setBackgroundColor(getColor(R.color.paper))
            table.addView(row)
        }
        line(listOf(getString(R.string.diag_run_column), getString(R.string.diag_frames_column), getString(R.string.diag_time_column)), true)
        for (row in rows) {
            val kind = getString(runTitle(row.getString("kind")))
            val status = getString(when (row.getString("status")) {
                "ok" -> R.string.diag_ok
                "no_flight" -> R.string.diag_no_flight
                "cancelled" -> R.string.cancel
                else -> R.string.diag_error
            })
            val fps = row.optDouble("live_fps", row.optDouble("clip_fps"))
            val frames = "${row.getInt("frames_decoded")} / ${row.getInt("frames_analysed")}" +
                (if (row.has("dropped_frames")) " / ${row.getInt("dropped_frames")}" else "")
            val time = getString(R.string.diag_seconds, row.getLong("wall_ms") / 1000.0)
            line(listOf("$kind\n$status", frames, time + if (fps.isFinite()) "\n" + getString(R.string.diag_fps, fps) else ""))
        }
        button(getString(R.string.diag_clear)) {
            AlertDialog.Builder(this).setMessage(R.string.diag_clear_confirm)
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.diag_clear) { _, _ ->
                    log.clear()
                    diagnostics()
                }.show()
        }
    }
    button(getString(R.string.ui_profile)) { profile() }
}
