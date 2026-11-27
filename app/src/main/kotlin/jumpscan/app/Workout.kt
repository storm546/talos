package jumpscan.app

import android.content.Context
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** One set row of a running workout; values stay as typed until the set is ticked done. */
internal class LiftSet(var load: String = "", var reps: String = "", var rpe: String = "", var done: Boolean = false,
                       val previous: String? = null)

internal class LiftExercise(val id: Long, val name: String, var rest: Int, var superset: Int?, val best: Double?,
                            val sets: MutableList<LiftSet>)

/** A workout in progress (SPEC 9). Kept on the phone until Finish, then sent as one workout and its sets. */
internal class WorkoutDraft(val clientId: String, val startedAt: String, val day: String, var title: String,
                            val templateId: Long?, val planId: Long?, var notes: String, val exercises: MutableList<LiftExercise>) {
    fun toJson(): JSONObject = JSONObject().put("client_id", clientId).put("started_at", startedAt).put("day", day)
        .put("title", title).put("template_id", templateId ?: JSONObject.NULL).put("plan_id", planId ?: JSONObject.NULL)
        .put("notes", notes).put("exercises", JSONArray(exercises.map { ex ->
            JSONObject().put("id", ex.id).put("name", ex.name).put("rest", ex.rest).put("superset", ex.superset ?: JSONObject.NULL)
                .put("best", ex.best ?: JSONObject.NULL).put("sets", JSONArray(ex.sets.map { s ->
                    JSONObject().put("load", s.load).put("reps", s.reps).put("rpe", s.rpe).put("done", s.done)
                        .put("previous", s.previous ?: JSONObject.NULL)
                }))
        }))

    /** Superset partners follow one another; the rest timer starts after the last of them. */
    fun restsAfter(index: Int): Boolean {
        val group = exercises[index].superset ?: return true
        return exercises.getOrNull(index + 1)?.superset != group
    }

    companion object {
        private fun JSONObject.long(key: String) = if (isNull(key)) null else getLong(key)
        fun fromJson(o: JSONObject) = WorkoutDraft(o.getString("client_id"), o.getString("started_at"), o.getString("day"),
            o.getString("title"), o.long("template_id"), o.long("plan_id"), o.getString("notes"),
            o.getJSONArray("exercises").objects().map { ex ->
                LiftExercise(ex.getLong("id"), ex.getString("name"), ex.getInt("rest"),
                    if (ex.isNull("superset")) null else ex.getInt("superset"), if (ex.isNull("best")) null else ex.getDouble("best"),
                    ex.getJSONArray("sets").objects().map { s ->
                        LiftSet(s.getString("load"), s.getString("reps"), s.getString("rpe"), s.getBoolean("done"),
                            if (s.isNull("previous")) null else s.getString("previous"))
                    }.toMutableList())
            }.toMutableList())

        private fun store(context: Context) = context.getSharedPreferences("workout", Context.MODE_PRIVATE)
        fun load(context: Context) = store(context).getString("draft", null)?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }
        fun save(context: Context, draft: WorkoutDraft) = store(context).edit().putString("draft", draft.toJson().toString()).apply()
        fun clear(context: Context) = store(context).edit().remove("draft").apply()
    }
}

/** The club's exercises and the athlete's recent sets, for the picker and pre-filled rows. */
internal class GymData(val exercises: List<JSONObject>, val logs: List<JSONObject>) {
    fun lastSession(id: Long): List<JSONObject> {
        val rows = logs.filter { it.getLong("exercise_id") == id }
        val day = rows.maxOfOrNull { it.getString("day") } ?: return emptyList()
        return rows.filter { it.getString("day") == day }.sortedBy { it.getInt("set_no") }
    }
    fun best(id: Long) = logs.filter { it.getLong("exercise_id") == id && !it.isNull("load_kg") }.maxOfOrNull { it.getDouble("load_kg") }
    fun recent() = logs.sortedByDescending { it.getString("day") }.map { it.getLong("exercise_id") }.distinct()
        .mapNotNull { id -> exercises.firstOrNull { it.getLong("id") == id } }.take(6)
}

internal var gymData: GymData? = null
private var restExercise: Long? = null

