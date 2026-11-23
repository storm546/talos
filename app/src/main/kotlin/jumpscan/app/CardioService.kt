package jumpscan.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import jumpscan.core.GpsTrack
import org.json.JSONObject

/** Outdoor cardio (SPEC 9.3). The service is the only writer; the route lives only in [recorder], in memory. */
internal object CardioState {
    var recorder: GpsTrack.Recorder? = null
    var running = false
    var endedAt = 0L
    var lastAccuracy: Float? = null
    var provider = ""

    fun elapsedMs(now: Long = SystemClock.elapsedRealtime()) = recorder?.let { (if (running) now else endedAt) - it.startMs } ?: 0L
}

class CardioService : Service(), LocationListener {
    companion object {
        const val START = "jumpscan.cardio.START"
        const val STOP = "jumpscan.cardio.STOP"
        private const val ID = 43
    }

    override fun onBind(intent: Intent?) = null

    @SuppressLint("MissingPermission") // checked before the service is started and again here
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { stopSelf(); return START_NOT_STICKY }
                val manager = getSystemService(LocationManager::class.java)
                val provider = if (Build.VERSION.SDK_INT >= 31 && manager.hasProvider(LocationManager.FUSED_PROVIDER))
                    LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER
                CardioState.recorder = GpsTrack.Recorder(intent.getStringExtra("activity") ?: "run", SystemClock.elapsedRealtime())
                CardioState.running = true
                CardioState.lastAccuracy = null
                CardioState.provider = provider
                getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel("cardio", getString(R.string.ft_cardio), NotificationManager.IMPORTANCE_LOW))
                val notification = Notification.Builder(this, "cardio").setSmallIcon(R.drawable.ic_conditioning).setOngoing(true)
                    .setContentTitle(getString(R.string.ft_cardio_recording)).setUsesChronometer(true)
                    .setWhen(System.currentTimeMillis()).setCategory(Notification.CATEGORY_SERVICE)
                    .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE))
                    .addAction(Notification.Action.Builder(null, getString(R.string.timer_stop), PendingIntent.getService(this, 1,
                        Intent(this, CardioService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)).build())
                    .build()
                if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                else startForeground(ID, notification)
                manager.removeUpdates(this)
                manager.requestLocationUpdates(provider, 1000L, 0f, this, Looper.getMainLooper())
            }
            STOP -> finish()
            else -> if (!CardioState.running) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onLocationChanged(location: Location) {
        val recorder = CardioState.recorder ?: return
        CardioState.lastAccuracy = if (location.hasAccuracy()) location.accuracy else null
        recorder.add(GpsTrack.Fix(location.elapsedRealtimeNanos / 1_000_000, location.latitude, location.longitude,
            if (location.hasAccuracy()) location.accuracy else Float.NaN))
    }

    @Deprecated("Required on API 26-28")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    private fun finish() {
        if (CardioState.running) CardioState.endedAt = SystemClock.elapsedRealtime()
        CardioState.running = false
        getSystemService(LocationManager::class.java).removeUpdates(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        getSystemService(LocationManager::class.java).removeUpdates(this)
        if (CardioState.running) { CardioState.endedAt = SystemClock.elapsedRealtime(); CardioState.running = false }
        super.onDestroy()
    }
}

/** The recorded route drawn to scale, north up; it is never uploaded. */
internal class RouteSketch(context: Context, private val route: List<GpsTrack.Fix>) : View(context) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        if (route.size < 2) return
        val d = resources.displayMetrics.density
        val lat0 = route.first().lat
        val xs = route.map { it.lon * kotlin.math.cos(Math.toRadians(lat0)) }; val ys = route.map { it.lat }
        val span = maxOf(xs.max() - xs.min(), ys.max() - ys.min()).coerceAtLeast(1e-6)
        val scale = minOf(width, height) - 24 * d
        val ox = (width - scale * (xs.max() - xs.min()) / span) / 2; val oy = (height - scale * (ys.max() - ys.min()) / span) / 2
        val path = Path()
        route.indices.forEach { i ->
            val x = (ox + (xs[i] - xs.min()) / span * scale).toFloat(); val y = (height - oy - (ys[i] - ys.min()) / span * scale).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        pen.color = context.getColor(R.color.signal); pen.strokeWidth = 4 * d
        canvas.drawPath(path, pen)
    }
}

private fun MainActivity.pace(seconds: Double?) = seconds?.let { "%d:%02d".format((it / 60).toInt(), (it % 60).toInt()) } ?: "—"

