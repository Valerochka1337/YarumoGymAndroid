package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.valerochka1337.valerochkagym.data.db.entity.HealthHeadHistoryEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthLogicalRecordEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthRecordVersionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HealthDao {
  @Query("SELECT EXISTS(SELECT 1 FROM workouts WHERE finishedAt IS NULL)")
  suspend fun hasActiveWorkout(): Boolean

  @Query("SELECT * FROM health_logical_records WHERE logicalId=:logicalId AND scope=:scope")
  suspend fun record(logicalId: String, scope: String): HealthLogicalRecordEntity?

  @Query(
      "SELECT * FROM health_record_versions WHERE logicalId=:logicalId ORDER BY enteredAtEpochMs, versionId"
  )
  fun observeVersions(logicalId: String): Flow<List<HealthRecordVersionEntity>>

  @Query(
      "SELECT v.* FROM health_record_versions v INNER JOIN health_logical_records r ON r.logicalId=v.logicalId WHERE r.scope=:scope AND v.serverSequence IS NULL ORDER BY v.enteredAtEpochMs, v.versionId LIMIT :limit"
  )
  suspend fun unacknowledgedVersions(scope: String, limit: Int): List<HealthRecordVersionEntity>

  @Query(
      "SELECT v.* FROM health_record_versions v INNER JOIN health_logical_records r ON r.logicalId=v.logicalId WHERE r.scope=:scope AND v.serverSequence IS NULL ORDER BY v.enteredAtEpochMs, v.versionId"
  )
  suspend fun allUnacknowledgedVersions(scope: String): List<HealthRecordVersionEntity>

  @Query("SELECT * FROM health_record_versions WHERE versionId=:versionId")
  suspend fun version(versionId: String): HealthRecordVersionEntity?

  @Query(
      "SELECT * FROM health_head_history WHERE logicalId=:logicalId ORDER BY healthRevision, headRevision"
  )
  fun observeHeadHistory(logicalId: String): Flow<List<HealthHeadHistoryEntity>>

  @Query(
      "SELECT * FROM health_head_history WHERE logicalId=:logicalId AND headRevision=:headRevision"
  )
  suspend fun headHistory(logicalId: String, headRevision: Long): HealthHeadHistoryEntity?

  @Upsert suspend fun upsertRecord(record: HealthLogicalRecordEntity)

  /** Immutable rows are inserted once; sync performs an equal-row comparison before retrying. */
  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insertVersion(version: HealthRecordVersionEntity)

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insertHeadHistory(history: HealthHeadHistoryEntity)

  /** The only permitted mutable part of an immutable version: a matching first server receipt. */
  @Query(
      "UPDATE health_record_versions SET serverSequence=:serverSequence, healthRevision=:healthRevision WHERE versionId=:versionId AND serverSequence IS NULL AND healthRevision IS NULL"
  )
  suspend fun upsertVersionAssignment(
      versionId: String,
      serverSequence: Long,
      healthRevision: Long,
  ): Int

  @Query("DELETE FROM health_logical_records WHERE scope=:scope")
  suspend fun deleteScope(scope: String)
}
