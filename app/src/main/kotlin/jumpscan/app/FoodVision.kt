package jumpscan.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.LocationManager
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

// Food photos, pantry recipes and nearby stores (SPEC 10.6). A photo leaves the phone only when the user starts the
// analysis, downscaled and re-encoded here (no EXIF); the server forwards it to the model and keeps nothing.

internal const val FOOD_CAMERA = 70
internal const val FOOD_GALLERY = 71
internal val FOOD_REQUESTS = FOOD_CAMERA..FOOD_GALLERY
private const val MODEL_TIMEOUT_MS = 120_000

private class CookState(val ingredients: MutableList<String> = mutableListOf(), var goal: String = "any",
                        var stores: List<JSONObject>? = null, var recipes: List<JSONObject>? = null)
private var cookState = CookState()
internal var photoFor = "meal"

/** At most [max] px on the long side, upright, re-encoded as JPEG so no EXIF (location, device, time) survives. */
internal fun foodJpeg(input: ByteArray, max: Int = 1024): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw AppError(R.string.food_photo_unreadable)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(input, 0, input.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: throw AppError(R.string.food_photo_unreadable)
    val degrees = when (runCatching { ExifInterface(ByteArrayInputStream(input)).getAttributeInt(
        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    val scale = minOf(1f, max.toFloat() / maxOf(decoded.width, decoded.height))
    val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height,
        Matrix().apply { postScale(scale, scale); postRotate(degrees) }, true)
    return ByteArrayOutputStream().use { out ->
        upright.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (upright !== decoded) upright.recycle()
        decoded.recycle()
        out.toByteArray()
    }
}

private fun MainActivity.photoNoticeSeen() = getSharedPreferences("food", 0).getBoolean("photo_notice", false)

/** The one-time privacy notice; [next] runs once it is accepted. */
internal fun MainActivity.photoNotice(next: () -> Unit) {
    layout(getString(R.string.food_privacy_title), "food_privacy")
    val panel = card()
    text(getString(R.string.food_privacy_text), 15, getColor(R.color.ink), panel)
    text(getString(R.string.food_privacy_minor), 14, parent = panel)
    button(getString(R.string.food_privacy_accept), primary = true) {
        getSharedPreferences("food", 0).edit().putBoolean("photo_notice", true).apply()
        next()
    }
    button(getString(R.string.cancel)) { nutritionBack() }
}

internal fun MainActivity.foodPhoto(day: String = diaryDay, message: String? = null) {
    diaryDay = day
    photoFor = "meal"
    if (!photoNoticeSeen()) return photoNotice { foodPhoto(day) }
    layout(getString(R.string.food_photo_title), "food_photo")
    featureError(message)
    text("${mealName(diaryMeal)} · ${localDate(day, locale)}", 14)
    text(getString(R.string.food_photo_note), 15, getColor(R.color.ink))
    photoButtons()
    button(getString(R.string.food_photo_add_manually), look = ActionTone.TEXT) { foods(day) }
}

private fun MainActivity.photoButtons() = toolRow(UiTile(R.string.food_photo_camera, "camera") { takeFoodPhoto() },
    UiTile(R.string.food_photo_gallery, "gallery") { pickFoodPhoto() })

private fun MainActivity.cameraFile() = File(cacheDir, "food-photo/photo.jpg")

internal fun MainActivity.takeFoodPhoto() {
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
        requestPermissions(arrayOf(Manifest.permission.CAMERA), 66)
        return snack(getString(R.string.food_camera_needed))
    }
    val file = cameraFile().apply { parentFile?.mkdirs(); delete() }
    val uri = FileProvider.getUriForFile(this, "$packageName.diagnostic-files", file)
    launchVideo(Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION), FOOD_CAMERA)
}

internal fun MainActivity.pickFoodPhoto() {
    val pick = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES)
        else Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
    launchVideo(pick.setType("image/*"), FOOD_GALLERY)
}

