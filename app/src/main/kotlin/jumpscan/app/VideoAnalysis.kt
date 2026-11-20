package jumpscan.app

import android.content.Context
import jumpscan.core.FormAnalyzer
import jumpscan.core.FormMetrics
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import jumpscan.core.Frame
import jumpscan.core.JumpAnalyzer
import jumpscan.core.Landmark
import jumpscan.core.readLandmarkCsv
import jumpscan.core.toJson
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

internal data class Analysis(val landing: JumpAnalyzer.Analysis, val truncated: Boolean,
    val preview: Bitmap, val previewUnclear: Boolean, val frames: List<Frame>, val timesUs: List<Long>,
    val uri: Uri, val aspect: Float) {
    val metrics get() = landing.metrics
    val contactFound get() = landing.contactMs != null
}

/** Decode only the requested frame; retained results keep timestamps and poses, not a bitmap per frame. */
internal fun videoFrame(context: Context, uri: Uri, timesUs: List<Long>, index: Int, aspect: Float): Bitmap {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        return videoFrame(retriever, timesUs, index, aspect)
    } finally { retriever.release() }
}

private fun videoFrame(retriever: MediaMetadataRetriever, timesUs: List<Long>, index: Int, aspect: Float): Bitmap {
    val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
    val raw = retriever.getFrameAtTime(timesUs[index], MediaMetadataRetriever.OPTION_CLOSEST)
        ?: throw AppError(R.string.decode_error)
    val display = if (kotlin.math.abs(raw.width.toFloat() / raw.height - aspect) < 0.01) raw
        else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height,
            Matrix().apply { postRotate(rotation.toFloat()) }, true).also { if (it !== raw) raw.recycle() }
    val factor = minOf(1f, 720f / max(display.width, display.height))
    val scaled = Bitmap.createScaledBitmap(display, max(1, (display.width * factor).roundToInt()),
        max(1, (display.height * factor).roundToInt()), true).also { if (it !== display) display.recycle() }
    return if (scaled.config == Bitmap.Config.ARGB_8888) scaled
        else scaled.copy(Bitmap.Config.ARGB_8888, false).also { scaled.recycle() }
}

internal fun framePreview(context: Context, analysis: Analysis, index: Int): Bitmap {
    val raw = videoFrame(context, analysis.uri, analysis.timesUs, index, analysis.aspect)
    val frame = analysis.frames.firstOrNull { it.tMs == analysis.timesUs[index] / 1000 }
    return try { if (frame == null) raw.copy(Bitmap.Config.ARGB_8888, false) else landingPreview(raw, frame) }
        finally { raw.recycle() }
}

internal fun applyEdges(context: Context, analysis: Analysis, takeoffMs: Long, landingMs: Long): Analysis {
    val landing = JumpAnalyzer.analyzeWithLanding(analysis.frames, analysis.metrics.test, analysis.aspect,
        takeoffMs = takeoffMs, landingMs = landingMs)
    val preview = framePreview(context, analysis, analysis.timesUs.indexOfFirst { it / 1000 == landingMs })
    return analysis.copy(landing = landing, preview = preview,
        previewUnclear = unclearPreview(analysis.frames.first { it.tMs == landingMs }))
}

internal fun analyzeVideo(context: Context, uri: Uri, test: String, mode: String = "full", progress: (Int) -> Unit): Analysis =
    withVideoPose(context, uri, mode, 10_000L, progress) { retriever, frames, timesUs, aspect, truncated ->
        val landing = try { JumpAnalyzer.analyzeWithLanding(frames, test, aspect) }
            catch (_: IllegalArgumentException) { throw AppError(R.string.too_few_clear_frames) }
        val previewFrame = landing.contactMs?.let { time -> frames.first { it.tMs == time } } ?: peakFlexionFrame(frames)
        val previewIndex = timesUs.indexOfFirst { it / 1000 == previewFrame.tMs }
        val raw = videoFrame(retriever, timesUs, previewIndex, aspect)
        val preview = try { landingPreview(raw, previewFrame) } finally { raw.recycle() }
        if (BuildConfig.DEBUG) {
            android.util.Log.d("JumpScan", "Metrics ${landing.metrics.toJson()} contactMs=${landing.contactMs} previewMs=${previewFrame.tMs}")
            File(context.filesDir, "metrics.json").writeText(landing.metrics.toJson())
        }
        Analysis(landing, truncated, preview, unclearPreview(previewFrame), frames, timesUs, uri, aspect)
    }

