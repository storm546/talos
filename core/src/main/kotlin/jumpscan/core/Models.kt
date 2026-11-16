package jumpscan.core

/** One MediaPipe pose landmark: normalised image coordinates (x right, y down, 0..1). */
data class Landmark(val x: Float, val y: Float, val z: Float, val visibility: Float)

/**
 * Captured at [tMs] milliseconds from clip start, 33 landmarks each in MediaPipe order:
 * [landmarks] normalised image coordinates, [worldLandmarks] MediaPipe world landmarks (metres, hip-centred).
 */
data class Frame(val tMs: Long, val landmarks: List<Landmark>, val worldLandmarks: List<Landmark>)

/** Fields of SPEC.md section 1. L/R are the person's left/right; null means unavailable in this view. */
data class JumpMetrics(
    val test: String,
    val fps: Double,
    val flightTimeMs: Double?,
    val jumpHeightCm: Double?,
    val kneeFlexionContactL: Double?,
    val kneeFlexionContactR: Double?,
    val kneeFlexionPeakL: Double?,
    val kneeFlexionPeakR: Double?,
    val fppaPeakL: Double?,
    val fppaPeakR: Double?,
    val kneeAnkleRatio: Double?,
    val flexionAsymmetryPct: Double?,
    val quality: Double,
    val warnings: List<String>,
)

/** Hand-written JSON with the snake_case keys of SPEC.md section 1; numbers rounded to 2 decimals. */
fun JumpMetrics.toJson(): String {
    fun num(v: Double?): String {
        if (v == null) return "null"
        require(v.isFinite()) { "metric is not finite" }
        return (Math.round(v * 100) / 100.0).toString()
    }
    fun str(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    val fields = listOf(
        "test" to str(test),
        "fps" to num(fps),
        "flight_time_ms" to num(flightTimeMs),
        "jump_height_cm" to num(jumpHeightCm),
        "knee_flexion_contact_l" to num(kneeFlexionContactL),
        "knee_flexion_contact_r" to num(kneeFlexionContactR),
        "knee_flexion_peak_l" to num(kneeFlexionPeakL),
        "knee_flexion_peak_r" to num(kneeFlexionPeakR),
        "fppa_peak_l" to num(fppaPeakL),
        "fppa_peak_r" to num(fppaPeakR),
        "knee_ankle_ratio" to num(kneeAnkleRatio),
        "flexion_asymmetry_pct" to num(flexionAsymmetryPct),
        "quality" to num(quality),
        "warnings" to warnings.joinToString(",", "[", "]") { str(it) },
    )
    return fields.joinToString(",", "{", "}") { (k, v) -> "\"$k\":$v" }
}
