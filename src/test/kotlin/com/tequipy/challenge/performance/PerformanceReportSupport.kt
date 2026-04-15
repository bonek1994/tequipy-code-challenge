package com.tequipy.challenge.performance

data class AlgorithmSpeedStats(
    val samples: Int,
    val avgMs: Double,
    val p50Ms: Double,
    val p99Ms: Double,
    val maxMs: Double,
    val totalMs: Double,
    val throughputPerSecond: Double
)

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

fun renderAllocationAlgorithmSpeedSection(
    stats: AlgorithmSpeedStats,
    tableDivider: String
): String = buildString {
    appendLine("### Allocation Algorithm Speed")
    appendLine("| Metric | Value |")
    appendLine(tableDivider)
    appendLine("| Samples | ${stats.samples} |")
    appendLine("| Avg | ${"%.3f".format(stats.avgMs)} ms |")
    appendLine("| P50 | ${"%.3f".format(stats.p50Ms)} ms |")
    appendLine("| P99 | ${"%.3f".format(stats.p99Ms)} ms |")
    appendLine("| Max | ${"%.3f".format(stats.maxMs)} ms |")
    appendLine("| Total algorithm time | ${"%.3f".format(stats.totalMs)} ms |")
    appendLine("| Throughput | ${"%.1f".format(stats.throughputPerSecond)} alloc/s |")
}

private fun percentileMs(sortedDurationsNanos: List<Long>, percentile: Double): Double {
    if (sortedDurationsNanos.isEmpty()) return 0.0
    val index = (sortedDurationsNanos.size * percentile).toInt().coerceAtMost(sortedDurationsNanos.size - 1)
    return sortedDurationsNanos[index] / 1_000_000.0
}
