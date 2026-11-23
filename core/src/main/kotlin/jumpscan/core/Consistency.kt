/*
 * Consistency score (SPEC 10.3): the habit strength of Loop Habit Tracker, ported from uhabits-core
 * `org.isoron.uhabits.core.models.Score.compute` (Copyright (C) 2016-2025 Álinson Santos Xavier
 * <git@axavier.org>), free software under the GNU General Public License version 3 or (at your option) any
 * later version. This file is part of Talos and is distributed under the same licence (GPL-3.0-or-later);
 * see LICENSE and LICENSES-AUDIT.md.
 */
package jumpscan.core

import kotlin.math.pow
import kotlin.math.sqrt

object Consistency {
    /** Loop's step: `previous * m + done * (1 - m)` with `m = 0.5 ^ (sqrt(frequency) / 13)`. */
    fun step(frequency: Double, previous: Double, done: Boolean): Double {
        val multiplier = 0.5.pow(sqrt(frequency) / 13.0)
        return previous * multiplier + (if (done) 1.0 - multiplier else 0.0)
    }

    /** Score after the last day of [days], oldest first, starting at 0; [frequency] per day (habit target / 7 etc.). */
    fun score(days: List<Boolean>, frequency: (Int) -> Double = { 1.0 }): Double =
        days.foldIndexed(0.0) { index, score, done -> step(frequency(index), score, done) }

    /** The score as a whole percentage, as shown beside "12 of the last 14 days". */
    fun percent(score: Double): Int = Math.round(score * 100).toInt()
}
