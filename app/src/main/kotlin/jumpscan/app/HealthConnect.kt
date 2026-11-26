package jumpscan.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import jumpscan.core.NightHeartRate
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.reflect.KClass

// Health Connect (SPEC 10.5). Read only: Talos never writes to it. What is read stays on the phone until the athlete
// saves a check-in, weigh-in or training session through the normal routes; steps are never uploaded.

internal const val HEALTH_REQUEST = 60
internal const val HEALTH_PROVIDER = "com.google.android.apps.healthdata"
private const val SAMSUNG_HEALTH = "com.sec.android.app.shealth"

/** Read permission of each record type, with the words the Profile row lists it by. */
internal val healthTypes: List<Pair<KClass<out Record>, Int>> = listOf(RestingHeartRateRecord::class to R.string.hc_p_resting,
    HeartRateRecord::class to R.string.hc_p_heart, HeartRateVariabilityRmssdRecord::class to R.string.hc_p_hrv,
    SleepSessionRecord::class to R.string.hc_p_sleep, StepsRecord::class to R.string.hc_p_steps,
    WeightRecord::class to R.string.hc_p_weight, ExerciseSessionRecord::class to R.string.hc_p_exercise)
internal val HEALTH_READ: Set<String> = healthTypes.map { HealthPermission.getReadPermission(it.first) }.toSet()

internal fun Context.healthStatus() = HealthConnectClient.getSdkStatus(this, HEALTH_PROVIDER)

/** Check-in values from Health Connect, already inside the server's bounds (server/main.py Checkin). */
internal class HealthCheckin(val restingHr: Int?, val estimated: Boolean, val hrv: Double?, val bedtime: String?,
                             val wakeTime: String?, val skipped: List<Pair<String, String>>)

/** A value outside the server's bounds is skipped with a note, never clamped. */
internal fun healthCheckin(resting: Long?, estimate: Int?, rmssd: Double?, sleep: Pair<Instant, Instant>?, zone: ZoneId): HealthCheckin {
    val skipped = mutableListOf<Pair<String, String>>()
    val hr = resting?.toInt() ?: estimate
    val hrv = rmssd?.let { Math.round(it * 10) / 10.0 }
    if (hr != null && hr !in 25..150) skipped += "resting_hr" to "$hr"
    if (hrv != null && !(hrv > 0 && hrv <= 300)) skipped += "hrv_rmssd" to "$hrv"
    val clock = DateTimeFormatter.ofPattern("HH:mm")
    return HealthCheckin(hr?.takeIf { it in 25..150 }, resting == null && hr != null, hrv?.takeIf { it > 0 && it <= 300 },
        sleep?.first?.atZone(zone)?.format(clock), sleep?.second?.atZone(zone)?.format(clock), skipped)
}

internal class HealthSession(val start: Instant, val minutes: Int, val type: Int, val title: String?, val origin: String)

/** Sessions whose start is not within 5 minutes of a start Talos already knows. */
internal fun unknownSessions(sessions: List<HealthSession>, known: List<Instant>) =
    sessions.filter { session -> known.none { Duration.between(it, session.start).abs().seconds <= 300 } }

internal class HealthReader(context: Context, private val zone: ZoneId = ZoneId.systemDefault()) {
    private val client by lazy { HealthConnectClient.getOrCreate(context) }
    private fun startOf(day: LocalDate) = day.atStartOfDay(zone).toInstant()

    fun granted(): Set<String> = runBlocking { client.permissionController.getGrantedPermissions() }

