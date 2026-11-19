package jumpscan.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.Surface
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import jumpscan.core.FormExercise
import jumpscan.core.FormExercises
import jumpscan.core.FormProgress
import jumpscan.core.FormStream
import jumpscan.core.Frame
import jumpscan.core.JumpAnalyzer
import jumpscan.core.formInFrame
import jumpscan.core.toJson
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LiveFormActivity : ComponentActivity() {
    private lateinit var exercise: FormExercise
    private var side: String? = null
    private var replay = false
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
    private lateinit var voice: TextView
    private lateinit var action: Button
    private var mirror = false
    private var aspect = 0f
    private var observedView = JumpAnalyzer.View.AUTO
    private val calibration = ArrayDeque<Frame>(10)
    private var stream: FormStream? = null
    private var setStarted = -1L
    private var feedbackAt = Long.MIN_VALUE / 2
    private var spokenCue: String? = null
    private var spokenCount = 0
    private var tts: TextToSpeech? = null
    private var countsOnly = true
    private var ttsReady = false
    private var firstResultAt = 0L
    private var lastResultAt = 0L
    private val latencyMs = mutableListOf<Long>()
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && running) openFeed() else if (!granted) banner.setText(R.string.form_camera_permission)
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
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.paper))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        val name = exercise.label(bg)
        val title = side?.let { getString(R.string.form_named_side, name, getString(if (it == "L") R.string.form_left else R.string.form_right)) } ?: name
        header.addView(label(getString(if (replay) R.string.form_replay_debug else R.string.form_live), 13, R.color.accent).apply { letterSpacing = .10f })
        header.addView(label(title, 24, R.color.ink).apply { setTypeface(null, Typeface.BOLD) })
        header.addView(label(getString(exercise.viewGuide()), 14, R.color.muted))
        page.addView(header)
        preview = LiveFormPreview(this)
        page.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(8))
        }
        fun display(value: String, size: Int, tint: Int) = label(value, size, tint).also {
            it.gravity = Gravity.CENTER
            controls.addView(it, LinearLayout.LayoutParams(-1, -2))
        }
        counter = display("", 46, R.color.ink).apply { setTypeface(Typeface.MONOSPACE, Typeface.BOLD) }
        subcount = display("", 14, R.color.muted)
        banner = display(getString(R.string.form_finding_camera), 16, R.color.ink).apply {
            setBackgroundColor(getColor(R.color.hover)); minHeight = dp(50); setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        voice = display(getString(R.string.form_voice_pending), 12, R.color.muted)
        action = Button(this).apply {
            text = getString(R.string.form_start); isAllCaps = false; isEnabled = false; minHeight = dp(48)
            setOnClickListener { if (stream == null) startSet() else finishSet() }
        }
        controls.addView(action, LinearLayout.LayoutParams(-1, -2))
        page.addView(controls)
        page.setOnApplyWindowInsetsListener { view, insets ->
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
        setContentView(page)
        page.requestApplyInsets()
        showProgress(FormProgress())
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
                val cameraFeed = CameraFormFeed(applicationContext, worker, { bitmap, frame, latency ->
                    runOnUiThread { if (ticket == generation && running) onFrame(bitmap, frame, latency) }
                }, { runOnUiThread { if (ticket == generation) failed() } })
                feed?.close()
                feed = cameraFeed
                if (replay) openReplay(ticket, cameraFeed)
                else runOnUiThread { if (ticket == generation && running) bindCamera(ticket, cameraFeed) }
            } catch (_: Exception) { runOnUiThread { if (ticket == generation) failed() } }
        }
    }

    private fun bindCamera(ticket: Int, cameraFeed: CameraFormFeed) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!running || ticket != generation) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                mirror = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                val analyzer = ImageAnalysis.Builder().setTargetRotation(Surface.ROTATION_0)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis = analyzer
                analyzer.setAnalyzer(worker) { image ->
                    try { cameraFeed.camera(image) }
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
            val position = if (start == 0L) 0L else ((now - start) / 50) * 50
            if (position >= duration) {
                runOnUiThread { if (ticket == generation && stream != null) finishSet() }
                return
            }
            try {
                if ((!previewed || start != 0L) && position != lastVideoMs && cameraFeed.isReady()) {
                    // MediaPipe accepts only ARGB_8888; the retriever may return RGB_565.
                    val bitmap = video.getScaledFrameAtTime(position * 1000, MediaMetadataRetriever.OPTION_CLOSEST,
                        (width * scale).toInt(), (height * scale).toInt())?.copy(Bitmap.Config.ARGB_8888, false)
                    if (bitmap != null && cameraFeed.submit(bitmap, now)) {
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

    private fun onFrame(bitmap: Bitmap, frame: Frame, latency: Long) {
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
            banner.setText(when {
                ready || replay -> R.string.form_frame_ready
                !inFrame -> R.string.form_full_body
                calibration.size < 10 || observedView == JumpAnalyzer.View.AUTO -> R.string.form_hold_frame
                else -> exercise.viewGuide()
            })
            return
        }
        if (setStarted < 0) setStarted = frame.tMs
        active.feed(frame.copy(tMs = frame.tMs - setStarted))
        showProgress(active.progress)
        if (BuildConfig.DEBUG) {
            lastResultAt = SystemClock.elapsedRealtime()
            if (latencyMs.isEmpty()) firstResultAt = lastResultAt
            latencyMs += latency
        }
        val key = if (inFrame) active.progress.cue else "tracking_gap"
        val message = cue(key)
        banner.text = message
        speak(active.progress, key, message)
        if (frame.tMs - setStarted >= 60_000) finishSet()
    }

    private fun startSet() {
        if (!action.isEnabled || aspect <= 0) return
        stream = FormStream(exercise.id, side, aspect)
        setStarted = -1
        spokenCount = 0; spokenCue = null; feedbackAt = Long.MIN_VALUE / 2
        latencyMs.clear()
        action.setText(R.string.form_stop)
        banner.setText(R.string.form_begin)
        if (replay) replayStart = SystemClock.elapsedRealtime()
    }

    private fun finishSet() {
        val active = stream ?: return
        stream = null
        replayStart = 0L
        val metrics = try { active.finish().copy(mode = exercise.mode, source = "live") }
        catch (_: IllegalArgumentException) {
            action.setText(R.string.form_start)
            showProgress(FormProgress())
            Toast.makeText(this, R.string.too_few_clear_frames, Toast.LENGTH_LONG).show()
            return
        }
        if (BuildConfig.DEBUG && latencyMs.isNotEmpty()) {
            val elapsed = lastResultAt - firstResultAt
            val sorted = latencyMs.sorted()
            val rate = if (elapsed > 0) (sorted.size - 1) * 1000.0 / elapsed else 0.0
            android.util.Log.i("JumpScanForm", "replay=$replay model=lite-cpu callbacks=${sorted.size} elapsedMs=$elapsed " +
                "callbackFps=$rate meanMs=${sorted.average()} p95Ms=${sorted[(sorted.size * .95).toInt().coerceAtMost(sorted.lastIndex)]} " +
                "complete=${metrics.summary.repetitionCount} partial=${metrics.summary.partialCount} unclear=${metrics.summary.unclearCount}")
        }
        val payload = if (exercise.signal.legacy) metrics.copy(repetitions = metrics.repetitions.map { rep ->
            if (rep.status == "unclear") rep else rep.copy(checks = mapOf("knee_flexion_peak" to rep.depth,
                "knee_alignment" to listOfNotNull(rep.fppaL, rep.fppaR).maxOrNull()))
        }) else metrics
        setResult(RESULT_OK, Intent().putExtra("metrics", payload.toJson()))
        finish()
    }

    private fun showProgress(progress: FormProgress) {
        if (exercise.mode == "hold") {
            counter.text = getString(R.string.form_seconds, progress.holdMs / 1000.0)
            subcount.text = getString(R.string.form_in_band, progress.inBandMs / 1000.0)
        } else {
            counter.text = String.format(appLocale, "%d", progress.complete)
            subcount.text = getString(R.string.form_counts, progress.complete, progress.partial, progress.unclear)
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
            val fallback = installed.firstOrNull { it.locale.language == "en" } ?: installed.firstOrNull()
            val selected = match ?: fallback
            ttsReady = selected != null && engine.setVoice(selected) == TextToSpeech.SUCCESS
            countsOnly = match == null
        }
        voice.setText(when {
            !ttsReady -> R.string.form_voice_unavailable
            countsOnly -> R.string.form_voice_counts_only
            else -> R.string.form_voice_local
        })
    }

    private fun speak(progress: FormProgress, key: String?, message: String) {
        if (!ttsReady) return
        val now = SystemClock.elapsedRealtime()
        if (progress.complete > spokenCount) {
            spokenCount = progress.complete
            tts?.speak(spokenCount.toString(), TextToSpeech.QUEUE_FLUSH, null, "rep-$spokenCount")
            feedbackAt = now
        } else if (!countsOnly && key != null && key != spokenCue && now - feedbackAt >= 2500) {
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "cue-$now")
            spokenCue = key
            feedbackAt = now
        } else if (key == null) spokenCue = null
    }

    private fun failed() {
        if (stream != null) finishSet()
        action.isEnabled = false
        banner.setText(R.string.form_camera_error)
    }

    override fun onStop() {
        running = false
        generation++
        if (stream != null && !isFinishing) finishSet()
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
        worker.shutdown()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
