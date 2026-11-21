package jumpscan.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Gym-floor calculators (SPEC 9, wave 1). Loads in kilograms; plate sizes are per plate, loaded in pairs. */
object GymMath {
    /** Plates per side for a bar total. [exact] is false when the target cannot be built; [plates] then
     *  loads the nearest total below it and [above] is the nearest total above it (null when none). */
    data class PlateLoad(val target: Double, val total: Double, val plates: List<Double>, val exact: Boolean, val above: Double?)

    private const val STEP = 0.05 // smallest plate unit we resolve (fractional plates of 0.25 and 1.25 kg fit)

    private fun units(kg: Double) = (kg / STEP).roundToInt()

    /** Fewest plates per side for every reachable side weight up to [limit] units (unbounded plate counts). */
    private fun reachable(plates: List<Int>, limit: Int): IntArray {
        val best = IntArray(limit + 1) { -1 }
        best[0] = 0
        for (w in 1..limit) for (p in plates) if (p <= w && best[w - p] >= 0 && (best[w] < 0 || best[w - p] + 1 < best[w])) best[w] = best[w - p] + 1
        return best
    }

    private fun platesFor(side: Int, sizes: List<Int>, best: IntArray): List<Double> {
        val out = mutableListOf<Double>()
        var rest = side
        while (rest > 0) {
            val p = sizes.first { it <= rest && best[rest - it] == best[rest] - 1 }
            out += p * STEP
            rest -= p
        }
        return out.sortedDescending()
    }

    fun plates(target: Double, bar: Double, available: List<Double>): PlateLoad {
        require(target.isFinite() && bar >= 0 && target >= 0)
        val sizes = available.filter { it > 0 }.map(::units).filter { it > 0 }.distinct().sortedDescending()
        if (target < bar) return PlateLoad(target, bar, emptyList(), false, bar)
        val wanted = floor((target - bar) / 2 / STEP + 1e-6).toInt()
        val limit = wanted + (sizes.maxOrNull() ?: 0)
        val best = reachable(sizes, limit)
        val below = (wanted downTo 0).first { best[it] >= 0 }
        val total = round2(bar + below * STEP * 2)
        val exact = abs(total - target) < 1e-6
        val above = if (exact) total else (below + 1..limit).firstOrNull { best[it] >= 0 }?.let { round2(bar + it * STEP * 2) }
        return PlateLoad(target, total, platesFor(below, sizes, best), exact, above)
    }

    /** Estimated one-repetition maximum. Both formulas return the load itself for one repetition. */
    fun epley(load: Double, reps: Int): Double {
        require(load >= 0 && reps >= 1)
        return if (reps == 1) load else load * (1 + reps / 30.0)
    }

    /** Brzycki is defined below 37 repetitions; it is commonly trusted only up to about 10. */
    fun brzycki(load: Double, reps: Int): Double? {
        require(load >= 0 && reps >= 1)
        return if (reps >= 37) null else load * 36.0 / (37 - reps)
    }

    /** Loads at 100 % down to 50 % of a one-repetition maximum, rounded down to the smallest increment. */
    fun percentTable(oneRepMax: Double, increment: Double = 2.5): List<Pair<Int, Double>> =
        (100 downTo 50 step 5).map { it to roundDown(oneRepMax * it / 100, increment) }

    data class WarmupSet(val load: Double, val reps: Int)

    /** Bar ×10, then 40 / 60 / 80 % of the working load for 5 / 3 / 2 repetitions, rounded to the increment,
     *  each heavier than the last and lighter than the working load. */
    fun warmup(working: Double, bar: Double, increment: Double = 2.5): List<WarmupSet> {
        if (working <= bar) return emptyList()
        val sets = mutableListOf(WarmupSet(bar, 10))
        for ((share, reps) in listOf(.4 to 5, .6 to 3, .8 to 2)) {
            val load = roundNearest(working * share, increment)
            if (load > sets.last().load && load < working) sets += WarmupSet(load, reps)
        }
        return sets
    }

    private fun round2(value: Double) = (value * 100).roundToLong() / 100.0
    private fun roundDown(value: Double, step: Double) = round2(floor(value / step + 1e-9) * step)
    private fun roundNearest(value: Double, step: Double) = round2((value / step).roundToLong() * step)
}

/** Interval timer phases (SPEC 9, wave 1). A stopwatch has no phases and counts up. */
object IntervalPlan {
    enum class Kind { READY, WORK, REST }
    data class Phase(val kind: Kind, val seconds: Int, val round: Int, val rounds: Int)

    const val READY_SECONDS = 5

    fun intervals(work: Int, rest: Int, rounds: Int): List<Phase> {
        require(work > 0 && rest >= 0 && rounds > 0)
        val out = mutableListOf(Phase(Kind.READY, READY_SECONDS, 1, rounds))
        for (round in 1..rounds) {
            out += Phase(Kind.WORK, work, round, rounds)
            if (rest > 0 && round < rounds) out += Phase(Kind.REST, rest, round, rounds)
        }
        return out
    }

    fun tabata() = intervals(20, 10, 8)

    /** Every minute on the minute: one work block of 60 s per round. */
    fun emom(minutes: Int) = intervals(60, 0, minutes)

    /** As many rounds as possible: one long work block. */
    fun amrap(minutes: Int) = intervals(minutes * 60, 0, 1)

    fun total(phases: List<Phase>) = phases.sumOf { it.seconds }
}
