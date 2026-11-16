package jumpscan.app

import android.content.Context
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
import jumpscan.core.JumpMetrics
import jumpscan.core.Landmark
import jumpscan.core.readLandmarkCsv
import jumpscan.core.toJson
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

internal data class Analysis(val metrics: JumpMetrics, val truncated: Boolean,
    val preview: Bitmap, val contactFound: Boolean, val previewUnclear: Boolean)

internal fun analyzeVideo(context: Context, uri: Uri, test: String, mode: String = "full", progress: (Int) -> Unit): Analysis {
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
        val limit = minOf(duration, 10_000L)
        val extractor = MediaExtractor()
        val timesUs = try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw AppError(R.string.no_track)
            extractor.selectTrack(track)
            val times = mutableListOf<Long>()
            while (extractor.sampleTime >= 0 && extractor.sampleTime < limit * 1000) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                times += extractor.sampleTime
                if (!extractor.advance()) break
            }
            // Compressed samples can arrive in decoding order rather than presentation order.
            times.distinct().sorted()
        } finally { extractor.release() }
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

        fun detect(samples: List<Long>, model: String, longSide: Int, from: Int, to: Int): List<Frame> {
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
                    val bitmap = decode(timeUs, longSide)
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
                    progress(from + (index + 1) * (to - from) / samples.size)
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

        fun finish(frames: List<Frame>, phase: String): Analysis {
            export(frames, "landmarks.csv")
            if (BuildConfig.DEBUG) android.util.Log.d("JumpScan",
                "Analysis elapsedMs=${android.os.SystemClock.elapsedRealtime() - started} phase=$phase frames=${frames.size}")
            val landing = try { CoreLanding(frames, test, aspect) }
                catch (_: IllegalArgumentException) { throw AppError(R.string.too_few_clear_frames) }
            val previewFrame = landing.contactMs?.let { time -> frames.first { it.tMs == time } }
                ?: peakFlexionFrame(frames)
            val previewUs = timesUs.firstOrNull { it / 1000 == previewFrame.tMs } ?: previewFrame.tMs * 1000
            val previewIndex = timesUs.indexOfFirst { it / 1000 == previewFrame.tMs }
            val indexed = if (Build.VERSION.SDK_INT >= 28 && previewIndex >= 0)
                runCatching { retriever.getFrameAtIndex(previewIndex) }.getOrNull() else null
            val raw = if (indexed == null) decode(previewUs, 720) else {
                val display = if (kotlin.math.abs(indexed.width.toFloat() / indexed.height - aspect) < 0.01) indexed
                    else Bitmap.createBitmap(indexed, 0, 0, indexed.width, indexed.height,
                        Matrix().apply { postRotate(rotation.toFloat()) }, true).also { if (it !== indexed) indexed.recycle() }
                val factor = minOf(1f, 720f / max(display.width, display.height))
                val scaled = Bitmap.createScaledBitmap(display, max(1, (display.width * factor).roundToInt()),
                    max(1, (display.height * factor).roundToInt()), true).also { if (it !== display) display.recycle() }
                if (scaled.config == Bitmap.Config.ARGB_8888) scaled
                    else scaled.copy(Bitmap.Config.ARGB_8888, false).also { scaled.recycle() }
            }
            val preview = try { landingPreview(raw, previewFrame) } finally { raw.recycle() }
            if (BuildConfig.DEBUG) {
                android.util.Log.d("JumpScan", "Metrics ${landing.metrics.toJson()} contactMs=${landing.contactMs} previewMs=${previewFrame.tMs}")
                File(context.filesDir, "metrics.json").writeText(landing.metrics.toJson())
            }
            return Analysis(landing.metrics, duration - limit > 1000.0 / fps, preview, landing.contactMs != null, unclearPreview(previewFrame))
        }

        if (BuildConfig.DEBUG && mode == "replay") {
            val (fixtureAspect, frames) = File(context.filesDir, "replay.csv").bufferedReader().use(::readLandmarkCsv)
            require(kotlin.math.abs(fixtureAspect - aspect) < 0.01) { "Fixture aspect differs from video" }
            progress(100)
            return finish(frames, "replay")
        }
        // Two-pass modes are explicit debug experiments. Full analysis remains the production default.
        if (BuildConfig.DEBUG && mode in listOf("lite", "sparse")) {
            val coarseStarted = android.os.SystemClock.elapsedRealtime()
            val model = if (mode == "lite") "pose_landmarker_lite.task" else "pose_landmarker_heavy.task"
            val stride = if (mode == "lite") 2 else 3
            val coarse = detect(timesUs.filterIndexed { index, _ -> index % stride == 0 }, model, 360, 0, 35)
            export(coarse, "coarse.csv")
            val landing = try { CoreLanding(coarse, test, aspect) } catch (_: IllegalArgumentException) { null }
            val contact = landing?.contactMs
            if (BuildConfig.DEBUG) android.util.Log.d("JumpScan",
                "Coarse elapsedMs=${android.os.SystemClock.elapsedRealtime() - coarseStarted} contactMs=$contact flightMs=${landing?.metrics?.flightTimeMs}")
            if (contact != null) {
                val start = (contact - (landing.metrics.flightTimeMs ?: 0.0).toLong() - 1500).coerceAtLeast(0)
                val end = contact + 1000
                val window = timesUs.filter { it / 1000 in start..end }
                if (BuildConfig.DEBUG) android.util.Log.d("JumpScan", "Window startMs=$start endMs=$end frames=${window.size}")
                return finish(detect(window, "pose_landmarker_heavy.task", 720, 35, 100), mode)
            }
            if (BuildConfig.DEBUG) android.util.Log.d("JumpScan", "Coarse pass found no landing; using full analysis")
            return finish(detect(timesUs, "pose_landmarker_heavy.task", 720, 35, 100), "fallback")
        }
        return finish(detect(timesUs, "pose_landmarker_heavy.task", 720, 0, 100), "full")
    } finally { retriever.release() }
}
