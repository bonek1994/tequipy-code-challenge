package com.tequipy.challenge.performance

import java.util.Locale

data class AlgorithmSpeedStats(
    val samples: Int,
    val avgMs: Double,
    val p50Ms: Double,
    val p99Ms: Double,
    val maxMs: Double,
    val totalMs: Double,
    val throughputPerSecond: Double
)

/** Default number of initial invocations considered JVM warm-up (JIT, class loading). */
const val DEFAULT_WARMUP_COUNT = 50

fun buildAlgorithmSpeedStats(durationsNanos: List<Long>): AlgorithmSpeedStats {
    val totalNanos = durationsNanos.sum()
    return AlgorithmSpeedStats(
        samples = durationsNanos.size,
        avgMs = if (durationsNanos.isNotEmpty()) durationsNanos.average() / 1_000_000.0 else 0.0,
        p50Ms = percentileMs(durationsNanos, 0.50),
        p99Ms = percentileMs(durationsNanos, 0.99),
        maxMs = durationsNanos.maxOrNull()?.toDouble()?.div(1_000_000.0) ?: 0.0,
        totalMs = totalNanos / 1_000_000.0,
        throughputPerSecond = if (totalNanos > 0) durationsNanos.size * 1_000_000_000.0 / totalNanos else 0.0
    )
}

/**
 * Renders the algorithm speed section, splitting out JVM warm-up samples
 * from steady-state so the Max column makes sense.
 *
 * @param allDurationsSortedNanos **chronologically ordered** durations (as recorded)
 *        then sorted within each phase for percentile calculations.
 * @param chronologicalNanos raw recording order — used to separate warm-up from steady-state.
 * @param warmupCount how many initial invocations to attribute to warm-up.
 */
fun renderAllocationAlgorithmSpeedSection(
    stats: AlgorithmSpeedStats,
    tableDivider: String,
    chronologicalNanos: List<Long> = emptyList(),
    warmupCount: Int = DEFAULT_WARMUP_COUNT
): String = buildString {
    val hasWarmup = chronologicalNanos.size > warmupCount

    if (hasWarmup) {
        val warmupNanos = chronologicalNanos.take(warmupCount).sorted()
        val steadyNanos = chronologicalNanos.drop(warmupCount).sorted()
        val warmupStats = buildAlgorithmSpeedStats(warmupNanos)
        val steadyStats = buildAlgorithmSpeedStats(steadyNanos)

        appendLine("### Allocation Algorithm Speed (steady-state, after $warmupCount warm-up invocations)")
        appendLine("| Metric | Value |")
        appendLine(tableDivider)
        appendLine("| Samples | ${steadyStats.samples} |")
        appendLine("| Avg | ${fmt3(steadyStats.avgMs)} ms |")
        appendLine("| P50 | ${fmt3(steadyStats.p50Ms)} ms |")
        appendLine("| P99 | ${fmt3(steadyStats.p99Ms)} ms |")
        appendLine("| Max | ${fmt3(steadyStats.maxMs)} ms |")
        appendLine("| Total algorithm time | ${fmt3(steadyStats.totalMs)} ms |")
        appendLine("| Throughput | ${fmt1(steadyStats.throughputPerSecond)} alloc/s |")
        appendLine()
        appendLine("### JVM Warm-up Phase (first $warmupCount invocations)")
        appendLine("| Metric | Value |")
        appendLine(tableDivider)
        appendLine("| Samples | ${warmupStats.samples} |")
        appendLine("| Avg | ${fmt3(warmupStats.avgMs)} ms |")
        appendLine("| Max | ${fmt3(warmupStats.maxMs)} ms |")
    } else {
        appendLine("### Allocation Algorithm Speed")
        appendLine("| Metric | Value |")
        appendLine(tableDivider)
        appendLine("| Samples | ${stats.samples} |")
        appendLine("| Avg | ${fmt3(stats.avgMs)} ms |")
        appendLine("| P50 | ${fmt3(stats.p50Ms)} ms |")
        appendLine("| P99 | ${fmt3(stats.p99Ms)} ms |")
        appendLine("| Max | ${fmt3(stats.maxMs)} ms |")
        appendLine("| Total algorithm time | ${fmt3(stats.totalMs)} ms |")
        appendLine("| Throughput | ${fmt1(stats.throughputPerSecond)} alloc/s |")
    }
}

private fun fmt3(value: Double) = String.format(Locale.ROOT, "%.3f", value)
private fun fmt1(value: Double) = String.format(Locale.ROOT, "%.1f", value)

private fun percentileMs(sortedDurationsNanos: List<Long>, percentile: Double): Double {
    if (sortedDurationsNanos.isEmpty()) return 0.0
    val index = (sortedDurationsNanos.size * percentile).toInt().coerceAtMost(sortedDurationsNanos.size - 1)
    return sortedDurationsNanos[index] / 1_000_000.0
}
