package com.example.stockzilla

import java.io.Serializable

data class HealthScoreDetail(
    val label: String,
    val value: String,
    val weight: String?,
    val normalized: String?,
    val rationale: String?
) : Serializable