package jumpscan.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Fake Health Connect data written by the test APK (debug builds hold the write permissions; release reads only). */
private class HealthFixture(private val test: Instrumentation) {
    val context = test.targetContext
    val zone: ZoneId = ZoneId.systemDefault()
    val now: Instant = Instant.now()
    private val today = LocalDate.now(zone).atStartOfDay(zone).toInstant()
    // Wake at 06:55 today, or just before now early in the morning, so the sleep always ends today.
    val wake: Instant = ZonedDateTime.of(LocalDate.now(zone), java.time.LocalTime.of(6, 55), zone).toInstant()
        .let { if (it.isBefore(now.minus(Duration.ofMinutes(30)))) it else now.minus(Duration.ofMinutes(5)) }
    val bed: Instant = wake.minus(Duration.ofMinutes(495))
    val workoutStart: Instant = now.minus(Duration.ofMinutes(60))
    private val client = HealthConnectClient.getOrCreate(context)
    private val types = healthTypes.map { it.first }
    private val writes = types.map { HealthPermission.getWritePermission(it) }

    /** `pm grant` works for Health Connect permissions on API 34. Revoking is left to the run script: it kills this process. */
    fun grant() = (HEALTH_READ + writes).forEach {
        val result = test.uiAutomation.executeShellCommand("pm grant ${context.packageName} $it")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(result).use { stream -> stream.readBytes() }
    }

    fun insert(resting: Boolean) = runBlocking {
        val offset = zone.rules.getOffset(now)
        // Night heart rate once a minute two hours after bedtime: the lowest 5-minute mean is 49.6, so the estimate is 50.
        val night = bed.plus(Duration.ofHours(2))
        val rates = listOf(60, 58, 50, 48, 49, 50, 51, 57, 59, 60)
        val records = mutableListOf<Record>(
            SleepSessionRecord(bed, offset, wake, offset, Metadata.manualEntry()),
            HeartRateRecord(night, offset, night.plus(Duration.ofMinutes(10)), offset,
                rates.mapIndexed { i, bpm -> HeartRateRecord.Sample(night.plus(Duration.ofMinutes(i.toLong())), bpm.toLong()) }, Metadata.manualEntry()),
            HeartRateVariabilityRmssdRecord(wake.minus(Duration.ofMinutes(10)), offset, 68.24, Metadata.manualEntry()),
            WeightRecord(now.minus(Duration.ofDays(1)), offset, Mass.kilograms(64.3), Metadata.manualEntry()),
            StepsRecord(maxOf(today, now.minus(Duration.ofMinutes(30))), offset, now.minus(Duration.ofSeconds(5)), offset, 4412, Metadata.manualEntry()),
            ExerciseSessionRecord(workoutStart, offset, workoutStart.plus(Duration.ofMinutes(42)), offset, Metadata.manualEntry(),
                ExerciseSessionRecord.EXERCISE_TYPE_RUNNING))
        if (resting) records += RestingHeartRateRecord(wake, offset, 53, Metadata.manualEntry())
        client.insertRecords(records)
    }

    /** Deletes only the records this app wrote. */
    fun clear() = runBlocking {
        types.forEach { client.deleteRecords(it, TimeRangeFilter.between(now.minus(Duration.ofDays(3)), now.plus(Duration.ofDays(1)))) }
    }

    fun clock(at: Instant): String = at.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
}

private fun <T : View> views(view: View, type: Class<T>): List<T> = when {
    type.isInstance(view) -> listOf(type.cast(view)!!)
    view is ViewGroup -> (0 until view.childCount).flatMap { views(view.getChildAt(it), type) }
    else -> emptyList()
}

private fun Instrumentation.waitFor(what: String, condition: () -> Boolean) {
    val end = SystemClock.uptimeMillis() + 15000
    var met = false
    while (!met && SystemClock.uptimeMillis() < end) { runOnMainSync { met = condition() }; if (!met) SystemClock.sleep(150) }
    check(met) { "timed out waiting for $what" }
}

private fun Instrumentation.click(host: MainActivity, label: String) = runOnMainSync {
    views(host.page, Button::class.java).first { it.text.toString() == label }.performClick()
}

