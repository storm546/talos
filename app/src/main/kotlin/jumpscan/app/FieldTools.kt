package jumpscan.app

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.widget.LinearLayout
import jumpscan.core.RangeOfMotion
import org.json.JSONObject
import java.util.UUID

// Setup and result screens of the wave-3 field tools (SPEC 9.3). The camera work is in CameraToolActivity,
// GPS recording in CardioService; these screens explain, launch and save.

internal const val FIELD_TOOL_REQUEST = 50

/**
 * One field result waiting on the result screen; [status] follows the queue like a form set. [local] holds phone-only
 * details (sway velocity and noise floor, how the cue time was taken) that are shown but never uploaded.
 */
internal class FieldRun(val body: JSONObject, val clientId: String = UUID.randomUUID().toString(), var status: Int? = null,
                        val realtime: Boolean = true, val local: JSONObject = JSONObject())

/** A top-down placement drawing: the phone, its view cone and the athlete (side/front) or the timing line. */
internal class PlacementSketch(context: Context, private val kind: String) : View(context) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat(); val h = height.toFloat()
        val ink = context.getColor(R.color.on_hero); val signal = context.getColor(R.color.signal)
        pen.strokeCap = Paint.Cap.ROUND
        fun phone(x: Float, y: Float) {
            pen.style = Paint.Style.FILL; pen.color = signal
            canvas.drawRoundRect(RectF(x - 7 * d, y - 12 * d, x + 7 * d, y + 12 * d), 3 * d, 3 * d, pen)
        }
        fun cone(x: Float, y: Float, toX: Float, spread: Float) {
            pen.style = Paint.Style.FILL; pen.color = signal; pen.alpha = 40
            canvas.drawPath(Path().apply { moveTo(x, y); lineTo(toX, y - spread); lineTo(toX, y + spread); close() }, pen)
            pen.alpha = 255
        }
        if (kind == "line") {
            // Lanes from start (left) to the finish line at the phone; the camera looks across the line.
            pen.style = Paint.Style.STROKE; pen.strokeWidth = 1.5f * d; pen.color = ink; pen.alpha = 90
            for (lane in 0..2) canvas.drawLine(16 * d, h * (.3f + lane * .2f), w - 16 * d, h * (.3f + lane * .2f), pen)
            pen.alpha = 255; pen.strokeWidth = 3 * d; pen.color = signal
            canvas.drawLine(w * .72f, h * .18f, w * .72f, h * .82f, pen)
            pen.strokeWidth = 2 * d; pen.color = ink
            canvas.drawLine(w * .18f, h * .18f, w * .18f, h * .82f, pen)
            phone(w * .72f, h * .08f + 12 * d)
            pen.style = Paint.Style.FILL; pen.color = ink
            canvas.drawCircle(w * .3f, h * .5f, 7 * d, pen)
            pen.style = Paint.Style.STROKE; pen.strokeWidth = 2 * d
            canvas.drawLine(w * .36f, h * .5f, w * .6f, h * .5f, pen)
            canvas.drawLine(w * .6f, h * .5f, w * .56f, h * .45f, pen); canvas.drawLine(w * .6f, h * .5f, w * .56f, h * .55f, pen)
            return
        }
        // Phone on the left, athlete 2-3 m away; side view shows a profile, front view a facing figure.
        val px = w * .16f; val ax = w * .7f; val cy = h / 2
        cone(px, cy, ax + 30 * d, h * .38f)
        phone(px, cy)
        pen.style = Paint.Style.FILL; pen.color = ink
        if (kind == "side") {
            canvas.drawOval(RectF(ax - 9 * d, cy - 20 * d, ax + 9 * d, cy + 20 * d), pen) // shoulders seen end-on
            pen.color = signal; canvas.drawCircle(ax, cy, 4 * d, pen)
        } else {
            canvas.drawOval(RectF(ax - 6 * d, cy - 26 * d, ax + 6 * d, cy + 26 * d), pen) // shoulders across the view
            pen.color = signal; canvas.drawCircle(ax - 10 * d, cy, 4 * d, pen)
        }
        pen.color = ink; pen.alpha = 160; pen.style = Paint.Style.STROKE; pen.strokeWidth = 1.5f * d
        pen.pathEffect = android.graphics.DashPathEffect(floatArrayOf(4 * d, 4 * d), 0f)
        canvas.drawLine(px + 14 * d, h - 10 * d, ax, h - 10 * d, pen)
        pen.pathEffect = null; pen.alpha = 255
    }
}

