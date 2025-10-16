package com.example.stockzilla

import java.io.Serializable

enum class MetricPerformance {
    GOOD,
    NEUTRAL,
    POOR
}

data class HealthScoreDetail(
    val label: String,
    val value: String,
    val weight: String?,
    val normalized: String?,
    val performance: MetricPerformance?,
    val rationale: String?
) : Serializable