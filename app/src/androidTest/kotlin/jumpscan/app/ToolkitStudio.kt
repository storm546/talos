package jumpscan.app

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Offline checks of the toolkit's queue contract and workout model (SPEC 9). */
internal fun toolkitChecks(test: Instrumentation) {
    val draft = WorkoutDraft(UUID.randomUUID().toString(), "2026-11-22T10:00:00Z", "2026-11-22", "Сила", 4, null, "бел.",
        mutableListOf(LiftExercise(1, "A", 90, 1, 60.0, mutableListOf(LiftSet("60", "5", done = true, previous = "57.5 × 5"))),
            LiftExercise(2, "B", 60, 1, null, mutableListOf(LiftSet())), LiftExercise(3, "C", 120, null, null, mutableListOf())))
    val copy = WorkoutDraft.fromJson(JSONObject(draft.toJson().toString()))
    check(copy.toJson().toString() == draft.toJson().toString())
    check(!draft.restsAfter(0) && draft.restsAfter(1) && draft.restsAfter(2))
    val root = test.targetContext.cacheDir.resolve("toolkit-checks")
    try {
        val queue = PendingUploads(root, "https://club.example", 1)
        val workout = JSONObject().put("started_at", draft.startedAt).put("ended_at", "2026-11-22T11:00:00Z").put("title", "Сила")
            .put("template_id", JSONObject.NULL).put("notes", "")
        val first = queue.enqueueFeature(FeatureWrite.WORKOUT, "2026-11-22", workout, draft.clientId)
        val set = setBody("2026-11-22", 9, null, listOf("1", "5", "60", ""))!!.put("workout_client_id", draft.clientId)
        val second = queue.enqueueFeature(FeatureWrite.SET, "2026-11-22", set, UUID.randomUUID().toString())
        check(first < second) // the workout is sent before its sets
        check(runCatching { queue.enqueueFeature(FeatureWrite.WORKOUT, "", JSONObject(workout.toString()).put("sets", 1),
            UUID.randomUUID().toString()) }.isFailure)
        val sent = mutableListOf<String>()
        queue.send(mutableMapOf(), featureSend = { _, path, body -> sent += path; check(!body.has("athlete_id")); 1L }) { 0L }
        check(sent == listOf("/api/workouts", "/api/set-logs"))
    } finally { root.deleteRecursively() }
}

/**
 * Toolkit screens against a real seeded server (fixtureServer argument, reached through a reverse tunnel).
 * Logs one workout of two exercises with three sets each through the app's own controls.
 */
