package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity

/** Exact immutable request bytes survive lost acknowledgements and process death. */
@Entity(tableName = "coach_runs")
data class CoachRunEntity(
    @androidx.room.PrimaryKey val requestId: String,
    val accountId: String,
    val workoutId: String,
    val requestJson: String,
    val contextVersion: String,
    val createdAt: Long,
    val submitted: Boolean = false,
    val imported: Boolean = false,
    val cursor: Long = 0,
    val proposalId: String? = null,
)

/** A single persisted full snapshot is retried byte-for-byte until acknowledged. */
@Entity(tableName = "coach_session_outbox")
data class CoachSessionOutboxEntity(
    @androidx.room.PrimaryKey val workoutId: String,
    val accountId: String,
    val sequence: Long,
    val contextVersion: String,
    val payload: String,
    val delivered: Boolean = false,
    val discoveryComplete: Boolean = false,
)

@Entity(tableName = "coach_receipt_outbox")
data class CoachReceiptOutboxEntity(
    @androidx.room.PrimaryKey val receiptId: String,
    val accountId: String,
    val runId: String,
    val payload: String,
)

@Entity(tableName = "coach_dirty_sessions")
data class CoachDirtyEntity(
    @androidx.room.PrimaryKey val workoutId: String,
    val generation: Long = 1,
)