    private fun <T : Record> read(type: KClass<T>, granted: Set<String>, from: Instant, to: Instant): List<T> =
        if (HealthPermission.getReadPermission(type) !in granted) emptyList()
        else runBlocking { client.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(from, to))).records }

    /** Resting HR (latest in 24 h, else the night estimate), HRV (latest night or morning value in 24 h), longest sleep ending today. */
    fun checkin(granted: Set<String>, now: Instant = Instant.now()): HealthCheckin {
        val today = startOf(LocalDate.now(zone))
        val sleep = read(SleepSessionRecord::class, granted, today.minus(Duration.ofDays(1)), now)
            .filter { it.endTime >= today && it.endTime <= now }.maxByOrNull { Duration.between(it.startTime, it.endTime) }
        val resting = read(RestingHeartRateRecord::class, granted, now.minus(Duration.ofHours(24)), now).maxByOrNull { it.time }
        val estimate = if (resting != null || sleep == null) null else NightHeartRate.restingEstimate(
            read(HeartRateRecord::class, granted, sleep.startTime, sleep.endTime).flatMap { it.samples }
                .filter { it.time >= sleep.startTime && it.time <= sleep.endTime }.map { it.time.toEpochMilli() to it.beatsPerMinute.toDouble() })
        val hrv = read(HeartRateVariabilityRmssdRecord::class, granted, now.minus(Duration.ofHours(24)), now)
            .filter { it.time.atZone(zone).hour !in 12..19 }.maxByOrNull { it.time }
        return healthCheckin(resting?.beatsPerMinute, estimate, hrv?.heartRateVariabilityMillis, sleep?.let { it.startTime to it.endTime }, zone)
    }

    /** The latest weight of the last 7 days in kg, rounded to 0.1. */
    fun weight(granted: Set<String>, now: Instant = Instant.now()): Double? =
        read(WeightRecord::class, granted, now.minus(Duration.ofDays(7)), now).maxByOrNull { it.time }
            ?.let { Math.round(it.weight.inKilograms * 10) / 10.0 }

    /** Today's steps; the aggregate removes duplicates between apps. */
    fun steps(granted: Set<String>, now: Instant = Instant.now()): Long? =
        if (HealthPermission.getReadPermission(StepsRecord::class) !in granted) null
        else runBlocking { client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL),
            TimeRangeFilter.between(startOf(LocalDate.now(zone)), now)))[StepsRecord.COUNT_TOTAL] }

    /** Exercise sessions of today and yesterday that fit a training session (1 to 600 minutes). */
    fun sessions(granted: Set<String>, now: Instant = Instant.now()): List<HealthSession> =
        read(ExerciseSessionRecord::class, granted, startOf(LocalDate.now(zone).minusDays(1)), now).map {
            HealthSession(it.startTime, Duration.between(it.startTime, it.endTime).toMinutes().toInt(), it.exerciseType, it.title,
                it.metadata.dataOrigin.packageName)
        }.filter { it.minutes in 1..600 }
}

/** Starts of Health Connect sessions the athlete already added or turned down, kept on the phone. */
internal fun Context.handledSessions(): List<Instant> = getSharedPreferences("health", Context.MODE_PRIVATE)
    .getString("handled", "").orEmpty().split(',').mapNotNull { it.toLongOrNull()?.let(Instant::ofEpochSecond) }

internal fun Context.handleSession(start: Instant) {
    val kept = (handledSessions() + start).takeLast(50).joinToString(",") { it.epochSecond.toString() }
    getSharedPreferences("health", Context.MODE_PRIVATE).edit().putString("handled", kept).apply()
}

internal fun MainActivity.requestHealth(then: (Set<String>) -> Unit) {
    healthResult = then
    try { startActivityForResult(PermissionController.createRequestPermissionResultContract().createIntent(this, HEALTH_READ), HEALTH_REQUEST) }
    catch (_: ActivityNotFoundException) { healthResult = null; snack(getString(R.string.hc_unavailable)) }
}

internal fun MainActivity.healthPermissionResult(resultCode: Int, data: Intent?) {
    val granted = PermissionController.createRequestPermissionResultContract().parseResult(resultCode, data)
    healthResult.also { healthResult = null }?.invoke(granted)
}

/** Profile action: ask for access, or open the Play Store page when Health Connect is missing or outdated (Android 9-13). */
internal fun MainActivity.connectHealth(done: () -> Unit) {
    if (healthStatus() == HealthConnectClient.SDK_AVAILABLE) return requestHealth { granted ->
        snack(getString(if (granted.isEmpty()) R.string.hc_denied else R.string.hc_connected)); done()
    }
    val store = Uri.parse("market://details?id=$HEALTH_PROVIDER&url=healthconnect%3A%2F%2Fonboarding")
    try { startActivity(Intent(Intent.ACTION_VIEW, store).setPackage("com.android.vending")) }
    catch (_: ActivityNotFoundException) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$HEALTH_PROVIDER"))) }
        catch (_: ActivityNotFoundException) { snack(getString(R.string.hc_unavailable)) }
    }
}