internal fun analyzeFormVideo(context: Context, uri: Uri, choice: FormChoice, progress: (Int) -> Unit): FormMetrics =
    withVideoPose(context, uri, "form", 60_000L, progress) { _, frames, _, aspect, _ ->
        try { FormAnalyzer.analyze(frames, choice.exercise, choice.side, aspect) }
        catch (_: IllegalArgumentException) { throw AppError(R.string.too_few_clear_frames) }
    }

internal fun videoSampleTimes(context: Context, uri: Uri, limitUs: Long): List<Long> {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: throw AppError(R.string.no_track)
        extractor.selectTrack(track)
        val times = mutableListOf<Long>()
        while (extractor.sampleTime >= 0) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val time = extractor.sampleTime
            if (time < limitUs) times += time
            if (!extractor.advance()) break
        }
        times.distinct().sorted()
    } finally { extractor.release() }
}

private fun <T> withVideoPose(context: Context, uri: Uri, mode: String, maxMs: Long, progress: (Int) -> Unit,
    finish: (MediaMetadataRetriever, List<Frame>, List<Long>, Float, Boolean) -> T): T {
    val started = android.os.SystemClock.elapsedRealtime()
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        fun metadata(key: Int) = retriever.extractMetadata(key)
        val duration = metadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ?: throw AppError(R.string.duration_error)
        if (duration <= 0) throw AppError(R.string.no_frames)
        val width = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        if (width <= 0 || height <= 0) throw AppError(R.string.dimensions_error)
        val rotation = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val displayWidth = if (rotation % 180 != 0) height else width
        val displayHeight = if (rotation % 180 != 0) width else height
        val aspect = displayWidth.toFloat() / displayHeight
        if (mode == "form" && duration > maxMs) throw AppError(R.string.form_clip_limit)
        val limit = minOf(duration, maxMs)
        val timesUs = videoSampleTimes(context, uri, limit * 1000)
        if (timesUs.size < 2) throw AppError(R.string.too_few_frames)
        val fps = (timesUs.size - 1) * 1_000_000.0 / (timesUs.last() - timesUs.first())
        if (!fps.isFinite() || fps !in 10.0..480.0) throw AppError(R.string.fps_error)
        if (!timesUs.zipWithNext().all { (a, b) -> b / 1000 > a / 1000 })
            throw AppError(R.string.timestamps_error)

        fun decode(timeUs: Long, longSide: Int): Bitmap {
            val scale = minOf(1.0, longSide.toDouble() / max(displayWidth, displayHeight))
            val raw = (if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST,
                    max(1, (displayWidth * scale).roundToInt()), max(1, (displayHeight * scale).roundToInt()))
            } else retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST))
                ?: throw AppError(R.string.decode_error)
            // Retriever applies display rotation before image coordinates are used.
            if (kotlin.math.abs(raw.width.toDouble() / raw.height - aspect) >= 0.01) {
                raw.recycle()
                throw AppError(R.string.orientation_error)
            }
            val resized = if (max(raw.width, raw.height) > longSide) {
                val factor = longSide.toDouble() / max(raw.width, raw.height)
                Bitmap.createScaledBitmap(raw, max(1, (raw.width * factor).roundToInt()),
                    max(1, (raw.height * factor).roundToInt()), true).also { if (it !== raw) raw.recycle() }
            } else raw
            return if (resized.config == Bitmap.Config.ARGB_8888) resized
                else resized.copy(Bitmap.Config.ARGB_8888, false).also { resized.recycle() }
        }

        fun detect(): List<Frame> {
            val samples = timesUs
            val model = "pose_landmarker_heavy.task"
            val passStarted = android.os.SystemClock.elapsedRealtime()
            var decodeMs = 0L
            var inferenceMs = 0L
            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(model).build())
                .setMinPoseDetectionConfidence(0.5f).setMinPosePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f).setRunningMode(RunningMode.VIDEO).setNumPoses(1).build()
            val landmarker = PoseLandmarker.createFromOptions(context, options)
            try {
                val frames = ArrayList<Frame>(samples.size)
                val absent = List(33) { Landmark(0f, 0f, 0f, 0f) }
                var foundPerson = false
                for ((index, timeUs) in samples.withIndex()) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val decodeStarted = if (BuildConfig.DEBUG) android.os.SystemClock.elapsedRealtime() else 0L
                    val bitmap = decode(timeUs, 720)
                    if (BuildConfig.DEBUG) decodeMs += android.os.SystemClock.elapsedRealtime() - decodeStarted
                    if (BuildConfig.DEBUG && index == 0) android.util.Log.d("JumpScan",
                        "Decoded ${bitmap.width}x${bitmap.height}, rotation=$rotation, aspect=$aspect, samples=${samples.size}, model=$model, mode=$mode")
                    try {
                        val image = BitmapImageBuilder(bitmap).build()
                        try {
                            val inferenceStarted = if (BuildConfig.DEBUG) android.os.SystemClock.elapsedRealtime() else 0L
                            val result = landmarker.detectForVideo(image, timeUs / 1000)
                            if (BuildConfig.DEBUG) inferenceMs += android.os.SystemClock.elapsedRealtime() - inferenceStarted
                            val pose = result.landmarks().firstOrNull()
                            val world = result.worldLandmarks().firstOrNull()
                            frames += if (pose?.size == 33 && world?.size == 33) {
                                foundPerson = true
                                Frame(timeUs / 1000,
                                    pose.map { Landmark(it.x(), it.y(), it.z(), it.visibility().orElse(0f)) },
                                    world.map { Landmark(it.x(), it.y(), it.z(), it.visibility().orElse(0f)) })
                            } else Frame(timeUs / 1000, absent, absent)
                        } finally { image.close() }
                    } finally { bitmap.recycle() }
                    progress((index + 1) * 100 / samples.size)
                }
                if (!foundPerson) throw AppError(R.string.no_person)
                if (BuildConfig.DEBUG) android.util.Log.d("JumpScan",
                    "Pass model=$model samples=${samples.size} elapsedMs=${android.os.SystemClock.elapsedRealtime() - passStarted} decodeMs=$decodeMs inferenceMs=$inferenceMs")
                return frames
            } finally { landmarker.close() }
        }

        fun export(frames: List<Frame>, name: String) {
            // Debug fixture export stays in app-private storage; release bytecode removes this branch.
            if (BuildConfig.DEBUG) File(context.filesDir, name).bufferedWriter().use { out ->
                out.appendLine("tMs,aspect," + listOf("image", "world").flatMap { space ->
                    (0 until 33).flatMap { i -> listOf("x", "y", "z", "visibility").map { "${space}_${i}_$it" } }
                }.joinToString(","))
                for (frame in frames) out.appendLine("${frame.tMs},$aspect," + (frame.landmarks + frame.worldLandmarks)
                    .flatMap { listOf(it.x, it.y, it.z, it.visibility) }.joinToString(","))
            }
        }

        fun complete(frames: List<Frame>, phase: String): T {
            if (mode != "form") export(frames, "landmarks.csv")
            if (BuildConfig.DEBUG) android.util.Log.d("JumpScan",
                "Analysis elapsedMs=${android.os.SystemClock.elapsedRealtime() - started} phase=$phase frames=${frames.size}")
            return finish(retriever, frames, timesUs, aspect, duration - limit > 1000.0 / fps)
        }

        if (BuildConfig.DEBUG && mode == "replay") {
            val (fixtureAspect, frames) = File(context.filesDir, "replay.csv").bufferedReader().use(::readLandmarkCsv)
            require(kotlin.math.abs(fixtureAspect - aspect) < 0.01) { "Fixture aspect differs from video" }
            progress(100)
            return complete(frames, "replay")
        }
        return complete(detect(), "full")
    } finally { retriever.release() }
}
