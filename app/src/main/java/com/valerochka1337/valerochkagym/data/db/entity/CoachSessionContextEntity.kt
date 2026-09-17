package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Durable, app-authored context; authority, prompts and pulse stream never enter this row. */
@Entity(
    tableName = "coach_session_context",
    primaryKeys = ["workoutId"],
    foreignKeys =
        [
            ForeignKey(
                entity = WorkoutEntity::class,
                parentColumns = ["id"],
                childColumns = ["workoutId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("accountId")],
)
data class CoachSessionContextEntity(
    val workoutId: String,
    val accountId: String,
    val availableTimeMinutes: Int? = null,
    /** Wall-clock deadline keeps a user-stated remaining time stable across process recreation. */
    val availableTimeEndsAtMillis: Long? = null,
    val futureRestSeconds: Int? = null,
    val excludedExerciseIdsJson: String = "[]",
    val lastUndoPacketJson: String? = null,
    val lastUndoRevision: Long? = null,
    val initiativeEnabled: Boolean = true,
    val initiativeWelcomed: Boolean = false,
    val initiativeAutomaticCount: Int = 0,
    val initiativeLastAutomaticAtMillis: Long? = null,
    val initiativeAskedExerciseIdsJson: String = "[]",
    val initiativeEndReminderSent: Boolean = false,
    val initiativePendingInteraction: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "'{}'") val autoregulationOptionsJson: String = "{}",
    @androidx.room.ColumnInfo(defaultValue = "'[]'") val decisionMemoryJson: String = "[]",
)