/** The Profile row: availability, which data may be read, and the Samsung Health note once connected. */
internal fun MainActivity.healthProfile() {
    eyebrow(getString(R.string.hc_title)).setPadding(0, dp(22), 0, 0)
    val value = statusRow("checkin", getString(R.string.hc_title), settingsGroup()) { connectHealth { profile() } }
    val allowed = text("", 13).apply { tag = "health-allowed" }
    val samsung = text(getString(R.string.hc_samsung_note), 13).apply { visibility = android.view.View.GONE }
    when (healthStatus()) {
        HealthConnectClient.SDK_AVAILABLE -> {
            value.text = getString(R.string.hc_state_connect)
            later(value, { HealthReader(applicationContext).granted() }) { granted ->
                val shown = healthTypes.filter { HealthPermission.getReadPermission(it.first) in granted.orEmpty() }
                if (shown.isNotEmpty()) value.text = getString(R.string.hc_state_connected, shown.size, healthTypes.size)
                if (shown.isNotEmpty()) samsung.visibility = android.view.View.VISIBLE
                allowed.text = if (shown.isEmpty()) getString(R.string.hc_allowed_none)
                    else getString(R.string.hc_allowed, shown.joinToString(", ") { getString(it.second) })
            }
        }
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> value.text = getString(R.string.hc_state_update)
        else -> value.text = getString(R.string.hc_state_missing)
    }
    value.visibility = android.view.View.VISIBLE
}

/** Reads in the background; asks for access first when nothing is granted. Not shown when Health Connect is missing. */
internal fun <T> MainActivity.withHealth(view: android.view.View, read: (HealthReader, Set<String>) -> T, show: (T) -> Unit) {
    val reader = HealthReader(applicationContext)
    later(view, { reader.granted() }) { granted ->
        when {
            granted == null -> snack(getString(R.string.hc_failed))
            granted.none { it in HEALTH_READ } -> requestHealth { now ->
                if (now.isEmpty()) snack(getString(R.string.hc_denied)) else withHealth(view, read, show)
            }
            else -> later(view, { read(reader, granted) }) { result -> if (result == null) snack(getString(R.string.hc_failed)) else show(result) }
        }
    }
}

/** Fill a field and mark its caption "from Health Connect" until the athlete changes the value. */
internal fun MainActivity.fillFromHealth(field: EditText, value: String, label: String? = null) {
    val parent = field.parent as ViewGroup
    val caption = parent.getChildAt(parent.indexOfChild(field) - 1) as TextView
    val original = (caption.tag as? String) ?: caption.text.toString().also { caption.tag = it }
    field.setText(value)
    caption.text = getString(R.string.hc_source, label ?: original)
    field.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            if (s.toString() != value) { caption.text = original; field.removeTextChangedListener(this) }
        }
    })
}