private fun plain(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

private fun MainActivity.defaultRest(exercise: JSONObject?, id: Long) = getSharedPreferences("workout", Context.MODE_PRIVATE)
    .getInt("rest_$id", when (exercise?.optString("kind")) { "strength" -> 120; "plyometric" -> 90; "mobility" -> 45; else -> 60 })

/** A new exercise block, pre-filled from a template or plan item, else from the athlete's last session. */
private fun MainActivity.newExercise(id: Long, fallbackName: String, item: JSONObject? = null): LiftExercise {
    val gym = gymData
    val info = gym?.exercises?.firstOrNull { it.getLong("id") == id }
    val last = gym?.lastSession(id).orEmpty()
    val count = item?.optInt("sets", 0)?.takeIf { it > 0 } ?: last.size.takeIf { it > 0 } ?: 3
    val sets = MutableList(count) { i ->
        val before = last.getOrNull(i) ?: last.lastOrNull()
        val previous = last.getOrNull(i)?.let { if (it.isNull("load_kg")) "× ${it.getInt("reps")}" else "${plain(it.getDouble("load_kg"))} × ${it.getInt("reps")}" }
        val load = item?.takeIf { !it.isNull("load_kg") && it.has("load_kg") }?.getDouble("load_kg")
            ?: before?.takeIf { !it.isNull("load_kg") }?.getDouble("load_kg")
        LiftSet(load?.let(::plain).orEmpty(), (item?.optInt("reps", 0)?.takeIf { it > 0 } ?: before?.getInt("reps"))?.toString().orEmpty(),
            previous = previous)
    }
    val rest = item?.takeIf { it.has("rest_s") && !it.isNull("rest_s") }?.getInt("rest_s") ?: defaultRest(info, id)
    val superset = item?.takeIf { it.has("superset") && !it.isNull("superset") }?.getInt("superset")
    return LiftExercise(id, info?.getString("name") ?: fallbackName, rest, superset, gym?.best(id), sets)
}

internal fun MainActivity.workoutStart() {
    if (WorkoutDraft.load(this) != null) return workoutActive()
    val api = featureApi()
    work(getString(R.string.feature_loading), {
        Triple(GymData(api.array("/api/exercises"), api.array("/api/set-logs?limit=500")),
            api.array("/api/workout-templates"), api.array("/api/plans"))
    }, { (gym, templates, plans) -> gymData = gym; workoutChoices(templates, plans) },
        { workoutChoices(emptyList(), emptyList(), it) })
}

private fun MainActivity.workoutChoices(templates: List<JSONObject>, plans: List<JSONObject>, message: String? = null) {
    layout(getString(R.string.tab_training), "gym")
    if (message != null) notice(message, caution = true)
    val today = LocalDate.now()
    val starts = settingsGroup()
    settingRow("plus", getString(R.string.workout_empty), getString(R.string.workout_empty_note), starts) {
        beginWorkout(getString(R.string.workout_default_title), null, null, emptyList())
    }
    divider(starts)
    settingRow("index", getString(R.string.programs_title), getString(R.string.programs_note), starts) { programs() }
    for (plan in plans) {
        val items = planDays(plan, weekStart(today)).filter { it.first == today }.map { it.second }
        if (items.isEmpty()) continue
        divider(starts)
        settingRow("index", getString(R.string.workout_today_plan, plan.getString("title")),
            resources.getQuantityString(R.plurals.workout_exercises, items.size, items.size), starts) {
            beginWorkout(plan.getString("title"), null, plan.getLong("id"), items)
        }
    }
    if (templates.isNotEmpty()) {
        eyebrow(getString(R.string.workout_templates))
        val list = settingsGroup().apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(4) }
        templates.forEachIndexed { index, template ->
            if (index > 0) divider(list)
            val items = template.getJSONArray("items").objects()
            val owner = getString(if (template.isNull("club_id")) R.string.workout_template_mine else R.string.workout_template_club)
            settingRow("training", template.getString("title"),
                resources.getQuantityString(R.plurals.workout_exercises, items.size, items.size) + " · " + owner, list) {
                beginWorkout(template.getString("title"), template.getLong("id"), null, items)
            }
        }
    }
    askButton(getString(R.string.ask_training_draft))
    toolList(R.string.section_training_more, Tools.training)
    toolList(R.string.section_training_tools, Tools.trainingTools)
}