internal fun MainActivity.foodPhotoResult(request: Int, result: Int, uri: Uri?) {
    val back = { if (photoFor == "meal") foodPhoto(diaryDay) else if (photoFor == "progress") body() else cook(diaryDay) }
    if (result != android.app.Activity.RESULT_OK) return back()
    val file = cameraFile()
    if (photoFor == "progress") return work(getString(R.string.feature_saving), {
        saveProgressPhoto(if (request == FOOD_CAMERA) file.readBytes().also { file.delete() }
            else contentResolver.openInputStream(checkNotNull(uri))!!.use { it.readBytes() })
    }, { body(getString(R.string.body_photo_saved)) }, { body(it) })
    analyseFoodPhoto(diaryDay) {
        if (request == FOOD_CAMERA) file.readBytes().also { file.delete() }
        else contentResolver.openInputStream(checkNotNull(uri))!!.use { stream ->
            stream.readBytes().also { if (it.size > 40 * 1024 * 1024) throw AppError(R.string.food_photo_unreadable) }
        }
    }
}

/** Shrinks the image and sends it for the meal review or the pantry list; [read] runs off the main thread. */
internal fun MainActivity.analyseFoodPhoto(day: String, read: () -> ByteArray) {
    val api = featureApi()
    val meal = photoFor == "meal"
    work(getString(if (meal) R.string.food_photo_working else R.string.cook_working), {
        val body = JSONObject().put("image_jpeg_base64", Base64.encodeToString(foodJpeg(read()), Base64.NO_WRAP))
        if (meal) api.post("/api/me/nutrition/photo", body.put("meal", diaryMeal), MODEL_TIMEOUT_MS)
        else api.post("/api/me/nutrition/pantry", body, MODEL_TIMEOUT_MS)
    }, { response ->
        if (meal) photoReview(day, response.optJSONArray("items")?.objects().orEmpty())
        else {
            for (item in response.optJSONArray("ingredients")?.objects().orEmpty()) addIngredient(modelWords(item))
            cookState.recipes = null
            cook(day)
        }
    }, { if (meal) foodPhoto(day, it) else cook(day, it) })
}

private fun matched(item: JSONObject) = !item.isNull("food_id") || !item.isNull("catalog_id")

/** The model's own words in the app's language. */
private fun MainActivity.modelWords(item: JSONObject): String =
    item.optString("label_bg").takeIf { locale.language == "bg" && it.isNotBlank() && !item.isNull("label_bg") } ?: item.optString("label")

/** The matched food's name, or the model's words when nothing matched. */
private fun MainActivity.photoLabel(item: JSONObject): String = if (matched(item)) foodName(item) else modelWords(item)

