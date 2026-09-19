package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.valerochka1337.valerochkagym.data.db.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CoachDao {
  @Query(
      """
    SELECT s.*, w.id AS historyWorkoutId, w.finishedAt AS historyWorkoutFinishedAt FROM workout_sets s
    JOIN workout_exercises we ON we.id=s.workoutExerciseId
    JOIN workouts w ON w.id=we.workoutId
    WHERE we.exerciseId=:exerciseId AND s.isCompleted=1 AND w.id IN (
      SELECT w2.id FROM workouts w2
      WHERE w2.finishedAt IS NOT NULL AND EXISTS (
        SELECT 1 FROM workout_exercises we2 JOIN workout_sets s2 ON s2.workoutExerciseId=we2.id
        WHERE we2.workoutId=w2.id AND we2.exerciseId=:exerciseId AND s2.isCompleted=1
      ) ORDER BY w2.finishedAt DESC, w2.id DESC LIMIT :workoutLimit
    ) ORDER BY w.finishedAt DESC, w.id DESC, we.position, we.id, s.setIndex, s.id
  """
  )
  suspend fun exerciseHistory(exerciseId: Long, workoutLimit: Int): List<CoachHistorySet>

  @Query("SELECT * FROM coach_messages WHERE workoutId=:workoutId ORDER BY createdAt, id")
  fun observeMessages(workoutId: String): Flow<List<CoachMessageEntity>>

  @Query("SELECT * FROM coach_messages WHERE workoutId=:workoutId ORDER BY createdAt, id")
  suspend fun messages(workoutId: String): List<CoachMessageEntity>

  @Query(
      "SELECT COUNT(*) FROM coach_messages WHERE workoutId=:workoutId AND role='assistant' AND readAt IS NULL"
  )
  fun observeUnreadAssistantCount(workoutId: String): Flow<Int>

  @Query(
      "UPDATE coach_messages SET readAt=:readAt WHERE workoutId=:workoutId AND role='assistant' AND readAt IS NULL"
  )
  suspend fun markAssistantMessagesRead(
      workoutId: String,
      readAt: Long = System.currentTimeMillis(),
  ): Int

  @Query(
      "UPDATE coach_messages SET readAt=:readAt WHERE workoutId=:workoutId AND id IN (:ids) AND role='assistant' AND readAt IS NULL"
  )
  suspend fun markAssistantMessagesReadByIds(
      workoutId: String,
      ids: List<String>,
      readAt: Long = System.currentTimeMillis(),
  ): Int

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun saveMessage(message: CoachMessageEntity)

  @Query(
      "DELETE FROM coach_messages WHERE id=:id AND accountId=:accountId AND workoutId=:workoutId AND role='assistant'"
  )
  suspend fun deleteAssistantMessage(id: String, accountId: String, workoutId: String)

  @Query("UPDATE coach_messages SET status=:status WHERE id=:id")
  suspend fun setMessageStatus(id: String, status: String)

  @Query(
      "SELECT * FROM coach_proposals WHERE workoutId=:workoutId AND state='PENDING' ORDER BY expiresAt DESC LIMIT 1"
  )
  suspend fun pendingProposal(workoutId: String): CoachProposalEntity?

  @Query(
      "SELECT * FROM coach_proposals WHERE workoutId=:workoutId AND state='PENDING' ORDER BY expiresAt DESC LIMIT 1"
  )
  fun observePendingProposal(workoutId: String): Flow<CoachProposalEntity?>

  @Query("SELECT * FROM coach_proposals WHERE id=:id AND state='PENDING' LIMIT 1")
  suspend fun pendingProposalForId(id: String): CoachProposalEntity?

  @Query("SELECT * FROM coach_proposals WHERE id=:id LIMIT 1")
  suspend fun proposalForId(id: String): CoachProposalEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun saveProposal(proposal: CoachProposalEntity)

  @Query("UPDATE coach_proposals SET state=:state WHERE id=:id")
  suspend fun setProposalState(id: String, state: String)

  @Query(
      "UPDATE coach_proposals SET state='SUPERSEDED' WHERE accountId=:accountId AND workoutId=:workoutId AND state='PENDING'"
  )
  suspend fun supersedePendingProposals(accountId: String, workoutId: String)

  @Query("SELECT * FROM coach_command_receipts WHERE operationId=:operationId")
  suspend fun receipt(operationId: String): CoachCommandReceiptEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun saveReceipt(receipt: CoachCommandReceiptEntity)

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun saveJournal(entry: CoachJournalEntity): Long

  @Query(
      "SELECT * FROM coach_journal WHERE accountId=:accountId AND uploaded=0 ORDER BY createdAt,id LIMIT :limit"
  )
  suspend fun pendingJournal(accountId: String, limit: Int): List<CoachJournalEntity>

  @Query("UPDATE coach_journal SET uploaded=1 WHERE id IN (:ids)")
  suspend fun markJournalUploaded(ids: List<String>)

  @Query("SELECT * FROM coach_session_context WHERE workoutId=:workoutId")
  suspend fun context(workoutId: String): CoachSessionContextEntity?

  @Query("SELECT * FROM coach_session_context WHERE workoutId=:workoutId")
  fun observeContext(workoutId: String): Flow<CoachSessionContextEntity?>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertContext(context: CoachSessionContextEntity)

  @Query(
      "INSERT INTO coach_dirty_sessions(workoutId,generation) VALUES(:workoutId,1) ON CONFLICT(workoutId) DO UPDATE SET generation=generation+1"
  )
  suspend fun markContextDirty(workoutId: String)

  @androidx.room.Transaction
  suspend fun saveContext(context: CoachSessionContextEntity) {
    insertContext(context)
    markContextDirty(context.workoutId)
  }

  @Query("DELETE FROM coach_session_context WHERE accountId=:accountId")
  suspend fun clearContext(accountId: String)

  @Query("DELETE FROM coach_messages WHERE accountId=:accountId")
  suspend fun clearMessages(accountId: String)

  @Query("DELETE FROM coach_proposals WHERE accountId=:accountId")
  suspend fun clearProposals(accountId: String)

  @Query("DELETE FROM coach_command_receipts WHERE accountId=:accountId")
  suspend fun clearReceipts(accountId: String)

  @Query("DELETE FROM coach_journal WHERE accountId=:accountId")
  suspend fun clearJournal(accountId: String)

  @Query(
      "DELETE FROM coach_dirty_sessions WHERE workoutId IN (SELECT workoutId FROM coach_session_outbox WHERE accountId=:accountId UNION SELECT workoutId FROM coach_runs WHERE accountId=:accountId UNION SELECT workoutId FROM coach_session_context WHERE accountId=:accountId)"
  )
  suspend fun clearDirtySessions(accountId: String)

  @Query("DELETE FROM coach_runs WHERE accountId=:accountId")
  suspend fun clearRemoteRuns(accountId: String)

  @Query("DELETE FROM coach_session_outbox WHERE accountId=:accountId")
  suspend fun clearRemoteSessions(accountId: String)

  @Query("DELETE FROM coach_receipt_outbox WHERE accountId=:accountId")
  suspend fun clearRemoteReceipts(accountId: String)

  @androidx.room.Transaction
  suspend fun clearAccount(accountId: String) {
    clearDirtySessions(accountId)
    clearRemoteRuns(accountId)
    clearRemoteSessions(accountId)
    clearRemoteReceipts(accountId)
    clearMessages(accountId)
    clearProposals(accountId)
    clearReceipts(accountId)
    clearJournal(accountId)
    clearContext(accountId)
  }
}

data class CoachHistorySet(
    @androidx.room.Embedded val set: WorkoutSetEntity,
    val historyWorkoutId: String,
    val historyWorkoutFinishedAt: Long,
)
