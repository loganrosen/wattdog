package io.github.loganrosen.wattdog.batterystats.analysis

import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.DurationUnit

/** A change the user made, and the screen-off drain it's measured against. */
data class Experiment(
    val title: String,
    val startedAtMillis: Long,
    val beforePercentPerHour: Double,
    /** Whether [beforePercentPerHour] is this phone's usual rate rather than one period's. */
    val beforeIsUsual: Boolean,
)

data class ExperimentProgress(
    val screenOff: Duration,
    val afterPercentPerHour: Double?,
    /** Null until there's [Experiments.NEEDED] of screen-off time to judge. */
    val outcome: Outcome?,
) {
    enum class Outcome { BETTER, NO_CHANGE, WORSE }

    /** Relative change in the screen-off rate, e.g. -0.3 for 30% lower. */
    fun change(before: Double): Double? = afterPercentPerHour?.let { it / before - 1 }
}

object Experiments {
    val NEEDED = 6.hours

    /** Changes smaller than this are within ordinary day-to-day variation. */
    private const val MIN_RELATIVE = 0.2
    private const val MIN_ABSOLUTE = 0.3

    fun start(title: String, diagnosis: Diagnosis, nowMillis: Long): Experiment? {
        val usual = diagnosis.typical?.idle?.median
        val before = usual ?: diagnosis.idle?.takeIf { it.material }?.percentPerHour ?: return null
        return Experiment(title, nowMillis, before, beforeIsUsual = usual != null)
    }

    /** Measures intervals that started after the change. */
    fun progress(experiment: Experiment, intervals: List<UsageInterval>): ExperimentProgress {
        val after = intervals.filter { it.startMillis >= experiment.startedAtMillis }
        val off = after.fold(Duration.ZERO) { acc, i -> acc + i.screenOff }
        val hours = off.toDouble(DurationUnit.HOURS)
        val rate = if (hours > 0) after.sumOf { it.screenOffShare } * 100 / hours else null
        val outcome = if (off < NEEDED || rate == null) {
            null
        } else {
            val diff = rate - experiment.beforePercentPerHour
            when {
                abs(diff) < MIN_ABSOLUTE || abs(diff) / experiment.beforePercentPerHour < MIN_RELATIVE ->
                    ExperimentProgress.Outcome.NO_CHANGE

                diff < 0 -> ExperimentProgress.Outcome.BETTER

                else -> ExperimentProgress.Outcome.WORSE
            }
        }
        return ExperimentProgress(off, rate, outcome)
    }
}
