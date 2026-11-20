package jumpscan.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Typeface
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import jumpscan.core.FormExercise
import jumpscan.core.FormExercises
import jumpscan.core.FormEvent
import jumpscan.core.FormProgress
import jumpscan.core.FormRepetition
import jumpscan.core.FormStream
import jumpscan.core.Frame
import jumpscan.core.JumpAnalyzer
import jumpscan.core.formInFrame
import jumpscan.core.toJson
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class RepeatCue {
    private val recent = ArrayDeque<Collection<String>>(3)

    fun record(faults: Collection<String>): String? {
        if (recent.size == 3) recent.removeFirst()
        recent.addLast(faults)
        return faults.firstOrNull { fault -> recent.count { fault in it } >= 2 }
    }
}

class LiveFormActivity : ComponentActivity() {
    private lateinit var exercise: FormExercise
    private var side: String? = null
    private var replay = false
    private val phoneTrial by lazy { intent.getIntExtra("phone_trial", 0).takeIf { it in 21..23 }?.let { PhoneTrial(it) } }
    private val appLocale get() = resources.configuration.locales[0]
    private val bg get() = appLocale.language == "bg"
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var feed: CameraFormFeed? = null
    private var retriever: MediaMetadataRetriever? = null
    @Volatile private var running = false
    @Volatile private var generation = 0
    @Volatile private var replayStart = 0L
    private lateinit var preview: LiveFormPreview
    private lateinit var counter: TextView
    private lateinit var subcount: TextView
    private lateinit var banner: TextView
    private lateinit var voice: Button
    private lateinit var action: Button
    private var mirror = false
    private var aspect = 0f
    private var observedView = JumpAnalyzer.View.AUTO
    private val calibration = ArrayDeque<Frame>(10)
    private var stream: FormStream? = null
    private var setStarted = -1L
    private var cues = RepeatCue()
    private var cuesMuted = false
    private var shownCueUntil = 0L
    private var spokenCount = 0
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var voiceNoticeShown = false
    private var voiceSettingsOpened = false
    private var countTone: ToneGenerator? = null
    @Volatile private var timing: RunTiming? = null
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && running) openFeed() else if (!granted) { banner.setText(R.string.form_camera_permission); tone(Tone.WARNING) }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        exercise = FormExercises[intent.getStringExtra("exercise") ?: "squat"]
        side = intent.getStringExtra("side")
        exercise.validateSide(side)
        replay = BuildConfig.DEBUG && intent.getBooleanExtra("replay", false) && File(filesDir, LIVE_REPLAY_FILE).isFile
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildScreen()
        tts = TextToSpeech(this) { status -> voice.post { setupVoice(status) } }
        if (!replay && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.hero))
        }
        preview = LiveFormPreview(this)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(6))
        }
        root.addView(header, LinearLayout.LayoutParams(-1, -2))
        val name = exercise.label(bg)
        val title = side?.let { getString(R.string.form_named_side, name, getString(if (it == "L") R.string.form_left else R.string.form_right)) } ?: name
        header.addView(label(getString(if (replay) R.string.form_replay_debug else R.string.ui_live).uppercase(appLocale), 12, R.color.signal).apply {
            letterSpacing = .12f; setTypeface(null, Typeface.BOLD)
        })
        val tally = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        tally.addView(label(title, 22, R.color.surface).apply {
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        counter = label("", 28, R.color.signal).apply {
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            includeFontPadding = false
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        tally.addView(counter)
        val unit = label(getString(if (exercise.mode == "hold") R.string.ui_held else R.string.ui_reps_unit), 16, R.color.on_hero)
        tally.addView(unit, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        header.addView(tally)
        subcount = label("", 13, R.color.on_hero)
        header.addView(subcount)
        banner = label(getString(R.string.form_finding_camera), 14, R.color.ink).apply {
            minHeight = dp(36); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(5), dp(10), dp(5)); setTypeface(null, Typeface.BOLD)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        header.addView(banner, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        tone(Tone.NEUTRAL)
        voice = Button(this).apply {
            text = getString(R.string.form_voice_settings)
            textSize = 13f
            isAllCaps = false
            minHeight = dp(48)
            setTextColor(getColor(R.color.on_hero))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            visibility = View.GONE
            setOnClickListener {
                visibility = View.GONE
                voiceSettingsOpened = true
                sequenceOf(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA, "com.android.settings.TTS_SETTINGS", Settings.ACTION_SETTINGS)
                    .any { runCatching { startActivity(Intent(it)) }.isSuccess }
            }
        }
        header.addView(voice, LinearLayout.LayoutParams(-1, -2))
        if (exercise.mode == "reps") {
            val settings = getSharedPreferences("live-form", MODE_PRIVATE)
            cuesMuted = settings.getBoolean("mute-technique", false)
            header.addView(Switch(this).apply {
                setText(R.string.form_mute_technique)
                setTextColor(getColor(R.color.on_hero))
                minHeight = dp(48)
                isChecked = cuesMuted
                setOnCheckedChangeListener { _, muted ->
                    cuesMuted = muted
                    settings.edit().putBoolean("mute-technique", muted).apply()
                    if (muted) shownCueUntil = 0L
                }
            })
        }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        action = styleButton(Button(this), primary = true).apply {
            text = getString(R.string.form_start); isAllCaps = false; isEnabled = false; minHeight = dp(48); textSize = 16f
            setOnClickListener { if (stream == null) startSet() else finishSet() }
        }
        root.addView(action, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(6), dp(16), dp(8)) })
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(root)
        @Suppress("DEPRECATION")
        window.statusBarColor = getColor(R.color.hero)
        @Suppress("DEPRECATION")
        window.navigationBarColor = getColor(R.color.hero)
        // Light system-bar icons over the dark camera surface.
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.setSystemBarsAppearance(0,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = 0
        }
        root.requestApplyInsets()
        showProgress(FormProgress())
    }

    private enum class Tone(val fill: Int, val text: Int) {
        GOOD(R.color.signal, R.color.hero), NEUTRAL(R.color.on_hero, R.color.hero), WARNING(R.color.notice, R.color.warning)
    }

    private fun tone(value: Tone) {
        banner.background = shape(getColor(value.fill), 14)
        banner.setTextColor(getColor(value.text))
    }

    private fun label(value: String, size: Int, foreground: Int) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(getColor(foreground))
    }

    override fun onStart() {
        super.onStart()
        running = true
        if (replay || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openFeed()
    }

    private fun openFeed() {
        val ticket = ++generation
        worker.execute {
            try {
                val cameraFeed = CameraFormFeed(applicationContext, worker, { bitmap, frame, latency, run ->
                    runOnUiThread { if (ticket == generation && running) onFrame(bitmap, frame, latency, run) }
                }, { runOnUiThread { if (ticket == generation) failed() } })
                feed?.close()
                feed = cameraFeed
                if (replay) openReplay(ticket, cameraFeed)
                else runOnUiThread { if (ticket == generation && running) bindCamera(ticket, cameraFeed) }
            } catch (_: Exception) { runOnUiThread { if (ticket == generation) failed() } }
        }
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun bindCamera(ticket: Int, cameraFeed: CameraFormFeed) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!running || ticket != generation) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                mirror = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                val builder = ImageAnalysis.Builder().setTargetRotation(Surface.ROTATION_0)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                Camera2Interop.Extender(builder).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        if (ticket == generation) timing?.capture()
                    }
                })
                val analyzer = builder.build()
                analysis = analyzer
                analyzer.setAnalyzer(worker) { image ->
                    try { cameraFeed.camera(image, timing) }
                    catch (_: RuntimeException) { runOnUiThread { if (ticket == generation) failed() } }
                }
                cameraProvider.bindToLifecycle(this, if (mirror) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, analyzer)
            } catch (_: Exception) { failed() }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun openReplay(ticket: Int, cameraFeed: CameraFormFeed) {
        val video = MediaMetadataRetriever().also { retriever = it }
        video.setDataSource(File(filesDir, LIVE_REPLAY_FILE).absolutePath)
        val duration = checkNotNull(video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
        val width = checkNotNull(video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)).toInt()
        val height = checkNotNull(video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)).toInt()
        val scale = minOf(1.0, 640.0 / maxOf(width, height))
        var lastVideoMs = -1L
        var previewed = false
        fun next() {
            if (!running || ticket != generation) return
            val now = SystemClock.elapsedRealtime()
            val start = replayStart
            val run = timing.takeIf { start != 0L }
            val position = if (start == 0L) 0L else ((now - start) / 50) * 50
            if (position >= duration) {
                runOnUiThread { if (ticket == generation && run === timing) finishSet(limitMs = duration) }
                return
            }
            try {
                if ((!previewed || start != 0L) && position != lastVideoMs && cameraFeed.isReady()) {
                    // MediaPipe accepts only ARGB_8888; the retriever may return RGB_565.
                    val started = SystemClock.elapsedRealtimeNanos()
                    val bitmap = video.getScaledFrameAtTime(position * 1000, MediaMetadataRetriever.OPTION_CLOSEST,
                        (width * scale).toInt(), (height * scale).toInt())?.copy(Bitmap.Config.ARGB_8888, false)
                    if (bitmap != null) run?.decoded(SystemClock.elapsedRealtimeNanos() - started)
                    if (bitmap != null && cameraFeed.submit(bitmap, now, run)) {
                        previewed = true
                        lastVideoMs = if (start == 0L) -1 else position
                    }
                }
                worker.schedule({ next() }, 15, TimeUnit.MILLISECONDS)
            } catch (error: Exception) {
                android.util.Log.w("JumpScanForm", "replay failed", error)
                runOnUiThread { if (ticket == generation) failed() }
            }
        }
        next()
    }

    private fun onFrame(bitmap: Bitmap, frame: Frame, latency: Long, run: RunTiming?) {
        preview.show(bitmap, frame, mirror)
        aspect = bitmap.width.toFloat() / bitmap.height
        val inFrame = formInFrame(frame)
        val active = stream
        if (active == null) {
            if (inFrame) {
                if (calibration.size == 10) calibration.removeFirst()
                calibration.addLast(frame)
                if (calibration.size == 10) observedView = FormStream.cameraView(calibration.toList(), aspect)
            } else {
                calibration.clear()
                observedView = JumpAnalyzer.View.AUTO
            }
            val ready = calibration.size == 10 && (exercise.view == "either" || exercise.view.equals(observedView.name, true))
            action.isEnabled = replay || ready
            val status = when {
                ready || replay -> R.string.form_frame_ready
                !inFrame -> R.string.form_full_body
                calibration.size < 10 || observedView == JumpAnalyzer.View.AUTO -> R.string.form_hold_frame
                else -> exercise.viewGuide()
            }
            banner.setText(status)
            tone(when (status) {
                R.string.form_frame_ready -> Tone.GOOD
                R.string.form_hold_frame -> Tone.NEUTRAL
                else -> Tone.WARNING
            })
            return
        }
        if (run == null || run !== timing) return
        if (setStarted < 0) setStarted = frame.tMs
        run.analysed(latency)
        val events = active.feed(frame.copy(tMs = frame.tMs - setStarted))
        updateFeedback(active.progress, events, inFrame)
        if (frame.tMs - setStarted >= 60_000) finishSet()
    }

    private fun updateFeedback(progress: FormProgress, events: List<FormEvent>, inFrame: Boolean) {
        showProgress(progress)
        val now = SystemClock.elapsedRealtime()
        val key = if (!inFrame) "tracking_gap" else progress.cue.takeIf { it == "ready_position" || it == "hold_position" }
        val terminalCue = events.filterIsInstance<FormEvent.End>().map { cues.record(faults(it.repetition)) }.lastOrNull()
            .takeUnless { cuesMuted }
        if (terminalCue != null) {
            banner.text = cue(terminalCue)
            tone(Tone.WARNING)
            shownCueUntil = now + 2_500
        } else if (!inFrame || now >= shownCueUntil) {
            banner.text = cue(key)
            tone(if (!inFrame) Tone.WARNING else if (key == null) Tone.GOOD else Tone.NEUTRAL)
        }
        speak(progress, terminalCue)
    }

    private fun startSet() {
        if (!action.isEnabled || aspect <= 0) return
        voice.visibility = View.GONE
        stream = FormStream(exercise.id, side, aspect)
        setStarted = -1
        spokenCount = 0
        cues = RepeatCue()
        shownCueUntil = 0L
        showProgress(FormProgress())
        timing = RunTiming(applicationContext, if (replay) "form_replay" else "form_live", "lite", phoneTrial)
        action.setText(R.string.form_stop)
        banner.setText(R.string.form_begin)
        tone(Tone.GOOD)
        if (replay) replayStart = SystemClock.elapsedRealtime()
    }

    private fun finishSet(status: String = "ok", limitMs: Long = Long.MAX_VALUE) {
        val active = stream ?: return
        val run = checkNotNull(timing)
        timing = null
        run.clipMs = minOf(run.elapsedMs, limitMs)
        stream = null
        replayStart = 0L
        if (status != "ok") {
            run.save(status)
            action.setText(R.string.form_start)
            showProgress(FormProgress())
            return
        }
        val metrics = try { active.finish().copy(mode = exercise.mode, source = "live") }
        catch (_: IllegalArgumentException) {
            run.save("error")
            action.setText(R.string.form_start)
            showProgress(FormProgress())
            Toast.makeText(this, R.string.too_few_clear_frames, Toast.LENGTH_LONG).show()
            return
        }
        if (exercise.mode == "reps") run.appReps = metrics.summary.repetitionCount
        run.save("ok")
        val payload = if (exercise.signal.legacy) metrics.copy(repetitions = metrics.repetitions.map { rep ->
            if (rep.status == "unclear") rep else rep.copy(checks = mapOf("knee_flexion_peak" to rep.depth,
                "knee_alignment" to listOfNotNull(rep.fppaL, rep.fppaR).maxOrNull()))
        }) else metrics
        setResult(RESULT_OK, Intent().putExtra("metrics", payload.toJson()))
        finish()
    }

    private fun showProgress(progress: FormProgress) {
        if (exercise.mode == "hold") {
            counter.text = String.format(appLocale, "%.1f", progress.holdMs / 1000.0)
            subcount.text = getString(R.string.form_in_band, progress.inBandMs / 1000.0)
        } else {
            counter.text = String.format(appLocale, "%d", progress.complete)
            val counts = getString(R.string.form_counts, progress.complete, progress.partial, progress.unclear)
            subcount.text = if (stream == null) counts else getString(R.string.form_provisional_count, counts)
        }
    }

    private fun faults(rep: FormRepetition): Collection<String> = buildList {
        addAll(rep.reasons.filter { it == "too_fast" || it == "below_min_depth" })
        if (rep.status != "unclear") for (check in exercise.checks) {
            val value = rep.checks?.get(check.key) ?: when (check.key) {
                "knee_flexion_peak" -> rep.depth
                "knee_alignment" -> listOfNotNull(rep.fppaL, rep.fppaR).maxOrNull()
                else -> null
            }
            if (value != null && !check.band.contains(value)) add(check.key)
        }
    }

    private fun cue(key: String?): String {
        exercise.checks.firstOrNull { it.key == key }?.let { return if (bg) it.cueBg else it.cueEn }
        return getString(when (key) {
            "too_fast" -> R.string.form_slow_down
            "tracking_gap" -> R.string.form_full_body
            "below_min_depth" -> R.string.form_more_range
            "ready_position", "hold_position" -> R.string.form_get_position
            else -> R.string.form_tracking
        })
    }

    private fun setupVoice(status: Int) {
        val engine = tts ?: return
        if (isDestroyed) return
        if (status == TextToSpeech.SUCCESS) {
            val installed = engine.voices.orEmpty().filter { !it.isNetworkConnectionRequired && "notInstalled" !in it.features.orEmpty() }
            val match = installed.firstOrNull { it.locale == appLocale } ?: installed.firstOrNull { it.locale.language == appLocale.language }
            ttsReady = match != null && engine.setVoice(match) == TextToSpeech.SUCCESS
        }
        if (!ttsReady && !voiceNoticeShown && stream == null) {
            voiceNoticeShown = true
            voice.visibility = View.VISIBLE
            voice.postDelayed({ voice.visibility = View.GONE }, 8000)
        }
    }

    override fun onResume() {
        super.onResume()
        if (voiceSettingsOpened) {
            voiceSettingsOpened = false
            tts?.shutdown()
            tts = TextToSpeech(this) { status -> voice.post { setupVoice(status) } }
        }
    }

    private fun speak(progress: FormProgress, key: String?) {
        var counted = false
        if (progress.complete > spokenCount) {
            spokenCount = progress.complete
            counted = ttsReady && tts?.speak(spokenCount.toString(), TextToSpeech.QUEUE_FLUSH, null, "rep-$spokenCount") == TextToSpeech.SUCCESS
            if (!counted) {
                if (countTone == null) countTone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 65) }.getOrNull()
                countTone?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
            }
        }
        if (ttsReady && key != null) {
            tts?.speak(cue(key), if (counted) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH,
                null, "cue-${SystemClock.elapsedRealtime()}")
        }
    }

    private fun failed() {
        if (stream != null) finishSet("error")
        action.isEnabled = false
        banner.setText(R.string.form_camera_error)
        tone(Tone.WARNING)
    }

    override fun onStop() {
        running = false
        generation++
        if (stream != null) finishSet(if (isFinishing || phoneTrial != null) "cancelled" else "ok")
        analysis?.let { analyzer -> analyzer.clearAnalyzer(); provider?.unbind(analyzer) }
        analysis = null
        calibration.clear()
        action.isEnabled = false
        tts?.stop()
        worker.execute {
            feed?.close(); feed = null
            retriever?.release(); retriever = null
        }
        super.onStop()
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        countTone?.release()
        countTone = null
        worker.shutdown()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
