package jumpscan.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.camera.view.PreviewView
import jumpscan.core.FormEvent
import jumpscan.core.FormProgress
import jumpscan.core.FormRepetition
import jumpscan.core.FormStream
import jumpscan.core.Frame
import jumpscan.core.Landmark
import jumpscan.core.toJson
import java.io.File
import kotlin.math.abs

internal class CaptureChecks(private val test: Instrumentation) {
    private val context = test.targetContext

    private fun frame(time: Long = 0): Frame {
        val points = List(33) { joint ->
            Landmark(if (joint % 2 == 0) .56f else .44f, when (joint) {
                in 0..10 -> .23f
                in 11..12 -> .38f
                in 13..16 -> .51f
                in 23..24 -> .56f
                in 25..26 -> .66f
                else -> .76f
            }, 0f, 1f)
        }
        return Frame(time, points, points)
    }

    fun policies() {
        for (target in listOf(60, 120, 240)) {
            val times = List(target * 2) { it * 1_000_000L / target }
            check(abs(checkNotNull(checkedCaptureRate(times, 2_000_000, target)) - target) < .01)
            check(checkedCaptureRate(times, 2_400_000, target) == null)
            check(checkedCaptureRate(times.filterIndexed { index, _ -> index != 25 }, 2_000_000, target) == null)
            check(checkedCaptureRate(times.toMutableList().apply { this[25] = this[24] }, 2_000_000, target) == null)
        }
        val full = frame()
        check(jumpFraming(full) == R.string.jump_frame_ready)
        check(jumpFraming(full.copy(landmarks = full.landmarks.mapIndexed { index, point ->
            if (index <= 10) point.copy(y = .09f) else point
        })) == R.string.jump_frame_space)
        check(jumpFraming(full.copy(landmarks = full.landmarks.mapIndexed { index, point ->
            if (index == 32) point.copy(y = 1.01f) else point
        })) == R.string.jump_frame_body)
        val landing = JumpLandingWatch(.76f)
        check(listOf(.76f, .75f, .68f, .64f, .66f, .75f, .77f).map(landing::landed) ==
            listOf(false, false, false, false, false, false, true))
        val cues = RepeatCue()
        check(cues.record(listOf("depth", "knees")) == null)
        check(cues.record(emptyList()) == null)
        check(cues.record(listOf("knees", "depth")) == "knees")
        check(cues.record(emptyList()) == null)
        check(cues.record(emptyList()) == null)
        check(cues.record(listOf("knees")) == null)
    }

    private fun clip(fps: Int) = Uri.fromFile(File(context.filesDir, "jump-$fps.mp4"))

    fun parity(): String {
        val rejected = runCatching { analyzeVideo(context, clip(30), "cmj", captureFps = 60) {} }.exceptionOrNull()
        check(rejected is AppError)
        val old30 = analyzeVideo(context, clip(30), "cmj") {}
        val old60 = analyzeVideo(context, clip(60), "cmj") {}
        val capture = analyzeVideo(context, clip(60), "cmj", captureFps = 60) {}
        check(old30.metrics.flightTimeMs != null && old60.metrics.flightTimeMs != null)
        check(old30.frames.size * 2 == old60.frames.size)
        check(capture.timesUs == old60.timesUs && capture.frames == old60.frames)
        check(capture.landing.copy(metrics = capture.metrics.copy(fps = old60.metrics.fps)) == old60.landing)
        File(context.filesDir, "landmarks.csv").copyTo(File(context.filesDir, "replay.csv"), overwrite = true)
        return "30 fps import: ${old30.metrics.toJson()}\n60 fps import: ${old60.metrics.toJson()}\n" +
            "60 fps capture analysis: ${capture.metrics.toJson()}"
    }