private fun MainActivity.beginWorkout(title: String, templateId: Long?, planId: Long?, items: List<JSONObject>) {
    val draft = WorkoutDraft(UUID.randomUUID().toString(), OffsetDateTime.now(ZoneOffset.UTC).withNano(0).toString(), todayLocal(),
        title, templateId, planId, "", items.map { newExercise(it.getLong("exercise_id"), it.optString("exercise"), it) }.toMutableList())
    WorkoutDraft.save(this, draft)
    workoutActive()
}

private fun MainActivity.smallInput(value: String, hint: String, decimal: Boolean, parent: LinearLayout, changed: (String) -> Unit) =
    EditText(this).apply {
        setSingleLine(true) // before inputType: setSingleLine rewrites the input type
        setText(value); this.hint = hint; contentDescription = hint
        inputType = if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_NUMBER
        if (decimal) keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789.,")
        gravity = Gravity.CENTER; textSize = 16f; instrumentType(numeric = true)
        setTextColor(getColor(R.color.ink)); setHintTextColor(getColor(R.color.line))
        setPadding(dp(4), dp(8), dp(4), dp(8)); minHeight = dp(44)
        backgroundTintList = null
        background = shape(getColor(R.color.surface), 10, getColor(R.color.line))
        onChange { changed(value()) }
        parent.addView(this, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
    }

private fun MainActivity.headerCell(value: String, weight: Float, parent: LinearLayout, start: Int = 6) =
    text(value.uppercase(locale), 11, getColor(R.color.muted), parent).apply {
        gravity = Gravity.CENTER; letterSpacing = .08f; maxLines = 1
        layoutParams = LinearLayout.LayoutParams(0, -2, weight).apply { marginStart = dp(start) }
    }

/** The running workout: clock, exercise blocks with set rows, add exercise, notes and Finish. */
internal fun MainActivity.workoutActive(scrollTo: Int = 0) {
    val draft = WorkoutDraft.load(this) ?: return workoutStart()
    fun save() = WorkoutDraft.save(this, draft)
    layout(draft.title, "workout", headline = false)
    val head = card(hero = true)
    val line = row(head, center = true)
    text(draft.title, 22, android.graphics.Color.WHITE, line).apply {
        instrumentType(display = true); layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    }
    val clockView = TextView(this).apply {
        textSize = 30f; instrumentType(numeric = true); setTextColor(getColor(R.color.signal))
        line.addView(this, LinearLayout.LayoutParams(-2, -2))
    }
    val doneCount = draft.exercises.sumOf { ex -> ex.sets.count { it.done } }
    val status = text("", 13, getColor(R.color.on_hero), head)
    val started = OffsetDateTime.parse(draft.startedAt)
    fun updateHead() {
        clockView.text = clock(Duration.between(started, OffsetDateTime.now()).toMillis().coerceAtLeast(0))
        status.text = getString(R.string.workout_status, localDateTime(draft.startedAt, locale),
            draft.exercises.sumOf { ex -> ex.sets.count { it.done } }, draft.exercises.sumOf { it.sets.size })
    }
    updateHead()
    ticker(clockView) { updateHead() }
    if (doneCount == 0 && draft.exercises.isEmpty()) notice(getString(R.string.workout_empty_hint))
    var letter = 'A'
    var member = 0
    draft.exercises.forEachIndexed { index, ex ->
        val previousGroup = draft.exercises.getOrNull(index - 1)?.superset
        val tag = if (ex.superset == null) null else {
            if (previousGroup != ex.superset) { if (index > 0 && member > 0) letter++; member = 0 }
            member++
            "$letter$member"
        }
        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(18), 0, 0) }
        page.addView(block)
        val title = row(block, center = true)
        if (tag != null) statusChip(tag, R.color.tonal, R.color.accent, title).apply {
            (layoutParams as LinearLayout.LayoutParams).apply { marginStart = 0; marginEnd = dp(8) }
            contentDescription = getString(R.string.workout_superset_tag, tag)
        }
        text(ex.name, 18, parent = title).apply { instrumentType(display = true); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        statusChip(getString(R.string.workout_rest_chip, clock(ex.rest * 1000L)), R.color.hover, R.color.ink, title).apply {
            contentDescription = getString(R.string.workout_rest_default, clock(ex.rest * 1000L))
        }
        val columns = row(block).apply { setPadding(0, dp(6), 0, 0) }
        headerCell(getString(R.string.workout_set), .55f, columns, 0)
        headerCell(getString(R.string.workout_previous), 1.35f, columns)
        headerCell(getString(R.string.unit_kg), 1f, columns)
        headerCell(getString(R.string.workout_reps), 1f, columns)
        headerCell(getString(R.string.workout_rpe), .85f, columns)
        columns.addView(View(this), LinearLayout.LayoutParams(dp(44), 0).apply { marginStart = dp(6) })
        ex.sets.forEachIndexed { setIndex, set ->
            val setRow = row(block, top = 6, center = true)
            fun paint() { setRow.background = if (set.done) shape(getColor(R.color.tonal), 10) else null }
            text("${setIndex + 1}", 15, getColor(R.color.ink), setRow).apply {
                instrumentType(numeric = true); gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, -2, .55f)
            }
            text(set.previous ?: "—", 13, getColor(R.color.muted), setRow).apply {
                instrumentType(numeric = true); gravity = Gravity.CENTER; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, -2, 1.35f).apply { marginStart = dp(6) }
            }
            smallInput(set.load, getString(R.string.unit_kg), true, setRow) { set.load = it; save() }
            smallInput(set.reps, getString(R.string.workout_reps), false, setRow) { set.reps = it; save() }
            smallInput(set.rpe, getString(R.string.workout_rpe), true, setRow) { set.rpe = it; save() }
                .layoutParams = LinearLayout.LayoutParams(0, -2, .85f).apply { marginStart = dp(6) }
            val tick = android.widget.ImageButton(this).apply {
                contentDescription = getString(R.string.workout_done_set, setIndex + 1, ex.name)
                scaleType = android.widget.ImageView.ScaleType.CENTER
                fun look() {
                    setImageDrawable(lineIcon("check", getColor(if (set.done) R.color.on_action else R.color.muted)))
                    background = shape(getColor(if (set.done) R.color.signal else R.color.hover), 10)
                    isSelected = set.done
                }
                look()
                setOnClickListener {
                    if (!set.done && setBody(draft.day, ex.id, null, listOf("${setIndex + 1}", set.reps, set.load, set.rpe)) == null) {
                        snack(getString(R.string.workout_set_invalid)); return@setOnClickListener
                    }
                    set.done = !set.done
                    save(); look(); paint(); updateHead()
                    if (set.done && draft.restsAfter(index)) startRest(ex)
                }
                pressFeedback()
            }
            setRow.addView(tick, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(6) })
            paint()
        }
        val actions = row(block)
        fun rerender() { save(); workoutActive((page.parent as ScrollView).scrollY) }
        button(getString(R.string.workout_add_set), parent = actions, look = ActionTone.TEXT) {
            val last = ex.sets.lastOrNull()
            ex.sets += LiftSet(last?.load.orEmpty(), last?.reps.orEmpty())
            rerender()
        }.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        if (index < draft.exercises.lastIndex) button(getString(if (ex.superset != null && ex.superset == draft.exercises[index + 1].superset)
                R.string.workout_unlink else R.string.workout_link), parent = actions, look = ActionTone.TEXT) {
            val next = draft.exercises[index + 1]
            if (ex.superset != null && ex.superset == next.superset) next.superset = null
            else { val group = ex.superset ?: ((draft.exercises.mapNotNull { it.superset }.maxOrNull() ?: 0) + 1); ex.superset = group; next.superset = group }
            if (draft.exercises.none { it !== ex && it.superset == ex.superset }) ex.superset = null
            rerender()
        }.layoutParams = LinearLayout.LayoutParams(0, -2, 1.3f)
    }
    button(getString(R.string.workout_add_exercise), look = ActionTone.TONAL) { exercisePicker() }.apply {
        compoundDrawablePadding = dp(8)
        setCompoundDrawablesRelativeWithIntrinsicBounds(lineIcon("plus", green), null, null, null)
        (layoutParams as LinearLayout.LayoutParams).topMargin = dp(20)
    }
    val notes = field(getString(R.string.workout_notes), draft.notes)
    notes.setSingleLine(false); notes.maxLines = 4
    notes.onChange { draft.notes = notes.value().take(1000); save() }
    button(getString(R.string.workout_finish), primary = true) { finishWorkout(draft) }.apply { minHeight = dp(56); textSize = 17f }
    button(getString(R.string.workout_discard), look = ActionTone.TEXT) {
        android.app.AlertDialog.Builder(this).setMessage(R.string.workout_discard_confirm)
            .setPositiveButton(R.string.workout_discard) { _, _ -> WorkoutDraft.clear(this); timerCommand(TimerService.STOP); workoutStart() }
            .setNegativeButton(R.string.cancel, null).show()
    }
    restBar()
    if (scrollTo > 0) page.post { (page.parent as ScrollView).scrollTo(0, scrollTo) }
}

