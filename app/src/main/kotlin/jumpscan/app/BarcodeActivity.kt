package jumpscan.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/** Reads one product barcode with the on-device model and returns its digits; no image leaves the phone. */
class BarcodeActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(base.instrumentTheme())

    private val worker = Executors.newSingleThreadExecutor()
    private val scanner: BarcodeScanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E).build())
    private var camera: Camera? = null
    private var done = false
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var torch: Button
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) openCamera() else status.setText(R.string.scan_permission)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.hero)) }
        preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            contentDescription = getString(R.string.scan_title)
        }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        root.addView(Reticle(this), FrameLayout.LayoutParams(-1, -1))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
            background = raisedSurface(hero = true)
        }
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        val title = TextView(this).apply {
            setText(R.string.scan_title); textSize = 24f; setTextColor(Color.WHITE); instrumentType(display = true)
        }
        panel.addView(title)
        status = TextView(this).apply {
            setText(R.string.scan_hint); textSize = 14f; setTextColor(getColor(R.color.on_hero)); instrumentType()
            setPadding(0, dp(4), 0, dp(10))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        panel.addView(status)
        val actions = LinearLayout(this)
        panel.addView(actions, LinearLayout.LayoutParams(-1, -2))
        torch = styleButton(Button(this), false, ActionTone.SECONDARY).apply {
            setText(R.string.scan_torch)
            setCompoundDrawablesRelativeWithIntrinsicBounds(lineIcon("torch", getColor(R.color.ink)), null, null, null)
            isEnabled = false
            setOnClickListener {
                val on = !isSelected
                camera?.cameraControl?.enableTorch(on)
                isSelected = on
                setText(if (on) R.string.scan_torch_off else R.string.scan_torch)
            }
        }
        actions.addView(torch, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        actions.addView(styleButton(Button(this), true).apply {
            setText(R.string.scan_manual)
            setOnClickListener { setResult(RESULT_FIRST_USER); finish() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            panel.setPadding(dp(20), dp(16), dp(20), dp(20) + insets.systemWindowInsetBottom)
            view.setPadding(insets.systemWindowInsetLeft, 0, insets.systemWindowInsetRight, 0)
            insets
        }
        setContentView(root)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun openCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching {
                val provider = future.get()
                val shown = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(worker) { frame ->
                    val image = frame.image
                    if (image == null || done) { frame.close(); return@setAnalyzer }
                    scanner.process(InputImage.fromMediaImage(image, frame.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes -> codes.firstNotNullOfOrNull { it.rawValue?.takeIf(::validBarcode) }?.let(::found) }
                        .addOnCompleteListener { frame.close() }
                }
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, shown, analysis).also {
                    torch.isEnabled = it.cameraInfo.hasFlashUnit()
                }
            }.onFailure { status.setText(R.string.scan_camera_failed) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun found(code: String) {
        if (done) return
        done = true
        val vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
            else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        runCatching { vibrator?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)) }
        setResult(RESULT_OK, Intent().putExtra("code", code))
        finish()
    }

    override fun onDestroy() {
        scanner.close()
        worker.shutdown()
        super.onDestroy()
    }

    /** Corner brackets in timing orange around the read zone, with a sweeping index line. */
    private class Reticle(context: Context) : View(context) {
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
        private val start = System.nanoTime()
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(canvas: Canvas) {
            val density = resources.displayMetrics.density
            val w = width * .78f; val h = w * .55f
            val left = (width - w) / 2; val top = height * .36f - h / 2
            pen.color = Color.BLACK; pen.alpha = 110; pen.style = Paint.Style.FILL
            canvas.drawRect(0f, 0f, width.toFloat(), top, pen)
            canvas.drawRect(0f, top + h, width.toFloat(), height.toFloat(), pen)
            canvas.drawRect(0f, top, left, top + h, pen)
            canvas.drawRect(left + w, top, width.toFloat(), top + h, pen)
            pen.style = Paint.Style.STROKE; pen.alpha = 255
            pen.color = context.getColor(R.color.signal); pen.strokeWidth = density * 4
            val arm = density * 26
            for ((x, y) in listOf(left to top, left + w to top, left to top + h, left + w to top + h)) {
                val dx = if (x == left) arm else -arm; val dy = if (y == top) arm else -arm
                canvas.drawLine(x, y, x + dx, y, pen); canvas.drawLine(x, y, x, y + dy, pen)
            }
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                val phase = ((System.nanoTime() - start) / 1.6e9 % 1.0).toFloat()
                val y = top + h * (if (phase < .5f) phase * 2 else 2 - phase * 2)
                pen.strokeWidth = density * 1.5f; pen.alpha = 200
                canvas.drawLine(left + arm / 2, y, left + w - arm / 2, y, pen)
                postInvalidateOnAnimation()
            }
        }
    }
}

/** Product codes the server accepts: 8 to 14 digits. */
internal fun validBarcode(code: String) = code.length in 8..14 && code.all { it in '0'..'9' }
