package jumpscan.app

import android.graphics.BitmapFactory
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Tape measurements with the server's Navy body-fat estimate, and progress photos that never leave the phone (SPEC 10.7). */
private val GIRTHS = listOf("waist_cm" to R.string.body_waist, "neck_cm" to R.string.body_neck, "hip_cm" to R.string.body_hip,
    "chest_cm" to R.string.body_chest, "arm_cm" to R.string.body_arm, "thigh_cm" to R.string.body_thigh)

/** One folder per athlete on one server (the same id on another server is another person), in app-private storage;
 *  app backup is off, so the photos stay on this phone. */
internal fun MainActivity.progressPhotos() = File(filesDir, "progress/" + java.security.MessageDigest.getInstance("SHA-256")
    .digest("${base()}\n${athleteId()}".toByteArray()).joinToString("") { "%02x".format(it) }.take(32)).apply { mkdirs() }

internal fun MainActivity.saveProgressPhoto(bytes: ByteArray) {
    val name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")) + ".jpg"
    File(progressPhotos(), name).writeBytes(foodJpeg(bytes, 1600))
}

internal fun MainActivity.body(message: String? = null) {
    work(getString(R.string.feature_loading), { featureApi().array("/api/measurements?kind=body&limit=60") },
        { bodyPage(it, message) }, { bodyPage(emptyList(), it) })
}

private fun MainActivity.bodyPage(rows: List<JSONObject>, message: String?) {
    layout(getString(R.string.body_title), "body")
    if (message != null) notice(message)
    val settings = getSharedPreferences("body", android.content.Context.MODE_PRIVATE)
    fun cm(row: JSONObject, key: String) = row.optDouble(key).takeIf { !row.isNull(key) && it.isFinite() }
    fun one(value: Double) = String.format(locale, "%.1f", value)
    // Latest estimate and the change since the first one.
    val estimates = rows.filter { cm(it, "body_fat_pct") != null }  // newest first
    section(R.string.body_fat)
    if (estimates.isEmpty()) text(getString(R.string.body_fat_none), 14)
    else {
        val latest = estimates.first()
        text("${one(cm(latest, "body_fat_pct")!!)} % ± ${one(cm(latest, "body_fat_error_pct")!!)}", 28, green).instrumentType(numeric = true)
        val first = estimates.last()
        val firstDay = first.optString("created_at").take(10)
        if (firstDay != latest.optString("created_at").take(10)) valueRow(getString(R.string.body_since_first, localDate(firstDay, locale)),
            getString(R.string.body_points, "%+.1f".format(locale, cm(latest, "body_fat_pct")!! - cm(first, "body_fat_pct")!!)), page)
        text(getString(R.string.body_fat_note), 12)
    }
    // Progress photos: first and latest side by side, then add or delete.
    section(R.string.body_photos).setPadding(0, dp(22), 0, 0)
    text(getString(R.string.body_photos_note), 12)
    val photos = progressPhotos().listFiles { file -> file.name.endsWith(".jpg") }.orEmpty().sortedBy { it.name }
    if (photos.isNotEmpty()) {
        val pair = row(top = 6)
        for (file in listOf(photos.first(), photos.last()).distinct()) {
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            pair.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            column.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 2 }))
                contentDescription = getString(R.string.body_photo_of, localDate(file.name.take(10), locale))
            }, LinearLayout.LayoutParams(-1, dp(220)))
            text(localDate(file.name.take(10), locale), 12, parent = column)
        }
        if (photos.size == 1) pair.addView(android.view.View(this), LinearLayout.LayoutParams(0, 0, 1f))
        if (photos.size > 2) text(getString(R.string.body_photo_count, photos.size), 12)
    }
    toolRow(UiTile(R.string.food_photo_camera, "camera") { photoFor = "progress"; takeFoodPhoto() },
        UiTile(R.string.food_photo_gallery, "gallery") { photoFor = "progress"; pickFoodPhoto() })
    if (photos.isNotEmpty()) button(getString(R.string.body_delete_latest), look = ActionTone.TEXT) {
        photos.last().delete()
        body(getString(R.string.body_deleted))
    }
    // New entry; height and sex are remembered on this phone only.
    section(R.string.body_new).setPadding(0, dp(22), 0, 0)
    val sexes = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL; background = shape(getColor(R.color.hover), 10); setPadding(dp(3), dp(3), dp(3), dp(3)) }
    page.addView(sexes, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    val buttons = listOf("male" to R.string.body_male, "female" to R.string.body_female).associate { (key, label) ->
        key to RadioButton(this).apply {
            id = android.view.View.generateViewId(); text = getString(label); textSize = 13f; segment()
            isChecked = settings.getString("sex", null) == key
            sexes.addView(this, RadioGroup.LayoutParams(0, -2, 1f))
        }
    }
    val height = decimalField(R.string.body_height, settings.getString("height", "").orEmpty())
    val fields = GIRTHS.associate { (key, label) -> key to decimalField(label) }
    text(getString(R.string.body_how), 12)
    button(getString(R.string.body_save), primary = true) {
        val entry = JSONObject().put("kind", "body")
        var bad = false
        for ((key, field) in fields) {
            val value = field.value().takeIf { it.isNotEmpty() }?.let(::decimal) ?: continue
            if (value !in 10.0..250.0) { field.error = getString(R.string.body_invalid); bad = true } else entry.put(key, value)
        }
        val tall = height.value().takeIf { it.isNotEmpty() }?.let(::decimal)
        if (tall != null && tall !in 100.0..250.0) { height.error = getString(R.string.body_invalid); bad = true }
        if (bad) return@button
        if (entry.length() == 1) return@button snack(getString(R.string.body_empty))
        val sex = buttons.entries.firstOrNull { it.value.isChecked }?.key
        if (tall != null) entry.put("height_cm", tall)
        if (sex != null) entry.put("sex", sex)
        settings.edit().putString("height", height.value()).putString("sex", sex).apply()
        saveFeature(FeatureWrite.MEASUREMENT, entry) { _, result -> body(result) }
    }
    // History, newest first.
    if (rows.isNotEmpty()) {
        section(R.string.body_history).setPadding(0, dp(22), 0, 0)
        for (row in rows.take(20)) {
            val parts = GIRTHS.mapNotNull { (key, label) -> cm(row, key)?.let { "${getString(label)} ${one(it)}" } } +
                listOfNotNull(cm(row, "body_fat_pct")?.let { getString(R.string.body_fat_short, one(it)) })
            valueRow(localDate(row.optString("created_at").take(10), locale), parts.joinToString(" · "), page)
        }
    }
}