internal class ToolkitStudio(private val test: Instrumentation, private val server: String) {
    private val context get() = test.targetContext

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    fun screens(): String {
        val login = JSONObject(api(server, "/api/auth/login", "POST", body = JSONObject().put("email", "athlete1@demo.test").put("password", "demo1234")))
        context.getSharedPreferences("account", 0).edit().clear().putString("server", server).putString("token", login.getString("token"))
            .putString("name", login.getJSONObject("user").getString("name")).putLong("user_id", login.getJSONObject("user").getLong("id")).commit()
        context.getSharedPreferences("workout", 0).edit().clear().commit()
        context.getSharedPreferences("tools", 0).edit().clear().putString("pinned", "workout,timer,calculators,jump").commit()
        val host = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val language = host.resources.configuration.locales[0].language
        val state = MainActivity::class.java.getDeclaredField("screen").apply { isAccessible = true }
        fun save(name: String) {
            val bitmap = checkNotNull(test.uiAutomation.takeScreenshot())
            File(context.filesDir, "$language-toolkit-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun wait(expected: String) {
            val end = SystemClock.uptimeMillis() + 30000
            var actual = ""
            do {
                test.runOnMainSync { actual = state.get(host).toString() }
                if (actual == expected) return
                SystemClock.sleep(150)
            } while (SystemClock.uptimeMillis() < end)
            error("expected $expected, got $actual")
        }
        fun shot(name: String, expected: String) {
            wait(expected); SystemClock.sleep(900); test.requireAppWindow(name); save(name)
        }
        fun scroll(fraction: Float) = test.runOnMainSync {
            (host.page.parent as ScrollView).let { it.scrollTo(0, (host.page.height * fraction).toInt()) }
        }
        fun click(predicate: (View) -> Boolean) = test.runOnMainSync { views(host.window.decorView).first(predicate).performClick() }
        fun button(label: Int) = click { it is Button && it.text == host.getString(label) }
        try {
            // Stage fix 5b366e4: a typed password shows as dots.
            test.runOnMainSync {
                host.login()
                val password = views(host.page).filterIsInstance<EditText>().first {
                    it.inputType and android.text.InputType.TYPE_MASK_VARIATION == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
                views(host.page).filterIsInstance<EditText>().first().setText("athlete1@demo.test")
                password.setText("demo1234")
                check(password.transformationMethod is android.text.method.PasswordTransformationMethod) { "password is not masked" }
            }
            shot("login-password", "login")
            test.runOnMainSync { host.toolsHub() }; shot("hub", "tools")
            scroll(1f); shot("hub-groups", "tools")
            // Workout: empty start, two exercises from the picker, three sets each.
            test.runOnMainSync { host.workoutStart() }; shot("workout-start", "workout_start")
            click { it.isClickable && views(it).any { v -> v is android.widget.TextView && v.text == host.getString(R.string.workout_empty) } }
            wait("workout")
            val names = listOf("Клякане със щанга", "Лежанка")
            for (name in names) {
                test.runOnMainSync { host.exercisePicker() }; wait("workout_pick")
                click { it.isClickable && views(it).any { v -> v is android.widget.TextView && v.text == name } && it !is ScrollView }
                wait("workout")
            }
            SystemClock.sleep(500)
            // Exactly three set rows per exercise, then type load, reps and RPE into each row's inputs.
            test.runOnMainSync {
                val draft = WorkoutDraft.load(host)!!
                for (ex in draft.exercises) {
                    while (ex.sets.size < 3) ex.sets += LiftSet()
                    while (ex.sets.size > 3) ex.sets.removeAt(ex.sets.lastIndex)
                }
                WorkoutDraft.save(host, draft)
                host.workoutActive()
            }
            wait("workout")
            test.runOnMainSync {
                val inputs = views(host.page).filterIsInstance<EditText>().dropLast(1) // the last one is the notes field
                check(inputs.size == 18) { "expected 18 set inputs, found ${inputs.size}" }
                inputs.chunked(3).forEachIndexed { row, (load, reps, rpe) ->
                    load.setText(if (row < 3) "${60 + row * 5}" else "40"); reps.setText("${8 - row % 3}"); rpe.setText("${7 + row % 3}")
                }
                views(host.page).filterIsInstance<EditText>().last().setText("E2E: two exercises, three sets each")
            }
            val ticks = mutableListOf<View>()
            test.runOnMainSync { ticks += views(host.page).filterIsInstance<ImageButton>() }
            check(ticks.size == 6) { "expected 6 set rows, found ${ticks.size}" }
            for (tick in ticks) { test.runOnMainSync { tick.performClick() }; SystemClock.sleep(150) }
            SystemClock.sleep(1500)
            check(TimerState.running && TimerState.kind == "rest") { "rest timer is not running" }
            scroll(.18f)
            shot("workout-rest", "workout")
            button(R.string.workout_finish)
            shot("finish-summary", "workout_summary")
            var summaryText = ""
            test.runOnMainSync { summaryText = views(host.page).filterIsInstance<android.widget.TextView>().joinToString("|") { it.text.toString() } }
            check(host.getString(R.string.feature_saved) in summaryText) { "workout was not saved on the server: $summaryText" }
            scroll(1f); shot("finish-summary-list", "workout_summary")
            // Calculators.
            test.runOnMainSync { host.calculators(0) }; shot("plates", "calculators")
            test.runOnMainSync { host.calculators(0); views(host.page).filterIsInstance<EditText>().first().setText("101") }
            shot("plates-impossible", "calculators")
            test.runOnMainSync { host.calculators(0); views(host.page).filterIsInstance<EditText>().first().setText("100") }
            test.runOnMainSync { host.calculators(1) }; shot("one-rm", "calculators")
            scroll(1f); shot("one-rm-table", "calculators")
            test.runOnMainSync { host.calculators(2) }; shot("warmup", "calculators")
            // Interval timer: setup, then running (Tabata) with the screen state captured mid-phase.
            test.runOnMainSync { host.intervalTimer("intervals") }; shot("timer-setup", "interval_timer")
            test.runOnMainSync { host.intervalTimer("tabata") }
            button(R.string.timer_start)
            SystemClock.sleep(7000)
            check(TimerState.running && TimerState.kind == "interval" && TimerState.index >= 1) { "interval timer is not running" }
            shot("timer-running", "interval_timer")
            test.runOnMainSync { host.timerCommand(TimerService.STOP) }
            SystemClock.sleep(1500)
            shot("timer-finished", "interval_timer")
            return "workout client ${WorkoutDraft.load(host)?.clientId ?: "sent"}"
        } finally {
            test.runOnMainSync { host.timerCommand(TimerService.STOP); host.finish() }
        }
    }
}
