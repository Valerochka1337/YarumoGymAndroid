package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "workouts",
    foreignKeys =
        [
            ForeignKey(
                entity = RoutineEntity::class,
                parentColumns = ["id"],
                childColumns = ["routineId"],
                onDelete = ForeignKey.SET_NULL,
            ),
        ],
    indices = [Index("routineId"), Index("finishedAt")],
)
data class WorkoutEntity(
    @PrimaryKey val id: String,
    val routineId: Long? = null,
    val name: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val note: String = "",
    val uploadStatus: UploadStatus = UploadStatus.PENDING,
    val uploadError: String? = null,
    /** Monotonic local revision used by the coach command/proposal gate. */
    @ColumnInfo(defaultValue = "0") val coachRevision: Long = 0,
    /** Captured once when the workout is created. Null marks a legacy session. */
    val coachOriginalPlanJson: String? = null,
)