private fun MainActivity.toolHeader(title: Int, subtitle: String, sketch: String) {
    val panel = card(hero = true)
    panel.addView(PlacementSketch(this, sketch), LinearLayout.LayoutParams(-1, dp(120)))
    text(getString(title), 20, getColor(R.color.on_hero), panel).instrumentType(display = true)
    text(subtitle, 14, getColor(R.color.on_hero), panel)
}

private fun MainActivity.launchCameraTool(mode: String, extras: Intent.() -> Unit = {}) {
    startActivityForResult(Intent(this, CameraToolActivity::class.java).putExtra("mode", mode).apply(extras), FIELD_TOOL_REQUEST)
}

private val ROM_JOINTS = listOf("knee_flexion", "hip_flexion", "shoulder_flexion", "shoulder_abduction")

internal fun MainActivity.romSetup(joint: String = getSharedPreferences("field", 0).getString("joint", "knee_flexion")!!) {
    getSharedPreferences("field", 0).edit().putString("joint", joint).apply()
    layout(getString(R.string.ft_rom), "field_rom")
    val side = RangeOfMotion.Joint.of(joint).view == jumpscan.core.JumpAnalyzer.View.SIDE
    segments(ROM_JOINTS.take(2).map { getString(romLabel(it)) }, ROM_JOINTS.indexOf(joint)) { romSetup(ROM_JOINTS[it]) }
    segments(ROM_JOINTS.drop(2).map { getString(romLabel(it)) }, ROM_JOINTS.indexOf(joint) - 2) { romSetup(ROM_JOINTS[it + 2]) }
    toolHeader(romLabel(joint), getString(if (side) R.string.ft_rom_setup_side else R.string.ft_rom_setup_front), if (side) "side" else "front")
    text(getString(R.string.ft_rom_how), 14)
    notice(getString(if (joint == "knee_flexion") R.string.ft_rom_error_knee else R.string.ft_rom_error_other))
    button(getString(R.string.ft_open_camera), primary = true) { launchCameraTool("rom") { putExtra("joint", joint) } }
        .apply { minHeight = dp(56); textSize = 17f }
}

internal fun MainActivity.balanceSetup() {
    layout(getString(R.string.ft_balance), "field_balance")
    toolHeader(R.string.ft_balance, getString(R.string.ft_balance_setup), "front")
    text(getString(R.string.ft_balance_how), 14)
    notice(getString(R.string.ft_balance_limits))
    button(getString(R.string.ft_open_camera), primary = true) { launchCameraTool("balance") }.apply { minHeight = dp(56); textSize = 17f }
}

private val SPEED_MODES = listOf("sprint", "agility", "reaction")

