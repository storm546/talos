package jumpscan.app

import android.app.DatePickerDialog
import android.text.InputType
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

internal val MainActivity.locale: Locale get() = resources.configuration.locales[0]
internal fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
internal fun MainActivity.featureError(message: String?) {
    if (message != null) text(message, 15, getColor(R.color.error))
}

internal class FeatureApi(private val base: String, private val token: String) {
    fun array(path: String) = JSONArray(api(base, path, token = token)).objects()
    fun obj(path: String) = JSONObject(api(base, path, token = token))
    fun post(path: String, body: JSONObject) = JSONObject(api(base, path, "POST", token, body))
    fun send(method: String, path: String, body: JSONObject? = null) = api(base, path, method, token, body)
}

internal fun MainActivity.featureApi() = FeatureApi(base(), token())

internal fun MainActivity.dayPicker(day: String, picked: (String) -> Unit) {
    button(localDate(day, locale)) {
        val date = LocalDate.parse(day)
        DatePickerDialog(this, { _, year, month, dayOfMonth ->
            picked(LocalDate.of(year, month + 1, dayOfMonth).toString())
        }, date.year, date.monthValue - 1, date.dayOfMonth).show()
    }
}

internal fun MainActivity.decimalField(label: Int, value: String = "", parent: android.widget.LinearLayout = page) =
    field(getString(label), value, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, parent).apply {
        keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789.,")
        setRawInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
    }

internal fun EditText.value() = text.toString().trim()
internal fun decimal(value: String) = value.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
