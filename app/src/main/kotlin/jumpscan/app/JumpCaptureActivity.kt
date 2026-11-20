package jumpscan.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Range
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.ExperimentalSessionConfig
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.ExperimentalHighSpeedVideo
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.HighSpeedVideoSessionConfig
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import jumpscan.core.Frame
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAX_RECORDING_MS = 9_500L
private const val POST_LANDING_MS = 2_000L

internal fun jumpFraming(frame: Frame): Int {
    val points = frame.landmarks
    val margin = 0.03f..0.97f
    fun inside(joint: Int) = points[joint].let { it.visibility >= .5f && it.x in margin && it.y in margin }
    if (!inside(0) || (11..16).any { !inside(it) } || (23..32).any { !inside(it) }) return R.string.jump_frame_body
    val head = listOf(0, 2, 5, 7, 8).filter(::inside).minOf { points[it].y }
    val feet = (27..32).maxOf { points[it].y }
    return if (head < .30f * (feet - head)) R.string.jump_frame_space else R.string.jump_frame_ready
}

internal class JumpLandingWatch(initialFeet: Float?) {
    private var ground = initialFeet
    private var raised = 0
    private var touching = 0
    private var airborne = false

    fun landed(feet: Float): Boolean {
        val floor = ground ?: feet
        if (!airborne) {
            ground = maxOf(floor, feet)
            raised = if (floor - feet > .045f) raised + 1 else 0
            airborne = raised >= 2
        } else {
            touching = if (floor - feet < .025f) touching + 1 else 0
        }
        return touching >= 2
    }
}