/** Review: each recognised food with editable grams; only the chosen matched foods go to the existing batch route. */
internal fun MainActivity.photoReview(day: String, items: List<JSONObject>, message: String? = null) {
    layout(getString(R.string.food_photo_review), "food_photo_review")
    featureError(message)
    text(localDate(day, locale) + " · " + getString(R.string.food_photo_estimates), 13)
    if (items.isEmpty()) {
        text(getString(R.string.food_photo_nothing), 15, getColor(R.color.ink))
        photoButtons()
        button(getString(R.string.food_photo_add_manually), look = ActionTone.TEXT) { foods(day) }
        return
    }
    mealGrid(diaryMeal) { diaryMeal = it }
    val chosen = items.map { matched(it) }.toMutableList()
    val fields = arrayOfNulls<EditText>(items.size)
    lateinit var total: TextView
    fun grams(index: Int) = fields[index]?.let { decimal(it.value()) } ?: items[index].optDouble("grams", 0.0)
    fun kcal(index: Int) = items[index].optDouble("kcal", 0.0) * grams(index) / items[index].optDouble("grams", 1.0).coerceAtLeast(1.0)
    fun refresh() { total.text = getString(R.string.food_photo_total, kcalText(items.indices.filter { chosen[it] }.sumOf { kcal(it) })) }
    eyebrow(getString(R.string.ai_entry_proposal))
    items.forEachIndexed { index, item ->
        val line = row(center = true).apply { setPadding(0, dp(6), 0, dp(6)) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        line.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
        text(photoLabel(item), 15, getColor(R.color.ink), column).setPadding(0, 0, 0, 0)
        val detail = text("", 13, parent = column).apply { setPadding(0, dp(2), 0, 0) }
        if (!matched(item)) {
            detail.text = "${gramText(item.optDouble("grams", 0.0))} · ${getString(R.string.food_photo_not_found)}"
            detail.setTextColor(getColor(R.color.warning))
            divider(page, inset = false)
            return@forEachIndexed
        }
        val confidence = item.optDouble("confidence", 1.0)
        fun describe() { detail.text = listOf(kcalText(kcal(index)), "${getString(R.string.macro_p)} ${gramText(
            item.optDouble("protein_g", 0.0) * grams(index) / item.optDouble("grams", 1.0).coerceAtLeast(1.0))}")
            .plus(if (confidence < 0.5) listOf(getString(R.string.food_photo_uncertain)) else emptyList()).joinToString(" · ") }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        line.addView(box, LinearLayout.LayoutParams(dp(92), -2).apply { marginStart = dp(8) })
        fields[index] = decimalField(R.string.unit_grams, short(item.optDouble("grams", 0.0)).replace(',', '.'), box).apply {
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) { describe(); refresh() }
            })
        }
        line.addView(android.widget.Switch(this).apply {
            instrumentSwitch(); isChecked = true; contentDescription = photoLabel(item)
            setOnCheckedChangeListener { _, on -> chosen[index] = on; refresh() }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        describe()
        divider(page, inset = false)
    }
    total = text("", 17, getColor(R.color.ink)).apply { instrumentType(numeric = true) }
    refresh()
    if (chosen.any { it }) button(getString(R.string.ai_entry_confirm), primary = true) {
        val entries = JSONArray()
        for (index in items.indices) {
            if (!chosen[index]) continue
            val amount = grams(index)
            if (amount <= 0 || amount > 5000) { fields[index]?.error = getString(R.string.nutr_invalid_grams); return@button }
            val item = items[index]
            val entry = JSONObject().put("day", day).put("meal", diaryMeal).put("grams", amount).put("client_id", UUID.randomUUID().toString())
            if (!item.isNull("food_id")) entry.put("food_id", item.getLong("food_id")) else entry.put("catalog_id", item.getString("catalog_id"))
            entries.put(entry)
        }
        if (entries.length() == 0) return@button
        val api = featureApi()
        // The batch route answers with a JSON array, so the body is not parsed as an object here.
        work(getString(R.string.feature_saving), { api.send("POST", "/api/food-logs/batch", JSONObject().put("entries", entries)) },
            { nutritionHome(day, getString(R.string.ai_entry_logged, entries.length())) }, { photoReview(day, items, it) })
    }
    button(getString(R.string.food_photo_add_manually), look = ActionTone.TEXT) { foods(day) }
}

private fun addIngredient(name: String) {
    val clean = name.trim().take(60)
    if (clean.isNotEmpty() && cookState.ingredients.none { it.equals(clean, ignoreCase = true) } && cookState.ingredients.size < 30)
        cookState.ingredients += clean
}

internal fun MainActivity.startCook(day: String = diaryDay) { cookState = CookState(); cook(day) }

