package com.valerochka1337.valerochkagym.data.ai

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Durable owner-scoped preparation journal. */
@Entity(tableName = "workout_preparations", primaryKeys = ["owner", "requestId"])
data class PreparationEntity(
    val owner: String,
    val requestId: String,
    val intentJson: String,
    val replacesJson: String,
    val requestJson: String? = null,
    val revision: Long? = null,
    val catalogRevision: Long? = null,
    val generation: Long? = null,
    val state: String = "WAITING",
    val errorCode: String? = null,
    val proposalJson: String? = null,
    val createdAtMillis: Long = 0,
)

@Dao
interface PreparationDao {
  @Query(
      "SELECT * FROM workout_preparations WHERE owner=:owner ORDER BY createdAtMillis DESC, requestId DESC"
  )
  fun observeAll(owner: String): Flow<List<PreparationEntity>>

  @Query(
      "SELECT * FROM workout_preparations WHERE owner=:owner ORDER BY createdAtMillis DESC, requestId DESC LIMIT 1"
  )
  fun observeLatest(owner: String): Flow<PreparationEntity?>

  /** Compatibility for pre-journal form defaults only. */
  @Query(
      "SELECT * FROM workout_preparations WHERE owner=:owner ORDER BY createdAtMillis DESC, requestId DESC LIMIT 1"
  )
  suspend fun get(owner: String): PreparationEntity?

  @Query("SELECT * FROM workout_preparations WHERE owner=:owner AND requestId=:requestId")
  suspend fun get(owner: String, requestId: String): PreparationEntity?

  @Query(
      "SELECT * FROM workout_preparations WHERE owner=:owner AND instr(replacesJson, '\"' || :requestId || '\"') > 0 LIMIT 1"
  )
  suspend fun replacementOf(owner: String, requestId: String): PreparationEntity?

  @Query("SELECT generation FROM backend_state WHERE owner=:owner")
  suspend fun generation(owner: String): Long?

  @Upsert suspend fun save(row: PreparationEntity)

  @Query("SELECT generation FROM backend_state WHERE owner=:owner")
  fun observeGeneration(owner: String): Flow<Long?>
}
