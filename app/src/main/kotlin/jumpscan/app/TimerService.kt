package jumpscan.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech

/**
 * Rest and interval timers (SPEC 9). A foreground service with a partial wake lock keeps counting, beeping and
 * vibrating with the screen off. Screens read [TimerState]; the service is the only writer.
 */
internal object TimerState {
    var kind = ""            // "rest", "interval" or "stopwatch"
    var labels: List<String> = emptyList()
    var details: List<String> = emptyList()
    var seconds: IntArray = IntArray(0)
    var work: BooleanArray = BooleanArray(0)
    var index = 0
    var phaseEnd = 0L        // elapsedRealtime of the end of the current phase
    var startedAt = 0L
    var finishedAt = 0L
    var running = false

    fun remainingMs(now: Long = SystemClock.elapsedRealtime()) = (phaseEnd - now).coerceAtLeast(0)
    fun elapsedMs(now: Long = SystemClock.elapsedRealtime()) = (if (running) now else finishedAt) - startedAt
}

internal fun clock(ms: Long): String {
    val total = (ms + 999) / 1000
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60) else "%d:%02d".format(total / 60, total % 60)
}

/** Spoken cues are on unless the user turned them off on the interval timer screen (SPEC 10.2). */
internal fun Context.voiceCues() = getSharedPreferences("timer", Context.MODE_PRIVATE).getBoolean("voice", true)

internal fun Context.startTimer(kind: String, labels: List<String>, details: List<String>, seconds: IntArray, work: BooleanArray) =
    startForegroundService(Intent(this, TimerService::class.java).setAction(TimerService.START).putExtra("kind", kind)
        .putExtra("labels", labels.toTypedArray()).putExtra("details", details.toTypedArray())
        .putExtra("seconds", seconds).putExtra("work", work))

internal fun Context.timerCommand(action: String, delta: Int = 0) {
    if (TimerState.running) startService(Intent(this, TimerService::class.java).setAction(action).putExtra("delta", delta))
}

class TimerService : Service() {
    companion object {
        const val START = "jumpscan.timer.START"
        const val ADJUST = "jumpscan.timer.ADJUST"
        const val SKIP = "jumpscan.timer.SKIP"
        const val STOP = "jumpscan.timer.STOP"
        private const val RUNNING_ID = 41
        private const val DONE_ID = 42
    }