/** "What can I cook?": ingredients from a photo or typed, a goal, optional nearby stores, then up to 3 recipe cards. */
internal fun MainActivity.cook(day: String = diaryDay, message: String? = null) {
    diaryDay = day
    photoFor = "pantry"
    val state = cookState
    layout(getString(R.string.cook_title), "cook")
    if (message != null) notice(message)
    text(getString(R.string.cook_note), 14)
    toolRow(UiTile(R.string.food_photo_camera, "camera") { if (photoNoticeSeen()) takeFoodPhoto() else photoNotice { cook(day) } },
        UiTile(R.string.food_photo_gallery, "gallery") { if (photoNoticeSeen()) pickFoodPhoto() else photoNotice { cook(day) } })
    section(R.string.cook_ingredients).setPadding(0, dp(18), 0, dp(2))
    if (state.ingredients.isEmpty()) text(getString(R.string.cook_no_ingredients), 14)
    state.ingredients.chunked(3).forEach { names ->
        val line = row()
        for (name in names) chip("$name  ×", line).apply {
            textSize = 14f; minHeight = dp(40); gravity = Gravity.CENTER_VERTICAL
            contentDescription = getString(R.string.cook_remove, name); isClickable = true
            setOnClickListener { state.ingredients.remove(name); state.recipes = null; cook(day) }
        }
    }
    val input = field(getString(R.string.cook_ingredient)).apply { filters = arrayOf(InputFilter.LengthFilter(60)) }
    button(getString(R.string.cook_add)) {
        input.value().split(',').forEach { addIngredient(it) }
        state.recipes = null; cook(day)
    }
    text(getString(R.string.cook_goal), 14).setPadding(0, dp(14), 0, 0)
    val goals = listOf("high_protein", "light", "any")
    segments(listOf(getString(R.string.cook_goal_protein), getString(R.string.cook_goal_light), getString(R.string.cook_goal_any)),
        goals.indexOf(state.goal)) { state.goal = goals[it] }
    storesBlock(day, state)
    if (state.ingredients.isNotEmpty()) button(getString(R.string.cook_suggest), primary = true) { suggestRecipes(day) }
    val recipes = state.recipes ?: return
    section(R.string.cook_recipes).setPadding(0, dp(22), 0, dp(4))
    if (recipes.isEmpty()) text(getString(R.string.cook_none), 14)
    for (recipe in recipes) recipeCard(day, recipe)
}

private fun MainActivity.storesBlock(day: String, state: CookState) {
    val stores = state.stores
    if (stores == null) {
        button(getString(R.string.cook_stores)) { nearbyStores(day) }
        text(getString(R.string.cook_stores_note), 13)
        return
    }
    section(R.string.cook_stores).setPadding(0, dp(18), 0, dp(2))
    if (stores.isEmpty()) text(getString(R.string.cook_stores_none), 14)
    for (store in stores.take(6)) text("${store.optString("name")} · ${distanceText(store.optInt("distance_m"))}", 15, getColor(R.color.ink))
        .setPadding(0, dp(3), 0, dp(3))
    text(getString(R.string.cook_stores_attribution), 12)
}

private fun MainActivity.distanceText(metres: Int) = if (metres < 1000) getString(R.string.cook_distance_m, (metres / 10.0).roundToInt() * 10)
    else getString(R.string.cook_distance_km, String.format(locale, "%.1f", metres / 1000.0))

private fun MainActivity.nearbyStores(day: String) {
    val granted = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        .any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    if (!granted) {
        requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 67)
        return snack(getString(R.string.cook_location_needed))
    }
    val manager = getSystemService(LocationManager::class.java)
    val last = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
        .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    if (last != null) return loadStores(day, last.latitude, last.longitude)
    if (Build.VERSION.SDK_INT >= 30) runCatching {
        manager.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, mainExecutor) { found ->
            if (found != null && !isDestroyed) loadStores(day, found.latitude, found.longitude)
            else snack(getString(R.string.cook_location_unknown))
        }
    }.onFailure { snack(getString(R.string.cook_location_unknown)) }
    else snack(getString(R.string.cook_location_unknown))
}

/** The position is rounded to 2 decimals (about 1 km) here, before it leaves the phone. */
internal fun MainActivity.loadStores(day: String, lat: Double, lon: Double) {
    val api = featureApi()
    val body = JSONObject().put("lat", Math.round(lat * 100) / 100.0).put("lon", Math.round(lon * 100) / 100.0)
    work(getString(R.string.feature_loading), { api.post("/api/stores/nearby", body).optJSONArray("stores")?.objects().orEmpty() },
        { cookState.stores = it; cook(day) }, { cook(day, it) })
}

