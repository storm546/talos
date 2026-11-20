package jumpscan.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import jumpscan.core.Frame
import jumpscan.core.Landmark
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal const val LIVE_REPLAY_FILE = "form-live-replay.mp4"

internal class CameraFormFeed(context: Context, private val worker: Executor,
    private val output: (Bitmap, Frame, Long, RunTiming?) -> Unit, onError: () -> Unit) : AutoCloseable {
    private val busy = AtomicBoolean()
    @Volatile private var closed = false
    @Volatile private var submittedAt = 0L
    @Volatile private var submittedBitmap: Bitmap? = null
    @Volatile private var submittedTiming: RunTiming? = null
    private var lastStamp = -1L
    private val detector = PoseLandmarker.createFromOptions(context,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").setDelegate(Delegate.CPU).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener { result, _ ->
                if (!closed) {
                    val latency = SystemClock.elapsedRealtimeNanos() - submittedAt
                    val timing = submittedTiming
                    val empty = List(33) { Landmark(0f, 0f, 0f, 0f) }
                    val imagePose = result.landmarks().firstOrNull()?.map {
                        Landmark(it.x(), it.y(), it.z(), it.visibility().orElse(0f))
                    } ?: empty
                    val worldPose = result.worldLandmarks().firstOrNull()?.map {
                        Landmark(it.x(), it.y(), it.z(), it.visibility().orElse(0f))
                    } ?: empty
                    val bitmap = checkNotNull(submittedBitmap)
                    worker.execute {
                        try {
                            if (!closed) output(bitmap, Frame(result.timestampMs(), imagePose, worldPose), latency, timing)
                        } finally { busy.set(false) }
                    }
                }
            }
            .setErrorListener { error ->
                android.util.Log.w("JumpScanForm", "pose tracking failed", error)
                busy.set(false); if (!closed) onError()
            }
            .build())

    fun camera(image: ImageProxy, timing: RunTiming?) {
        try {
            if (closed || busy.get()) return
            val started = SystemClock.elapsedRealtimeNanos()
            val plane = image.planes[0]
            val width = plane.rowStride / 4
            val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            val pixels = plane.buffer
            pixels.rewind()
            val transform = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
            if (width == image.width) bitmap.copyPixelsFromBuffer(pixels)
            else {
                val rowBytes = image.width * 4
                val packed = java.nio.ByteBuffer.allocate(rowBytes * image.height)
                val row = ByteArray(rowBytes)
                repeat(image.height) { index ->
                    pixels.position(index * plane.rowStride)
                    pixels.get(row)
                    packed.put(row)
                }
                packed.flip()
                bitmap.copyPixelsFromBuffer(packed)
            }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height, transform, false)
            if (rotated !== bitmap) bitmap.recycle()
            timing?.decoded(SystemClock.elapsedRealtimeNanos() - started)
            submit(rotated, image.imageInfo.timestamp / 1_000_000, timing)
        } finally { image.close() }
    }

    fun submit(bitmap: Bitmap, timestamp: Long, timing: RunTiming?): Boolean {
        if (closed || timestamp <= lastStamp || !busy.compareAndSet(false, true)) return false
        submittedAt = SystemClock.elapsedRealtimeNanos()
        submittedBitmap = bitmap
        submittedTiming = timing
        lastStamp = timestamp
        try { detector.detectAsync(BitmapImageBuilder(bitmap).build(), timestamp) }
        catch (error: RuntimeException) { busy.set(false); throw error }
        return true
    }

    fun isReady() = !closed && !busy.get()

    override fun close() {
        closed = true
        detector.close()
    }
}
