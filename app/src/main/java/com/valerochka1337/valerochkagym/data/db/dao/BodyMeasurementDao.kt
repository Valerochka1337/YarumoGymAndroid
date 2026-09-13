package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import kotlinx.coroutines.flow.Flow

/** Локальное хранилище замеров тела и их статуса выгрузки в Sheets. */
@Dao
interface BodyMeasurementDao {

  @Insert suspend fun insert(measurement: BodyMeasurementEntity)

  @Update suspend fun update(measurement: BodyMeasurementEntity)

  @Query("SELECT * FROM body_measurements ORDER BY measuredAt DESC")
  fun observeAll(): Flow<List<BodyMeasurementEntity>>

  @Query("SELECT * FROM body_measurements WHERE id = :id")
  suspend fun getById(id: String): BodyMeasurementEntity?

  @Query(
      "UPDATE body_measurements SET uploadStatus = :status, uploadError = :error WHERE id = :measurementId",
  )
  suspend fun setUploadStatus(measurementId: String, status: UploadStatus, error: String?)

  /** Замеры, ещё не попавшие в Sheets: нужны действию «Выгрузить всё». */
  @Query(
      """
        SELECT id FROM body_measurements
        WHERE uploadStatus IN ('PENDING', 'FAILED')
        ORDER BY measuredAt DESC
        """,
  )
  suspend fun getNotUploaded(): List<String>

  @Query("DELETE FROM body_measurements WHERE id = :id") suspend fun delete(id: String)
}
