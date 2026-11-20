package jumpscan.app

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import org.json.JSONObject

internal fun MainActivity.diagnostics(message: String? = null) {
    val log = DiagnosticLog(this)
    val rows = log.read().asReversed()
    layout(getString(R.string.diagnostics), "diagnostics")
    text(getString(R.string.diag_privacy), 14)
    if (message != null) notice(message, caution = true)
    button(getString(R.string.diag_share)) {
        try { startActivity(Intent.createChooser(log.share(log.read()), getString(R.string.diag_share))) }
        catch (_: Exception) { diagnostics(getString(R.string.diag_share_error)) }
    }.isEnabled = rows.isNotEmpty()

    val jumps = rows.filter { it.completedJump() }.map { it.getInt("trial") }.toSet()
    val sets = rows.filter { it.completedSquat() && it.has("human_reps") }.map { it.getInt("trial") }.toSet()
    val panel = card()
    section(R.string.diag_phone_test, panel)
    val totals = row(panel, center = true)
    totals.addView(Dial(this).apply { show((jumps.size + sets.size) * 100 / 23) }, LinearLayout.LayoutParams(dp(80), dp(80)))
    text(getString(R.string.diag_progress, jumps.size, sets.size), 14, parent = totals).apply {
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) }
    }
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
                input.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
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
        var toggle: () -> Unit = {}
        val details = button(getString(R.string.diag_details)) { toggle() }.apply {
            styleButton(this, false, ActionTone.TONAL)
        }
        button(getString(R.string.diag_clear)) { diagClearSheet(log) }
        for (runRow in rows) runCard(runRow)
        val detailsPanel = card().apply { visibility = View.GONE }
        var expanded = false
        toggle = {
            expanded = !expanded
            if (expanded && detailsPanel.childCount == 0) {
                val table = TableLayout(this)
                detailsPanel.addView(table)
                fun line(values: List<String>, heading: Boolean = false) {
                    val tableRow = TableRow(this)
                    for ((index, value) in values.withIndex()) tableRow.addView(TextView(this).apply {
                        text = value
                        textSize = 13f
                        setTextColor(getColor(R.color.ink))
                        setPadding(dp(3), dp(8), dp(3), dp(8))
                        if (heading) instrumentType(display = true)
                    }, TableRow.LayoutParams(0, -2, if (index == 0) 1.25f else 1f))
                    if (!heading && table.childCount % 2 == 1) tableRow.setBackgroundColor(getColor(R.color.paper))
                    table.addView(tableRow)
                }
                line(listOf(getString(R.string.diag_run_column), getString(R.string.diag_frames_column),
                    getString(R.string.diag_time_column)), true)
                for (runRow in rows) {
                    val kind = getString(runTitle(runRow.getString("kind")))
                    val status = getString(when (runRow.getString("status")) {
                        "ok" -> R.string.diag_ok
                        "no_flight" -> R.string.diag_no_flight
                        "cancelled" -> R.string.cancel
                        else -> R.string.diag_error
                    })
                    val fps = runRow.optDouble("live_fps", runRow.optDouble("clip_fps"))
                    val frames = "${runRow.getInt("frames_decoded")} / ${runRow.getInt("frames_analysed")}" +
                        (if (runRow.has("dropped_frames")) " / ${runRow.getInt("dropped_frames")}" else "")
                    val time = getString(R.string.diag_seconds, runRow.getLong("wall_ms") / 1000.0)
                    line(listOf("$kind\n$status", frames,
                        time + if (fps.isFinite()) "\n" + getString(R.string.diag_fps, fps) else ""))
                }
            }
            detailsPanel.visibility = if (expanded) View.VISIBLE else View.GONE
        }
    }
}

private fun MainActivity.runCard(runRow: JSONObject) {
    val panel = card()
    val head = row(panel, center = true)
    text(getString(runTitle(runRow.getString("kind"))), 17, parent = head).apply {
        instrumentType(display = true)
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    }
    val status = when (runRow.getString("status")) {
        "ok" -> Triple(R.string.diag_ok, R.color.ready_surface, R.color.ready)
        "no_flight" -> Triple(R.string.diag_no_flight, R.color.notice, R.color.warning)
        "cancelled" -> Triple(R.string.cancel, R.color.hover, R.color.muted)
        else -> Triple(R.string.diag_error, R.color.hover, R.color.error)
    }
    statusChip(getString(status.first), status.second, status.third, head)
    val fps = runRow.optDouble("live_fps", runRow.optDouble("clip_fps"))
    val meta = buildString {
        append(getString(R.string.diag_wall)).append(" · ")
        append(getString(R.string.diag_seconds, runRow.getLong("wall_ms") / 1000.0))
        if (fps.isFinite()) append(" · ").append(getString(R.string.diag_fps, fps))
    }
    text(meta, 13, parent = panel).setPadding(0, dp(6), 0, 0)
}

private fun MainActivity.diagClearSheet(log: DiagnosticLog) {
    val dialog = Dialog(this)
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(16), dp(22), dp(24))
        background = raisedSurface()
    }
    text(getString(R.string.diag_clear_confirm), 16, parent = panel)
    button(getString(R.string.diag_clear), parent = panel) { dialog.dismiss(); log.clear(); diagnostics() }
    button(getString(R.string.cancel), parent = panel) { dialog.dismiss() }
    dialog.setContentView(ScrollView(this).apply { addView(panel) })
    dialog.window?.apply { setGravity(Gravity.BOTTOM); setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(-1, -2) }
    dialog.show()
    panel.arrive()
}
