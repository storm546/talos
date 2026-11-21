package jumpscan.app

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.time.LocalDate
import kotlin.concurrent.thread
import kotlin.math.sin

/**
 * Screens of the version 2 food tracker against a small in-process fake of the SPEC 7.1 endpoints,
 * so the captures do not depend on a running server. Field names follow the nutri-srv work in progress.
 */
internal class NutritionStudio(private val test: Instrumentation) {
    private val context get() = test.targetContext
    private val today = LocalDate.now()

    private fun food(id: Long?, bg: String, en: String, kcal: Double, p: Double, c: Double, f: Double, servings: String = "[]",
                     extra: String = "") = JSONObject("""{"id":${id ?: "null"},"name_bg":"$bg","name_en":"$en","kcal":$kcal,"protein_g":$p,
        "carbs_g":$c,"fat_g":$f,"fibre_g":1.2,"brand":"","club_id":null,"owner_id":null,"source":"usda",
        "servings":[{"label":"100 g","grams":100}${if (servings == "[]") "" else "," + servings.trim('[', ']')}]}""").also { base ->
        val more = JSONObject("{${extra.removePrefix(",")}}")
        for (key in more.keys()) base.put(key, more.get(key))
    }

    private val oats = food(11, "Овесени ядки", "Oats, rolled", 379.0, 13.2, 67.7, 6.5, """[{"label":"1 cup","grams":81}]""",
        ""","last_serving_label":"1 cup","last_quantity":0.5,"last_grams":40.5""")
    private val yoghurt = food(12, "Кисело мляко 3,6 %", "Yoghurt, whole milk", 61.0, 3.5, 4.7, 3.3, """[{"label":"1 кофичка","grams":400}]""")
    private val chicken = food(13, "Пилешко филе, печено", "Chicken breast, roasted", 165.0, 31.0, 0.0, 3.6, """[{"label":"1 филе","grams":172}]""")
    private val bread = food(14, "Хляб пълнозърнест", "Bread, whole wheat", 252.0, 12.4, 42.7, 3.5, """[{"label":"1 филия","grams":32}]""",
        ""","club_id":3,"brand":"Добруджа","barcode":"3800123456789","source":"Open Food Facts"""")
    private val milk = food(null, "Мляко, прясно 3 %", "Milk, whole, 3.25% milkfat", 61.0, 3.2, 4.8, 3.3,
        """[{"label":"1 cup","grams":244}]""", ""","catalog_id":"fdc:746782"""")
    private val milkPowder = food(null, "Мляко на прах", "Milk, dry, whole", 496.0, 26.3, 38.4, 26.7, extra = ""","catalog_id":"fdc:171279"""")
    private val shake = food(15, "Протеинов шейк", "Protein shake", 92.0, 12.0, 6.0, 2.0, """[{"label":"1 шейкър","grams":330}]""",
        ""","owner_id":7,"club_id":3,"source":"custom"""")

    private fun log(id: Long, meal: String, item: JSONObject, grams: Double, serving: String?, quantity: Double?) = JSONObject()
        .put("id", id).put("day", today.toString()).put("meal", meal).put("food_id", item.opt("id"))
        .put("name_bg", item.getString("name_bg")).put("name_en", item.getString("name_en")).put("grams", grams)
        .put("serving_label", serving ?: JSONObject.NULL).put("quantity", quantity ?: JSONObject.NULL).put("label", JSONObject.NULL)
        .apply { for (key in listOf("kcal", "protein_g", "carbs_g", "fat_g", "fibre_g")) put(key, item.getDouble(key) * grams / 100) }

    private val logs = listOf(log(1, "breakfast", oats, 81.0, "1 cup", 1.0), log(2, "breakfast", yoghurt, 200.0, "1 кофичка", .5),
        log(3, "lunch", chicken, 172.0, "1 филе", 1.0), log(4, "lunch", bread, 64.0, "1 филия", 2.0),
        JSONObject("""{"id":5,"day":"$today","meal":"snack","food_id":null,"name_bg":"Протеиново блокче","name_en":"","grams":null,
            "serving_label":null,"quantity":null,"label":"Протеиново блокче","kcal":210,"protein_g":20,"carbs_g":22,"fat_g":7,"fibre_g":0}"""))

    private fun summary(): JSONObject {
        val totals = JSONObject()
        for (key in listOf("kcal", "protein_g", "carbs_g", "fat_g", "fibre_g")) totals.put(key, logs.sumOf { it.optDouble(key, 0.0) })
        val meals = JSONObject()
        for (meal in listOf("breakfast", "lunch", "dinner", "snack")) meals.put(meal, logs.filter { it.getString("meal") == meal }.sumOf { it.getDouble("kcal") })
        val nutrients = JSONArray()
        for ((key, name, unit, amount) in listOf(listOf("calcium_mg", "Calcium", "mg", 412.0), listOf("iron_mg", "Iron", "mg", 6.84),
                listOf("potassium_mg", "Potassium", "mg", 1210.0), listOf("sodium_mg", "Sodium", "mg", 690.5),
                listOf("vitamin_c_mg", "Vitamin C", "mg", null), listOf("vitamin_d_ug", "Vitamin D", "µg", null),
                listOf("vitamin_b12_ug", "Vitamin B12", "µg", 1.92), listOf("magnesium_mg", "Magnesium", "mg", 214.0)))
            nutrients.put(JSONObject().put("key", key).put("name", name).put("unit", unit).put("reference", JSONObject.NULL)
                .put("amount", amount ?: JSONObject.NULL).put("known_entries", if (amount == null) 0 else if (key == "iron_mg") 3 else 4).put("total_entries", 4))
        return JSONObject().put("athlete_id", 7).put("day", today.toString()).put("totals", totals).put("by_meal_kcal", meals)
            .put("target", JSONObject("""{"kcal":2600,"protein_g":150,"carbs_g":320,"fat_g":80}""")).put("nutrients", nutrients)
    }

    private fun analytics(days: Int): JSONObject {
        val daily = JSONArray()
        for (n in 0 until days) {
            val day = today.minusDays(days - 1L - n)
            val skipped = n % 9 == 4
            val kcal = 2600 + 380 * sin(n * 1.7) - (if (n % 5 == 0) 520 else 0)
            daily.put(JSONObject().put("day", day.toString()).put("entries", if (skipped) 0 else 6)
                .put("kcal", if (skipped) JSONObject.NULL else kcal).put("protein_g", if (skipped) JSONObject.NULL else 142 + 10 * sin(n * 1.1))
                .put("carbs_g", if (skipped) JSONObject.NULL else 310.0).put("fat_g", if (skipped) JSONObject.NULL else 79.0))
        }
        val logged = (0 until daily.length()).map { daily.getJSONObject(it) }.filter { !it.isNull("kcal") }
        val within = logged.count { kotlin.math.abs(it.getDouble("kcal") - 2600) <= 260 }
        return JSONObject().put("days", days).put("daily", daily).put("target_kcal", 2600)
            .put("averages", JSONObject().put("logged_days", logged.size).put("kcal", logged.sumOf { it.getDouble("kcal") } / logged.size)
                .put("protein_g", 143.6).put("carbs_g", 310.0).put("fat_g", 79.0))
            .put("adherence", JSONObject().put("band_pct", 10).put("days_within", within).put("logged_days", logged.size))
    }

    private fun weights(): JSONObject {
        val rows = JSONArray()
        for (n in 0 until 30) if (n % 4 != 3) {
            val trend = 72.4 + n * .035
            rows.put(JSONObject().put("day", today.minusDays(29L - n).toString()).put("kg", trend + .55 * sin(n * 2.3)).put("trend_kg", trend))
        }
        return JSONObject().put("weights", rows)
    }

    private val recipe = JSONObject("""{"id":4,"name":"Овесена каша с банан","servings_count":2,"food_id":21,"total_grams":520,
        "serving_grams":260,"per_serving":{"kcal":318,"protein_g":12.6,"carbs_g":52.1,"fat_g":6.3},
        "per_100g":{"kcal":122.3,"protein_g":4.8,"carbs_g":20.0,"fat_g":2.4,"fibre_g":2.1},
        "items":[{"food_id":11,"name_bg":"Овесени ядки","grams":80,"kcal":303.2,"protein_g":10.6,"carbs_g":54.2,"fat_g":5.2},
                 {"food_id":16,"name_bg":"Банан","grams":120,"kcal":106.8,"protein_g":1.3,"carbs_g":27.4,"fat_g":0.4},
                 {"food_id":17,"name_bg":"Мляко, прясно 3 %","grams":300,"kcal":183,"protein_g":9.6,"carbs_g":14.4,"fat_g":9.9},
                 {"food_id":18,"name_bg":"Мед","grams":20,"kcal":60.8,"protein_g":0.1,"carbs_g":16.5,"fat_g":0}]}""")

    private fun route(method: String, path: String): Pair<Int, String> {
        val base = path.substringBefore('?')
        return when {
            base.endsWith("/nutrition/summary") -> 200 to summary().toString()
            base == "/api/food-logs" && method == "GET" -> 200 to JSONArray(logs).toString()
            base == "/api/foods/recent" -> 200 to JSONArray(listOf(oats, yoghurt, chicken, bread, shake)).toString()
            base == "/api/foods" -> 200 to JSONArray(listOf(yoghurt, shake, bread, milk, milkPowder)).toString()
            base == "/api/recipes" -> 200 to JSONArray().put(recipe).toString()
            base.endsWith("/nutrition/analytics") -> 200 to analytics(path.substringAfter("days=").take(2).trimEnd('&').toIntOrNull() ?: 7).toString()
            base.endsWith("/weights") -> 200 to weights().toString()
            base.endsWith("/nutrition/goal") -> 200 to """{"goal":{"phase":"gain","target_weight_kg":74.5,"end_date":"${today.plusDays(84)}",
                "rate_kg_week":0.18,"applied":true},"current_trend_kg":73.42,"remaining_kg":1.08,"trend_kg_week":0.21,
                "projected_date":"${today.plusDays(36)}","on_track":true}"""
            base.endsWith("/nutrition/program") -> 200 to """{"applied":true,"program":{"goal":"gain","rate_kg_week":0.18,"protein_g":150,"fat_g":80,
                "recommended_target":{"calories":2600,"protein_g":150,"carbs_g":320,"fat_g":80}}}"""
            base.endsWith("/nutrition/expenditure") -> 200 to JSONObject().put("expenditure", JSONObject("""{"value_kcal":2410,"status":"updating"}"""))
                .put("history", JSONArray((0 until 14).map { JSONObject().put("date", today.minusDays(13L - it).toString()).put("value_kcal", 2350 + it * 4.5) })).toString()
            base == "/api/nutrient-definitions" -> 200 to "[]"
            else -> 404 to """{"detail":"Not found"}"""
        }
    }

    private fun serve(socket: ServerSocket) = thread(isDaemon = true) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            client.use {
                val input = it.getInputStream().bufferedReader()
                val (method, path) = input.readLine().split(' ').let { parts -> parts[0] to parts[1] }
                var length = 0
                while (true) {
                    val header = input.readLine() ?: break
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
                }
                repeat(length) { input.read() }
                val (status, body) = route(method, path)
                val bytes = body.toByteArray()
                it.getOutputStream().write(("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray() + bytes)
            }
        }
    }

    fun screens() {
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serve(socket)
        context.getSharedPreferences("account", 0).edit().clear().putString("server", "http://127.0.0.1:${socket.localPort}")
            .putString("token", "studio").putString("name", "Студио").putLong("user_id", 7).commit()
        val host = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val language = host.resources.configuration.locales[0].language
        val state = MainActivity::class.java.getDeclaredField("screen").apply { isAccessible = true }
        fun save(name: String) {
            val bitmap = checkNotNull(test.uiAutomation.takeScreenshot())
            File(context.filesDir, "$language-nutri-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun shot(name: String, expected: String) {
            val end = SystemClock.uptimeMillis() + 20000
            var actual = ""
            do {
                test.runOnMainSync { actual = state.get(host).toString() }
                if (actual == expected) break
                SystemClock.sleep(150)
            } while (SystemClock.uptimeMillis() < end)
            check(actual == expected) { "$name: expected $expected, got $actual" }
            SystemClock.sleep(900)
            test.requireAppWindow(name)
            save(name)
        }
        fun scroll(name: String, expected: String, fraction: Float) {
            test.runOnMainSync { (host.page.parent as android.widget.ScrollView).let { it.scrollTo(0, (host.page.height * fraction).toInt()) } }
            shot(name, expected)
        }
        fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        val day = today.toString()
        try {
            test.runOnMainSync { host.nutritionHome(day) }; shot("diary", "nutrition")
            scroll("diary-meals", "nutrition", .45f)
            test.runOnMainSync { host.foods(day) }; shot("add-food", "food_search")
            test.runOnMainSync { host.foods(day, if (language == "bg") "мляко" else "milk") }; shot("search", "food_search")
            test.runOnMainSync { host.foodLog(day, oats) }; shot("serving", "food_log")
            scroll("serving-meal", "food_log", 1f)
            test.runOnMainSync { host.foodLog(day, bread, logs[3]) }; shot("edit-entry", "food_log")
            test.runOnMainSync { host.insights(day, 30) }; shot("insights", "insights")
            scroll("insights-weight", "insights", .3f)
            scroll("insights-goal", "insights", .58f)
            scroll("insights-micros", "insights", 1f)
            test.runOnMainSync { host.recipes(day) }; shot("recipes", "recipes")
            test.runOnMainSync {
                views(host.page).first { it.contentDescription == host.getString(R.string.recipe_edit_named, recipe.getString("name")) }.performClick()
            }; shot("recipe-editor", "recipe_edit")
            scroll("recipe-editor-items", "recipe_edit", .5f)
            val proposal = JSONArray("""[{"text":"2 eggs","grams":100,"match":{"id":31,"catalog_id":null,"name_bg":"Яйце, цяло, сварено","name_en":"Egg, whole, boiled","kcal":155},
                "entry":{"day":"$day","meal":"breakfast","food_id":31,"grams":100}},
                {"text":"toast","grams":30,"match":{"id":14,"catalog_id":null,"name_bg":"Хляб пълнозърнест","name_en":"Bread, whole wheat","kcal":252},
                "entry":{"day":"$day","meal":"breakfast","food_id":14,"grams":30}},
                {"text":"homemade jam","grams":20,"match":null,"entry":{"day":"$day","meal":"breakfast","label":"homemade jam","kcal":50,"protein_g":0.1,"carbs_g":12.6,"fat_g":0}}]""")
            test.runOnMainSync { host.aiEntry(day, if (language == "bg") "2 яйца, филия и домашно сладко" else "2 eggs, toast and homemade jam",
                (0 until proposal.length()).map { proposal.getJSONObject(it) }) }; shot("ai-entry", "ai_entry")
            test.runOnMainSync { host.foodForm(day, "4006381333931", message = host.getString(R.string.scan_not_found, "4006381333931")) }
            shot("scan-not-found", "food_form")
            val scanner = test.startActivitySync(Intent(context, BarcodeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            SystemClock.sleep(3500)
            test.requireAppWindow("scanner")
            save("scanner")
            test.runOnMainSync { scanner.finish() }
        } finally {
            test.runOnMainSync { host.finish() }
            socket.close()
        }
    }
}
