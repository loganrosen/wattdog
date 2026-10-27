package io.github.loganrosen.wattdog.batterystats.analysis

import kotlin.math.abs
import kotlin.time.Duration.Companion.days

/** A change the user made. */
data class Experiment(
    val title: String,
    val startedAtMillis: Long,
    /** False when the change is to the always-on display itself, so earlier stretches can't be matched on it. */
    val matchAod: Boolean = true,
)

/**
 * Quiet stretches since the change, set against similar ones from before it. Even a clear difference
 * only shows drain was lower or higher; signal, app updates and background work all move it too.
 */
data class ExperimentProgress(
    val after: List<QuietStretch>,
    /** Quiet stretches of any kind in the two weeks before the change. */
    val before: Int,
    /** The stretches since the change being judged, all of one kind. */
    val compared: List<QuietStretch>,
    /** Earlier stretches of the same kind. */
    val earlier: List<QuietStretch>,
    /** Null until there's a quiet stretch since the change. */
    val outcome: Outcome?,
) {
    enum class Outcome { LOWER, SIMILAR, HIGHER, NOTHING_TO_COMPARE }

    val afterPercentPerHour: Double? get() = median(compared.map { it.percentPerHour })
    val earlierPercentPerHour: Double? get() = median(earlier.map { it.percentPerHour })
}

object Experiments {
    const val MIN_EARLIER = 3
    private val LOOKBACK = 14.days

    /** Changes smaller than this are within ordinary night-to-night variation. */
    private const val MIN_RELATIVE = 0.2
    private const val MIN_ABSOLUTE = 0.3

    fun start(title: String, action: SuggestedAction?, nowMillis: Long) =
        Experiment(title, nowMillis, matchAod = action != SuggestedAction.OpenLockScreenSettings)

    fun progress(experiment: Experiment, intervals: List<UsageInterval>): ExperimentProgress {
        val start = experiment.startedAtMillis
        val stretches = UsageLog.quietStretches(intervals)
        val after = stretches.filter { it.startMillis >= start }
        val before = stretches.filter { it.endMillis <= start && start - it.endMillis <= LOOKBACK.inWholeMilliseconds }
        if (after.isEmpty()) return ExperimentProgress(after, before.size, emptyList(), emptyList(), null)

        fun kind(s: QuietStretch) = s.network to (s.aod.takeIf { experiment.matchAod })
        val groups = after.groupBy(::kind).values.map { group -> group to before.filter { kind(it) == kind(group.first()) } }
        val comparable = groups.filter { it.second.size >= MIN_EARLIER }.maxByOrNull { it.first.size }
        if (comparable == null) {
            val (group, earlier) = groups.maxBy { it.first.size }
            return ExperimentProgress(after, before.size, group, earlier, ExperimentProgress.Outcome.NOTHING_TO_COMPARE)
        }
        val (group, earlier) = comparable
        val now = median(group.map { it.percentPerHour })!!
        val rates = earlier.map { it.percentPerHour }
        val usual = median(rates)!!
        val clear = abs(now - usual) >= MIN_ABSOLUTE && abs(now - usual) / usual >= MIN_RELATIVE
        val outcome = when {
            // Outside every earlier stretch as well as clearly off the middle, so one odd night can't decide it.
            clear && now < rates.min() -> ExperimentProgress.Outcome.LOWER

            clear && now > rates.max() -> ExperimentProgress.Outcome.HIGHER

            else -> ExperimentProgress.Outcome.SIMILAR
        }
        return ExperimentProgress(after, before.size, group, earlier, outcome)
    }
}

private fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}
