package jumpscan.app

import android.content.Context
import android.graphics.Bitmap
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
import jumpscan.core.JumpMetrics
import jumpscan.core.Landmark
import kotlin.math.max
import kotlin.math.roundToInt

internal data class Analysis(val metrics: JumpMetrics, val truncated: Boolean)

internal fun analyzeVideo(context: Context, uri: Uri, test: String, progress: (Int) -> Unit): Analysis {
    val started = android.os.SystemClock.elapsedRealtime()
    val trial = if (BuildConfig.DEBUG) java.io.File(context.getExternalFilesDir(null), "trial.txt")
        .takeIf { it.exists() }?.readText()?.trim()?.split(",") else null
    val longSide = trial?.getOrNull(2)?.toInt() ?: 720
    val confidence = trial?.getOrNull(1)?.toFloat() ?: 0.5f
    val model = trial?.getOrNull(0)?.takeIf { it != "lite" }?.let {
        val bytes = java.io.File(context.getExternalFilesDir(null), "$it.task").readBytes()
        java.nio.ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { rewind() }
    }
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        fun metadata(key: Int) = retriever.extractMetadata(key)
        val duration = metadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ?: error("Cannot read this video's duration. Try another video.")
        require(duration > 0) { "This video has no frames." }
        val width = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        require(width > 0 && height > 0) { "Cannot read this video's dimensions." }
        val rotation = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val rotated = rotation % 180 != 0
        val displayWidth = if (rotated) height else width
        val displayHeight = if (rotated) width else height
        val aspect = displayWidth.toFloat() / displayHeight
        val scale = minOf(1.0, longSide.toDouble() / max(displayWidth, displayHeight))
        val targetWidth = max(1, (displayWidth * scale).roundToInt())
        val targetHeight = max(1, (displayHeight * scale).roundToInt())
        val limit = minOf(duration, 10_000L)
        val extractor = MediaExtractor()
        val timesUs = try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("This video has no video track.")
            extractor.selectTrack(track)
            val times = mutableListOf<Long>()
            while (extractor.sampleTime >= 0 && extractor.sampleTime < limit * 1000) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                times += extractor.sampleTime
                if (!extractor.advance()) break
            }
            // Sort presentation times because compressed samples may arrive in decoding order.
            times.distinct().sorted()
        } finally { extractor.release() }
        require(timesUs.size >= 2) { "This video has too few frames." }
        val fps = (timesUs.size - 1) * 1_000_000.0 / (timesUs.last() - timesUs.first())
        require(fps.isFinite() && fps in 10.0..480.0) { "Use a video between 10 and 480 frames per second." }
        // Core and VIDEO mode use milliseconds; reject samples that cannot have distinct timestamps.
        require(timesUs.zipWithNext().all { (a, b) -> b / 1000 > a / 1000 }) {
            "This video's frame timestamps are too close together. Try another video."
        }
        val count = timesUs.size
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
             .setBaseOptions(BaseOptions.builder().apply {
                if (model == null) setModelAssetPath("pose_landmarker_lite.task") else setModelAssetBuffer(model)
            }.build())
            .setMinPoseDetectionConfidence(confidence)
            .setMinPosePresenceConfidence(confidence)
            .setMinTrackingConfidence(confidence)
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(1)
            .build()
        val landmarker = PoseLandmarker.createFromOptions(context, options)
        try {
            val frames = ArrayList<Frame>(count)
            val absent = List(33) { Landmark(0f, 0f, 0f, 0f) }
            var foundPerson = false
            for (index in 0 until count) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val timeUs = timesUs[index]
                val tMs = timeUs / 1000
                val raw = if (Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST,
                        targetWidth, targetHeight)
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                } ?: error("Cannot decode this video. Try an MP4 video from your camera.")
                // Retriever applies the display rotation; verify its bitmap aspect before using image coordinates.
                require(kotlin.math.abs(raw.width.toDouble() / raw.height - aspect) < 0.01) {
                    "Cannot decode this video's orientation. Try another video."
                }
                if (BuildConfig.DEBUG && index == 0) android.util.Log.d("JumpScan",
                    "Decoded ${raw.width}x${raw.height}, rotation=$rotation, aspect=$aspect, samples=$count")
                val resized = if (max(raw.width, raw.height) > longSide) {
                    val factor = longSide.toDouble() / max(raw.width, raw.height)
                    Bitmap.createScaledBitmap(raw, max(1, (raw.width * factor).roundToInt()),
                        max(1, (raw.height * factor).roundToInt()), true).also { if (it !== raw) raw.recycle() }
                } else raw
                val bitmap = if (resized.config == Bitmap.Config.ARGB_8888) resized
                    else resized.copy(Bitmap.Config.ARGB_8888, false).also { resized.recycle() }
                try {
                    val image = BitmapImageBuilder(bitmap).build()
                    try {
                        val result = landmarker.detectForVideo(image, tMs)
                        val pose = result.landmarks().firstOrNull()
                        val world = result.worldLandmarks().firstOrNull()
                        if (pose?.size == 33 && world?.size == 33) {
                            foundPerson = true
                            frames += Frame(tMs,
                                pose.map { Landmark(it.x(), it.y(), it.z(), it.visibility().orElse(0f)) },
                                world.map { Landmark(it.x(), it.y(), it.z(), it.visibility().orElse(0f)) })
                        } else {
                            // Keep missing frames so core computes the actual sampling rate and visibility.
                            frames += Frame(tMs, absent, absent)
                        }
                    } finally { image.close() }
                } finally { bitmap.recycle() }
                progress((index + 1) * 100 / count)
            }
            require(foundPerson) { "No person found. Face the camera with your whole body in frame." }
            // The constant debug guard removes this local-only fixture export from release bytecode.
            if (BuildConfig.DEBUG) {
                java.io.File(context.getExternalFilesDir(null), "landmarks.csv").bufferedWriter().use { out ->
                    out.appendLine("tMs,aspect," + listOf("image", "world").flatMap { space ->
                        (0 until 33).flatMap { i -> listOf("x", "y", "z", "visibility").map { "${space}_${i}_$it" } }
                    }.joinToString(","))
                    frames.forEach { frame ->
                        out.appendLine("${frame.tMs},$aspect," + (frame.landmarks + frame.worldLandmarks)
                            .flatMap { listOf(it.x, it.y, it.z, it.visibility) }.joinToString(","))
                    }
                }
            }
            if (BuildConfig.DEBUG) android.util.Log.d("JumpScan", "Analysis elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
            val metrics = try { JumpAnalyzer.analyze(frames, test, aspect) }
                catch (_: IllegalArgumentException) {
                    error("Too few clear frames. Keep hips, knees and feet visible, and try a longer video.")
                }
            // Container duration can extend past the last frame; warn only for at least one omitted frame.
            return Analysis(metrics, duration - limit > 1000.0 / fps)
        } finally { landmarker.close() }
    } finally { retriever.release() }
}