internal fun MainActivity.cardioSummary(body: JSONObject, panel: LinearLayout) {
    val activity = body.getString("activity")
    text(getString(R.string.ft_cardio_named, getString(activityLabel(activity))), 20, getColor(R.color.on_hero), panel).instrumentType(display = true)
    val line = row(panel, center = true)
    line.addView(Measurement(this).apply {
        text = String.format(locale, "%.2f", body.getDouble("distance_m") / 1000); textSize = 56f
        setTextColor(getColor(R.color.signal)); instrumentType(numeric = true); includeFontPadding = false
    })
    text(getString(R.string.ft_km), 18, getColor(R.color.on_hero), line).setPadding(dp(8), dp(14), 0, 0)
    val pace = if (body.isNull("avg_pace_s_per_km")) null else body.getDouble("avg_pace_s_per_km")
    text(getString(R.string.ft_cardio_line, clock(body.getLong("duration_s") * 1000), pace(pace)), 14, getColor(R.color.on_hero), panel)
    CardioState.recorder?.route?.takeIf { it.size >= 2 && kotlin.math.abs(body.getDouble("distance_m") - CardioState.recorder!!.distanceM) < 0.01 }?.let {
        panel.addView(RouteSketch(this, it.toList()), LinearLayout.LayoutParams(-1, dp(160)).apply { topMargin = dp(10) })
    }
    val splits = body.getJSONArray("splits_s")
    if (splits.length() > 0) {
        section(R.string.ft_splits).setPadding(0, dp(18), 0, dp(4))
        val group = settingsGroup()
        for (i in 0 until splits.length()) {
            if (i > 0) divider(group, inset = false)
            readoutRow(getString(R.string.ft_split_km, i + 1), pace(splits.getDouble(i)), "", group)
        }
    }
}

private fun activityLabel(activity: String) = when (activity) { "walk" -> R.string.ft_walk; "cycle" -> R.string.ft_cycle; else -> R.string.ft_run }

private val ACTIVITIES = listOf("run", "walk", "cycle")

/** Setup, live readout while the service records, and the summary when it stops. */
internal fun MainActivity.cardioHome() {
    if (CardioState.running) return cardioLive()
    val prefs = getSharedPreferences("field", 0)
    val activity = prefs.getString("activity", "run")!!
    layout(getString(R.string.ft_cardio), "cardio")
    segments(ACTIVITIES.map { getString(activityLabel(it)) }, ACTIVITIES.indexOf(activity)) {
        prefs.edit().putString("activity", ACTIVITIES[it]).apply(); cardioHome()
    }
    val panel = card(hero = true)
    panel.addView(Measurement(this).apply { text = String.format(resources.configuration.locales[0], "%.2f", 0.0); textSize = 56f; setTextColor(getColor(R.color.signal)); instrumentType(numeric = true) })
    text(getString(R.string.ft_cardio_setup), 14, getColor(R.color.on_hero), panel)
    notice(getString(R.string.ft_cardio_limits))
    button(getString(R.string.ft_cardio_start), primary = true) {
        val needed = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) +
            (if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(needed.toTypedArray(), 63)
            snack(getString(R.string.ft_location_needed))
            return@button
        }
        startForegroundService(Intent(this, CardioService::class.java).setAction(CardioService.START).putExtra("activity", activity))
        CardioState.running = true // the service confirms; avoids a setup flash before it starts
        CardioState.recorder = GpsTrack.Recorder(activity, SystemClock.elapsedRealtime())
        cardioLive()
    }.apply { minHeight = dp(56); textSize = 17f }
    text(getString(R.string.ft_route_stays), 13)
}

internal fun MainActivity.cardioLive() {
    layout(getString(R.string.ft_cardio), "cardio")
    val panel = card(hero = true)
    text(getString(R.string.ft_cardio_recording).uppercase(locale), 12, getColor(R.color.signal), panel).apply { letterSpacing = .12f; instrumentType(display = true) }
    val distance = Measurement(this).apply { textSize = 64f; setTextColor(getColor(R.color.signal)); instrumentType(numeric = true); includeFontPadding = false }
    panel.addView(distance)
    text(getString(R.string.ft_km), 14, getColor(R.color.on_hero), panel)
    val time = text("", 28, getColor(R.color.on_hero), panel).apply { instrumentType(numeric = true) }
    val paceLine = text("", 14, getColor(R.color.on_hero), panel)
    val gps = text("", 13)
    ticker(distance) {
        val recorder = CardioState.recorder ?: return@ticker
        if (!CardioState.running) return@ticker cardioStopped()
        val summary = recorder.summary(SystemClock.elapsedRealtime())
        distance.text = String.format(locale, "%.2f", recorder.distanceM / 1000)
        time.text = clock(CardioState.elapsedMs())
        paceLine.text = getString(R.string.ft_pace_line, pace(summary.paceSPerKm), summary.splitsS.size)
        gps.text = CardioState.lastAccuracy?.let { getString(R.string.ft_gps_accuracy, String.format(locale, "%.0f", it)) }
            ?: getString(R.string.ft_gps_waiting)
    }
    button(getString(R.string.ft_cardio_stop), primary = true) {
        startService(Intent(this, CardioService::class.java).setAction(CardioService.STOP))
        CardioState.endedAt = SystemClock.elapsedRealtime(); CardioState.running = false
        cardioStopped()
    }.apply { minHeight = dp(56); textSize = 17f }
    text(getString(R.string.ft_cardio_background), 13)
}

private fun MainActivity.cardioStopped() {
    val recorder = CardioState.recorder ?: return cardioHome()
    val end = if (CardioState.endedAt > recorder.startMs) CardioState.endedAt else SystemClock.elapsedRealtime()
    fieldResult(FieldRun(JSONObject(recorder.summary(end).toJson())))
}