/** The check-in's "Fill from Health Connect" button and its notes (SPEC 10.5). */
internal fun MainActivity.healthCheckinButton(slot: LinearLayout, restingHr: EditText, hrv: EditText, bedtime: EditText, wakeTime: EditText) {
    if (healthStatus() != HealthConnectClient.SDK_AVAILABLE) return
    lateinit var notes: TextView
    button(getString(R.string.hc_fill), parent = slot) {
        withHealth(slot, { reader, granted -> reader.checkin(granted) }) { found ->
            found.restingHr?.let { fillFromHealth(restingHr, "$it", if (found.estimated) getString(R.string.hc_resting_estimated) else null) }
            found.hrv?.let { fillFromHealth(hrv, short(it)) }
            if (found.bedtime != null && found.wakeTime != null) { fillFromHealth(bedtime, found.bedtime); fillFromHealth(wakeTime, found.wakeTime) }
            val labels = mapOf("resting_hr" to R.string.rd_resting_hr_field, "hrv_rmssd" to R.string.rd_hrv_field)
            notes.text = (found.skipped.map { (key, value) -> getString(R.string.hc_skipped, getString(labels.getValue(key)), value) } +
                listOfNotNull(getString(R.string.hc_no_hrv).takeIf { found.hrv == null },
                    getString(R.string.hc_nothing).takeIf { found.restingHr == null && found.hrv == null && found.bedtime == null }))
                .joinToString("\n")
            notes.visibility = if (notes.text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        }
    }
    notes = text("", 13, parent = slot).apply { visibility = android.view.View.GONE }
}

/** The weigh-in's fill button: the latest weight of the last 7 days. */
internal fun MainActivity.healthWeightButton(kg: EditText) {
    if (healthStatus() != HealthConnectClient.SDK_AVAILABLE) return
    val slot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    page.addView(slot)
    button(getString(R.string.hc_fill), parent = slot) {
        withHealth(slot, { reader, granted -> reader.weight(granted) ?: Double.NaN }) { found ->
            when {
                found.isNaN() -> snack(getString(R.string.hc_nothing))
                found !in 20.0..400.0 -> snack(getString(R.string.hc_skipped, getString(R.string.nutr_weight_kg), short(found)))
                else -> fillFromHealth(kg, short(found))
            }
        }
    }
}

internal fun MainActivity.sessionName(session: HealthSession): String = session.title?.takeIf { it.isNotBlank() } ?: getString(when (session.type) {
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> R.string.hc_run
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING, ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> R.string.hc_walk
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> R.string.hc_bike
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> R.string.hc_strength
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> R.string.hc_swim
    else -> R.string.hc_workout
})

/** Today: steps on the readiness card and workouts Talos does not know yet, offered as training sessions. */
internal fun MainActivity.healthToday(cards: LinearLayout) {
    if (healthStatus() != HealthConnectClient.SDK_AVAILABLE) return
    val slot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "health-sessions" }
    page.addView(slot, page.indexOfChild(cards)) // above the cards: it asks for a decision
    val reader = HealthReader(applicationContext); val api = featureApi(); val handled = handledSessions()
    val today = LocalDate.now()
    later(cards, {
        val granted = reader.granted()
        val sessions = reader.sessions(granted)
        // Workouts logged in Talos count as known; offline, only the phone's own list is used.
        val workouts = if (sessions.isEmpty()) emptyList() else runCatching {
            api.array("/api/workouts?from=${today.minusDays(2)}&to=$today&limit=100")
                .map { java.time.OffsetDateTime.parse(it.getString("started_at")).toInstant() }
        }.getOrDefault(emptyList())
        reader.steps(granted) to unknownSessions(sessions, handled + workouts)
    }) { found ->
        val (steps, sessions) = found ?: return@later
        if (steps != null && steps != healthSteps) { healthSteps = steps; redrawCard(cards, "readiness") }
        if (sessions.isEmpty()) return@later
        eyebrow(getString(R.string.hc_title), slot).setPadding(0, dp(18), 0, 0)
        sessions.sortedByDescending { it.start }.take(3).forEach { session ->
            val source = if (session.origin == SAMSUNG_HEALTH) "Samsung Health" else getString(R.string.hc_title)
            val panel = card(slot)
            text(getString(R.string.hc_session_offer, source, sessionName(session), session.minutes), 16, getColor(R.color.ink), panel)
            text(localDateTime(session.start.toString(), locale), 13, parent = panel)
            val strip = row(panel)
            button(getString(R.string.hc_session_add), parent = strip, look = ActionTone.TONAL) {
                sessionsScreen(session.start.atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString(),
                    minutes = "${session.minutes}", healthStart = session.start)
            }.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(10) }
            button(getString(R.string.hc_session_dismiss), parent = strip, look = ActionTone.TEXT) {
                handleSession(session.start); slot.removeView(panel)
            }.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(10); marginStart = dp(8) }
        }
    }
}

/** Shown by Health Connect before access is asked for, and from the Android 14 permission usage screen. */
class HealthRationaleActivity : Activity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(base.instrumentTheme())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }
        column.addView(TextView(this).apply {
            text = getString(R.string.hc_privacy_title); textSize = 24f; setTextColor(getColor(R.color.ink)); instrumentType(display = true)
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        column.addView(TextView(this).apply {
            text = getString(R.string.hc_privacy_text); textSize = 16f; setTextColor(getColor(R.color.ink)); setPadding(0, pad, 0, pad)
            setLineSpacing(0f, 1.2f)
        })
        column.addView(android.widget.Button(this).apply {
            text = getString(R.string.hc_close); minHeight = pad * 2 + pad / 2; setOnClickListener { finish() }
        })
        setContentView(ScrollView(this).apply { setBackgroundColor(getColor(R.color.paper)); addView(column) })
    }
}
