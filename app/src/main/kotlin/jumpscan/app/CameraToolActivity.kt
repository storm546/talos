package jumpscan.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaMetadataRetriever
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import jumpscan.core.Balance
import jumpscan.core.FormStream
import jumpscan.core.Frame
import jumpscan.core.JumpAnalyzer
import jumpscan.core.LineCrossing
import jumpscan.core.RangeOfMotion
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.random.Random

/**
 * Plays the start tone (1 kHz) on its own AudioTrack and reports when its first sample reached the audio output, in
 * ms on the camera clock: elapsedRealtime when [boottime], else System.nanoTime. The time comes from
 * AudioTrack.getTimestamp; when no timestamp arrives within a second, the request time is reported instead and
 * [onPresented]'s second argument is false. Runs on its own thread; the callback runs there too.
 */
internal fun playCueTone(ms: Int, boottime: Boolean, onPresented: (Long, Boolean) -> Unit) = thread(name = "cue-tone") {
    fun offsetNs() = if (boottime) SystemClock.elapsedRealtimeNanos() - System.nanoTime() else 0L
    val requestedMs = (System.nanoTime() + offsetNs()) / 1_000_000
    val rate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: 48_000
    val count = rate * ms / 1000
    val fade = rate / 200 // 5 ms ramps avoid clicks
    val samples = ShortArray(count) { i ->
        (sin(2 * PI * 1000 * i / rate) * 0.8 * minOf(1.0, i / fade.toDouble(), (count - i) / fade.toDouble()) * Short.MAX_VALUE).toInt().toShort()
    }
    val track = try {
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(count * 2,
                AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT))).build()
    } catch (_: Exception) { return@thread onPresented(requestedMs, false) }
    try {
        // The whole tone fits in the buffer, so frame 0 of the track is the tone's first sample.
        track.write(samples, 0, count, AudioTrack.WRITE_BLOCKING)
        track.play()
        val stamp = AudioTimestamp()
        val deadline = SystemClock.elapsedRealtime() + 1000
        var presented: Long? = null
        while (presented == null && SystemClock.elapsedRealtime() < deadline) {
            if (track.getTimestamp(stamp) && stamp.framePosition > 0) {
                presented = LineCrossing.tonePresentedNs(stamp.framePosition, stamp.nanoTime, 0, rate, offsetNs()) / 1_000_000
            } else Thread.sleep(5)
        }
        onPresented(presented ?: requestedMs, presented != null)
        while (track.playbackHeadPosition < count && SystemClock.elapsedRealtime() < deadline + ms) Thread.sleep(10)
    } catch (_: Exception) { onPresented(requestedMs, false) }
    finally { runCatching { track.stop() }; track.release() }
}

/** The timing line: a vertical band at the frame centre, filled as the band activity rises. */
internal class TimingBand(context: android.content.Context) : View(context) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    var activity = 0.0
        set(value) { field = value; invalidate() }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        val band = width * LineCrossing.BAND.toFloat()
        val left = (width - band) / 2
        pen.style = Paint.Style.FILL
        pen.color = context.getColor(R.color.signal); pen.alpha = (40 + 180 * (activity / LineCrossing.TRIGGER).coerceIn(0.0, 1.0)).toInt()
        canvas.drawRect(left, 0f, left + band, height.toFloat(), pen)
        pen.alpha = 255; pen.strokeWidth = 2 * resources.displayMetrics.density
        canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), pen)
    }
}

/**
 * Camera tools of SPEC 9.3. Pose modes (rom, balance) reuse the live form feed and skeleton; timing modes (sprint,
 * agility, reaction) read luminance from camera frames. The result JSON goes back to MainActivity; nothing is uploaded here.
 */
class CameraToolActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(base.instrumentTheme())

    private lateinit var mode: String
    private val pose get() = mode == "rom" || mode == "balance"
    private var joint = RangeOfMotion.Joint.KNEE_FLEXION
    private var distance = 20.0
    private var startKind = "cue"
    private var agilityTest = "505"
    private var replay = false
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private var provider: ProcessCameraProvider? = null
    private var feed: CameraFormFeed? = null
    private var retriever: MediaMetadataRetriever? = null
    @Volatile private var running = false
    @Volatile private var generation = 0
    private lateinit var skeleton: LiveFormPreview
    private var cameraView: PreviewView? = null
    private var band: TimingBand? = null
    private lateinit var readout: TextView
    private lateinit var detail: TextView
    private lateinit var banner: TextView
    private lateinit var action: Button
    private lateinit var secondary: Button
    private var mirror = false
    private var aspect = 0f
    private var tone: ToneGenerator? = null
    @Volatile private var active = false

    // Range of motion: the current phase's frames and the first side's hold.
    private val frames = ArrayList<Frame>()
    private var firstHold: RangeOfMotion.Result? = null
    private var phase = 1
    // Balance.
    private var tracker: Balance.Tracker? = null
    // Timing (worker thread except where noted).
    private var previous: ByteArray? = null
    private var previousWhole: ByteArray? = null
    private var detector = LineCrossing.Detector()
    private val stamps = ArrayList<Long>()
    private val motion = ArrayList<Pair<Long, Double>>()
    @Volatile private var cueMs = -1L
    @Volatile private var cueFinal = false   // cueMs is the presented time (or its fallback), not the request time
    @Volatile private var cueFromAudio = false
    @Volatile private var ignoreBeforeMs = Long.MAX_VALUE
    @Volatile private var firstCrossing = -1L
    @Volatile private var runStarted = 0L // elapsedRealtime of Start, for the live clock only
    @Volatile private var realtimeStamps = true

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && running) openCamera() else if (!granted) say(R.string.form_camera_permission, warn = true)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        mode = intent.getStringExtra("mode")?.takeIf { it in setOf("rom", "balance", "sprint", "agility", "reaction") } ?: "rom"
        intent.getStringExtra("joint")?.let { key -> joint = RangeOfMotion.Joint.entries.firstOrNull { it.key == key } ?: joint }
        distance = intent.getDoubleExtra("distance", 20.0).coerceIn(1.0, 400.0)
        startKind = if (intent.getStringExtra("start") == "crossing") "crossing" else "cue"
        agilityTest = if (intent.getStringExtra("test") == "t_test") "t_test" else "505"
        replay = BuildConfig.DEBUG && pose && intent.getBooleanExtra("replay", false) && File(filesDir, LIVE_REPLAY_FILE).isFile
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildScreen()
        if (!replay && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun title(): String = when (mode) {
        "rom" -> getString(romLabel(joint.key))
        "balance" -> getString(R.string.ft_balance)
        "sprint" -> getString(R.string.ft_sprint_named, distance.format())
        "agility" -> getString(if (agilityTest == "t_test") R.string.ft_t_test else R.string.ft_505)
        else -> getString(R.string.ft_reaction)
    }

    private fun clock(seconds: Double, digits: Int) = String.format(resources.configuration.locales[0], "%.${digits}f", seconds)
    private fun Double.format() = if (this % 1.0 == 0.0) toInt().toString() else String.format(resources.configuration.locales[0], "%.1f", this)

    private fun label(value: String, size: Int, color: Int) = TextView(this).apply {
        instrumentType(); text = value; typeSize(size); setTextColor(getColor(color))
    }

    private fun buildScreen() {
        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.hero)) }
        skeleton = LiveFormPreview(this).apply { fill = true }
        if (pose) root.addView(skeleton, FrameLayout.LayoutParams(-1, -1)) else {
            cameraView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            root.addView(cameraView, FrameLayout.LayoutParams(-1, -1))
            band = TimingBand(this).also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        }
        val paper = getColor(R.color.paper)
        fun scrim(top: Boolean) = android.graphics.drawable.GradientDrawable(
            if (top) android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM else android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf((paper and 0xffffff) or (0xF0 shl 24), (paper and 0xffffff) or (0xC8 shl 24), paper and 0xffffff))
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(36)); background = scrim(true) }
        column.addView(header, LinearLayout.LayoutParams(-1, -2))
        header.addView(label(getString(if (replay) R.string.form_replay_debug else R.string.ui_live).uppercase(resources.configuration.locales[0]), 12, R.color.accent)
            .apply { letterSpacing = .12f; instrumentType(display = true) })
        header.addView(label(title(), 26, R.color.ink).apply { instrumentType(display = true) })
        banner = label("", 14, R.color.ink).apply {
            minHeight = dp(36); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(5), dp(10), dp(5))
            instrumentType(display = true); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        header.addView(banner, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        column.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(40), dp(16), dp(8)); background = scrim(false) }
        column.addView(footer, LinearLayout.LayoutParams(-1, -2))
        readout = Measurement(this).apply {
            textSize = 64f; setTextColor(getColor(R.color.ink)); instrumentType(numeric = true); includeFontPadding = false
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        footer.addView(readout)
        detail = label("", 14, R.color.muted)
        footer.addView(detail)
        val buttons = LinearLayout(this)
        footer.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        secondary = styleButton(Button(this), primary = false).apply {
            isAllCaps = false; minHeight = dp(56); visibility = View.GONE; text = getString(R.string.ft_done)
            setOnClickListener { if (mode == "rom") finishRom(manual = true) }
        }
        buttons.addView(secondary, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })
        action = styleButton(Button(this), primary = true).apply {
            text = getString(R.string.form_start); isAllCaps = false; minHeight = dp(56); textSize = 17f
            isEnabled = !pose
            setOnClickListener { if (active) stop() else start() }
        }
        buttons.addView(action, LinearLayout.LayoutParams(0, -2, 1.4f))
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else @Suppress("DEPRECATION") view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root)
        @Suppress("DEPRECATION") window.statusBarColor = paper
        @Suppress("DEPRECATION") window.navigationBarColor = paper
        root.requestApplyInsets()
        idleText()
    }

    private fun idleText() {
        readout.text = when (mode) { "rom" -> "—°"; "balance" -> clock(0.0, 1); else -> clock(0.0, 2) }
        detail.text = getString(when (mode) {
            "rom" -> if (joint.view == JumpAnalyzer.View.SIDE) R.string.ft_rom_live_side else R.string.ft_rom_live_front
            "balance" -> R.string.ft_balance_live
            "reaction" -> R.string.ft_reaction_live
            else -> if (mode == "sprint" && startKind == "cue") R.string.ft_sprint_live_cue else R.string.ft_crossing_live
        })
        say(if (pose) R.string.form_finding_camera else R.string.ft_line_ready, warn = false)
    }

    private fun say(text: Int, warn: Boolean = false, good: Boolean = false, vararg args: Any) {
        banner.text = getString(text, *args)
        banner.background = shape(getColor(if (warn) R.color.notice else if (good) R.color.ready_surface else R.color.hover), 14)
        banner.setTextColor(getColor(if (warn) R.color.warning else if (good) R.color.ready else R.color.ink))
    }

    private fun beep(kind: Int = ToneGenerator.TONE_PROP_BEEP, ms: Int = 150) {
        if (tone == null) tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull()
        tone?.startTone(kind, ms)
    }

    override fun onStart() {
        super.onStart()
        running = true
        if (replay || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
    }

    private fun openCamera() {
        val ticket = ++generation
        if (!pose) return runOnUiThread { bindCamera(ticket, null) }
        worker.execute {
            try {
                val cameraFeed = CameraFormFeed(applicationContext, worker, { bitmap, frame, _, _ ->
                    runOnUiThread { if (ticket == generation && running) onPose(bitmap, frame) }
                }, { runOnUiThread { if (ticket == generation) failed() } })
                feed?.close(); feed = cameraFeed
                if (replay) openReplay(ticket, cameraFeed) else runOnUiThread { if (ticket == generation && running) bindCamera(ticket, cameraFeed) }
            } catch (_: Exception) { runOnUiThread { if (ticket == generation) failed() } }
        }
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun bindCamera(ticket: Int, cameraFeed: CameraFormFeed?) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!running || ticket != generation) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                // Pose tools face the athlete (front camera when present); timing tools watch the line (back camera).
                mirror = pose && cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                val selector = if (mirror) CameraSelector.DEFAULT_FRONT_CAMERA else if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA))
                    CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                val builder = ImageAnalysis.Builder().setTargetRotation(Surface.ROTATION_0)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                if (pose) builder.setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                val analyzer = builder.build()
                analyzer.setAnalyzer(worker) { image ->
                    try { if (cameraFeed != null) cameraFeed.camera(image, null) else timingFrame(image) }
                    catch (_: RuntimeException) { runOnUiThread { if (ticket == generation) failed() } }
                }
                cameraProvider.unbindAll()
                if (pose) cameraProvider.bindToLifecycle(this, selector, analyzer)
                else {
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(cameraView!!.surfaceProvider) }
                    val camera = cameraProvider.bindToLifecycle(this, selector, preview, analyzer)
                    realtimeStamps = Camera2CameraInfo.from(camera.cameraInfo)
                        .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
                }
            } catch (_: Exception) { failed() }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Debug only: a bundled clip drives the pose feed, as in the live form tracker. */
    private fun openReplay(ticket: Int, cameraFeed: CameraFormFeed) {
        val video = MediaMetadataRetriever().also { retriever = it }
        video.setDataSource(File(filesDir, LIVE_REPLAY_FILE).absolutePath)
        val duration = checkNotNull(video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
        val width = checkNotNull(video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)).toInt()
        val height = checkNotNull(video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)).toInt()
        val scale = minOf(1.0, 640.0 / maxOf(width, height))
        val start = SystemClock.elapsedRealtime()
        fun next() {
            if (!running || ticket != generation) return
            val now = SystemClock.elapsedRealtime()
            val position = ((now - start) / 66 * 66) % duration
            try {
                if (cameraFeed.isReady()) {
                    val bitmap = video.scaledFrame(position * 1000,
                        (width * scale).toInt(), (height * scale).toInt())?.copy(Bitmap.Config.ARGB_8888, false)
                    if (bitmap != null) cameraFeed.submit(bitmap, now, null)
                }
                worker.schedule({ next() }, 20, TimeUnit.MILLISECONDS)
            } catch (_: Exception) { runOnUiThread { if (ticket == generation) failed() } }
        }
        next()
    }

    // --- pose tools ---

    private val calibration = ArrayDeque<Frame>(10)

    private fun onPose(bitmap: Bitmap, frame: Frame) {
        skeleton.show(bitmap, frame, mirror)
        aspect = bitmap.width.toFloat() / bitmap.height
        if (calibration.size == 10) calibration.removeFirst()
        calibration.addLast(frame)
        val view = if (calibration.size == 10) FormStream.cameraView(calibration.toList(), aspect) else JumpAnalyzer.View.AUTO
        if (!active) {
            val ready = if (mode == "rom") view == joint.view else view == JumpAnalyzer.View.FRONT
            action.isEnabled = ready || replay
            when {
                ready || replay -> say(R.string.form_frame_ready, good = true)
                view == JumpAnalyzer.View.AUTO -> say(R.string.form_hold_frame)
                else -> say(if ((if (mode == "rom") joint.view else JumpAnalyzer.View.FRONT) == JumpAnalyzer.View.SIDE) R.string.ft_turn_side else R.string.ft_face_camera, warn = true)
            }
            if (mode == "rom") showAngle(frame)
            return
        }
        if (mode == "rom") romFrame(frame, view) else balanceFrame(frame)
    }

    private fun romSides(): List<Int> = RangeOfMotion.measuredSides(frames, joint, joint.view)

    private fun showAngle(frame: Frame): List<Double?> {
        val sides = if (frames.isEmpty()) RangeOfMotion.measuredSides(listOf(frame), joint, joint.view) else romSides()
        val angles = sides.map { RangeOfMotion.angle(frame, aspect, joint, it) }
        readout.text = angles.joinToString("  ") { a -> a?.let { "%.0f°".format(it) } ?: "—°" }
        return angles
    }

    private fun romFrame(frame: Frame, view: JumpAnalyzer.View) {
        frames += frame
        if (frame.tMs - frames.first().tMs > 60_000) frames.removeAt(0)
        showAngle(frame)
        if (view != JumpAnalyzer.View.AUTO && view != joint.view) {
            say(if (joint.view == JumpAnalyzer.View.SIDE) R.string.ft_turn_side else R.string.ft_face_camera, warn = true); return
        }
        val sides = romSides()
        if (phase == 2 && firstHold != null && sides.size == 1 && firstHold!!.let { (if (sides[0] == 0) it.leftDeg else it.rightDeg) != null }) {
            frames.clear() // still the first side: start the second side's window afresh once the athlete turns
            say(R.string.ft_other_side, warn = true); return
        }
        val samples = sides.map { side -> frames.mapNotNull { f -> RangeOfMotion.angle(f, aspect, joint, side)?.let { f.tMs to it } } }
        val still = samples.all { RangeOfMotion.stillNow(it) && it.last().second >= 20 }
        if (!still) { say(R.string.ft_hold_end_range); return }
        val held = RangeOfMotion.analyze(frames, aspect, joint)
        if (held.leftDeg == null && held.rightDeg == null) { say(R.string.ft_hold_end_range); return }
        beep(ToneGenerator.TONE_PROP_ACK, 200)
        if (joint.view == JumpAnalyzer.View.SIDE && phase == 1) {
            firstHold = held; phase = 2; frames.clear()
            detail.text = getString(R.string.ft_rom_first_done, (held.leftDeg ?: held.rightDeg ?: 0.0).let { "%.0f°".format(it) })
            say(R.string.ft_other_side, good = true)
        } else finishRom(manual = false, last = held)
    }

    private fun finishRom(manual: Boolean, last: RangeOfMotion.Result? = null) {
        val current = last ?: frames.takeIf { it.size >= 5 }?.let { RangeOfMotion.analyze(it, aspect, joint) }
            ?.takeIf { "wrong_view" !in it.warnings }
        val result = listOfNotNull(firstHold, current).reduceOrNull(RangeOfMotion::merge)
        if (result == null || (result.leftDeg == null && result.rightDeg == null)) {
            if (manual) say(R.string.ft_no_hold, warn = true)
            return
        }
        done(result.toJson())
    }

    private fun balanceFrame(frame: Frame) {
        val tracking = tracker ?: return
        val result = tracking.feed(frame)
        val start = tracking.startMs
        if (result != null) return finishBalance(result)
        if (start == null) { say(R.string.ft_lift_foot); readout.text = clock(0.0, 1); return }
        readout.text = clock((frame.tMs - start) / 1000.0, 1)
        say(R.string.ft_balance_timing, good = true)
    }

    private fun finishBalance(result: Balance.Result) {
        beep(ToneGenerator.TONE_PROP_BEEP2, 400)
        val sway = JSONObject().put("sway_velocity_pct", result.swayVelocityPct ?: JSONObject.NULL)
            .put("noise_floor_pct", result.noiseFloorPct ?: JSONObject.NULL)
        // The server stores both sway numbers with the balance result (SPEC 10.4); the result screen reads them locally.
        done(JSONObject(result.toJson()).put("sway_velocity_pct", sway.get("sway_velocity_pct"))
            .put("noise_floor_pct", sway.get("noise_floor_pct")).toString(), sway)
    }

    /** Plays the cue tone; the cue time becomes final when the tone's output timestamp (or its fallback) arrives. */
    private fun cue(ms: Int) {
        cueFinal = false; cueFromAudio = false
        cueMs = clockMs() // provisional, so motion before the tone is still a false start
        runStarted = SystemClock.elapsedRealtime()
        say(R.string.ft_go, good = true)
        playCueTone(ms, realtimeStamps) { at, audio -> if (active) { cueMs = at; cueFromAudio = audio; cueFinal = true } }
    }

    // --- timing tools (luminance on the worker thread) ---

    private fun luma(image: ImageProxy, whole: Boolean): ByteArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rows = plane.rowStride; val pixel = plane.pixelStride
        val w = image.width; val h = image.height
        val step = if (whole) 16 else 4
        // The band is vertical on the portrait screen: rows across a landscape sensor image, columns otherwise.
        val sideways = image.imageInfo.rotationDegrees % 180 != 0
        val half = (LineCrossing.BAND / 2 * (if (sideways) h else w)).toInt().coerceAtLeast(2)
        val xs = if (whole || sideways) 0 until w step step else (w / 2 - half until w / 2 + half step 2)
        val ys = if (whole || !sideways) 0 until h step step else (h / 2 - half until h / 2 + half step 2)
        val out = ByteArray(xs.count() * ys.count())
        var i = 0
        for (y in ys) for (x in xs) out[i++] = buffer.get(y * rows + x * pixel)
        return out
    }

    private fun timingFrame(image: ImageProxy) {
        try {
            val t = image.imageInfo.timestamp / 1_000_000
            val line = luma(image, whole = false)
            val whole = luma(image, whole = true)
            val bandActivity = previous?.takeIf { it.size == line.size }?.let { LineCrossing.activity(it, line) } ?: 0.0
            val wholeActivity = previousWhole?.takeIf { it.size == whole.size }?.let { LineCrossing.activity(it, whole) } ?: 0.0
            previous = line; previousWhole = whole
            runOnUiThread { band?.activity = if (mode == "reaction") wholeActivity else bandActivity }
            if (!active) return
            stamps += t
            if (mode == "reaction") return reactionFrame(t, wholeActivity)
            detector.feed(t, bandActivity) ?: return
            if (t < ignoreBeforeMs) return
            val crossing = detector.crossingMs.roundToLong() // interpolated between the quiet and the busy frame
            if (mode == "sprint" && startKind == "cue") {
                if (cueMs < 0 || !cueFinal || crossing <= cueMs) return
                timed(crossing - cueMs)
            } else if (firstCrossing < 0) {
                firstCrossing = crossing
                runOnUiThread { beep(); runStarted = SystemClock.elapsedRealtime(); say(R.string.ft_running, good = true) }
            } else if (crossing - firstCrossing >= 500) timed(crossing - firstCrossing)
        } finally { image.close() }
    }

    private fun clockMs() = if (realtimeStamps) SystemClock.elapsedRealtimeNanos() / 1_000_000 else System.nanoTime() / 1_000_000

    private fun reactionFrame(t: Long, activity: Double) {
        if (t < ignoreBeforeMs) return
        motion += t to activity
        val cue = cueMs
        if (cue < 0) {
            if (activity >= LineCrossing.MOTION) runOnUiThread { abortTiming(R.string.ft_false_start) }
            return
        }
        if (!cueFinal) return // decide once the tone's real start time is known
        when (val result = LineCrossing.reaction(cue, motion)) {
            is LineCrossing.Reaction.Time -> timed(result.ms)
            LineCrossing.Reaction.FalseStart -> runOnUiThread { abortTiming(R.string.ft_false_start) }
            LineCrossing.Reaction.TooSlow -> if (t - cue > LineCrossing.REACTION_MAX_MS) runOnUiThread { abortTiming(R.string.ft_too_slow) }
        }
    }

    private fun timed(ms: Long) {
        val precision = LineCrossing.precisionMs(stamps.takeLast(120))
        val fps = if (precision > 0) 1000 / precision else 30.0
        val extra = when (mode) {
            "sprint" -> listOf("distance_m" to distance, "start" to startKind)
            "agility" -> listOf("test" to agilityTest)
            else -> emptyList()
        }
        val json = LineCrossing.json(mode, ms, precision, fps, extra)
        active = false
        val local = if (mode == "reaction" || startKind == "cue" && mode == "sprint") JSONObject().put("cue_clock", if (cueFromAudio) "audio" else "request") else null
        runOnUiThread { beep(ToneGenerator.TONE_PROP_BEEP2, 400); done(json, local) }
    }

    private fun abortTiming(reason: Int) {
        if (!active) return
        active = false; cueMs = -1; firstCrossing = -1
        action.setText(R.string.form_start)
        say(reason, warn = true)
        beep(ToneGenerator.TONE_PROP_NACK, 300)
    }

    // --- start, stop and results ---

    private fun start() {
        if (!action.isEnabled) return
        active = true
        action.setText(R.string.form_stop)
        when (mode) {
            "rom" -> { frames.clear(); firstHold = null; phase = 1; secondary.visibility = View.VISIBLE; say(R.string.ft_hold_end_range) }
            "balance" -> { tracker = Balance.Tracker(aspect); say(R.string.ft_lift_foot) }
            else -> worker.execute {
                detector = LineCrossing.Detector(); stamps.clear(); motion.clear(); cueMs = -1; firstCrossing = -1
                // Touching the phone shakes the picture: ignore the first second after Start.
                ignoreBeforeMs = clockMs() + 1000
                runOnUiThread { startTiming() }
            }
        }
    }

    private fun startTiming() {
        runStarted = 0L
        when {
            mode == "reaction" -> {
                say(R.string.ft_reaction_wait)
                val delay = Random.nextLong(2000, 5000)
                readout.postDelayed({ if (active) cue(300) }, delay)
            }
            mode == "sprint" && startKind == "cue" -> {
                say(R.string.ft_marks); beep()
                readout.postDelayed({ if (active) { say(R.string.ft_set); beep() } }, 2000)
                readout.postDelayed({ if (active) cue(400) },
                    3000 + Random.nextLong(500, 2000))
            }
            else -> say(R.string.ft_wait_crossing)
        }
        tickClock()
    }

    private fun tickClock() {
        if (!active || pose) return
        if (runStarted > 0) readout.text = clock((SystemClock.elapsedRealtime() - runStarted) / 1000.0, 2)
        if (runStarted > 0 && SystemClock.elapsedRealtime() - runStarted > 120_000) return abortTiming(R.string.ft_too_slow)
        readout.postDelayed(::tickClock, 50)
    }

    private fun stop() {
        when (mode) {
            "rom" -> { finishRom(manual = true); if (!isFinishing) { active = false; action.setText(R.string.form_start); secondary.visibility = View.GONE } }
            "balance" -> {
                val result = tracker?.stop()
                active = false; tracker = null; action.setText(R.string.form_start)
                if (result != null) finishBalance(result) else say(R.string.ft_no_stance, warn = true)
            }
            else -> { active = false; cueMs = -1; firstCrossing = -1; action.setText(R.string.form_start); idleText() }
        }
    }

    private fun done(json: String, local: JSONObject? = null) {
        active = false
        setResult(RESULT_OK, Intent().putExtra("measurement", json).putExtra("realtime", realtimeStamps).putExtra("local", local?.toString()))
        finish()
    }

    private fun failed() {
        active = false
        action.isEnabled = false
        say(R.string.form_camera_error, warn = true)
    }

    override fun onStop() {
        running = false
        generation++
        active = false
        provider?.unbindAll()
        worker.execute { feed?.close(); feed = null; retriever?.release(); retriever = null }
        super.onStop()
    }

    override fun onDestroy() {
        tone?.release(); tone = null
        worker.shutdown()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

internal fun romLabel(key: String) = when (key) {
    "hip_flexion" -> R.string.ft_hip_flexion
    "shoulder_flexion" -> R.string.ft_shoulder_flexion
    "shoulder_abduction" -> R.string.ft_shoulder_abduction
    else -> R.string.ft_knee_flexion
}

/** getScaledFrameAtTime exists from API 27; the app supports 26, where the full frame is scaled here instead. */
internal fun MediaMetadataRetriever.scaledFrame(timeUs: Long, width: Int, height: Int): Bitmap? =
    if (android.os.Build.VERSION.SDK_INT >= 27) getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, width, height)
    else getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)?.let { Bitmap.createScaledBitmap(it, width, height, true) }