    private val handler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var wake: PowerManager.WakeLock? = null
    private var lastBeep = -1L
    private var voice: TextToSpeech? = null
    private var voiceReady = false
    private val tick = object : Runnable {
        override fun run() {
            step()
            if (TimerState.running) handler.postDelayed(this, 200)
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = TimerState
        when (intent?.action) {
            START -> {
                handler.removeCallbacks(tick)
                state.kind = intent.getStringExtra("kind").orEmpty()
                state.labels = intent.getStringArrayExtra("labels")?.toList().orEmpty()
                state.details = intent.getStringArrayExtra("details")?.toList().orEmpty()
                state.seconds = intent.getIntArrayExtra("seconds") ?: IntArray(0)
                state.work = intent.getBooleanArrayExtra("work") ?: BooleanArray(state.seconds.size)
                state.index = 0
                state.startedAt = SystemClock.elapsedRealtime()
                state.phaseEnd = state.startedAt + (state.seconds.firstOrNull() ?: 0) * 1000L
                state.running = true
                lastBeep = -1
                getSystemService(NotificationManager::class.java).cancel(DONE_ID)
                // Bounded: the plan plus a minute, or four hours for a stopwatch.
                val limit = if (state.seconds.isEmpty()) 4 * 3600_000L else state.seconds.sum() * 1000L + 60_000L
                wake?.takeIf { it.isHeld }?.release()
                wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jumpscan:timer")
                    .apply { setReferenceCounted(false); acquire(limit) }
                if (tone == null) tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull()
                if (voice == null && voiceCues()) voice = TextToSpeech(this) { status ->
                    voiceReady = status == TextToSpeech.SUCCESS &&
                        (voice?.setLanguage(resources.configuration.locales[0]) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE
                    if (voiceReady) cue(0)
                }
                val notification = running()
                if (Build.VERSION.SDK_INT >= 34) startForeground(RUNNING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                else startForeground(RUNNING_ID, notification)
                handler.post(tick)
            }
            ADJUST -> if (state.running) {
                val now = SystemClock.elapsedRealtime()
                state.phaseEnd = maxOf(now + 1000, state.phaseEnd + intent.getIntExtra("delta", 0) * 1000L)
                notifyRunning()
            }
            SKIP -> if (state.running) { state.phaseEnd = SystemClock.elapsedRealtime(); step() }
            STOP -> finish(alert = false)
            else -> if (!state.running) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun step() {
        val state = TimerState
        if (!state.running || state.seconds.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val left = state.phaseEnd - now
        val second = (left + 999) / 1000
        if (left > 0 && second != lastBeep && (second in 1..3 || second == 10L && state.seconds[state.index] >= 20)) {
            lastBeep = second
            if (second == 10L) say(getString(R.string.voice_ten_seconds)) else tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        }
        if (left > 0) return
        if (state.index + 1 >= state.seconds.size) return finish(alert = true)
        state.index++
        state.phaseEnd += state.seconds[state.index] * 1000L
        lastBeep = -1
        tone?.startTone(if (state.work[state.index]) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_ACK, 400)
        buzz(longArrayOf(0, 250))
        cue(state.index)
        notifyRunning()
    }

    private fun finish(alert: Boolean) {
        val state = TimerState
        handler.removeCallbacks(tick)
        if (state.running) state.finishedAt = SystemClock.elapsedRealtime()
        state.running = false
        if (alert) {
            say(getString(if (state.kind == "rest") R.string.voice_rest_over else R.string.timer_done))
            tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900)
            buzz(longArrayOf(0, 400, 200, 400))
            channels()
            getSystemService(NotificationManager::class.java).notify(DONE_ID, Notification.Builder(this, "timer_done")
                .setSmallIcon(R.drawable.ic_timer).setContentTitle(getString(
                    if (state.kind == "rest") R.string.rest_done else R.string.timer_done))
                .setContentIntent(openApp()).setAutoCancel(true).setCategory(Notification.CATEGORY_ALARM).build())
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        wake?.takeIf { it.isHeld }?.release()
        // Let the final tone finish before the generator is released.
        handler.postDelayed({ if (!TimerState.running) { tone?.release(); tone = null; stopSelf() } }, 2500)
    }

    /** "Round 3 of 8" when a work phase starts, the phase name otherwise; rest timers only speak their end. */
    private fun cue(index: Int) {
        val state = TimerState
        if (state.kind != "interval" || index >= state.labels.size) return
        say(if (state.work.getOrElse(index) { false }) state.details[index] else state.labels[index])
    }

    private fun say(words: String) {
        if (voiceReady && voiceCues()) voice?.speak(words, TextToSpeech.QUEUE_FLUSH, null, "timer")
    }

    private fun buzz(pattern: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
            else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun channels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("timer", getString(R.string.timer_channel), NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("timer_done", getString(R.string.timer_done_channel),
            NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null); enableVibration(true) })
    }

    private fun openApp() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun running(): Notification {
        channels()
        val state = TimerState
        val stopwatch = state.seconds.isEmpty()
        val wall = System.currentTimeMillis()
        return Notification.Builder(this, "timer").setSmallIcon(R.drawable.ic_timer).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STOPWATCH).setContentIntent(openApp())
            .setContentTitle(state.labels.getOrNull(state.index) ?: getString(R.string.timer_title))
            .setContentText(state.details.getOrNull(state.index).orEmpty())
            .setUsesChronometer(true).setChronometerCountDown(!stopwatch)
            .setWhen(if (stopwatch) wall - state.elapsedMs() else wall + state.remainingMs())
            .addAction(Notification.Action.Builder(null, getString(R.string.timer_stop), PendingIntent.getService(this, 1,
                Intent(this, TimerService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)).build())
            .build()
    }

    private fun notifyRunning() = getSystemService(NotificationManager::class.java).notify(RUNNING_ID, running())

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        wake?.takeIf { it.isHeld }?.release()
        tone?.release()
        voice?.shutdown()
        super.onDestroy()
    }
}
