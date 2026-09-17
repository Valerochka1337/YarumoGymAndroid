package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "workout_sets",
    foreignKeys =
        [
            ForeignKey(
                entity = WorkoutExerciseEntity::class,
                parentColumns = ["id"],
                childColumns = ["workoutExerciseId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("workoutExerciseId"), Index(value = ["syncId"], unique = true)],
)
data class WorkoutSetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutExerciseId: Long,
    val setIndex: Int,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val durationSec: Int? = null,
    val speedKmh: Double? = null,
    val inclinePct: Double? = null,
    val isCompleted: Boolean = false,
    val completedAt: Long? = null,
    @ColumnInfo(defaultValue = "''") val note: String = "",
    /** Stable portable identity. It is deliberately independent of the local Room primary key. */
    @ColumnInfo(defaultValue = "''") val syncId: String = UUID.randomUUID().toString(),
    val originalWeightKg: Double? = null,
    val originalReps: Int? = null,
    val originalDurationSec: Int? = null,
    val originalSpeedKmh: Double? = null,
    val originalInclinePct: Double? = null,
    val targetWeightKg: Double? = null,
    val targetReps: Int? = null,
    val targetDurationSec: Int? = null,
    val targetSpeedKmh: Double? = null,
    val targetInclinePct: Double? = null,
    val actualWeightKg: Double? = null,
    val actualReps: Int? = null,
    val actualDurationSec: Int? = null,
    val actualSpeedKmh: Double? = null,
    val actualInclinePct: Double? = null,
    @ColumnInfo(defaultValue = "'UNKNOWN'") val setType: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'[]'") val reportedFeelingsJson: String = "[]",
    val restSnapshotJson: String? = null,
    @ColumnInfo(defaultValue = "0") val coachMutationRevision: Long = 0,
    /** Retained only for compatibility with stored history; never used as a training target. */
    @ColumnInfo(name = "targetRir") val legacyTargetRir: Int? = null,
    val actualRir: Int? = null,
    @ColumnInfo(defaultValue = "0") val actualRirAtLeastFour: Boolean = false,
)
