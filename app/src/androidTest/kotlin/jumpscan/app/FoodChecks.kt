package jumpscan.app

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.Switch
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

private fun <T : View> foodViews(view: View, type: Class<T>): List<T> = when {
    type.isInstance(view) -> listOf(type.cast(view)!!)
    view is ViewGroup -> (0 until view.childCount).flatMap { foodViews(view.getChildAt(it), type) }
    else -> emptyList()
}

private fun Instrumentation.foodWait(what: String, condition: () -> Boolean) {
    val end = SystemClock.uptimeMillis() + 20000
    var met = false
    while (!met && SystemClock.uptimeMillis() < end) { runOnMainSync { met = condition() }; if (!met) SystemClock.sleep(150) }
    check(met) { "timed out waiting for $what" }
}

private fun Instrumentation.foodClick(host: MainActivity, label: String) = runOnMainSync {
    foodViews(host.page, Button::class.java).first { it.text.toString() == label }.performClick()
}

private fun testJpeg(width: Int, height: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(196, 120, 52)) }
    return ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it); bitmap.recycle(); it.toByteArray() }
}

/** SPEC 10.6 on the phone: a photo is shrunk to 1024 px, turned upright and loses its EXIF before it is sent. */
internal fun foodVisionChecks(test: Instrumentation) {
    val file = File(test.targetContext.cacheDir, "food-exif-check.jpg")
    file.writeBytes(testJpeg(3000, 2000))
    ExifInterface(file.path).apply {
        setAttribute(ExifInterface.TAG_GPS_LATITUDE, "42/1,41/1,0/1"); setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
        setAttribute(ExifInterface.TAG_MAKE, "TestCam"); setAttribute(ExifInterface.TAG_DATETIME, "2026:11:24 12:00:00")
        setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
        saveAttributes()
    }
    check(ExifInterface(file.path).getAttribute(ExifInterface.TAG_MAKE) == "TestCam")
    val out = foodJpeg(file.readBytes())
    file.delete()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(out, 0, out.size, bounds)
    check(bounds.outWidth == 683 && bounds.outHeight == 1024) { "size ${bounds.outWidth}x${bounds.outHeight}" }
    val exif = ExifInterface(ByteArrayInputStream(out))
    for (tag in listOf(ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_MAKE, ExifInterface.TAG_DATETIME))
        check(exif.getAttribute(tag) == null) { "$tag survived" }
    val small = foodJpeg(testJpeg(400, 300))
    BitmapFactory.decodeByteArray(small, 0, small.size, bounds)
    check(bounds.outWidth == 400 && bounds.outHeight == 300)  // never enlarged
    check(runCatching { foodJpeg(byteArrayOf(1, 2, 3)) }.exceptionOrNull() is AppError)
}

/** Photo review, pantry -> recipes and the stores list against a server started with JUMPSCAN_FOOD_FIXTURE=1;
 *  screenshots go to files/food-<language>-<name>.png. */