internal fun MainActivity.speedSetup(mode: String = getSharedPreferences("field", 0).getString("speed", "sprint")!!) {
    val prefs = getSharedPreferences("field", 0)
    prefs.edit().putString("speed", mode).apply()
    layout(getString(R.string.ft_speed), "field_speed")
    segments(listOf(R.string.ft_sprint, R.string.ft_agility, R.string.ft_reaction).map(::getString), SPEED_MODES.indexOf(mode)) { speedSetup(SPEED_MODES[it]) }
    when (mode) {
        "sprint" -> {
            val start = prefs.getString("start", "cue")!!
            toolHeader(R.string.ft_sprint, getString(if (start == "cue") R.string.ft_sprint_setup_cue else R.string.ft_sprint_setup_crossing), "line")
            segments(listOf(R.string.ft_start_cue, R.string.ft_start_crossing).map(::getString), if (start == "cue") 0 else 1) {
                prefs.edit().putString("start", if (it == 0) "cue" else "crossing").apply(); speedSetup("sprint")
            }
            val input = field(getString(R.string.ft_distance_m), prefs.getString("distance", "20")!!, android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL)
            notice(getString(R.string.ft_timing_limits))
            button(getString(R.string.ft_open_camera), primary = true) {
                val metres = decimal(input.value())?.takeIf { it in 1.0..400.0 }
                if (metres == null) { snack(getString(R.string.ft_distance_range)); return@button }
                prefs.edit().putString("distance", input.value()).apply()
                launchCameraTool("sprint") { putExtra("distance", metres); putExtra("start", start) }
            }.apply { minHeight = dp(56); textSize = 17f }
        }
        "agility" -> {
            val test = prefs.getString("agility", "505")!!
            segments(listOf(R.string.ft_505, R.string.ft_t_test).map(::getString), if (test == "505") 0 else 1) {
                prefs.edit().putString("agility", if (it == 0) "505" else "t_test").apply(); speedSetup("agility")
            }
            toolHeader(if (test == "505") R.string.ft_505 else R.string.ft_t_test,
                getString(if (test == "505") R.string.ft_505_setup else R.string.ft_t_test_setup), "line")
            notice(getString(R.string.ft_timing_limits))
            button(getString(R.string.ft_open_camera), primary = true) { launchCameraTool("agility") { putExtra("test", test) } }
                .apply { minHeight = dp(56); textSize = 17f }
        }
        else -> {
            toolHeader(R.string.ft_reaction, getString(R.string.ft_reaction_setup), "front")
            notice(getString(R.string.ft_reaction_limits))
            button(getString(R.string.ft_open_camera), primary = true) { launchCameraTool("reaction") }.apply { minHeight = dp(56); textSize = 17f }
        }
    }
}

private fun MainActivity.seconds(ms: Number, digits: Int = 2) = String.format(locale, "%.${digits}f", ms.toDouble() / 1000)
private fun MainActivity.degrees(value: Double?) = value?.let { String.format(locale, "%.0f°", it) } ?: "—"

