package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "coach_behavior")
data class CoachBehaviorEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val workoutId: String,
    val kind: String,
    val payload: String,
    val status: String = "OPEN",
    val requestJson: String? = null,
)

@Entity(tableName = "coach_phase", primaryKeys = ["accountId", "workoutId"])
data class CoachPhaseEntity(
    val accountId: String,
    val workoutId: String,
    val phase: String = "UNKNOWN",
    val paused: Boolean = false,
    val resolvedConcernKeys: String = "[]",
)
