package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One optional profile per local guest or authenticated owner scope. */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val scope: String,
    val syncId: String,
    val schemaVersion: Int = 1,
    val trainingGoal: String? = null,
    val sex: String? = null,
    val birthDate: String? = null,
    val experienceLevel: String? = null,
    val plannedSessionsPerWeek: Int? = null,
    val preferredSessionDurationMinutes: Int? = null,
    val manualConstraints: String? = null,
    val updatedAt: Long = 0,
    val preferredRepMin: Int? = null,
    val preferredRepMax: Int? = null,
)
