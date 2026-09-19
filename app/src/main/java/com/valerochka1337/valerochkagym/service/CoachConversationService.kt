package com.valerochka1337.valerochkagym.service

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.entity.CoachJournalEntity
import com.valerochka1337.valerochkagym.data.db.entity.CoachMessageEntity
import com.valerochka1337.valerochkagym.data.db.entity.CoachSessionContextEntity
import com.valerochka1337.valerochkagym.diagnostics.CoachDiagnostics
import com.valerochka1337.valerochkagym.domain.CoachWorkoutReader
import com.valerochka1337.valerochkagym.domain.CommandAuthority
import com.valerochka1337.valerochkagym.domain.WorkoutChangeSet
import com.valerochka1337.valerochkagym.domain.WorkoutEditor
import com.valerochka1337.valerochkagym.domain.WorkoutWriteQueue
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class CoachDraft(
    val id: String,
    val requestId: String,
    val workoutId: String,
    val accountId: String,
    val sessionEpoch: Long,
    val text: String,
    val streaming: Boolean = true,
    val quickReplies: List<String>? = null,
)

/** Delivers server runs and applies explicit user decisions to the local workout. */
@Singleton
class CoachConversationService
@Inject
constructor(
    private val remote: DurableCoachCoordinator,
    private val reader: CoachWorkoutReader,
    private val editor: WorkoutEditor,
    private val database: GymDatabase,
    private val sessions: BackendSessionStore,
    private val writes: WorkoutWriteQueue = WorkoutWriteQueue(),
) {
  private val json = Json { explicitNulls = false }
  val runningWorkouts: StateFlow<Set<String>>
    get() = remote.running

  val responseDrafts: StateFlow<Map<String, CoachDraft>>
    get() = remote.drafts

  val runningStages: StateFlow<Map<String, String>>
    get() = remote.stages

  val alerts
    get() = remote.alerts.asSharedFlow()

  fun attach(scope: CoroutineScope) = remote.attach(scope)

  fun detach() = remote.detach()

  fun stopWorkout(workoutId: String) = remote.stopWorkout(workoutId)

  suspend fun send(workoutId: String, text: String): Boolean =
      CoachDiagnostics.trace("conversation.send") {
        remote.send(workoutId, text).also {
          CoachDiagnostics.event("conversation.send.result", "accepted" to it)
        }
      }

  suspend fun retry(workoutId: String, errorMessageId: String): Boolean =
      CoachDiagnostics.trace("conversation.retry") {
        remote.retry(workoutId, errorMessageId).also {
          CoachDiagnostics.event("conversation.retry.result", "accepted" to it)
        }
      }

  suspend fun confirm(workoutId: String, proposalId: String): Boolean =
      CoachDiagnostics.trace("conversation.confirm") {
        confirmLogged(workoutId, proposalId).also {
          CoachDiagnostics.event("conversation.confirm.result", "accepted" to it)
        }
      }

  private suspend fun confirmLogged(workoutId: String, proposalId: String): Boolean {
    val session = sessions.snapshot() ?: return false
    val accountId = session.tokens.userId
    val proposal = database.coachDao().pendingProposalForId(proposalId)
    if (proposal?.accountId != accountId || proposal.workoutId != workoutId) return false
    val actionKind =
        com.valerochka1337.valerochkagym.domain.WorkoutApprovalPreview.decode(proposal.previewJson)
            ?.actions
            ?.firstOrNull()
            ?.kind ?: "change"
    val receipt =
        editor.confirmProposal(accountId, proposalId, UUID.randomUUID().toString(), session.epoch)
    val text =
        when (receipt.result) {
          com.valerochka1337.valerochkagym.domain.CommandResult.APPLIED ->
              "APPLIED|$actionKind|${proposal.afterSummary}"
          com.valerochka1337.valerochkagym.domain.CommandResult.REPLAYED ->
              "Предложение уже было применено."
          else -> "Предложение устарело: состояние тренировки изменилось."
        }
    appendSystemMessage(
        UUID.randomUUID().toString(),
        accountId,
        workoutId,
        text,
        expectedSessionEpoch = session.epoch,
    )
    remote.changed(workoutId)
    return receipt.result == com.valerochka1337.valerochkagym.domain.CommandResult.APPLIED
  }

  suspend fun cancel(
      workoutId: String,
      proposalId: String,
      reason: com.valerochka1337.valerochkagym.domain.CoachRejectionReason? = null,
  ): Boolean =
      CoachDiagnostics.trace("conversation.cancel") {
        cancelLogged(workoutId, proposalId, reason).also {
          CoachDiagnostics.event("conversation.cancel.result", "accepted" to it)
        }
      }

  private suspend fun cancelLogged(
      workoutId: String,
      proposalId: String,
      reason: com.valerochka1337.valerochkagym.domain.CoachRejectionReason?,
  ): Boolean {
    val session = sessions.snapshot() ?: return false
    val accountId = session.tokens.userId
    val proposal = database.coachDao().pendingProposalForId(proposalId)
    if (proposal?.accountId != accountId || proposal.workoutId != workoutId) return false
    val actionKind =
        com.valerochka1337.valerochkagym.domain.WorkoutApprovalPreview.decode(proposal.previewJson)
            ?.actions
            ?.firstOrNull()
            ?.kind ?: "change"
    val cancelled = editor.cancelProposal(accountId, proposalId, session.epoch, reason)
    if (cancelled)
        appendSystemMessage(
            UUID.randomUUID().toString(),
            accountId,
            workoutId,
            "REJECTED|$actionKind|${proposal.afterSummary}" +
                (reason?.let { " — ${it.label}" } ?: ""),
            expectedSessionEpoch = session.epoch,
        )
    remote.changed(workoutId)
    return cancelled
  }

  suspend fun undo(workoutId: String): Boolean =
      CoachDiagnostics.trace("conversation.undo") {
        undoLogged(workoutId).also {
          remote.changed(workoutId)
          CoachDiagnostics.event("conversation.undo.result", "accepted" to it)
        }
      }

  private suspend fun undoLogged(workoutId: String): Boolean {
    val session = sessions.snapshot() ?: return false
    val accountId = session.tokens.userId
    val snapshot = reader.snapshot(accountId, workoutId, session.epoch) ?: return false
    val anchors =
        CommandAuthority.Anchors(snapshot.currentSetId, snapshot.previousSetId, snapshot.nextSetId)
    val packet = WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.UndoLast))
    return editor
        .submit(
            accountId,
            workoutId,
            UUID.randomUUID().toString(),
            snapshot.revision,
            packet,
            CommandAuthority.local(packet, anchors),
            anchors,
            session.epoch,
        )
        .result == com.valerochka1337.valerochkagym.domain.CommandResult.APPLIED
  }

  suspend fun disableInitiative(workoutId: String): Boolean =
      sessions.snapshot()?.let {
        setInitiativeEnabled(it.tokens.userId, workoutId, false, it.epoch)
      } ?: false

  suspend fun considerInitiative(workoutId: String): Boolean = remote.changed(workoutId)

  private suspend fun setInitiativeEnabled(
      accountId: String,
      workoutId: String,
      enabled: Boolean,
      expectedSessionEpoch: Long? = null,
  ): Boolean {
    val saved =
        writes.write {
          database.withTransaction {
            if (!belongsToLiveAccount(accountId, expectedSessionEpoch)) return@withTransaction false
            val active = database.workoutDao().getWorkoutFull(workoutId)?.workout
            if (active?.finishedAt != null || active == null) return@withTransaction false
            val existing =
                database.coachDao().context(workoutId)
                    ?: CoachSessionContextEntity(workoutId, accountId)
            if (existing.accountId != accountId) return@withTransaction false
            database
                .coachDao()
                .saveContext(
                    existing.copy(initiativeEnabled = enabled, initiativePendingInteraction = false)
                )
            true
          }
        }
    if (saved) remote.changed(workoutId)
    return saved
  }

  private suspend fun appendSystemMessage(
      id: String,
      accountId: String,
      workoutId: String,
      text: String,
      createdAt: Long = System.currentTimeMillis(),
      expectedSessionEpoch: Long? = null,
  ): Boolean {
    return writes.write {
      database.withTransaction {
        if (!belongsToLiveAccount(accountId, expectedSessionEpoch)) return@withTransaction false
        val workout = database.workoutDao().getWorkoutFull(workoutId)?.workout
        if (workout?.finishedAt != null || workout == null) return@withTransaction false
        val context = database.coachDao().context(workoutId)
        if (context != null && context.accountId != accountId) return@withTransaction false
        val currentContext = context ?: CoachSessionContextEntity(workoutId, accountId)
        database.coachDao().saveContext(currentContext)
        database
            .coachDao()
            .saveMessage(
                CoachMessageEntity(
                    id,
                    accountId,
                    workoutId,
                    "system",
                    text,
                    createdAt,
                )
            )
        database
            .coachDao()
            .saveJournal(
                CoachJournalEntity(
                    id = id,
                    accountId = accountId,
                    workoutId = workoutId,
                    createdAt = createdAt,
                    payload =
                        json.encodeToString(
                            buildJsonObject {
                              put("kind", "message")
                              put("role", "system")
                              put("text", text)
                            }
                        ),
                )
            )
        check(belongsToLiveAccount(accountId, expectedSessionEpoch)) { "Request changed" }
        true
      }
    }
  }

  private fun belongsToLiveAccount(accountId: String, expectedSessionEpoch: Long? = null): Boolean {
    val session = sessions.snapshot() ?: return false
    if (
        session.tokens.userId != accountId ||
            (expectedSessionEpoch != null && session.epoch != expectedSessionEpoch)
    )
        return false
    return database.openHelper.writableDatabase
        .query("SELECT owner FROM backend_state WHERE id=1")
        .use { row -> row.moveToFirst() && !row.isNull(0) && row.getString(0) == accountId }
  }
}