/** SPEC 10.5: availability, denied access, the mapping of fake records into the check-in, weigh-in and Today. */
internal fun healthChecks(test: Instrumentation) {
    // Mapping and bounds without Health Connect.
    val sofia = ZoneId.of("Europe/Sofia")
    val bed = ZonedDateTime.of(2026, 11, 23, 22, 40, 0, 0, sofia).toInstant()
    val wake = ZonedDateTime.of(2026, 11, 24, 6, 55, 0, 0, sofia).toInstant()
    healthCheckin(52, 47, 68.24, bed to wake, sofia).let {
        check(it.restingHr == 52 && !it.estimated && it.hrv == 68.2 && it.bedtime == "22:40" && it.wakeTime == "06:55" && it.skipped.isEmpty())
    }
    healthCheckin(null, 47, null, null, sofia).let { check(it.restingHr == 47 && it.estimated && it.hrv == null && it.bedtime == null) }
    healthCheckin(160, null, 320.0, null, sofia).let {
        check(it.restingHr == null && it.hrv == null && it.skipped == listOf("resting_hr" to "160", "hrv_rmssd" to "320.0")) { "${it.skipped}" }
    }
    val run = HealthSession(wake, 42, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, null, "x")
    check(unknownSessions(listOf(run), listOf(wake.plusSeconds(299))).isEmpty())
    check(unknownSessions(listOf(run), listOf(wake.plusSeconds(360), wake.minusSeconds(400))) == listOf(run))

    val context = test.targetContext
    check(context.healthStatus() == HealthConnectClient.SDK_AVAILABLE) { "Health Connect status ${context.healthStatus()} on the API 34 emulator" }
    val fixture = HealthFixture(test)
    val account = context.getSharedPreferences("account", 0)
    val today = context.getSharedPreferences("today", 0)
    val health = context.getSharedPreferences("health", 0)
    account.edit().clear().putString("server", "http://127.0.0.1:9").putString("token", "fixture")
        .putString("name", "Ana Test").putLong("user_id", 7).commit()
    today.edit().clear().commit(); health.edit().clear().commit()
    var host = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    try {
        // Denied or revoked (the run script revokes before the checks): nothing is read, the Profile row offers to
        // connect, and a refused request reports no access.
        val reader = HealthReader(context)
        check(reader.granted().none { it in HEALTH_READ }) { "Health Connect access must be revoked before the checks" }
        reader.checkin(reader.granted()).let { check(it.restingHr == null && it.hrv == null && it.bedtime == null) }
        check(reader.steps(emptySet()) == null && reader.sessions(emptySet()).isEmpty() && reader.weight(emptySet()) == null)
        test.runOnMainSync { host.profile() }
        test.waitFor("profile row") { texts(host.page).contains(host.getString(R.string.hc_allowed_none)) }
        check(texts(host.page).contains(host.getString(R.string.hc_state_connect)))
        var refused: Set<String>? = null
        test.runOnMainSync { host.healthResult = { refused = it }; host.healthPermissionResult(Activity.RESULT_CANCELED, null) }
        check(refused == emptySet<String>() && host.healthResult == null)

        // Granted, with fake records and no resting value: the estimate from night heart rate fills the field.
        fixture.grant()
        fixture.clear()
        fixture.insert(resting = false)
        val granted = reader.granted()
        check(granted.containsAll(HEALTH_READ)) { "granted $granted" }
        reader.checkin(granted).let {
            check(it.restingHr == 50 && it.estimated && it.hrv == 68.2 && it.bedtime == fixture.clock(fixture.bed) &&
                it.wakeTime == fixture.clock(fixture.wake)) { "estimate ${it.restingHr} ${it.hrv} ${it.bedtime} ${it.wakeTime}" }
        }
        check(reader.weight(granted) == 64.3 && reader.steps(granted) == 4412L)
        val sessions = reader.sessions(granted)
        check(sessions.size == 1 && sessions[0].minutes == 42 && sessions[0].type == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING) { "sessions $sessions" }

        // The check-in form: tap Fill, the fields carry the values and the source label.
        test.runOnMainSync { host.checkin() }
        check(test.screenOf(host, "checkin") == "checkin")
        test.click(host, host.getString(R.string.hc_fill))
        test.waitFor("check-in prefill") { views(host.page, EditText::class.java)[2].text.isNotEmpty() }
        test.runOnMainSync {
            val fields = views(host.page, EditText::class.java).map { it.text.toString() }
            check(fields.subList(2, 6) == listOf("50", host.short(68.2), fixture.clock(fixture.bed), fixture.clock(fixture.wake))) { "fields $fields" }
            val shown = texts(host.page)
            for (label in listOf(host.getString(R.string.hc_resting_estimated), host.getString(R.string.rd_hrv_field),
                    host.getString(R.string.rd_bedtime_field), host.getString(R.string.rd_wake_field)))
                check(host.getString(R.string.hc_source, label) in shown) { "no source on $label in $shown" }
            // Editing a value removes its source label.
            views(host.page, EditText::class.java)[3].setText("70")
            check(host.getString(R.string.rd_hrv_field) in texts(host.page))
        }

        // A shared resting value wins over the estimate.
        runBlocking { HealthConnectClient.getOrCreate(context).insertRecords(listOf(RestingHeartRateRecord(fixture.wake,
            fixture.zone.rules.getOffset(fixture.wake), 53, Metadata.manualEntry()))) }
        reader.checkin(granted).let { check(it.restingHr == 53 && !it.estimated) }

        // The weigh-in field.
        lateinit var kg: EditText
        test.runOnMainSync { host.layout(host.getString(R.string.tool_weight), "weigh_check"); kg = host.decimalField(R.string.nutr_weight_kg); host.healthWeightButton(kg) }
        test.click(host, host.getString(R.string.hc_fill))
        test.waitFor("weight prefill") { kg.text.toString() == host.short(64.3) }
        test.runOnMainSync { check(host.getString(R.string.hc_source, host.getString(R.string.nutr_weight_kg)) in texts(host.page)) }

        // Today: steps in their own tile (SPEC 10.9) and the run offered as a session; turning it down keeps it away.
        context.saveToday(todayFixture())
        test.runOnMainSync { host.finish() }
        host = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        check(test.screenOf(host, "home") == "home")
        val offer = host.getString(R.string.hc_session_offer, host.getString(R.string.hc_title), host.getString(R.string.hc_run), 42)
        val steps = String.format(host.locale, "%,d", 4412)
        test.waitFor("Today suggestion and steps") { texts(host.page).let { offer in it && steps in it } }
        test.runOnMainSync {
            val tile = host.page.findViewWithTag<View>("card-steps")
            check(tile != null && steps in texts(tile)) { "steps tile missing" }
        }
        test.click(host, host.getString(R.string.hc_session_dismiss))
        check(context.handledSessions().any { Duration.between(it, fixture.workoutStart).abs().seconds < 1 })
        test.runOnMainSync { host.home() }
        test.waitFor("steps after reload") { steps in texts(host.page) }
        SystemClock.sleep(1000)
        test.runOnMainSync { check(offer !in texts(host.page)) { "dismissed session offered again" } }
    } finally {
        test.runOnMainSync { host.finish() }
        fixture.clear()
        account.edit().clear().commit(); today.edit().clear().commit(); health.edit().clear().commit()
    }
}