internal class FoodStudio(private val test: Instrumentation, private val server: String) {
    fun screens() {
        val context = test.targetContext
        val login = JSONObject(api(server, "/api/auth/login", "POST", body = JSONObject().put("email", "athlete1@demo.test").put("password", "demo1234")))
        val user = login.getJSONObject("user")
        context.getSharedPreferences("account", 0).edit().clear().putString("server", server).putString("token", login.getString("token"))
            .putString("name", user.getString("name")).putLong("user_id", user.getLong("id")).commit()
        context.getSharedPreferences("food", 0).edit().clear().commit()
        val host = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val language = host.resources.configuration.locales[0].language
        val day = todayLocal()
        fun shot(name: String) {
            SystemClock.sleep(900)
            test.requireAppWindow(name)
            val bitmap = checkNotNull(test.uiAutomation.takeScreenshot())
            File(context.filesDir, "food-$language-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun scrollTo(view: View) = test.runOnMainSync {
            var top = 0; var at: View = view
            while (at !== host.page) { top += at.top; at = at.parent as View }
            (host.page.parent as ScrollView).scrollTo(0, (top - host.dp(16)).coerceAtLeast(0))
        }
        fun scrollDown(screens: Float) = test.runOnMainSync {
            val scroll = host.page.parent as ScrollView
            scroll.scrollTo(0, (scroll.scrollY + scroll.height * screens).toInt())
        }
        fun tab(name: String, open: () -> Unit, ready: () -> Boolean) {
            test.runOnMainSync { open() }
            test.foodWait(name, ready)
            shot("tab-$name"); scrollDown(0.85f); shot("tab-$name-2"); scrollDown(5f); shot("tab-$name-end")
        }
        fun eyebrowShown(label: Int) = texts(host.page).any { it == host.getString(label).uppercase(host.resources.configuration.locales[0]) }
        try {
            check(test.screenOf(host, "home") == "home")
            // SPEC 10.9: Today's cards carry the server's findings; then the three parts and Profile.
            test.foodWait("today findings") { host.page.findViewWithTag<View>("card-sleep") != null }
            // The briefing answers within the model's time (up to a minute) or removes itself when there is no model.
            val end = SystemClock.uptimeMillis() + 75_000
            var settled = false
            while (!settled && SystemClock.uptimeMillis() < end) {
                test.runOnMainSync { settled = host.page.findViewWithTag<View>("today-briefing") == null ||
                    host.getString(R.string.today_briefing_note) in texts(host.page) }
                if (!settled) SystemClock.sleep(500)
            }
            shot("today"); scrollDown(0.85f); shot("today-2"); scrollDown(0.85f); shot("today-3"); scrollDown(0.85f); shot("today-4")
            tab("form", { host.formHome() }) { eyebrowShown(R.string.section_camera_tests) }
            tab("food", { host.nutritionHome() }) { eyebrowShown(R.string.section_food_more) }
            tab("training", { host.workoutStart() }) { eyebrowShown(R.string.section_training_more) }
            tab("profile", { host.profile() }) { eyebrowShown(R.string.section_you) }
            // The goal form's phase segments fit on one line each (bug from the phone, 24.11).
            test.runOnMainSync { host.insights(days = 30) }
            test.foodWait("goal button") { texts(host.page).any { it == host.getString(R.string.goal_set) || it == host.getString(R.string.goal_change) } }
            test.runOnMainSync { foodViews(host.page, Button::class.java).first { it.text == host.getString(R.string.goal_set) ||
                it.text == host.getString(R.string.goal_change) }.performClick() }
            check(test.screenOf(host, "goal_form") == "goal_form")
            test.runOnMainSync {
                val segments = foodViews(host.page, android.widget.RadioButton::class.java)
                check(segments.size == 3 && segments.all { it.lineCount == 1 }) { "segments: ${segments.map { it.text to it.lineCount }}" }
            }
            shot("goal-form")
            // The one-time notice comes before the first photo.
            test.runOnMainSync { diaryMeal = "lunch"; host.foodPhoto(day) }
            check(test.screenOf(host, "food_privacy") == "food_privacy")
            test.runOnMainSync { check(host.getString(R.string.food_privacy_text) in texts(host.page)) }
            shot("privacy")
            test.foodClick(host, host.getString(R.string.food_privacy_accept))
            check(test.screenOf(host, "food_photo") == "food_photo")

            // Meal photo -> review: three matched foods with grams and switches, one "not found".
            test.runOnMainSync { host.analyseFoodPhoto(day) { testJpeg(1600, 1200) } }
            check(test.screenOf(host, "food_photo_review") == "food_photo_review")
            test.runOnMainSync {
                val shown = texts(host.page)
                check(shown.any { it.endsWith(host.getString(R.string.food_photo_not_found)) }) { "no unmatched row in $shown" }
                check(foodViews(host.page, Switch::class.java).size == 3)
                check(shown.any { it.startsWith(host.getString(R.string.food_photo_total, "").trim()) })
            }
            shot("photo-review")
            test.foodClick(host, host.getString(R.string.ai_entry_confirm))
            check(test.screenOf(host, "nutrition") == "nutrition")
            test.foodWait("logged notice") { host.getString(R.string.ai_entry_logged, 3) in texts(host.page) }

            // Pantry photo -> editable chips -> stores -> recipe cards.
            test.runOnMainSync { host.startCook(day) }
            check(test.screenOf(host, "cook") == "cook")
            test.runOnMainSync { host.analyseFoodPhoto(day) { testJpeg(1200, 1600) } }
            test.foodWait("ingredient chips") { texts(host.page).count { it.endsWith("  ×") } == 5 }
            check(test.screenOf(host, "cook") == "cook")
            shot("ingredients")
            test.runOnMainSync { host.loadStores(day, 42.70321, 23.32187) }
            test.foodWait("stores") { texts(host.page).any { it.startsWith("Lidl · ") } }
            test.runOnMainSync {
                val shown = texts(host.page)
                check(host.getString(R.string.cook_stores_attribution) in shown)
                check(shown.indexOfFirst { it.startsWith("Плод-зеленчук") } < shown.indexOfFirst { it.startsWith("Lidl") })
            }
            test.foodClick(host, host.getString(R.string.cook_suggest))
            test.foodWait("recipe cards") { texts(host.page).count { it.startsWith(host.getString(R.string.cook_steps)) } == 3 }
            val storesLead = if (language == "bg") "Можете да ги намерите в: " else "You may find them at: "
            lateinit var first: View
            test.runOnMainSync {
                val shown = texts(host.page)
                check(shown.any { it.startsWith(storesLead) && "Lidl" in it }) { "no stores line in $shown" }
                check(shown.any { it.startsWith(host.getString(R.string.cook_missing, "").trim()) })
                first = foodViews(host.page, android.widget.TextView::class.java).first { it.text.toString() == host.getString(R.string.cook_recipes) }
            }
            scrollTo(first); shot("recipes")
            test.foodClick(host, host.getString(R.string.cook_save))
            test.foodWait("saved notice") { texts(host.page).any { it.startsWith(host.getString(R.string.cook_saved, "").trim()) } }

            // Body (SPEC 10.7): tape entry -> the server's Navy estimate; a progress photo saved only on the phone.
            host.progressPhotos().deleteRecursively()
            test.runOnMainSync { host.body() }
            check(test.screenOf(host, "body") == "body")
            test.runOnMainSync {
                foodViews(host.page, android.widget.RadioButton::class.java).first { it.text == host.getString(R.string.body_male) }.isChecked = true
                val fields = foodViews(host.page, android.widget.EditText::class.java)
                for ((index, value) in listOf("178", "85", "38").withIndex()) fields[index].setText(value)  // height, waist, neck
            }
            test.foodClick(host, host.getString(R.string.body_save))
            test.foodWait("body-fat estimate") { texts(host.page).any { it.startsWith("16") && "±" in it } }
            test.runOnMainSync { host.saveProgressPhoto(testJpeg(1200, 1600)) }
            check(host.progressPhotos().listFiles().orEmpty().size == 1)
            test.runOnMainSync { host.body() }
            check(test.screenOf(host, "body") == "body")
            shot("body")
            var photo: View? = null
            test.foodWait("progress photo") {
                photo = foodViews(host.page, android.widget.ImageView::class.java).firstOrNull { it.drawable != null && it.contentDescription != null }
                photo != null && host.getString(R.string.body_photos_note) in texts(host.page)
            }
            scrollTo(checkNotNull(photo))
            shot("body-photos")
            host.progressPhotos().deleteRecursively()

            // Programs (SPEC 10.8): adopt the beginner program; its two days appear as templates.
            test.runOnMainSync { host.programs() }
            test.foodWait("programs") { host.getString(R.string.programs_adopt) in texts(host.page) }
            shot("programs")
            test.foodClick(host, host.getString(R.string.programs_adopt))
            test.foodWait("adopted") { texts(host.page).any { it == host.resources.getQuantityString(R.plurals.programs_added, 2, 2) ||
                it == host.getString(R.string.programs_already) } }
            test.runOnMainSync { host.workoutStart() }
            test.foodWait("program templates") { texts(host.page).count { it.contains(" · A") || it.contains(" · B") } >= 2 }
            shot("workout-start")
        } finally {
            test.runOnMainSync { host.finish() }
            context.getSharedPreferences("food", 0).edit().clear().commit()
        }
    }
}