internal fun MainActivity.fieldResult(run: FieldRun, error: String? = null) {
    lastField = run
    val body = run.body
    val kind = body.getString("kind")
    layout(getString(R.string.ft_result), "field_result")
    val panel = card(hero = true)
    fun big(value: String, unit: String) {
        val line = row(panel, center = true)
        line.addView(Measurement(this).apply {
            text = value; textSize = 56f; setTextColor(getColor(R.color.signal)); instrumentType(numeric = true); includeFontPadding = false
        })
        text(unit, 18, getColor(R.color.on_hero), line).setPadding(dp(8), dp(14), 0, 0)
    }
    fun caption(value: String) = text(value, 14, getColor(R.color.on_hero), panel)
    when (kind) {
        "rom" -> {
            val left = if (body.isNull("left_deg")) null else body.getDouble("left_deg")
            val right = if (body.isNull("right_deg")) null else body.getDouble("right_deg")
            text(getString(romLabel(body.getString("joint"))), 20, getColor(R.color.on_hero), panel).instrumentType(display = true)
            val (l, r) = columns(panel)
            for ((column, label, value) in listOf(Triple(l, R.string.ft_left, left), Triple(r, R.string.ft_right, right))) {
                text(getString(label), 13, getColor(R.color.on_hero), column)
                column.addView(Measurement(this).apply { text = degrees(value); textSize = 48f; setTextColor(getColor(R.color.signal)); instrumentType(numeric = true) })
            }
            caption(getString(R.string.ft_error_line, degrees(body.getDouble("error_deg"))))
            if (left != null && right != null) caption(getString(if (RangeOfMotion.withinError(left, right)) R.string.ft_diff_within else R.string.ft_diff_outside,
                degrees(kotlin.math.abs(left - right))))
            if (left == null || right == null) caption(getString(R.string.ft_one_side))
            val warnings = body.getJSONArray("warnings").let { a -> List(a.length()) { a.getString(it) } }
            if ("not_validated" in warnings) notice(getString(R.string.ft_rom_error_other), caution = true)
            if ("low_visibility" in warnings) notice(getString(R.string.low_visibility), caution = true)
        }
        "balance" -> {
            text(getString(R.string.ft_balance_leg, getString(if (body.getString("leg") == "L") R.string.ft_left else R.string.ft_right)),
                20, getColor(R.color.on_hero), panel).instrumentType(display = true)
            big(seconds(body.getLong("stance_ms"), 1), getString(R.string.ui_seconds))
            caption(getString(R.string.ft_ended_by, getString(when (body.getString("ended_by")) {
                "touch_down" -> R.string.ft_end_touch; "sway" -> R.string.ft_end_sway; "time_limit" -> R.string.ft_end_limit
                "lost_tracking" -> R.string.ft_end_lost; else -> R.string.ft_end_stopped })))
            if (!body.isNull("sway_pct")) caption(getString(R.string.ft_sway_line, String.format(locale, "%.1f", body.getDouble("sway_pct"))))
            val velocity = run.local.optDouble("sway_velocity_pct").takeIf { it.isFinite() }
            val floor = run.local.optDouble("noise_floor_pct").takeIf { it.isFinite() }
            if (velocity != null) caption(if (floor != null) getString(R.string.ft_sway_speed_line,
                String.format(locale, "%.1f", (velocity - floor).coerceAtLeast(0.0)), String.format(locale, "%.1f", floor))
                else getString(R.string.ft_sway_speed_no_floor, String.format(locale, "%.1f", velocity)))
            text(getString(R.string.ft_balance_limits), 13)
            text(getString(R.string.ft_balance_other_leg), 14, getColor(R.color.ink))
        }
        "cardio" -> cardioSummary(body, panel)
        else -> {
            val title = when (kind) {
                "sprint" -> getString(R.string.ft_sprint_named, String.format(locale, "%.0f", body.getDouble("distance_m")))
                "agility" -> getString(if (body.getString("test") == "t_test") R.string.ft_t_test else R.string.ft_505)
                else -> getString(R.string.ft_reaction)
            }
            text(title, 20, getColor(R.color.on_hero), panel).instrumentType(display = true)
            big(seconds(body.getLong("time_ms"), if (kind == "reaction") 3 else 2), getString(R.string.ui_seconds))
            caption(getString(R.string.ft_precision_line, String.format(locale, "%.0f", body.getDouble("precision_ms")),
                String.format(locale, "%.0f", body.getDouble("fps"))))
            if (kind == "reaction" || body.optString("start") == "cue") notice(getString(
                if (run.local.optString("cue_clock") == "audio") R.string.ft_cue_note_audio else R.string.ft_cue_note), caution = true)
            if (!run.realtime) text(getString(R.string.ft_clock_note), 13)
        }
    }
    error?.let { notice(it, caution = true) }
    run.status?.let { notice(getString(it), caution = it == R.string.feature_rejected) }
    if (run.status !in listOf(R.string.feature_saved, R.string.saved_pending)) {
        button(getString(if (token().isEmpty()) R.string.sign_in_upload else R.string.ft_save), primary = true) {
            saveFeature(FeatureWrite.MEASUREMENT, JSONObject(body.toString()), clientId = run.clientId) { status, message ->
                run.status = status
                fieldResult(run, if (status == null) message else null)
            }
        }.apply { minHeight = dp(56); textSize = 17f }
    }
    if (run.status == R.string.saved_pending) pendingControls()
    button(getString(R.string.ft_again)) {
        when (kind) { "rom" -> romSetup(); "balance" -> balanceSetup(); "cardio" -> cardioHome(); else -> speedSetup(kind) }
    }
    text(getString(if (kind == "cardio") R.string.ft_route_stays else R.string.no_video_upload), 13)
}

/** Result of [CameraToolActivity]: show it with a save button. */
internal fun MainActivity.cameraToolResult(data: Intent?) {
    val json = data?.getStringExtra("measurement") ?: return formHome()
    fieldResult(FieldRun(JSONObject(json), realtime = data?.getBooleanExtra("realtime", true) ?: true,
        local = data?.getStringExtra("local")?.let(::JSONObject) ?: JSONObject()))
}
