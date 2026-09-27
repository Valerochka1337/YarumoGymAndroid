package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "coach_messages",
    primaryKeys = ["id"],
    foreignKeys =
        [
            ForeignKey(
                entity = WorkoutEntity::class,
                parentColumns = ["id"],
                childColumns = ["workoutId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("workoutId"), Index("accountId")],
)
data class CoachMessageEntity(
    val id: String,
    val accountId: String,
    val workoutId: String,
    val role: String,
    val text: String,
    val createdAt: Long,
    val status: String = "DELIVERED",
    val quickRepliesJson: String? = null,
    /** Only assistant replies participate in the Live Coach unread counter. */
    val readAt: Long? = null,
    val sourceSetsJson: String? = null,
)