private fun MainActivity.startRest(ex: LiftExercise) {
    askNotifications()
    restExercise = ex.id
    startTimer("rest", listOf(getString(R.string.rest_title)), listOf(getString(R.string.rest_next, ex.name)),
        intArrayOf(ex.rest), booleanArrayOf(false))
}

/** The rest timer docked under the page: large countdown, −15 s, +15 s and Skip. Hidden when no rest runs. */
private fun MainActivity.restBar() {
    val shell = (page.parent as ScrollView).parent as LinearLayout
    val bar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = raisedSurface(hero = true).apply { cornerRadius = 0f }
        setPadding(dp(18), dp(10), dp(18), dp(10))
        visibility = View.GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
    }
    shell.addView(bar, LinearLayout.LayoutParams(-1, -2))
    val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    bar.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
    text(getString(R.string.rest_title).uppercase(locale), 12, getColor(R.color.on_hero), column).apply { letterSpacing = .14f; setPadding(0, 0, 0, 0) }
    val countdown = TextView(this).apply {
        textSize = 36f; instrumentType(numeric = true); setTextColor(getColor(R.color.signal))
        column.addView(this)
    }
    fun key(label: String, description: Int, action: () -> Unit) = styleButton(android.widget.Button(this), false, ActionTone.SECONDARY).apply {
        text = label; contentDescription = getString(description); textSize = 15f; minWidth = dp(56); minimumWidth = dp(56)
        setPadding(dp(12), 0, dp(12), 0)
        background = shape(getColor(R.color.hero_top), 10, getColor(R.color.on_hero)); setTextColor(android.graphics.Color.WHITE)
        setOnClickListener { action() }
        bar.addView(this, LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(6) })
    }
    fun adjust(delta: Int) {
        timerCommand(TimerService.ADJUST, delta)
        val draft = WorkoutDraft.load(this) ?: return
        draft.exercises.filter { it.id == restExercise }.forEach { it.rest = (it.rest + delta).coerceIn(15, 900) }
        restExercise?.let { id -> draft.exercises.firstOrNull { it.id == id }?.let {
            getSharedPreferences("workout", Context.MODE_PRIVATE).edit().putInt("rest_$id", it.rest).apply() } }
        WorkoutDraft.save(this, draft)
    }
    key("−15", R.string.rest_minus) { adjust(-15) }
    key("+15", R.string.rest_plus) { adjust(15) }
    key(getString(R.string.rest_skip), R.string.rest_skip) { timerCommand(TimerService.SKIP) }
    ticker(countdown) {
        val resting = TimerState.running && TimerState.kind == "rest"
        bar.visibility = if (resting) View.VISIBLE else View.GONE
        if (resting) countdown.text = clock(TimerState.remainingMs(SystemClock.elapsedRealtime()))
    }
}