@OptIn(ExperimentalSessionConfig::class, ExperimentalHighSpeedVideo::class)
class JumpCaptureActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(base.instrumentTheme())

    private data class Mode(val fps: Int, val config: SessionConfig, val video: VideoCapture<Recorder>)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var feed: CameraFormFeed? = null
    private var provider: ProcessCameraProvider? = null
    private val selector = CameraSelector.DEFAULT_BACK_CAMERA
    private var previewOnly: SessionConfig? = null
    private var modes = emptyList<Mode>()
    private var selected: Mode? = null
    private var recording: Recording? = null
    private var finishingClip = false
    private var discardClip = false
    private var active = false
    private var startedAt = 0L
    private var poseAfter = 0L
    private var previewBound = false
    private var clearFrames = 0
    private var clearAt = -1L
    private var latestFeet: Float? = null
    private var landingWatch: JumpLandingWatch? = null
    private var stopAt = Long.MAX_VALUE
    private var lastTick = -1
    private var tone: ToneGenerator? = null
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var start: Button
    private lateinit var pick: Button
    private lateinit var rateChoices: LinearLayout
    private lateinit var rateNotice: TextView
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) openCamera() else status.setText(R.string.jump_camera_permission)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.surface))
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft + dp(12), insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight + dp(12), insets.systemWindowInsetBottom)
                insets
            }
        }
        root.addView(label(R.string.jump_capture_title, 22))
        root.addView(label(if (intent.getStringExtra("test") == "drop_jump") R.string.jump_capture_drop_tips
            else R.string.jump_capture_tips, 14))
        preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        status = label(R.string.form_finding_camera, 16).apply {
            gravity = Gravity.CENTER
            minHeight = dp(64)
            accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(status)
        rateChoices = LinearLayout(this).apply { gravity = Gravity.CENTER }
        root.addView(rateChoices)
        rateNotice = label(R.string.jump_rate_unavailable, 14).apply { visibility = View.GONE }
        root.addView(rateNotice)
        start = styleButton(Button(this), primary = true).apply {
            setText(R.string.jump_record_start)
            isEnabled = false
            setOnClickListener { if (recording == null) record() else stopRecording() }
        }
        root.addView(start)
        pick = styleButton(Button(this), primary = false).apply {
            setText(R.string.pick_video)
            setOnClickListener {
                setResult(Activity.RESULT_FIRST_USER)
                finish()
            }
        }
        root.addView(pick)
        setContentView(root)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun label(resource: Int, size: Int) = TextView(this).apply {
        setText(resource)
        textSize = size.toFloat()
        setTextColor(getColor(R.color.ink))
        setPadding(dp(4), dp(5), dp(4), dp(5))
    }

    override fun onStart() {
        super.onStart()
        active = true
        val app = applicationContext
        worker.execute {
            runCatching {
                CameraFormFeed(app, worker, { bitmap, frame, _, _ ->
                    bitmap.recycle()
                    main.post { if (active && frame.tMs >= poseAfter) observe(frame) }
                }, { main.post { if (active) cameraError() } })
            }.onSuccess { feed = it }.onFailure { main.post { if (active) cameraError() } }
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
        main.post(tick)
    }

    private fun openCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (active && provider == null) runCatching {
                val camera = future.get()
                provider = camera
                val info = camera.getCameraInfo(selector)
                val regular = Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR)
                val fast = Recorder.getHighSpeedVideoCapabilities(info)?.getSupportedQualities(DynamicRange.SDR).orEmpty()
                modes = listOfNotNull(mode(60, regular, info), mode(120, fast, info), mode(240, fast, info))
                previewOnly = SessionConfig(listOf(newPreview()))
                bind(modes.firstOrNull())
            }.onFailure { cameraError() }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun newPreview() = Preview.Builder().setResolutionSelector(ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).build()).build().also {
        it.surfaceProvider = preview.surfaceProvider
    }

    private fun mode(fps: Int, qualities: List<Quality>, info: CameraInfo): Mode? {
        val fixed = Range(fps, fps)
        for (quality in (listOf(Quality.HD, Quality.FHD) + qualities).distinct().filter { it in qualities }) {
            val useCase = newPreview()
            val video = VideoCapture.withOutput(Recorder.Builder().setQualitySelector(QualitySelector.from(quality)).build())
            val probe = if (fps == 60) SessionConfig(listOf(useCase, video))
                else HighSpeedVideoSessionConfig.Builder(video).setPreview(useCase).build()
            if (fixed !in runCatching { info.getSupportedFrameRateRanges(probe) }.getOrDefault(emptySet())) continue
            val config = if (fps == 60) SessionConfig(listOf(useCase, video), frameRateRange = fixed)
                else HighSpeedVideoSessionConfig.Builder(video).setPreview(useCase).setFrameRateRange(fixed).build()
            return Mode(fps, config, video)
        }
        return null
    }

    private fun bind(mode: Mode?) {
        val camera = provider ?: return
        val config = mode?.config ?: previewOnly ?: return
        clearFrames = 0
        clearAt = -1L
        latestFeet = null
        poseAfter = SystemClock.uptimeMillis() + 250
        start.isEnabled = false
        selected = null
        previewBound = false
        camera.unbindAll()
        runCatching {
            camera.bindToLifecycle(this, selector, config)
            selected = mode
            previewBound = true
            status.setText(R.string.jump_frame_body)
        }.onFailure {
            if (mode != null) {
                modes = modes - mode
                bind(modes.firstOrNull())
            } else cameraError()
        }
        showRates()
    }

    private fun showRates() {
        rateNotice.visibility = if (previewBound && modes.isEmpty()) View.VISIBLE else View.GONE
        rateChoices.removeAllViews()
        for (mode in modes) rateChoices.addView(styleButton(Button(this), primary = mode == selected).apply {
            text = getString(R.string.jump_capture_rate, mode.fps)
            isEnabled = recording == null && !finishingClip && mode != selected
            setOnClickListener { bind(mode) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            val now = SystemClock.uptimeMillis()
            if (recording != null && startedAt > 0) {
                val elapsed = now - startedAt
                when {
                    now >= stopAt || elapsed >= MAX_RECORDING_MS -> stopRecording()
                    elapsed < 3_000 && now - clearAt > 650 -> stopRecording(discard = true)
                    elapsed < 3_000 -> showCountdown(elapsed)
                    lastTick != 0 -> {
                        lastTick = 0
                        landingWatch = JumpLandingWatch(latestFeet)
                        status.textSize = 24f
                        status.setText(R.string.jump_capture_go)
                        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 250)
                    }
                }
            } else if (previewBound && !finishingClip && recording == null && now - clearAt > 650) {
                clearFrames = 0
                start.isEnabled = false
                if (status.text == getString(R.string.jump_frame_ready)) status.setText(R.string.jump_frame_body)
            }
            val cameraFeed = feed
            if (previewBound && now >= poseAfter && !finishingClip && cameraFeed?.isReady() == true &&
                preview.previewStreamState.value == PreviewView.StreamState.STREAMING) {
                preview.bitmap?.let { source ->
                    val scale = min(1f, 480f / maxOf(source.width, source.height))
                    val image = Bitmap.createScaledBitmap(source, (source.width * scale).roundToInt(),
                        (source.height * scale).roundToInt(), true)
                    if (image !== source) source.recycle()
                    worker.execute {
                        runCatching { cameraFeed.submit(image, now, null) }.onFailure {
                            main.post { if (active) cameraError() }
                        }.getOrNull().let { if (it != true) image.recycle() }
                    }
                }
            }
            main.postDelayed(this, 90)
        }
    }

    private fun showCountdown(elapsed: Long) {
        val number = (3 - elapsed / 1000).toInt()
        if (number != lastTick) {
            lastTick = number
            status.textSize = 40f
            status.text = getString(R.string.jump_countdown, number)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        }
    }

    private fun observe(frame: Frame) {
        val check = jumpFraming(frame)
        if (check == R.string.jump_frame_ready) {
            clearFrames++
            clearAt = frame.tMs
        } else {
            clearFrames = 0
            clearAt = -1L
        }
        val feet = (27..32).map { frame.landmarks[it] }.filter { it.visibility >= .5f && it.y in 0f..1f }
        latestFeet = if (feet.size >= 4) feet.maxOf { it.y } else null
        if (recording != null) {
            if (lastTick != 0 && startedAt > 0 && clearFrames == 0) stopRecording(discard = true)
            if (stopAt == Long.MAX_VALUE && latestFeet?.let { landingWatch?.landed(it) } == true) {
                stopAt = SystemClock.uptimeMillis() + POST_LANDING_MS
                status.textSize = 16f
                status.setText(R.string.jump_capture_landed)
            }
        } else if (!finishingClip && previewBound) {
            start.isEnabled = selected != null && clearFrames >= 3
            val message = if (clearFrames < 3 && check == R.string.jump_frame_ready) R.string.jump_frame_hold else check
            if (status.text != getString(message)) status.setText(message)
        }
    }

    private fun record() {
        val mode = selected ?: return
        if (!start.isEnabled || SystemClock.uptimeMillis() - clearAt > 650) return
        if (tone == null) tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 85) }.getOrNull()
        val dir = File(cacheDir, "jump-capture").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
        val file = runCatching { File.createTempFile("jump-", ".mp4", dir) }.getOrElse { cameraError(); return }
        startedAt = 0
        lastTick = -1
        landingWatch = null
        discardClip = false
        stopAt = Long.MAX_VALUE
        start.setText(R.string.jump_record_stop)
        start.isEnabled = true
        pick.isEnabled = false
        status.setText(R.string.jump_capture_starting)
        runCatching {
            recording = mode.video.output.prepareRecording(this, FileOutputOptions.Builder(file).build())
                .start(ContextCompat.getMainExecutor(this)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> startedAt = SystemClock.uptimeMillis()
                        is VideoRecordEvent.Finalize -> {
                            recording = null
                            finishingClip = false
                            if (!active || discardClip || event.hasError()) {
                                file.delete()
                                if (active) {
                                    start.setText(R.string.jump_record_start)
                                    start.isEnabled = false
                                    pick.isEnabled = true
                                    showRates()
                                    status.setText(if (discardClip) R.string.jump_frame_body else R.string.jump_camera_error)
                                    if (event.hasError() && !discardClip) cameraError()
                                }
                            } else {
                                setResult(Activity.RESULT_OK, Intent().putExtra("clip", file.name).putExtra("fps", mode.fps))
                                finish()
                            }
                        }
                    }
                }
            showRates()
        }.onFailure { file.delete(); cameraError() }
    }

    private fun stopRecording(discard: Boolean = lastTick != 0) {
        val clip = recording ?: return
        discardClip = discard
        finishingClip = true
        recording = null
        start.isEnabled = false
        status.textSize = 16f
        status.setText(if (discard) R.string.jump_frame_body else R.string.jump_capture_saving)
        clip.stop()
    }

    private fun cameraError() {
        stopRecording(discard = true)
        selected = null
        previewBound = false
        start.setText(R.string.jump_record_start)
        start.isEnabled = false
        pick.isEnabled = true
        showRates()
        status.setText(R.string.jump_camera_error)
    }

    override fun onStop() {
        active = false
        discardClip = true
        stopRecording(discard = true)
        main.removeCallbacks(tick)
        provider?.unbindAll()
        provider = null
        selected = null
        previewBound = false
        poseAfter = Long.MAX_VALUE
        worker.execute { feed?.close(); feed = null }
        super.onStop()
    }

    override fun onDestroy() {
        tone?.release()
        worker.shutdown()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
