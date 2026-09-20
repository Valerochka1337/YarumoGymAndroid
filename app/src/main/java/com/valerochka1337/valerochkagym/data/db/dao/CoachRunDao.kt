package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.*
import com.valerochka1337.valerochkagym.data.db.entity.*

@Dao
interface CoachRunDao {
  @Query(
      "SELECT * FROM coach_behavior WHERE accountId=:owner AND workoutId=:workout AND kind='concern' AND status='OPEN'"
  )
  suspend fun concerns(owner: String, workout: String): List<CoachBehaviorEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun saveBehavior(value: CoachBehaviorEntity)

  @Query("SELECT * FROM coach_behavior WHERE id=:id")
  suspend fun behavior(id: String): CoachBehaviorEntity?

  @Query("SELECT * FROM coach_behavior WHERE accountId=:owner AND requestJson IS NOT NULL")
  suspend fun pendingAnswers(owner: String): List<CoachBehaviorEntity>

  @Query("SELECT * FROM coach_phase WHERE accountId=:owner AND workoutId=:workout")
  suspend fun phase(owner: String, workout: String): CoachPhaseEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun savePhase(value: CoachPhaseEntity)

  @Query("SELECT sequence FROM coach_event_cursors WHERE accountId=:owner AND workoutId=:workout")
  suspend fun eventCursor(owner: String, workout: String): Long?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun saveEventCursor(cursor: CoachEventCursorEntity)

  @Query("DELETE FROM coach_event_cursors WHERE accountId=:accountId")
  suspend fun clearEventCursors(accountId: String)

  @Query(
      "INSERT INTO coach_dirty_sessions(workoutId,generation) VALUES(:workoutId,1) ON CONFLICT(workoutId) DO UPDATE SET generation=generation+1"
  )
  suspend fun markDirty(workoutId: String)

  @Query("SELECT * FROM coach_dirty_sessions") suspend fun dirtySessions(): List<CoachDirtyEntity>

  @Query("DELETE FROM coach_dirty_sessions WHERE workoutId=:workoutId AND generation=:generation")
  suspend fun clean(workoutId: String, generation: Long)

  @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(run: CoachRunEntity): Long

  @Update suspend fun update(run: CoachRunEntity)

  @Query(
      "SELECT * FROM coach_runs WHERE accountId=:accountId AND imported=0 ORDER BY createdAt, requestId"
  )
  suspend fun pending(accountId: String): List<CoachRunEntity>

  @Query("SELECT * FROM coach_runs WHERE requestId=:id")
  suspend fun run(id: String): CoachRunEntity?

  @Query(
      "SELECT * FROM coach_runs WHERE accountId=:accountId AND workoutId=:workoutId ORDER BY createdAt, requestId"
  )
  suspend fun runsForWorkout(accountId: String, workoutId: String): List<CoachRunEntity>

  @Query("SELECT * FROM coach_runs WHERE proposalId=:id")
  suspend fun proposal(id: String): CoachRunEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun saveSession(session: CoachSessionOutboxEntity)

  @Query("SELECT * FROM coach_session_outbox WHERE workoutId=:id")
  suspend fun session(id: String): CoachSessionOutboxEntity?

  @Query("SELECT * FROM coach_session_outbox WHERE accountId=:accountId")
  suspend fun sessions(accountId: String): List<CoachSessionOutboxEntity>

  @Query("SELECT * FROM coach_session_outbox WHERE accountId=:accountId AND delivered=0")
  suspend fun pendingSessions(accountId: String): List<CoachSessionOutboxEntity>

  @Query(
      "SELECT * FROM coach_session_outbox WHERE accountId=:accountId AND delivered=1 AND discoveryComplete=0"
  )
  suspend fun sessionsNeedingDiscovery(accountId: String): List<CoachSessionOutboxEntity>

  @Query(
      "UPDATE coach_session_outbox SET discoveryComplete=1 WHERE workoutId=:id AND sequence=:sequence AND delivered=1"
  )
  suspend fun discoveryComplete(id: String, sequence: Long)

  @Query("UPDATE coach_session_outbox SET delivered=1 WHERE workoutId=:id AND sequence=:sequence")
  suspend fun delivered(id: String, sequence: Long)

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun receipt(receipt: CoachReceiptOutboxEntity)

  @Query("SELECT * FROM coach_receipt_outbox WHERE accountId=:accountId")
  suspend fun receipts(accountId: String): List<CoachReceiptOutboxEntity>

  @Query("DELETE FROM coach_receipt_outbox WHERE receiptId=:id")
  suspend fun deliveredReceipt(id: String)

  @Query("DELETE FROM coach_runs WHERE accountId=:accountId")
  suspend fun clearRuns(accountId: String)

  @Query("DELETE FROM coach_session_outbox WHERE accountId=:accountId")
  suspend fun clearSessions(accountId: String)

  @Query("DELETE FROM coach_receipt_outbox WHERE accountId=:accountId")
  suspend fun clearReceipts(accountId: String)
}