/** The finish summary: duration, sets, volume and records hit, then the workout and its sets go through the queue. */
private fun MainActivity.finishWorkout(draft: WorkoutDraft) {
    val sets = draft.exercises.flatMap { ex -> ex.sets.filter { it.done }.mapIndexed { n, set ->
        setBody(draft.day, ex.id, draft.planId, listOf("${n + 1}", set.reps, set.load, set.rpe))?.put("workout_client_id", draft.clientId)
    } }
    if (sets.isEmpty()) return snack(getString(R.string.workout_nothing_done))
    if (sets.any { it == null }) return snack(getString(R.string.workout_set_invalid))
    val ended = OffsetDateTime.now(ZoneOffset.UTC).withNano(0)
    val body = JSONObject().put("started_at", draft.startedAt).put("ended_at", ended.toString()).put("title", draft.title)
        .put("template_id", draft.templateId ?: JSONObject.NULL).put("notes", draft.notes)
    if (TimerState.kind == "rest") timerCommand(TimerService.STOP)
    saveFeature(FeatureWrite.WORKOUT, body, day = draft.day, clientId = draft.clientId,
        more = sets.map { FeatureWrite.SET to it!! }) { status, message ->
        if (status == R.string.feature_saved || status == R.string.saved_pending) {
            WorkoutDraft.clear(this)
            workoutSummary(draft, Duration.between(OffsetDateTime.parse(draft.startedAt), ended).toMillis(), message)
        } else { workoutActive(); snack(message) }
    }
}