/** Health Connect screens against a seeded server, saved as files/health-<language>-<name>.png. */
internal class HealthStudio(private val test: Instrumentation, private val server: String) {
    fun screens() {
        val context = test.targetContext
        val fixture = HealthFixture(test)
        fixture.grant()
        fixture.clear()
        fixture.insert(resting = false)
        val login = JSONObject(api(server, "/api/auth/login", "POST", body = JSONObject().put("email", "athlete1@demo.test").put("password", "demo1234")))
        val user = login.getJSONObject("user")
        context.getSharedPreferences("account", 0).edit().clear().putString("server", server).putString("token", login.getString("token"))
            .putString("name", user.getString("name")).putLong("user_id", user.getLong("id")).commit()
        context.getSharedPreferences("today", 0).edit().clear().commit()
        context.getSharedPreferences("health", 0).edit().clear().commit()
        val host = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val language = host.resources.configuration.locales[0].language
        fun shot(name: String) {
            SystemClock.sleep(900)
            test.requireAppWindow(name)
            val bitmap = checkNotNull(test.uiAutomation.takeScreenshot())
            File(context.filesDir, "health-$language-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun scrollTo(view: View) = test.runOnMainSync {
            var top = 0; var at: View = view
            while (at !== host.page) { top += at.top; at = at.parent as View }
            (host.page.parent as ScrollView).scrollTo(0, (top - host.dp(24)).coerceAtLeast(0))
        }
        try {
            check(test.screenOf(host, "home") == "home")
            val offer = host.getString(R.string.hc_session_offer, host.getString(R.string.hc_title), host.getString(R.string.hc_run), 42)
            test.waitFor("Today suggestion") { offer in texts(host.page) && context.cachedToday() != null }
            shot("today")
            test.runOnMainSync { host.profile() }
            test.waitFor("profile row") { texts(host.page).any { it.startsWith(host.getString(R.string.hc_allowed, "").dropLast(1)) } }
            lateinit var row: View
            test.runOnMainSync { row = views(host.page, android.widget.TextView::class.java).first { it.text.toString() == host.getString(R.string.hc_title) } }
            scrollTo(row); shot("profile")
            test.runOnMainSync { host.checkin() }
            check(test.screenOf(host, "checkin") == "checkin")
            test.click(host, host.getString(R.string.hc_fill))
            test.waitFor("check-in prefill") { texts(host.page).contains(host.getString(R.string.hc_source, host.getString(R.string.hc_resting_estimated))) }
            lateinit var morning: View
            test.runOnMainSync { morning = views(host.page, android.widget.TextView::class.java).first { it.text.toString() == host.getString(R.string.rd_morning) } }
            scrollTo(morning); shot("checkin")
            test.runOnMainSync { host.sessionsScreen(todayLocal(), minutes = "42", healthStart = fixture.workoutStart) }
            check(test.screenOf(host, "sessions") == "sessions")
            lateinit var add: View
            test.runOnMainSync { add = views(host.page, android.widget.TextView::class.java).first { it.text.toString() == host.getString(R.string.hc_session_note) } }
            scrollTo(add.parent as View); shot("session")
        } finally {
            test.runOnMainSync { host.finish() }
            fixture.clear()
            context.getSharedPreferences("health", 0).edit().clear().commit()
        }
    }
}
