package jumpscan.core

/**
 * Early check-up hint from landing mechanics. A screening prompt to see a sports doctor or physiotherapist,
 * never a diagnosis.
 *
 * Rule: over the newest [JUMPS] usable front-view jumps, the median knee/ankle separation ratio at deepest
 * landing flexion is below [RATIO_BELOW] (knees caving inwards, dynamic valgus; Noyes et al. 2005 use the
 * same normalised separation). One jump is never enough. Left/right flexion asymmetry is not used: from the
 * front the knee angle is off by about 17 degrees, and on the Twente lab jumps a 15 % asymmetry rule
 * flagged a healthy subject in 2 of 4 windows, while this ratio rule flagged none of 25.
 */
object KneeCheck {
    const val JUMPS = 3
    const val RATIO_BELOW = 0.6
    private val unreliable = setOf("low_fps", "low_visibility", "no_flight_detected", "uncertain_timing", "single_leg_view")

    /** One jump: `ratio` is null for side-view jumps. */
    data class Mark(val ratio: Double?, val warnings: Collection<String>)

    /** `marks` newest first. True when the newest usable jumps show knees caving in. */
    fun suggestCheckup(marks: List<Mark>): Boolean {
        val usable = marks.filter { it.ratio != null && it.warnings.none(unreliable::contains) }.take(JUMPS)
        return usable.size == JUMPS && usable.map { it.ratio!! }.sorted()[JUMPS / 2] < RATIO_BELOW
    }
}
