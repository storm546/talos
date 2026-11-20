package jumpscan.app

import android.text.InputFilter
import android.text.InputType
import android.widget.ScrollView
import org.json.JSONObject
import java.text.NumberFormat

internal class AssistantChat {
    var conversationId: String? = null
    val turns = mutableListOf<Pair<String, String>>()
    var proposal: JSONObject? = null
}

internal fun MainActivity.assistantScreen(message: String? = null, draft: String = "") {
    val chat = assistantChat
    layout(getString(R.string.assistant_title), "assistant")
    text(getString(R.string.assistant_intro), 17)
    if (chat.turns.isEmpty()) notice(getString(R.string.ui_assistant_empty))
    for ((question, answer) in chat.turns) {
        val prompt = card()
        text(getString(R.string.assistant_you), 14, green, prompt)
        text(question, parent = prompt)
        text(getString(R.string.assistant_title), 14, green)
        text(answer)
    }
    if (message != null) notice(message, caution = true)
    val api = featureApi()
    chat.proposal?.let { proposal ->
        text(getString(R.string.assistant_proposal), 18, green)
        // The server summary carries consequential details (audience, cancellation, notes) the app cannot rebuild.
        val reviewed = proposal.getJSONArray("actions").objects().map { action ->
            val local = runCatching { describeAction(action) }.getOrNull()
            val server = action.optString("human_summary").takeIf { it.isNotBlank() }
            val panel = card()
            text(local ?: server ?: getString(R.string.assistant_unknown_action), parent = panel)
            if (local != null && server != null) {
                text(getString(R.string.ui_server_summary), 13, parent = panel).setPadding(0, dp(10), 0, 0)
                text(server, 14, parent = panel)
            }
            local != null || server != null
        }
        if (reviewed.isNotEmpty() && reviewed.all { it }) {
            button(getString(R.string.assistant_confirm), primary = true) {
                val body = JSONObject().put("proposal_id", proposal.getString("proposal_id"))
                work(getString(R.string.feature_saving), {
                    try { api.post("/api/assistant/confirm", body).getInt("applied") }
                    catch (error: ApiError) {
                        if (error.status in listOf(404, 409)) -1 else throw error
                    }
                }, { applied ->
                    chat.proposal = null
                    assistantScreen(if (applied < 0) getString(R.string.assistant_expired)
                        else getString(R.string.assistant_applied, applied))
                }, { assistantScreen(it) })
            }
        }
        button(getString(R.string.assistant_discard)) { chat.proposal = null; assistantScreen() }
    }
    if (chat.proposal == null) {
        val input = field(getString(R.string.assistant_message), draft).apply {
            setSingleLine(false)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            maxLines = 5
            filters = arrayOf(InputFilter.LengthFilter(4000))
        }
        button(getString(R.string.assistant_send), primary = true) {
            val question = input.value()
            if (question.isEmpty()) {
                input.error = getString(R.string.assistant_required)
                return@button
            }
            val body = JSONObject().put("message", question)
            chat.conversationId?.let { body.put("conversation_id", it) }
            work(getString(R.string.assistant_sending), {
                try { api.post("/api/assistant", body) }
                catch (error: ApiError) {
                    if (error.status == 503) throw AppError(R.string.assistant_unavailable)
                    throw error
                }
            }, { response ->
                val answer = response.getString("reply")
                chat.conversationId = response.getString("conversation_id")
                chat.proposal = response.optJSONObject("proposal")
                chat.turns.add(question to answer)
                if (chat.turns.size > 20) chat.turns.removeAt(0)
                assistantScreen()
            }, { assistantScreen(it, question) })
        }
    }
    if (chat.turns.isNotEmpty()) (page.parent as? ScrollView)?.let { scroll ->
        scroll.post {
            val last = if (chat.proposal == null) page.getChildAt(page.childCount - 3)
                else (0 until page.childCount).map { page.getChildAt(it) }.filterIsInstance<android.widget.TextView>()
                    .lastOrNull { it.text == getString(R.string.assistant_proposal) }
            last?.let { scroll.scrollTo(0, it.top - dp(80)) }
        }
    }
}

private fun MainActivity.describeAction(action: JSONObject): String? {
    val args = action.getJSONObject("args")
    fun day() = localDate(args.getString("day"), locale)
    val format = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 20 }
    fun value(key: String) = if (args.isNull(key)) getString(R.string.not_available) else format.format(args.getDouble(key))
    val load = value("load_kg").let { if (args.isNull("load_kg")) it else getString(R.string.feature_kg, it) }
    return when (action.getString("tool")) {
        "log_food" -> getString(R.string.assistant_food_action, args.getLong("food_id"), value("grams"), mealName(args.getString("meal")), day())
        "log_weight" -> getString(R.string.assistant_weight_action, value("kg"), day())
        "log_set" -> getString(R.string.assistant_set_action, args.getInt("set_no"), args.getLong("exercise_id"), args.getInt("reps"),
            day(), load, value("rpe")) + if (args.isNull("plan_id")) "" else " " + getString(R.string.assistant_plan_ref, args.getLong("plan_id"))
        "save_checkin" -> getString(R.string.assistant_checkin_action, day(), args.getInt("sleep"), args.getInt("fatigue"),
            args.getInt("soreness"), args.getInt("stress"), args.getInt("mood"), value("training_minutes"), value("rpe"))
        "set_nutrition_target" -> getString(R.string.assistant_target_action, args.getLong("athlete_id"), value("kcal"),
            value("protein_g"), value("carbs_g"), value("fat_g"))
        "set_nutrition_program" -> getString(R.string.assistant_program_action, args.getLong("athlete_id"),
            goalName(args.getString("goal")), value("rate_kg_week"), value("protein_g"), value("fat_g"))
        else -> null
    }
}
