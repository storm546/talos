package jumpscan.core

data class FormBand(val metric: String, val min: Double?, val max: Double?) {
    fun contains(value: Double?) = value != null && (min == null || value >= min) && (max == null || value <= max)
}

data class FormCheck(val key: String, val labelBg: String, val labelEn: String, val unit: String,
    val cueBg: String, val cueEn: String, val band: FormBand)

data class FormSignal(val metric: String, val up: Double, val down: Double, val minRange: Double,
    val gates: List<FormBand>, val peakAtTop: Boolean, val legacy: Boolean, val depthMin: Double?,
    val openFlexion: Double?, val minPhaseMs: Long, val eachLimb: Boolean = false, val minVisibility: Float = 0.5f)

/** [experimental] exercises have not passed the repetition benchmark (core/VALIDATION.md). The app lists them
 *  apart, collapsed and marked as not validated, with the one-line [reasonEn]/[reasonBg]. */
data class FormExercise(val id: String, val nameBg: String, val nameEn: String, val mode: String,
    val sided: Boolean, val view: String, val signal: FormSignal, val checks: List<FormCheck>,
    val experimental: Boolean = false, val reasonEn: String? = null, val reasonBg: String? = null,
    /** Driving signal used when the camera sees the athlete from the front, if it differs from [signal]. */
    val signalFront: FormSignal? = null) {
    fun validateSide(side: String?) {
        require(if (sided) side == "L" || side == "R" else side == null) { "invalid exercise side" }
    }
}

object FormExercises {
    val all: List<FormExercise> by lazy {
        val text = checkNotNull(FormExercises::class.java.getResourceAsStream("/form_exercises.json"))
            .bufferedReader().use { it.readText() }
        val json = FormJsonReader(text)
        json.array().map { entry ->
            val row = entry as Map<*, *>
            fun band(item: Map<*, *>) = FormBand(item.string("metric"), item["min"] as? Double, item["max"] as? Double)
            fun signal(signal: Map<*, *>) = FormSignal(signal.string("metric"), signal["up"] as Double, signal["down"] as Double,
                signal["min_range"] as Double, listOfNotNull(signal["gate"] as? Map<*, *>,
                    signal["support"] as? Map<*, *>).map(::band), signal["peak"] == "top", signal["legacy"] == true,
                signal["depth_min"] as? Double, signal["open_flexion"] as? Double,
                (signal["min_phase_ms"] as? Double)?.toLong() ?: 150, signal["limbs"] == "each",
                (signal["min_visibility"] as? Double)?.toFloat() ?: 0.5f)
            FormExercise(row.string("id"), row.string("name_bg"), row.string("name_en"), row.string("mode"),
                row["sided"] as Boolean, row.string("view"),
                signal(row["signal"] as Map<*, *>),
                (row["checks"] as List<*>).map { entryCheck ->
                    val check = entryCheck as Map<*, *>
                    FormCheck(check.string("key"), check.string("label_bg"), check.string("label_en"), check.string("unit"),
                        check.string("cue_bg"), check.string("cue_en"), band(check))
                }, row["experimental"] == true, row["experimental_reason_en"] as? String,
                row["experimental_reason_bg"] as? String, (row["signal_front"] as? Map<*, *>)?.let(::signal))
        }
    }
    /** Exercises that passed the repetition benchmark. */
    val validated: List<FormExercise> by lazy { all.filter { !it.experimental } }
    private val byId by lazy { all.associateBy { it.id } }
    operator fun get(id: String): FormExercise = requireNotNull(byId[id]) { "unknown form exercise: $id" }
    private fun Map<*, *>.string(key: String) = get(key) as String
}

private class FormJsonReader(private val text: String) {
    private var index = 0
    private fun peek(): Char {
        while (text.getOrNull(index)?.isWhitespace() == true) index++
        return text.getOrNull(index) ?: '\u0000'
    }
    private fun take(char: Char) { check(peek() == char) { "Expected $char at $index in form catalogue" }; index++ }
    private fun string(): String = buildString {
        take('"')
        while (text[index] != '"') {
            val char = text[index++]
            if (char != '\\') append(char) else append(when (val escape = text[index++]) {
                '"', '\\', '/' -> escape
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'b' -> '\b'
                'f' -> '\u000c'
                'u' -> text.substring(index, index + 4).toInt(16).toChar().also { index += 4 }
                else -> error("Invalid catalogue escape: $escape")
            })
        }
        index++
    }
    private fun <T> entries(open: Char, close: Char, read: () -> T): List<T> = buildList {
        take(open)
        if (peek() != close) {
            add(read())
            while (peek() == ',') { index++; add(read()) }
        }
        take(close)
    }
    fun array(): List<Any?> = entries('[', ']', ::value)
    private fun value(): Any? = when (peek()) {
        '"' -> string()
        '[' -> array()
        '{' -> entries('{', '}') { val key = string(); take(':'); key to value() }.toMap()
        else -> {
            val start = index
            while (text.getOrNull(index)?.let { !it.isWhitespace() && it !in ",]}" } == true) index++
            when (val token = text.substring(start, index)) {
                "true" -> true
                "false" -> false
                "null" -> null
                else -> token.toDouble()
            }
        }
    }
}
