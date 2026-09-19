package com.valerochka1337.valerochkagym.data.backend

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "backend_state")
data class BackendStateEntity(
    @PrimaryKey val id: Int = 1,
    val owner: String? = null,
    val generation: Long = 0,
    @ColumnInfo(defaultValue = "'GUEST'") val phase: GuestSyncPhase = GuestSyncPhase.GUEST,
    val mergeId: String? = null,
    @ColumnInfo(defaultValue = "0") val initialMergeAcknowledged: Boolean = false,
    val capabilityOwner: String? = null,
    @ColumnInfo(defaultValue = "''") val acceptedCapabilities: String = "",
)

enum class GuestSyncPhase {
  GUEST,
  CLAIMED,
  OWNED,
}

@Entity(tableName = "backend_baseline")
data class BackendBaselineEntity(@PrimaryKey val key: String, val recordJson: String)

@Entity(tableName = "backend_outbox")
data class BackendOutboxEntity(
    @PrimaryKey val id: Int = 1,
    val owner: String,
    val requestJson: String,
)

/** Records a definite server rejection without changing the retained request bytes. */
@Entity(tableName = "backend_rejected_operations")
data class BackendRejectedOperationEntity(
    @PrimaryKey val operationId: String,
    val owner: String,
)

@Entity(
    tableName = "backend_conflict_copies",
    primaryKeys =
        ["mergeId", "kind", "originalSyncId", "remoteRevision", "localPayloadFingerprint"],
    indices = [Index(value = ["localCopySyncId"], unique = true)],
)
data class BackendConflictCopyEntity(
    val mergeId: String,
    val kind: String,
    val originalSyncId: String,
    val remoteRevision: Long,
    val localPayloadFingerprint: String,
    val localCopySyncId: String,
)

object SyncSchema {
  val trackedTables =
      arrayOf(
          "exercises",
          "exercise_muscles",
          "exercise_equipment",
          "exercise_personal_hints",
          "profiles",
          "profile_equipment",
          "strength_planner_profiles",
          "strength_planner_key_exercises",
          "planner_exercise_preferences",
          // These only wake the existing serialized worker; PortableData deliberately excludes them
          // from the generic /sync request.
          "health_logical_records",
          "health_record_versions",
          "health_head_history",
          "health_metric_identities",
          "health_sync_outbox",
          "gyms",
          "gym_exercises",
          "gym_equipment",
          "routines",
          "routine_exercises",
          "routine_gyms",
          "workouts",
          "workout_efforts",
          "workout_exercises",
          "workout_sets",
          "workout_gyms",
          "body_measurements",
          "scheduled_workouts",
          "calendar_plans",
          "calendar_rules",
          "calendar_exceptions",
      )

  fun create(db: SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS backend_state (id INTEGER NOT NULL,owner TEXT,generation INTEGER NOT NULL,PRIMARY KEY(id))"
    )
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS backend_baseline (`key` TEXT NOT NULL,recordJson TEXT NOT NULL,PRIMARY KEY(`key`))"
    )
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS backend_outbox (id INTEGER NOT NULL,owner TEXT NOT NULL,requestJson TEXT NOT NULL,PRIMARY KEY(id))"
    )
    install(db)
  }

  fun install(db: SupportSQLiteDatabase) {
    db.execSQL("INSERT OR IGNORE INTO backend_state(id,owner,generation) VALUES (1,NULL,0)")
    trackedTables
        .filter { table ->
          db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table))
              .use { it.moveToFirst() }
        }
        .forEach { table ->
          listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS backend_${table}_${operation.lowercase()} AFTER $operation ON $table BEGIN UPDATE backend_state SET generation=generation+1 WHERE id=1; END"
            )
          }
        }
  }
}