    fun visual(studio: StudioChecks? = null) {
        fun movie(name: String, action: () -> Unit) { if (studio == null) action() else studio.record(name, action) }
        val locale = context.resources.configuration.locales[0].language
        fun shot(name: String) {
            test.waitForIdleSync()
            SystemClock.sleep(380)
            val image = checkNotNull(test.uiAutomation.takeScreenshot())
            File(context.filesDir, "$locale-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        fun <T : Activity> launch(clazz: Class<T>, extras: Intent.() -> Unit = {}): T = clazz.cast(
            test.startActivitySync(Intent(context, clazz).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply(extras)))!!
        fun field(instance: Any, name: String) = instance.javaClass.getDeclaredField(name).apply { isAccessible = true }
        fun call(instance: Any, name: String, vararg arguments: Any?) = instance.javaClass.declaredMethods.single {
            it.name == name && it.parameterCount == arguments.size
        }.apply { isAccessible = true }.invoke(instance, *arguments)
        val still = checkNotNull(BitmapFactory.decodeFile(File(context.filesDir, "capture-pose.jpg").path))
        val capture = launch(JumpCaptureActivity::class.java)
        val statusField = field(capture, "status")
        val deadline = SystemClock.uptimeMillis() + 8_000
        do {
            SystemClock.sleep(250)
            var opened = false
            test.runOnMainSync {
                opened = (statusField.get(capture) as TextView).text != capture.getString(R.string.form_finding_camera)
            }
        } while (!opened && SystemClock.uptimeMillis() < deadline)
        shot("camera-capabilities")
        test.runOnMainSync {
            val camera = field(capture, "preview").get(capture) as PreviewView
            camera.addView(ImageView(capture).apply {
                setImageBitmap(still)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.BLACK)
            }, -1, -1)
            field(capture, "previewBound").setBoolean(capture, true)
            field(capture, "poseAfter").setLong(capture, Long.MAX_VALUE)
            val status = field(capture, "status").get(capture) as TextView
            val cropped = frame().copy(landmarks = frame().landmarks.mapIndexed { index, point ->
                if (index < 11) point.copy(y = .08f) else point
            })
            call(capture, "observe", cropped)
            check(status.text == capture.getString(R.string.jump_frame_space))
        }
        shot("framing-fixture")
        test.runOnMainSync {
            repeat(3) { call(capture, "observe", frame(SystemClock.uptimeMillis())) }
            val status = field(capture, "status").get(capture) as TextView
            check(status.text == capture.getString(R.string.jump_frame_ready))
        }
        shot("framing-ready-fixture")
        test.runOnMainSync {
            (field(capture, "rateNotice").get(capture) as View).visibility = View.GONE
            (field(capture, "start").get(capture) as Button).apply {
                setText(R.string.jump_record_stop)
                isEnabled = true
            }
            (field(capture, "pick").get(capture) as Button).isEnabled = false
        }
        movie("countdown") {
            test.runOnMainSync { call(capture, "showCountdown", 0L) }
            SystemClock.sleep(720)
            test.runOnMainSync {
                check((statusField.get(capture) as TextView).text == "3")
                call(capture, "showCountdown", 1_000L)
                check((statusField.get(capture) as TextView).text == "2")
            }
        }
        shot("countdown-fixture")
        test.runOnMainSync {
            call(capture, "showCountdown", 2_000L)
            check((statusField.get(capture) as TextView).text == "1")
            capture.finish()
        }

        val result = analyzeVideo(context, clip(60), "cmj", "replay") {}
        val prefs = context.getSharedPreferences("account", 0)
        prefs.edit().putString("server", "http://10.0.2.2:18767").putString("token", "capture-fixture").apply()
        val main = launch(MainActivity::class.java)
        movie("digit-roll") {
            test.runOnMainSync {
                field(main, "analysis").set(main, result)
                call(main, "result", null)
            }
        }
        shot("result")
        if (studio != null) {
            test.runOnMainSync { call(main, "editFrames", result) }
            shot("frame-editor")
        }
        test.runOnMainSync { main.finish() }
        prefs.edit().clear().commit()

        val coaching = context.getSharedPreferences("live-form", 0)
        coaching.edit().clear().commit()
        val form = launch(LiveFormActivity::class.java) { putExtra("exercise", "squat") }
        SystemClock.sleep(1000)
        val rep = FormRepetition(1, "partial", listOf("below_min_depth"), 0, 500, 950, 40.0,
            40.0, 40.0, null, null, null, 500, 450, null)
        test.runOnMainSync {
            field(form, "running").setBoolean(form, false)
            (field(form, "preview").get(form) as LiveFormPreview).show(still, frame(), false)
            field(form, "stream").set(form, FormStream("squat", null, .6f))
            (field(form, "action").get(form) as Button).setText(R.string.form_stop)
            val banner = field(form, "banner").get(form) as TextView
            call(form, "updateFeedback", FormProgress(partial = 1), listOf(FormEvent.End(rep)), true)
            check(banner.text != form.getString(R.string.form_more_range))
            call(form, "updateFeedback", FormProgress(complete = 1, partial = 1),
                listOf(FormEvent.End(rep.copy(index = 2, status = "complete", reasons = emptyList(), depth = 90.0))), true)
            call(form, "updateFeedback", FormProgress(complete = 1, partial = 2), listOf(FormEvent.End(rep.copy(index = 3))), true)
            check(banner.text == form.getString(R.string.form_more_range))
        }
        SystemClock.sleep(900)
        shot("live-cue-fixture")
        movie("live-counter") {
            test.runOnMainSync {
                val content = form.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as android.view.ViewGroup
                val header = content.getChildAt(0) as android.view.ViewGroup
                (header.getChildAt(header.childCount - 1) as Switch).isChecked = true
                call(form, "updateFeedback", FormProgress(complete = 2, partial = 3), listOf(FormEvent.End(rep.copy(index = 5))), true)
                check((field(form, "banner").get(form) as TextView).text != form.getString(R.string.form_more_range))
                check((field(form, "counter").get(form) as TextView).text == "2")
            }
        }
        shot("live-muted-fixture")
        test.runOnMainSync {
            field(form, "stream").set(form, null)
            form.finish()
        }
        coaching.edit().clear().commit()
    }
}