private fun MainActivity.workoutSummary(draft: WorkoutDraft, durationMs: Long, message: String) {
    layout(getString(R.string.workout_done_title), "workout_summary")
    val done = draft.exercises.map { ex -> ex to ex.sets.filter { it.done } }.filter { it.second.isNotEmpty() }
    fun load(set: LiftSet) = decimal(set.load)
    val volume = done.sumOf { (_, sets) -> sets.sumOf { (load(it) ?: 0.0) * (it.reps.toIntOrNull() ?: 0) } }
    val records = done.count { (ex, sets) -> ex.best != null && (sets.mapNotNull(::load).maxOrNull() ?: 0.0) > ex.best }
    val hero = card(hero = true)
    text(draft.title, 14, getColor(R.color.on_hero), hero)
    hero.addView(Measurement(this).apply {
        text = clock(durationMs); textSize = 56f; setTextColor(getColor(R.color.signal)); instrumentType(numeric = true)
        contentDescription = getString(R.string.workout_duration) + " " + clock(durationMs)
    })
    text(getString(R.string.workout_duration), 13, getColor(R.color.on_hero), hero)
    statGrid(listOf(Triple(getString(R.string.workout_sets_done), done.sumOf { it.second.size }.toString(), ""),
        Triple(getString(R.string.workout_volume), metricNumber(volume, "kcal", locale), getString(R.string.unit_kg)),
        Triple(getString(R.string.workout_records), records.toString(), ""),
        Triple(getString(R.string.workout_exercise_count), done.size.toString(), "")))
    if (records > 0) text(getString(R.string.workout_records_note), 13)
    val list = settingsGroup()
    done.forEachIndexed { index, (ex, sets) ->
        if (index > 0) divider(list, inset = false)
        val top = sets.maxByOrNull { load(it) ?: 0.0 }!!
        val best = if (load(top) == null) "${sets.size} × ${top.reps}" else "${sets.size} × ${top.reps} · ${kgText(load(top)!!)}"
        valueRow(ex.name, best, list).apply { setPadding(dp(16), dp(10), dp(16), dp(10)) }
    }
    notice(message, caution = message != getString(R.string.feature_saved))
    button(getString(R.string.workout_save_template), look = ActionTone.TONAL) {
        val items = JSONArray(done.map { (ex, sets) ->
            JSONObject().put("exercise_id", ex.id).put("sets", sets.size).put("reps", sets.last().reps.toInt())
                .put("load_kg", sets.mapNotNull(::load).maxOrNull() ?: JSONObject.NULL).put("rest_s", ex.rest)
                .put("superset", ex.superset ?: JSONObject.NULL)
        })
        val api = featureApi()
        work(getString(R.string.feature_saving), { api.post("/api/workout-templates", JSONObject().put("title", draft.title).put("items", items)) },
            { workoutStart(); snack(getString(R.string.workout_template_saved)) }, { workoutSummary(draft, durationMs, it) })
    }
    button(getString(R.string.workout_close), primary = true) { workoutStart() }
}