private fun MainActivity.suggestRecipes(day: String) {
    val state = cookState
    val body = JSONObject().put("ingredients", JSONArray(state.ingredients)).put("goal", state.goal).put("servings", 2)
        .put("language", if (locale.language == "bg") "bg" else "en")
    state.stores?.let { stores -> body.put("stores", JSONArray(stores.take(20).map { store ->
        JSONObject().put("name", store.optString("name")).put("brand", store.optString("brand")).put("distance_m", store.optDouble("distance_m", 0.0))
    })) }
    val api = featureApi()
    work(getString(R.string.cook_working), { api.post("/api/me/nutrition/recipe-ideas", body, MODEL_TIMEOUT_MS) },
        { state.recipes = it.optJSONArray("recipes")?.objects().orEmpty(); cook(day) }, { cook(day, it) })
}

private fun MainActivity.recipeCard(day: String, recipe: JSONObject) {
    val panel = card()
    text(recipe.optString("name"), 18, getColor(R.color.ink), panel)
    val per = recipe.optJSONObject("per_serving") ?: JSONObject()
    val minutes = recipe.optInt("minutes", 0)
    text(if (minutes > 0) getString(R.string.cook_summary, minutes, kcalText(per.optDouble("kcal", 0.0)), gramText(per.optDouble("protein_g", 0.0)))
        else getString(R.string.cook_summary_no_time, kcalText(per.optDouble("kcal", 0.0)), gramText(per.optDouble("protein_g", 0.0))),
        14, getColor(R.color.accent), panel)
    if (!recipe.optBoolean("nutrition_complete", true)) text(getString(R.string.cook_incomplete), 13, getColor(R.color.warning), panel)
    fun names(key: String) = recipe.optJSONArray(key)?.let { list -> List(list.length()) { list.optString(it) } }.orEmpty()
    if (names("uses_from_pantry").isNotEmpty()) text(getString(R.string.cook_uses, names("uses_from_pantry").joinToString(", ")), 14, parent = panel)
    if (names("missing").isNotEmpty()) text(getString(R.string.cook_missing, names("missing").joinToString(", ")), 14, getColor(R.color.ink), panel)
    recipe.optString("stores_line").takeIf { it.isNotBlank() && !recipe.isNull("stores_line") }?.let { text(it, 13, parent = panel) }
    text(getString(R.string.cook_steps), 13, getColor(R.color.ink), panel).setPadding(0, dp(10), 0, dp(2))
    names("steps").forEachIndexed { index, step -> text("${index + 1}. $step", 14, parent = panel).setPadding(0, dp(2), 0, dp(2)) }
    val actions = row(panel)
    button(getString(R.string.cook_save), parent = actions) { saveIdea(day, recipe, log = false) }.layoutParams =
        LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(10) }
    button(getString(R.string.cook_log), parent = actions) { saveIdea(day, recipe, log = true) }.layoutParams =
        LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(10); marginStart = dp(8) }
}

/** Saves the idea through the existing recipes route (matched ingredients only); logging opens the usual serving picker. */
private fun MainActivity.saveIdea(day: String, recipe: JSONObject, log: Boolean) {
    val items = JSONArray()
    for (item in recipe.optJSONArray("ingredients")?.objects().orEmpty()) {
        if (!matched(item)) continue
        val entry = JSONObject().put("grams", item.getDouble("grams"))
        if (!item.isNull("food_id")) entry.put("food_id", item.getLong("food_id")) else entry.put("catalog_id", item.getString("catalog_id"))
        items.put(entry)
    }
    if (items.length() == 0) return cook(day, getString(R.string.cook_nothing_to_save))
    val name = recipe.optString("name").take(100)
    val body = JSONObject().put("name", name).put("servings_count", recipe.optInt("servings", 2)).put("items", items)
    val api = featureApi()
    work(getString(R.string.feature_saving), { api.post("/api/recipes", body) },
        { saved -> if (log) foodLog(day, recipeAsFood(saved)) else cook(day, getString(R.string.cook_saved, name)) }, { cook(day, it) })
}