/** Search the club's exercises; recent ones first. */
internal fun MainActivity.exercisePicker(query: String = "") {
    val gym = gymData ?: return run {
        val api = featureApi()
        work(getString(R.string.feature_loading), { GymData(api.array("/api/exercises"), api.array("/api/set-logs?limit=500")) },
            { gymData = it; exercisePicker(query) }, { workoutActive(); snack(it) })
    }
    layout(getString(R.string.workout_pick), "workout_pick")
    val search = field(getString(R.string.workout_search), query)
    val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; page.addView(this) }
    fun add(exercise: JSONObject) {
        val draft = WorkoutDraft.load(this) ?: return workoutStart()
        draft.exercises += newExercise(exercise.getLong("id"), exercise.getString("name"))
        WorkoutDraft.save(this, draft)
        workoutActive(Int.MAX_VALUE / 2)
    }
    fun list(label: String, rows: List<JSONObject>) {
        if (rows.isEmpty()) return
        eyebrow(label, results)
        val group = settingsGroup(results).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(4) }
        rows.forEachIndexed { index, exercise ->
            if (index > 0) divider(group)
            val last = gym.lastSession(exercise.getLong("id"))
            settingRow(when (exercise.optString("kind")) { "plyometric" -> "legs"; "mobility" -> "core"; "conditioning" -> "conditioning"; else -> "training" },
                exercise.getString("name"), last.firstOrNull()?.let { getString(R.string.workout_last, localDate(it.getString("day"), locale)) },
                group) { add(exercise) }
        }
    }
    fun update() {
        results.removeAllViews()
        val q = search.value().lowercase(locale)
        if (q.isEmpty()) list(getString(R.string.workout_recent), gym.recent())
        val matches = gym.exercises.filter { q.isEmpty() || it.getString("name").lowercase(locale).contains(q) }
        list(getString(if (q.isEmpty()) R.string.workout_all else R.string.workout_matches), matches)
        if (matches.isEmpty()) text(getString(R.string.workout_no_match), 14, parent = results)
    }
    search.onChange(::update)
    update()
}

/** Ready-made programs (SPEC 10.8); adopting one adds ordinary templates, shown on the start screen. */
internal fun MainActivity.programs(message: String? = null) {
    work(getString(R.string.feature_loading), { featureApi().array("/api/programs") }, { list ->
        layout(getString(R.string.programs_title), "programs")
        if (message != null) notice(message)
        val bg = locale.language == "bg"
        for (program in list) {
            text(program.getString(if (bg) "title_bg" else "title_en"), 20).apply {
                instrumentType(display = true); setPadding(0, dp(20), 0, 0)
                if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            }
            text(program.getString(if (bg) "note_bg" else "note_en"), 13)
            for (day in program.getJSONArray("days").objects()) {
                val items = day.getJSONArray("items").objects()
                text(day.optString(if (bg) "name" else "name_en", day.getString("name")), 14, getColor(R.color.muted)).setPadding(0, dp(8), 0, 0)
                text(items.joinToString("\n") {
                    val dose = if (it.optBoolean("hold")) getString(R.string.programs_hold, it.getInt("sets")) else "${it.getInt("sets")}×${it.getInt("reps")}"
                    "${it.optString(if (bg) "exercise" else "exercise_en", it.getString("exercise"))}  $dose"
                }, 15)
            }
            button(getString(R.string.programs_adopt)) {
                work(getString(R.string.feature_saving), { featureApi().post("/api/programs/${program.getString("key")}/adopt", JSONObject()) }, { result ->
                    val created = result.optJSONArray("created")?.length() ?: 0
                    programs(if (created == 0) getString(R.string.programs_already) else resources.getQuantityString(R.plurals.programs_added, created, created))
                }, { programs(it) })
            }
        }
        button(getString(R.string.programs_start), primary = true) { workoutStart() }
    }, { workoutStart() })
}
