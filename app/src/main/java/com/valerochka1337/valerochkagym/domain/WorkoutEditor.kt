@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.valerochka1337.valerochkagym.domain

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.ai.CoachChangeIntent
import com.valerochka1337.valerochkagym.data.ai.CoachRestAction
import com.valerochka1337.valerochkagym.data.ai.CoachSetValues
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.dao.CoachDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.CoachCommandReceiptEntity
import com.valerochka1337.valerochkagym.data.db.entity.CoachJournalEntity
import com.valerochka1337.valerochkagym.data.db.entity.CoachProposalEntity
import com.valerochka1337.valerochkagym.data.db.entity.CoachSessionContextEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.diagnostics.CoachDiagnostics
import com.valerochka1337.valerochkagym.service.RestTimerEngine
import com.valerochka1337.valerochkagym.service.RestTimerState
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val SET_VALUE_FIELDS =
    setOf(
        "weight_kg",
        "reps",
        "duration_sec",
        "speed_kmh",
        "incline_pct",
        "actual_rir",
        "set_type",
    )

sealed interface ModelProposalSaveResult {
  data class Saved(val proposal: WorkoutProposal) : ModelProposalSaveResult

  data object Stale : ModelProposalSaveResult

  data object InvalidOrder : ModelProposalSaveResult

  data object Invalid : ModelProposalSaveResult

  data object Unavailable : ModelProposalSaveResult
}

/** Serialized, transactional packet writer. Packet helpers never call [submit]. */
@Singleton
class WorkoutEditor
@Inject
constructor(
    private val database: GymDatabase,
    private val workoutDao: WorkoutDao,
    private val coachDao: CoachDao,
    private val restTimer: RestTimerEngine,
    private val sessions: BackendSessionStore,
    private val writes: WorkoutWriteQueue,
    private val restDurationResolver: RestDurationResolver? = null,
    private val settingsRepository: SettingsRepository? = null,
) {
  private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
  }

  suspend fun completeSetFromUser(setId: Long) {
    val expectedRestStartId = restTimer.currentStartId()
    val resolver = restDurationResolver ?: return
    val settings = settingsRepository?.settings?.first() ?: return
    writes.write {
      val restSeconds =
          database.withTransaction {
            val workoutId = workoutDao.getActiveWorkoutId() ?: return@withTransaction null
            val full = workoutDao.getWorkoutFull(workoutId) ?: return@withTransaction null
            val section =
                full.exercises.firstOrNull { it.sets.any { set -> set.id == setId } }
                    ?: return@withTransaction null
            if (section.sets.single { it.id == setId }.isCompleted) return@withTransaction null
            val completesWorkout =
                full.exercises
                    .flatMap { it.sets }
                    .none { set -> !set.isCompleted && set.id != setId }
            workoutDao.setSetCompleted(setId, true, System.currentTimeMillis())
            database.openHelper.writableDatabase.execSQL(
                "UPDATE workouts SET coachRevision = coachRevision + 1 WHERE id=?",
                arrayOf<Any?>(workoutId),
            )
            if (completesWorkout || !settings.restAutostart) null
            else if (settings.heartRateRestEnabled) Int.MIN_VALUE
            else resolver(full, section.exercise.id)
          }
      if (restTimer.currentStartId() != expectedRestStartId) return@write
      when (restSeconds) {
        null -> Unit
        Int.MIN_VALUE ->
            restTimer.startUntilHeartRateAtMost(
                settings.heartRateRestThresholdBpm,
                settings.heartRateRestHoldSeconds,
            )
        else -> restTimer.start(restSeconds)
      }
    }
  }

  suspend fun submit(
      accountId: String,
      workoutId: String,
      operationId: String,
      expectedRevision: Long,
      packet: WorkoutChangeSet.Packet,
      authority: CommandAuthority? = null,
      anchors: CommandAuthority.Anchors = CommandAuthority.Anchors(null, null, null),
      expectedSessionEpoch: Long? = null,
      isCurrent: () -> Boolean = { true },
  ): CommandReceipt =
      writes.write {
        val applied =
            runCatching {
                  database.withTransaction {
                    if (!belongsToLiveAccount(accountId, expectedSessionEpoch))
                        return@withTransaction Applied(stale(operationId, workoutId), null)
                    coachDao.receipt(operationId)?.let { saved ->
                      return@withTransaction Applied(
                          if (saved.accountId == accountId && saved.workoutId == workoutId)
                              CommandReceipt(
                                  operationId,
                                  workoutId,
                                  saved.revision,
                                  CommandResult.REPLAYED,
                              )
                          else stale(operationId, workoutId),
                          null,
                      )
                    }
                    val workout =
                        workoutDao.getWorkoutFull(workoutId)?.workout
                            ?: return@withTransaction Applied(stale(operationId, workoutId), null)
                    if (workout.finishedAt != null || workout.coachRevision != expectedRevision) {
                      return@withTransaction Applied(
                          saveReceipt(
                              accountId,
                              workoutId,
                              operationId,
                              workout.coachRevision,
                              CommandResult.STALE,
                          ),
                          null,
                      )
                    }
                    if (authority == null || !authority.authorizes(packet, anchors)) {
                      return@withTransaction Applied(
                          saveReceipt(
                              accountId,
                              workoutId,
                              operationId,
                              workout.coachRevision,
                              CommandResult.CONFIRMATION,
                          ),
                          null,
                      )
                    }
                    if (!isCurrent()) throw StaleCommand()
                    applyAccepted(accountId, workoutId, operationId, workout.coachRevision, packet)
                        .also {
                          if (
                              !belongsToLiveAccount(accountId, expectedSessionEpoch) || !isCurrent()
                          )
                              throw StaleCommand()
                        }
                  }
                }
                .getOrElse { error ->
                  if (error is CancellationException) throw error
                  CoachDiagnostics.failure("editor.command.failed", error)
                  failureReceipt(accountId, workoutId, operationId, error)
                }
        applied.applyRestAfterCommit()
        applied.receipt.also {
          CoachDiagnostics.event(
              "editor.command.result",
              "result" to it.result,
              "revision" to it.revision,
          )
        }
      }

  /** Saved proposals are app-owned data; confirmation never restores model-supplied authority. */
  suspend fun saveModelProposalResult(
      accountId: String,
      workoutId: String,
      baseRevision: Long,
      intents: List<CoachChangeIntent>,
      expiresAt: Long,
      expectedSessionEpoch: Long? = null,
      isCurrent: () -> Boolean = { true },
  ): ModelProposalSaveResult =
      CoachDiagnostics.trace(
          "editor.model_proposal",
          "revision" to baseRevision,
          "intents" to intents.joinToString(",") { it.javaClass.simpleName },
      ) {
        saveModelProposalResultLogged(
                accountId,
                workoutId,
                baseRevision,
                intents,
                expiresAt,
                expectedSessionEpoch,
                isCurrent,
            )
            .also {
              CoachDiagnostics.event(
                  "editor.model_proposal.result",
                  "result" to it.javaClass.simpleName,
              )
            }
      }

  private suspend fun saveModelProposalResultLogged(
      accountId: String,
      workoutId: String,
      baseRevision: Long,
      intents: List<CoachChangeIntent>,
      expiresAt: Long,
      expectedSessionEpoch: Long? = null,
      isCurrent: () -> Boolean = { true },
  ): ModelProposalSaveResult {
    // Preparation reads only local facts. Saving below recalculates under the checked revision.
    if (!belongsToLiveAccount(accountId, expectedSessionEpoch) || !isCurrent()) {
      return ModelProposalSaveResult.Unavailable
    }
    val full = workoutDao.getWorkoutFull(workoutId) ?: return ModelProposalSaveResult.Unavailable
    if (!belongsToLiveAccount(accountId, expectedSessionEpoch) || !isCurrent()) {
      return ModelProposalSaveResult.Unavailable
    }
    if (full.workout.finishedAt != null) return ModelProposalSaveResult.Unavailable
    if (full.workout.coachRevision != baseRevision) return ModelProposalSaveResult.Stale
    val autoregulate = intents.filterIsInstance<CoachChangeIntent.Autoregulate>().singleOrNull()
    if (autoregulate != null) {
      if (intents.size != 1) return ModelProposalSaveResult.Invalid
      val current =
          CoachWorkoutReader(database, restTimer, sessions)
              .snapshot(accountId, workoutId, expectedSessionEpoch)
              ?: return ModelProposalSaveResult.Unavailable
      val recommendation =
          com.valerochka1337.valerochkagym.domain.autoregulation.AutoregulationEngine.calculate(
              current,
              autoregulate.options ?: current.autoregulationOptions,
          )
      val calculatedPacket = recommendation.packet() ?: return ModelProposalSaveResult.Invalid
      return saveProposalResult(
          accountId,
          workoutId,
          calculatedPacket,
          baseRevision,
          expiresAt,
          expectedSessionEpoch,
          isCurrent,
      )
    }
    val snapshot =
        WorkoutSnapshot(
            accountId,
            workoutId,
            baseRevision,
            full.exercises.map { row ->
              SnapshotExercise(
                  row.workoutExercise.sectionId,
                  row.exercise.id,
                  position = row.workoutExercise.position,
                  type = row.exercise.type,
              )
            },
        )
    var packet = mapIntents(snapshot, intents)
    if (!belongsToLiveAccount(accountId, expectedSessionEpoch) || !isCurrent()) {
      return ModelProposalSaveResult.Unavailable
    }
    if (packet == null) return ModelProposalSaveResult.Invalid
    // Model recommendations are proposals, never direct writes. Keep the normal revision,
    // account, value, completed-result and confirmation checks; do not require a fixed local step.
    return try {
      saveProposalResult(
          accountId,
          workoutId,
          packet,
          baseRevision,
          expiresAt,
          expectedSessionEpoch,
          isCurrent,
      )
    } catch (_: InvalidExerciseOrder) {
      ModelProposalSaveResult.InvalidOrder
    } catch (error: IllegalArgumentException) {
      CoachDiagnostics.failure("editor.model_proposal.invalid", error, "phase" to "validate")
      ModelProposalSaveResult.Invalid
    } catch (error: NoSuchElementException) {
      CoachDiagnostics.failure(
          "editor.model_proposal.invalid",
          error,
          "phase" to "resolve_reference",
      )
      ModelProposalSaveResult.Invalid
    }
  }

  private suspend fun saveProposalResult(
      accountId: String,
      workoutId: String,
      packet: WorkoutChangeSet.Packet,
      expectedRevision: Long,
      expiresAt: Long,
      expectedSessionEpoch: Long?,
      isCurrent: () -> Boolean,
  ): ModelProposalSaveResult =
      writes.write {
        try {
          database.withTransaction {
            if (!belongsToLiveAccount(accountId, expectedSessionEpoch) || !isCurrent()) {
              return@withTransaction ModelProposalSaveResult.Unavailable
            }
            val workout =
                workoutDao.getWorkoutFull(workoutId)?.workout
                    ?: return@withTransaction ModelProposalSaveResult.Unavailable
            if (!belongsToLiveAccount(accountId, expectedSessionEpoch) || !isCurrent()) {
              return@withTransaction ModelProposalSaveResult.Unavailable
            }
            if (workout.finishedAt != null)
                return@withTransaction ModelProposalSaveResult.Unavailable
            if (workout.coachRevision != expectedRevision)
                return@withTransaction ModelProposalSaveResult.Stale
            if (coachDao.pendingProposal(workoutId) != null)
                return@withTransaction ModelProposalSaveResult.Unavailable
            if (!validAutoregulation(accountId, workoutId, packet, expectedSessionEpoch))
                return@withTransaction ModelProposalSaveResult.Stale
            val calculated = calculate(accountId, workoutId, packet)
            val summary =
                WorkoutChangeSummary.describe(
                    calculated.steps,
                    exerciseNames = calculated.catalogue.mapValues { it.value.name },
                )
            val preview =
                WorkoutApprovalFormatter.describe(
                    calculated.steps,
                    calculated.catalogue.mapValues { it.value.name },
                )
            require(preview.actions.isNotEmpty()) { "No changes to approve" }
            if (!belongsToLiveAccount(accountId, expectedSessionEpoch) || !isCurrent()) {
              return@withTransaction ModelProposalSaveResult.Unavailable
            }
            val proposal =
                WorkoutProposal(
                    accountId = accountId,
                    workoutId = workoutId,
                    baseRevision = workout.coachRevision,
                    beforeSummary = summary.before,
                    afterSummary = preview.text(),
                    packet = packet,
                    expiresAt = expiresAt,
                )
            coachDao.saveProposal(
                CoachProposalEntity(
                    id = proposal.id,
                    accountId = accountId,
                    workoutId = workoutId,
                    baseRevision = proposal.baseRevision,
                    beforeSummary = summary.before,
                    afterSummary = preview.text(),
                    previewJson = json.encodeToString(WorkoutApprovalPreview.serializer(), preview),
                    packetJson = json.encodeToString(WorkoutChangeSet.Packet.serializer(), packet),
                    expiresAt = expiresAt,
                )
            )
            saveProposalJournal(proposal, packet)
            if (!belongsToLiveAccount(accountId, expectedSessionEpoch) || !isCurrent()) {
              throw ProposalUnavailable()
            }
            ModelProposalSaveResult.Saved(proposal)
          }
        } catch (error: CancellationException) {
          throw error
        } catch (_: ProposalUnavailable) {
          ModelProposalSaveResult.Unavailable
        }
      }

  suspend fun saveProposal(
      accountId: String,
      workoutId: String,
      packet: WorkoutChangeSet.Packet,
      expectedRevision: Long,
      expiresAt: Long,
      expectedSessionEpoch: Long? = null,
      isCurrent: () -> Boolean = { true },
  ): WorkoutProposal? =
      (saveProposalResult(
              accountId,
              workoutId,
              packet,
              expectedRevision,
              expiresAt,
              expectedSessionEpoch,
              isCurrent,
          )
              as? ModelProposalSaveResult.Saved)
          ?.proposal

  suspend fun confirmProposal(
      accountId: String,
      proposalId: String,
      operationId: String,
      expectedSessionEpoch: Long? = null,
  ): CommandReceipt =
      writes.write {
        val applied =
            runCatching {
                  database.withTransaction {
                    if (!belongsToLiveAccount(accountId, expectedSessionEpoch))
                        return@withTransaction Applied(stale(operationId, ""), null)
                    val proposal =
                        coachDao.pendingProposalForId(proposalId)
                            ?: return@withTransaction Applied(stale(operationId, ""), null)
                    if (proposal.accountId != accountId)
                        return@withTransaction Applied(stale(operationId, ""), null)
                    if (proposal.expiresAt < System.currentTimeMillis()) {
                      coachDao.setProposalState(proposalId, "EXPIRED")
                      return@withTransaction Applied(stale(operationId, proposal.workoutId), null)
                    }
                    coachDao.receipt(operationId)?.let { saved ->
                      if (saved.accountId != accountId || saved.workoutId != proposal.workoutId)
                          return@withTransaction Applied(
                              stale(operationId, proposal.workoutId),
                              null,
                          )
                      return@withTransaction Applied(
                          CommandReceipt(
                              operationId,
                              saved.workoutId,
                              saved.revision,
                              CommandResult.REPLAYED,
                          ),
                          null,
                      )
                    }
                    val workout =
                        workoutDao.getWorkoutFull(proposal.workoutId)?.workout
                            ?: return@withTransaction Applied(
                                stale(operationId, proposal.workoutId),
                                null,
                            )
                    if (
                        workout.finishedAt != null || workout.coachRevision != proposal.baseRevision
                    ) {
                      coachDao.setProposalState(proposalId, "STALE")
                      return@withTransaction Applied(
                          saveReceipt(
                              accountId,
                              proposal.workoutId,
                              operationId,
                              workout.coachRevision,
                              CommandResult.STALE,
                          ),
                          null,
                      )
                    }
                    val packet =
                        json.decodeFromString(
                            WorkoutChangeSet.Packet.serializer(),
                            proposal.packetJson,
                        )
                    val accepted =
                        if (
                            !validAutoregulation(
                                accountId,
                                proposal.workoutId,
                                packet,
                                expectedSessionEpoch,
                            )
                        ) {
                          coachDao.setProposalState(proposalId, "STALE")
                          return@withTransaction Applied(
                              stale(operationId, proposal.workoutId),
                              null,
                          )
                        } else
                            applyAccepted(
                                accountId,
                                proposal.workoutId,
                                operationId,
                                workout.coachRevision,
                                packet,
                            )
                    rememberDecision(accountId, proposal, "APPLIED", packet)
                    coachDao.setProposalState(proposalId, "CONFIRMED")
                    updateContext(accountId, proposal.workoutId) {
                      it.copy(initiativePendingInteraction = false)
                    }
                    saveJournal(
                        journalId("$operationId:confirmed"),
                        accountId,
                        proposal.workoutId,
                        "decision",
                        "system",
                        "Предложение подтверждено",
                    )
                    if (!belongsToLiveAccount(accountId, expectedSessionEpoch)) throw StaleCommand()
                    accepted
                  }
                }
                .getOrElse { error ->
                  if (error is CancellationException) throw error
                  CoachDiagnostics.failure("editor.command.failed", error)
                  failureReceipt(accountId, null, operationId, error)
                }
        applied.applyRestAfterCommit()
        applied.receipt.also {
          CoachDiagnostics.event(
              "editor.command.result",
              "result" to it.result,
              "revision" to it.revision,
          )
        }
      }

  suspend fun cancelProposal(
      accountId: String,
      proposalId: String,
      expectedSessionEpoch: Long? = null,
      reason: CoachRejectionReason? = null,
  ): Boolean =
      writes.write {
        database.withTransaction {
          if (!belongsToLiveAccount(accountId, expectedSessionEpoch)) return@withTransaction false
          val proposal = coachDao.pendingProposalForId(proposalId) ?: return@withTransaction false
          if (proposal.accountId != accountId) return@withTransaction false
          rememberDecision(
              accountId,
              proposal,
              "REJECTED",
              json.decodeFromString(WorkoutChangeSet.Packet.serializer(), proposal.packetJson),
              reason,
          )
          coachDao.setProposalState(proposalId, "CANCELLED")
          json
              .decodeFromString(WorkoutChangeSet.Packet.serializer(), proposal.packetJson)
              .autoregulation
              ?.let { proof ->
                rememberAutoregulation(accountId, proposal.workoutId, proof.interventionKey)
              }
          updateContext(accountId, proposal.workoutId) {
            it.copy(initiativePendingInteraction = false)
          }
          saveJournal(
              journalId("$proposalId:cancelled"),
              accountId,
              proposal.workoutId,
              "decision",
              "system",
              "Предложение отменено",
          )
          if (!belongsToLiveAccount(accountId, expectedSessionEpoch)) throw StaleCommand()
          true
        }
      }

  private suspend fun rememberDecision(
      accountId: String,
      proposal: com.valerochka1337.valerochkagym.data.db.entity.CoachProposalEntity,
      status: String,
      packet: WorkoutChangeSet.Packet,
      reason: CoachRejectionReason? = null,
  ) {
    val snapshot =
        CoachWorkoutReader(database, restTimer, sessions).snapshot(accountId, proposal.workoutId)
            ?: return
    val decision =
        CoachDecisionMemory.capture(
            snapshot,
            proposal.id,
            status,
            proposal.afterSummary,
            packet,
            reason,
        )
    updateContext(accountId, proposal.workoutId) {
      it.copy(
          decisionMemoryJson =
              CoachDecisionMemory.encode(
                  CoachDecisionMemory.decode(it.decisionMemoryJson).filterNot { d ->
                    d.proposalId == proposal.id
                  } + decision
              )
      )
    }
  }

  private suspend fun applyAccepted(
      accountId: String,
      workoutId: String,
      operationId: String,
      priorRevision: Long,
      packet: WorkoutChangeSet.Packet,
  ): Applied {
    val undo = captureRestorePacket(workoutId)
    val contextBefore = coachDao.context(workoutId)
    val calculated = calculate(accountId, workoutId, packet)
    packet.autoregulation?.let { proof ->
      calculated.context =
          calculated.context.copy(
              autoregulationOptionsJson =
                  json.encodeToString(proof.options.copy(observedRestSeconds = null))
          )
    }
    val revision = priorRevision + 1
    persist(workoutId, calculated.sections, revision)
    coachDao.saveContext(calculated.context)
    packet.autoregulation?.let { rememberAutoregulation(accountId, workoutId, it.interventionKey) }
    database.openHelper.writableDatabase.execSQL(
        "UPDATE workouts SET coachRevision=? WHERE id=?",
        arrayOf<Any?>(revision, workoutId),
    )
    updateContext(accountId, workoutId) {
      if (
          packet.operations.singleOrNull() is WorkoutChangeSet.Operation.UndoLast ||
              packet.hasIrreversibleRest()
      ) {
        it.copy(lastUndoPacketJson = null, lastUndoRevision = null)
      } else {
        it.copy(
            lastUndoPacketJson =
                json.encodeToString(
                    UndoEntry.serializer(),
                    UndoEntry(
                        undo,
                        (contextBefore ?: CoachSessionContextEntity(workoutId, accountId))
                            .undoContext(),
                    ),
                ),
            lastUndoRevision = revision,
        )
      }
    }
    val receipt = saveReceipt(accountId, workoutId, operationId, revision, CommandResult.APPLIED)
    saveCommandJournal(operationId, accountId, workoutId, packet, receipt)
    return Applied(receipt, calculated.stagedRest)
  }

  // RestoreSection/RestoreSet hold every persisted field except local Room IDs. Keeping the
  // working copy in the existing inverse format lets preview, apply and undo share one calculation.
  private data class EditState(
      var sections: List<RestoreSection>,
      var context: CoachSessionContextEntity,
      val catalogue: Map<Long, ExerciseEntity>,
      val revision: Long,
      var rest: SnapshotRest?,
      var stagedRest: WorkoutChangeSet.Operation.Rest? = null,
      val steps: MutableList<WorkoutChangeSummary.Step> = mutableListOf(),
  ) {
    fun section(id: String) = sections.single { it.sectionId == id }

    fun set(id: String) = sections.flatMap { it.sets }.single { it.syncId == id }

    fun putSet(set: RestoreSet) {
      sections =
          sections.map { row ->
            row.copy(sets = row.sets.map { if (it.syncId == set.syncId) set else it })
          }
    }

    fun snapshot() =
        WorkoutSnapshot(
            context.accountId,
            context.workoutId,
            revision,
            sections
                .sortedBy { it.position }
                .map { row ->
                  val exercise = catalogue.getValue(row.exerciseId)
                  SnapshotExercise(
                      row.sectionId,
                      row.exerciseId,
                      exercise.syncId,
                      exercise.name,
                      row.position,
                      type = exercise.type,
                      sets =
                          row.sets.map { set ->
                            SnapshotSet(
                                set.syncId,
                                set.setIndex,
                                set.isCompleted,
                                set.weightKg,
                                set.reps,
                                set.durationSec,
                                set.completedAt,
                                set.speedKmh,
                                set.inclinePct,
                                actualRir = set.actualRir,
                                actualRirAtLeastFour = set.actualRirAtLeastFour,
                                setType = set.setType,
                                reportedFeelings =
                                    Json.decodeFromString<Set<String>>(set.reportedFeelingsJson),
                            )
                          },
                  )
                },
            rest = rest,
            futureRestSeconds = context.futureRestSeconds,
            availableTimeMinutes = context.availableTimeMinutes,
            excludedExerciseIds = Json.decodeFromString(context.excludedExerciseIdsJson),
        )
  }

  private suspend fun calculate(
      accountId: String,
      workoutId: String,
      packet: WorkoutChangeSet.Packet,
  ): EditState {
    val full = requireNotNull(workoutDao.getWorkoutFull(workoutId))
    val context = coachDao.context(workoutId) ?: CoachSessionContextEntity(workoutId, accountId)
    require(context.accountId == accountId)
    val state =
        EditState(
            full.exercises
                .sortedBy { it.workoutExercise.position }
                .map { row ->
                  RestoreSection(
                      row.workoutExercise.sectionId,
                      row.workoutExercise.exerciseId,
                      row.workoutExercise.position,
                      row.sets.sortedBy { it.setIndex }.map(::restoreSet),
                  )
                },
            context,
            database.exerciseDao().getAllOnce().associateBy { it.id },
            full.workout.coachRevision,
            restSnapshot(),
        )
    packet.operations.forEach { op ->
      val before = state.snapshot()
      when (op) {
        is WorkoutChangeSet.Operation.EditSet -> editSet(state, op)
        is WorkoutChangeSet.Operation.AddSet -> addSet(state, op.sectionId)
        is WorkoutChangeSet.Operation.DeleteSet -> deleteSet(state, op.setSyncId)
        is WorkoutChangeSet.Operation.MoveExercise ->
            moveExercise(state, op.sectionId, op.targetPosition)
        is WorkoutChangeSet.Operation.ReorderExercises -> reorder(state, op.sectionIds)
        is WorkoutChangeSet.Operation.AddExercise ->
            addExercise(state, op.exerciseId, op.prefilledSets, op.position)
        is WorkoutChangeSet.Operation.DeleteExercise -> deleteExercise(state, op.sectionId)
        is WorkoutChangeSet.Operation.RemoveRemaining -> removeRemaining(state, op.sectionId)
        is WorkoutChangeSet.Operation.ReplaceRemaining -> replaceRemaining(state, op)
        is WorkoutChangeSet.Operation.Rest -> {
          validateRest(op)
          if (op.action == RestAction.FUTURE_DURATION)
              state.context = state.context.copy(futureRestSeconds = op.seconds)
          else {
            require(state.stagedRest == null) { "A packet may change rest once" }
            state.stagedRest = op
            state.rest =
                when (op.action) {
                  RestAction.START -> SnapshotRest("preview", op.seconds, op.seconds, 0)
                  RestAction.EXTEND ->
                      state.rest?.let {
                        it.copy(
                            remainingSeconds = it.remainingSeconds?.plus(requireNotNull(op.seconds))
                        )
                      }
                  else -> null
                }
          }
        }
        is WorkoutChangeSet.Operation.SetAvailableTime -> {
          require(op.minutes == null || op.minutes > 0)
          state.context =
              state.context.copy(
                  availableTimeMinutes = op.minutes,
                  availableTimeEndsAtMillis =
                      op.minutes?.let { System.currentTimeMillis() + it * 60_000L },
              )
        }
        is WorkoutChangeSet.Operation.SetExcludedExercises ->
            state.context =
                state.context.copy(
                    excludedExerciseIdsJson = json.encodeToString(op.exerciseIds.sorted())
                )
        is WorkoutChangeSet.Operation.ReportFeelings -> reportFeelings(state, op)
        WorkoutChangeSet.Operation.UndoLast -> {
          require(packet.operations.size == 1)
          if (context.lastUndoRevision != state.revision) throw StaleCommand()
          val entry =
              context.lastUndoPacketJson?.let { json.decodeFromString(UndoEntry.serializer(), it) }
                  ?: throw StaleCommand()
          state.sections =
              (entry.packet.operations.single() as WorkoutChangeSet.Operation.RestoreWorkout)
                  .sections
          entry.context?.let { saved ->
            state.context =
                context.copy(
                    availableTimeMinutes = saved.availableTimeMinutes,
                    availableTimeEndsAtMillis = saved.availableTimeEndsAtMillis,
                    futureRestSeconds = saved.futureRestSeconds,
                    excludedExerciseIdsJson = saved.excludedExerciseIdsJson,
                    autoregulationOptionsJson = saved.autoregulationOptionsJson,
                )
          }
        }
        is WorkoutChangeSet.Operation.RestoreWorkout -> state.sections = op.sections
      }
      state.steps += WorkoutChangeSummary.Step(op, before, state.snapshot())
    }
    return state
  }

  private fun editSet(state: EditState, op: WorkoutChangeSet.Operation.EditSet) {
    val set = state.set(op.setSyncId)
    validateSetValues(state, op.setSyncId, op)
    val completed = op.completed ?: set.isCompleted
    val becameCompleted = (op.completed == true || op.recordResult) && !set.isCompleted
    val changesTarget = !set.isCompleted && !op.recordResult
    val edited =
        set.copy(
            setType = op.setType ?: if (op.actualRir != null) "WORK" else set.setType,
            actualRir =
                if ("actual_rir" in op.clearFields || op.setType == "WARMUP") null
                else op.actualRir ?: set.actualRir,
            actualRirAtLeastFour =
                if (
                    op.actualRir != null || "actual_rir" in op.clearFields || op.setType == "WARMUP"
                )
                    false
                else set.actualRirAtLeastFour,
            weightKg = if ("weight_kg" in op.clearFields) null else op.weightKg ?: set.weightKg,
            reps = if ("reps" in op.clearFields) null else op.reps ?: set.reps,
            durationSec =
                if ("duration_sec" in op.clearFields) null else op.durationSec ?: set.durationSec,
            speedKmh = if ("speed_kmh" in op.clearFields) null else op.speedKmh ?: set.speedKmh,
            inclinePct =
                if ("incline_pct" in op.clearFields) null else op.inclinePct ?: set.inclinePct,
            isCompleted = if (op.recordResult) true else completed,
            completedAt =
                if (becameCompleted) System.currentTimeMillis()
                else if (!completed) null else set.completedAt,
            targetWeightKg =
                if (changesTarget && (op.weightKg != null || "weight_kg" in op.clearFields))
                    if ("weight_kg" in op.clearFields) null else op.weightKg
                else set.targetWeightKg,
            targetReps =
                if (changesTarget && (op.reps != null || "reps" in op.clearFields))
                    if ("reps" in op.clearFields) null else op.reps
                else set.targetReps,
            targetDurationSec =
                if (changesTarget && (op.durationSec != null || "duration_sec" in op.clearFields))
                    if ("duration_sec" in op.clearFields) null else op.durationSec
                else set.targetDurationSec,
            targetSpeedKmh =
                if (changesTarget && (op.speedKmh != null || "speed_kmh" in op.clearFields))
                    if ("speed_kmh" in op.clearFields) null else op.speedKmh
                else set.targetSpeedKmh,
            targetInclinePct =
                if (changesTarget && (op.inclinePct != null || "incline_pct" in op.clearFields))
                    if ("incline_pct" in op.clearFields) null else op.inclinePct
                else set.targetInclinePct,
        )
    val withResult =
        when {
          !edited.isCompleted ->
              edited.copy(
                  actualWeightKg = null,
                  actualReps = null,
                  actualDurationSec = null,
                  actualSpeedKmh = null,
                  actualInclinePct = null,
                  actualRir = if (set.isCompleted) null else edited.actualRir,
                  actualRirAtLeastFour = !set.isCompleted && edited.actualRirAtLeastFour,
              )
          becameCompleted || op.recordResult || edited.isCompleted ->
              edited.copy(
                  actualWeightKg = edited.weightKg,
                  actualReps = edited.reps,
                  actualDurationSec = edited.durationSec,
                  actualSpeedKmh = edited.speedKmh,
                  actualInclinePct = edited.inclinePct,
              )
          else -> edited
        }
    state.putSet(withResult)
  }

  /** Reject values which would bypass the type-specific completed-set DAO contracts. */
  private fun validateSetValues(
      state: EditState,
      setSyncId: String,
      op: WorkoutChangeSet.Operation.EditSet,
  ) {
    val supplied = buildSet {
      if (op.weightKg != null) add("weight_kg")
      if (op.reps != null) add("reps")
      if (op.durationSec != null) add("duration_sec")
      if (op.speedKmh != null) add("speed_kmh")
      if (op.inclinePct != null) add("incline_pct")
      if (op.actualRir != null) add("actual_rir")
      addAll(op.clearFields)
    }
    require(op.clearFields.all { it in SET_VALUE_FIELDS })
    val type =
        state.catalogue
            .getValue(
                state.sections.single { row -> row.sets.any { it.syncId == setSyncId } }.exerciseId
            )
            .type
    val allowed =
        when (type) {
          ExerciseType.STRENGTH -> setOf("weight_kg", "reps", "actual_rir")
          ExerciseType.TIMED -> setOf("duration_sec")
          ExerciseType.CARDIO -> setOf("duration_sec", "speed_kmh", "incline_pct")
        }
    require(supplied.all { it in allowed }) { "Values do not match $type exercise" }
    require(op.weightKg == null || op.weightKg > 0)
    require(op.reps == null || op.reps > 0)
    require(op.durationSec == null || op.durationSec > 0)
    require(op.speedKmh == null || op.speedKmh > 0)
    // A flat treadmill is a valid cardio result; only negative incline is impossible.
    require(op.inclinePct == null || op.inclinePct >= 0)
    require(op.actualRir == null || op.actualRir in 0..10)
    require(
        op.actualRir == null ||
            state.set(setSyncId).isCompleted ||
            op.recordResult ||
            op.completed == true
    )
    require(op.setType == null || op.setType in setOf("WORK", "WARMUP", "UNKNOWN", "DROP", "AMRAP"))
  }

  private fun addSet(state: EditState, sectionId: String) {
    val section = state.section(sectionId)
    val last = section.sets.lastOrNull()
    val set =
        restoreSet(
            WorkoutSetEntity(
                workoutExerciseId = 0,
                setIndex = (section.sets.maxOfOrNull { it.setIndex } ?: -1) + 1,
                weightKg = last?.weightKg,
                reps = last?.reps,
                durationSec = last?.durationSec,
                speedKmh = last?.speedKmh,
                inclinePct = last?.inclinePct,
                originalWeightKg = last?.weightKg,
                originalReps = last?.reps,
                originalDurationSec = last?.durationSec,
                originalSpeedKmh = last?.speedKmh,
                originalInclinePct = last?.inclinePct,
                targetWeightKg = last?.weightKg,
                targetReps = last?.reps,
                targetDurationSec = last?.durationSec,
                targetSpeedKmh = last?.speedKmh,
                targetInclinePct = last?.inclinePct,
            )
        )
    state.sections = state.sections.map { if (it == section) it.copy(sets = it.sets + set) else it }
  }

  private fun deleteSet(state: EditState, syncId: String) {
    require(!state.set(syncId).isCompleted) { "Completed result cannot be deleted" }
    state.sections =
        state.sections.map { it.copy(sets = it.sets.filterNot { set -> set.syncId == syncId }) }
  }

  private fun moveExercise(state: EditState, sectionId: String, targetPosition: Int) {
    val order = state.sections.sortedBy { it.position }.map { it.sectionId }.toMutableList()
    require(order.remove(sectionId))
    order.add(targetPosition.coerceIn(0, order.size), sectionId)
    reorder(state, order)
  }

  private class InvalidExerciseOrder :
      IllegalArgumentException("Order must include every section exactly once")

  private fun reorder(state: EditState, sectionIds: List<String>) {
    if (
        state.sections.size != sectionIds.size ||
            state.sections.map { it.sectionId }.toSet() != sectionIds.toSet()
    ) {
      throw InvalidExerciseOrder()
    }
    val by = state.sections.associateBy { it.sectionId }
    state.sections =
        sectionIds.mapIndexed { position, id -> by.getValue(id).copy(position = position) }
  }

  private fun addExercise(
      state: EditState,
      exerciseId: Long,
      prefilledSets: List<RestoreSet>,
      position: Int?,
  ) {
    require(position == null || position in 0..state.sections.size)
    require(prefilledSets.none { it.isCompleted || it.completedAt != null })
    require(exerciseId in state.catalogue)
    val section =
        RestoreSection(
            UUID.randomUUID().toString(),
            exerciseId,
            (state.sections.maxOfOrNull { it.position } ?: -1) + 1,
            prefilledSets.ifEmpty {
              listOf(restoreSet(WorkoutSetEntity(workoutExerciseId = 0, setIndex = 0)))
            },
        )
    state.sections = state.sections + section
    if (position != null) moveExercise(state, section.sectionId, position)
  }

  private fun deleteExercise(state: EditState, sectionId: String) {
    require(state.section(sectionId).sets.none { it.isCompleted }) {
      "Completed exercise cannot be deleted"
    }
    state.sections = state.sections.filterNot { it.sectionId == sectionId }
    reorder(state, state.sections.sortedBy { it.position }.map { it.sectionId })
  }

  private fun removeRemaining(state: EditState, sectionId: String) {
    val source = state.section(sectionId)
    state.sections =
        state.sections.map {
          if (it == source) it.copy(sets = it.sets.filter { set -> set.isCompleted }) else it
        }
  }

  private fun replaceRemaining(state: EditState, op: WorkoutChangeSet.Operation.ReplaceRemaining) {
    val source = state.section(op.sourceSectionId)
    require(state.sections.none { it.sectionId == op.destinationSectionId })
    val replacement = state.catalogue.getValue(op.replacementExerciseId)
    val sourceType = state.catalogue.getValue(source.exerciseId).type
    val unfinished = source.sets.filterNot { it.isCompleted }
    require(
        unfinished.isNotEmpty() &&
            op.unfinishedSetSyncIds.size == unfinished.size &&
            unfinished.map { it.syncId }.toSet() == op.unfinishedSetSyncIds.toSet()
    )
    require(
        op.replacementWeightKg == null ||
            op.replacementWeightKg.isFinite() && op.replacementWeightKg > 0
    )
    val completed = source.sets.filter { it.isCompleted }
    val keepsSource = completed.isNotEmpty()
    state.sections =
        state.sections.mapNotNull { row ->
          when {
            row == source -> if (keepsSource) row.copy(sets = completed) else null
            keepsSource && row.position > source.position -> row.copy(position = row.position + 1)
            else -> row
          }
        } +
            RestoreSection(
                op.destinationSectionId,
                replacement.id,
                source.position + if (keepsSource) 1 else 0,
                unfinished.mapIndexed { index, set ->
                  set.forReplacement(index, sourceType, replacement.type, op.replacementWeightKg)
                },
            )
  }

  private fun RestoreSet.forReplacement(
      index: Int,
      sourceType: ExerciseType,
      replacementType: ExerciseType,
      frozenWeightKg: Double?,
  ): RestoreSet {
    val compatible = sourceType == replacementType
    val weight = if (replacementType == ExerciseType.STRENGTH) frozenWeightKg else null
    val reps = if (compatible && replacementType == ExerciseType.STRENGTH) reps else null
    val duration = if (compatible && replacementType != ExerciseType.STRENGTH) durationSec else null
    val speed = if (compatible && replacementType == ExerciseType.CARDIO) speedKmh else null
    val incline = if (compatible && replacementType == ExerciseType.CARDIO) inclinePct else null
    return copy(
        setIndex = index,
        weightKg = weight,
        reps = reps,
        durationSec = duration,
        speedKmh = speed,
        inclinePct = incline,
        originalWeightKg = weight,
        originalReps = reps,
        originalDurationSec = duration,
        originalSpeedKmh = speed,
        originalInclinePct = incline,
        targetWeightKg = weight,
        targetReps = reps,
        targetDurationSec = duration,
        targetSpeedKmh = speed,
        targetInclinePct = incline,
        actualWeightKg = null,
        actualReps = null,
        actualDurationSec = null,
        actualSpeedKmh = null,
        actualInclinePct = null,
    )
  }

  private fun reportFeelings(
      state: EditState,
      op: WorkoutChangeSet.Operation.ReportFeelings,
  ) {
    require(
        op.feelings.all {
          it in
              setOf(
                  "PAIN",
                  "FATIGUE",
                  "TECHNIQUE_BREAKDOWN",
                  "INTERRUPTED",
                  "PLANNED_EFFORT",
                  "HARDER_THAN_EXPECTED",
              )
        }
    )
    require(!op.feelings.containsAll(setOf("PLANNED_EFFORT", "HARDER_THAN_EXPECTED")))
    val set = state.set(op.setSyncId)
    state.putSet(set.copy(reportedFeelingsJson = json.encodeToString(op.feelings.sorted())))
  }

  private fun validateRest(op: WorkoutChangeSet.Operation.Rest) {
    when (op.action) {
      RestAction.START,
      RestAction.FUTURE_DURATION -> requireNotNull(op.seconds).also { require(it in 1..86_400) }
      RestAction.EXTEND -> {
        requireNotNull(op.seconds).also { require(it in 1..86_400) }
        if (restTimer.currentStartId() != op.expectedRestStartId) throw StaleCommand()
      }
      RestAction.SKIP ->
          if (restTimer.currentStartId() != op.expectedRestStartId) throw StaleCommand()
    }
  }

  private fun Applied.applyRestAfterCommit() {
    when (val op = rest) {
      null -> Unit
      is WorkoutChangeSet.Operation.Rest ->
          when (op.action) {
            RestAction.START -> restTimer.start(requireNotNull(op.seconds))
            RestAction.EXTEND ->
                restTimer.addSeconds(
                    requireNotNull(op.expectedRestStartId),
                    requireNotNull(op.seconds),
                )
            RestAction.SKIP -> restTimer.skip(requireNotNull(op.expectedRestStartId))
            RestAction.FUTURE_DURATION -> Unit
          }
    }
  }

  private suspend fun captureRestorePacket(workoutId: String): WorkoutChangeSet.Packet =
      WorkoutChangeSet.Packet(
          listOf(
              WorkoutChangeSet.Operation.RestoreWorkout(
                  workoutDao.getWorkoutExercises(workoutId).map { row ->
                    RestoreSection(
                        row.sectionId,
                        row.exerciseId,
                        row.position,
                        workoutDao.getSetsForWorkoutExercise(row.id).map(::restoreSet),
                    )
                  }
              )
          )
      )

  private fun restoreSet(set: WorkoutSetEntity) =
      RestoreSet(
          set.syncId,
          set.setIndex,
          set.weightKg,
          set.reps,
          set.durationSec,
          set.speedKmh,
          set.inclinePct,
          set.isCompleted,
          set.completedAt,
          set.originalWeightKg,
          set.originalReps,
          set.originalDurationSec,
          set.originalSpeedKmh,
          set.originalInclinePct,
          set.targetWeightKg,
          set.targetReps,
          set.targetDurationSec,
          set.targetSpeedKmh,
          set.targetInclinePct,
          set.actualWeightKg,
          set.actualReps,
          set.actualDurationSec,
          set.actualSpeedKmh,
          set.actualInclinePct,
          set.setType,
          set.reportedFeelingsJson,
          set.restSnapshotJson,
          set.coachMutationRevision,
          set.note,
          set.actualRir,
          set.actualRirAtLeastFour,
          set.legacyTargetRir,
      )

  private suspend fun persist(workoutId: String, sections: List<RestoreSection>, revision: Long) {
    val existing = workoutDao.getWorkoutExercises(workoutId).associateBy { it.sectionId }
    val savedSets =
        existing.values
            .flatMap { workoutDao.getSetsForWorkoutExercise(it.id) }
            .associateBy { it.syncId }
    val wantedSets = sections.flatMap { it.sets }.map { it.syncId }.toSet()
    val desired = sections.map { it.sectionId }.toSet()
    // Create destinations before moving UUID-bound sets; delete parents only after those moves.
    for (section in sections) {
      val prior = existing[section.sectionId]
      val row =
          prior?.copy(exerciseId = section.exerciseId, position = section.position)
              ?: WorkoutExerciseEntity(
                  workoutId = workoutId,
                  exerciseId = section.exerciseId,
                  sectionId = section.sectionId,
                  position = section.position,
              )
      val localId =
          if (prior == null) workoutDao.insertWorkoutExercise(row)
          else {
            if (row != prior) workoutDao.updateWorkoutExercises(listOf(row))
            row.id
          }
      for (set in section.sets) {
        val saved = savedSets[set.syncId]
        val next = set.toEntity(localId, saved?.id ?: 0)
        if (next != saved) {
          val changed = next.copy(coachMutationRevision = revision)
          if (saved == null) workoutDao.insertSet(changed) else workoutDao.updateSet(changed)
        }
      }
    }
    savedSets.filterKeys { it !in wantedSets }.values.forEach { workoutDao.deleteSet(it.id) }
    existing
        .filterKeys { it !in desired }
        .values
        .forEach { workoutDao.deleteWorkoutExercise(it.id) }
  }

  private fun RestoreSet.toEntity(workoutExerciseId: Long, id: Long) =
      WorkoutSetEntity(
          id = id,
          workoutExerciseId = workoutExerciseId,
          setIndex = setIndex,
          syncId = syncId,
          weightKg = weightKg,
          reps = reps,
          durationSec = durationSec,
          speedKmh = speedKmh,
          inclinePct = inclinePct,
          isCompleted = isCompleted,
          completedAt = completedAt,
          note = note,
          originalWeightKg = originalWeightKg,
          originalReps = originalReps,
          originalDurationSec = originalDurationSec,
          originalSpeedKmh = originalSpeedKmh,
          originalInclinePct = originalInclinePct,
          targetWeightKg = targetWeightKg,
          targetReps = targetReps,
          targetDurationSec = targetDurationSec,
          targetSpeedKmh = targetSpeedKmh,
          targetInclinePct = targetInclinePct,
          actualWeightKg = actualWeightKg,
          actualReps = actualReps,
          actualDurationSec = actualDurationSec,
          actualSpeedKmh = actualSpeedKmh,
          actualInclinePct = actualInclinePct,
          setType = setType,
          reportedFeelingsJson = reportedFeelingsJson,
          restSnapshotJson = restSnapshotJson,
          coachMutationRevision = coachMutationRevision,
          actualRir = actualRir,
          actualRirAtLeastFour = actualRirAtLeastFour,
          legacyTargetRir = targetRir,
      )

  private suspend fun updateContext(
      accountId: String,
      workoutId: String,
      transform: (CoachSessionContextEntity) -> CoachSessionContextEntity,
  ) {
    val existing = coachDao.context(workoutId)
    require(existing == null || existing.accountId == accountId) { "Context account changed" }
    coachDao.saveContext(transform(existing ?: CoachSessionContextEntity(workoutId, accountId)))
  }

  private fun CoachSessionContextEntity.undoContext() =
      UndoContext(
          availableTimeMinutes,
          availableTimeEndsAtMillis,
          futureRestSeconds,
          excludedExerciseIdsJson,
          autoregulationOptionsJson,
      )

  private fun WorkoutChangeSet.Packet.hasIrreversibleRest() =
      operations.any {
        it is WorkoutChangeSet.Operation.Rest && it.action != RestAction.FUTURE_DURATION
      }

  private fun belongsToLiveAccount(accountId: String, expectedSessionEpoch: Long? = null): Boolean {
    val session = sessions.snapshot() ?: return false
    if (
        session.tokens.userId != accountId ||
            (expectedSessionEpoch != null && session.epoch != expectedSessionEpoch)
    )
        return false
    val owner =
        database.openHelper.writableDatabase
            .query("SELECT owner FROM backend_state WHERE id=1")
            .use { row -> if (row.moveToFirst() && !row.isNull(0)) row.getString(0) else null }
    return owner == accountId
  }

  private suspend fun failureReceipt(
      accountId: String,
      workoutId: String?,
      operationId: String,
      error: Throwable,
  ): Applied {
    val workout = workoutId?.let { workoutDao.getWorkoutFull(it)?.workout }
    if (workout == null || !belongsToLiveAccount(accountId))
        return Applied(stale(operationId, workoutId.orEmpty()), null)
    return Applied(
        saveReceipt(
            accountId,
            workout.id,
            operationId,
            workout.coachRevision,
            if (error is StaleCommand) CommandResult.STALE else CommandResult.INVALID,
        ),
        null,
    )
  }

  private suspend fun saveReceipt(
      accountId: String,
      workoutId: String,
      operationId: String,
      revision: Long,
      result: CommandResult,
  ): CommandReceipt {
    coachDao.saveReceipt(
        CoachCommandReceiptEntity(
            operationId,
            accountId,
            workoutId,
            revision,
            result.name,
            System.currentTimeMillis(),
        )
    )
    return CommandReceipt(operationId, workoutId, revision, result)
  }

  private suspend fun saveJournal(
      id: String,
      accountId: String,
      workoutId: String,
      kind: String,
      role: String,
      text: String,
  ) {
    coachDao.saveJournal(
        CoachJournalEntity(
            id = id,
            accountId = accountId,
            workoutId = workoutId,
            createdAt = System.currentTimeMillis(),
            payload =
                json.encodeToString(
                    buildJsonObject {
                      put("kind", kind)
                      put("role", role)
                      put("text", text)
                    }
                ),
        )
    )
  }

  private suspend fun saveProposalJournal(
      proposal: WorkoutProposal,
      packet: WorkoutChangeSet.Packet,
  ) {
    coachDao.saveJournal(
        CoachJournalEntity(
            id = proposal.id,
            accountId = proposal.accountId,
            workoutId = proposal.workoutId,
            createdAt = System.currentTimeMillis(),
            payload =
                json.encodeToString(
                    buildJsonObject {
                      put("kind", "proposal")
                      put("role", "assistant")
                      put("text", proposal.afterSummary)
                      put("before", proposal.beforeSummary)
                      put("after", proposal.afterSummary)
                      put(
                          "packet",
                          json.encodeToJsonElement(WorkoutChangeSet.Packet.serializer(), packet),
                      )
                    }
                ),
        )
    )
  }

  private suspend fun saveCommandJournal(
      operationId: String,
      accountId: String,
      workoutId: String,
      packet: WorkoutChangeSet.Packet,
      receipt: CommandReceipt,
  ) {
    coachDao.saveJournal(
        CoachJournalEntity(
            id = operationId,
            accountId = accountId,
            workoutId = workoutId,
            createdAt = System.currentTimeMillis(),
            payload =
                json.encodeToString(
                    buildJsonObject {
                      put("kind", "command")
                      put("role", "system")
                      put("text", "Изменения тренировки применены")
                      put("result", receipt.result.name)
                      put("revision", receipt.revision)
                      put(
                          "packet",
                          json.encodeToJsonElement(WorkoutChangeSet.Packet.serializer(), packet),
                      )
                    }
                ),
        )
    )
  }

  private fun journalId(seed: String): String =
      UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8)).toString()

  private fun stale(operationId: String, workoutId: String) =
      CommandReceipt(operationId, workoutId, 0, CommandResult.STALE)

  private data class Applied(
      val receipt: CommandReceipt,
      val rest: WorkoutChangeSet.Operation.Rest?,
  )

  @Serializable
  private data class UndoEntry(val packet: WorkoutChangeSet.Packet, val context: UndoContext?)

  @Serializable
  private data class UndoContext(
      val availableTimeMinutes: Int?,
      val availableTimeEndsAtMillis: Long?,
      val futureRestSeconds: Int?,
      val excludedExerciseIdsJson: String,
      @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
      val autoregulationOptionsJson: String = "{}",
  )

  private suspend fun mapIntents(
      snapshot: WorkoutSnapshot,
      intents: List<CoachChangeIntent>,
  ): WorkoutChangeSet.Packet? =
      runCatching {
            require(intents.isNotEmpty())
            val byExerciseSync = database.exerciseDao().getAllOnce().associateBy { it.syncId }
            val replacementWeights = mutableMapOf<CoachChangeIntent.Replace, Double?>()
            for (intent in intents.filterIsInstance<CoachChangeIntent.Replace>()) {
              val replacement = byExerciseSync.getValue(intent.exerciseId)
              replacementWeights[intent] =
                  intent.weightKg
                      ?: if (replacement.type == ExerciseType.STRENGTH)
                          database
                              .workoutDao()
                              .latestComparableCompletedWeight(replacement.id, snapshot.workoutId)
                      else null
            }
            val operations =
                intents.map { intent ->
                  when (intent) {
                    is CoachChangeIntent.Autoregulate ->
                        error("Autoregulation must be the only intent")
                    is CoachChangeIntent.AddExercise ->
                        WorkoutChangeSet.Operation.AddExercise(
                            byExerciseSync.getValue(intent.exerciseId).id,
                            database
                                .coachDao()
                                .exerciseHistory(byExerciseSync.getValue(intent.exerciseId).id, 1)
                                .mapIndexed { index, historical ->
                                  val previous = historical.set
                                  val weight = previous.actualWeightKg ?: previous.weightKg
                                  val reps = previous.actualReps ?: previous.reps
                                  val duration = previous.actualDurationSec ?: previous.durationSec
                                  val speed = previous.actualSpeedKmh ?: previous.speedKmh
                                  val incline = previous.actualInclinePct ?: previous.inclinePct
                                  restoreSet(
                                      WorkoutSetEntity(
                                          workoutExerciseId = 0,
                                          setIndex = index,
                                          weightKg = weight,
                                          reps = reps,
                                          durationSec = duration,
                                          speedKmh = speed,
                                          inclinePct = incline,
                                          originalWeightKg = weight,
                                          originalReps = reps,
                                          originalDurationSec = duration,
                                          originalSpeedKmh = speed,
                                          originalInclinePct = incline,
                                          targetWeightKg = weight,
                                          targetReps = reps,
                                          targetDurationSec = duration,
                                          targetSpeedKmh = speed,
                                          targetInclinePct = incline,
                                          setType = previous.setType,
                                      )
                                  )
                                },
                            intent.position,
                        )
                    is CoachChangeIntent.RemoveRemaining ->
                        WorkoutChangeSet.Operation.RemoveRemaining(intent.sectionId)
                    is CoachChangeIntent.Move ->
                        WorkoutChangeSet.Operation.MoveExercise(intent.sectionId, intent.position)
                    is CoachChangeIntent.Swap -> {
                      val ids =
                          snapshot.exercises
                              .sortedBy { it.position }
                              .map { it.sectionId }
                              .toMutableList()
                      val first = ids.indexOf(intent.first)
                      val second = ids.indexOf(intent.second)
                      require(first >= 0 && second >= 0)
                      ids[first] = intent.second
                      ids[second] = intent.first
                      WorkoutChangeSet.Operation.ReorderExercises(ids)
                    }
                    is CoachChangeIntent.Reorder ->
                        WorkoutChangeSet.Operation.ReorderExercises(intent.sectionIds)
                    is CoachChangeIntent.Replace ->
                        WorkoutChangeSet.Operation.ReplaceRemaining(
                            intent.sectionId,
                            UUID.randomUUID().toString(),
                            byExerciseSync.getValue(intent.exerciseId).id,
                            intent.remainingSetIds,
                            replacementWeights.getValue(intent),
                        )
                    is CoachChangeIntent.AddSet ->
                        WorkoutChangeSet.Operation.AddSet(intent.sectionId)
                    is CoachChangeIntent.DeleteSet ->
                        WorkoutChangeSet.Operation.DeleteSet(intent.setId)
                    is CoachChangeIntent.EditSet ->
                        editOperation(intent.setId, intent.values, intent.recordResult)
                    is CoachChangeIntent.Complete ->
                        WorkoutChangeSet.Operation.EditSet(
                            intent.setId,
                            completed = intent.completed,
                        )
                    is CoachChangeIntent.Rest ->
                        WorkoutChangeSet.Operation.Rest(
                            intent.action.toDomain(),
                            intent.startId,
                            intent.seconds,
                        )
                    is CoachChangeIntent.AvailableTime ->
                        WorkoutChangeSet.Operation.SetAvailableTime(intent.minutes)
                    is CoachChangeIntent.ExcludedExercises ->
                        WorkoutChangeSet.Operation.SetExcludedExercises(
                            intent.ids.map { byExerciseSync.getValue(it).id }.toSet()
                        )
                    is CoachChangeIntent.Feelings ->
                        WorkoutChangeSet.Operation.ReportFeelings(intent.setId, intent.feelings)
                    CoachChangeIntent.Undo -> WorkoutChangeSet.Operation.UndoLast
                  }
                }
            WorkoutChangeSet.Packet(operations)
          }
          .getOrElse {
            if (it is CancellationException) throw it
            CoachDiagnostics.failure("editor.model_proposal.invalid", it, "phase" to "map_intents")
            null
          }

  private fun editOperation(setId: String, values: CoachSetValues, recordResult: Boolean) =
      WorkoutChangeSet.Operation.EditSet(
          setSyncId = setId,
          weightKg = values.weightKg,
          reps = values.reps,
          durationSec = values.durationSec,
          speedKmh = values.speedKmh,
          inclinePct = values.inclinePct,
          actualRir = values.actualRir,
          setType = values.setType,
          clearFields =
              values.supplied
                  .filter { field ->
                    when (field) {
                      "weight_kg" -> values.weightKg == null
                      "reps" -> values.reps == null
                      "duration_sec" -> values.durationSec == null
                      "speed_kmh" -> values.speedKmh == null
                      "incline_pct" -> values.inclinePct == null
                      "actual_rir" -> values.actualRir == null
                      else -> false
                    }
                  }
                  .toSet(),
          recordResult = recordResult,
      )

  private fun CoachRestAction.toDomain() =
      when (this) {
        CoachRestAction.START -> RestAction.START
        CoachRestAction.EXTEND -> RestAction.EXTEND
        CoachRestAction.SKIP -> RestAction.SKIP
        CoachRestAction.FUTURE_DURATION -> RestAction.FUTURE_DURATION
      }

  private suspend fun validAutoregulation(
      accountId: String,
      workoutId: String,
      packet: WorkoutChangeSet.Packet,
      epoch: Long?,
  ): Boolean {
    val proof = packet.autoregulation ?: return true
    val current =
        CoachWorkoutReader(database, restTimer, sessions).snapshot(accountId, workoutId, epoch)
            ?: return false
    val recommendation =
        com.valerochka1337.valerochkagym.domain.autoregulation.AutoregulationEngine.calculate(
            current,
            proof.options,
        )
    return (recommendation.packet() == packet).also {
      CoachDiagnostics.event(
          "editor.autoregulation.revalidated",
          "matches" to it,
          "revision" to current.revision,
      )
    }
  }

  private suspend fun rememberAutoregulation(accountId: String, workoutId: String, key: String) {
    updateContext(accountId, workoutId) { context ->
      val previous = json.decodeFromString<Set<String>>(context.initiativeAskedExerciseIdsJson)
      context.copy(
          initiativeAskedExerciseIdsJson =
              json.encodeToString((previous + "autoregulation:$key").sorted())
      )
    }
  }

  /**
   * Replaces stale calculations under the same write queue. A new ID requires a new confirmation.
   */
  suspend fun refreshAutoregulationProposal(accountId: String, workoutId: String, epoch: Long?) =
      writes.write {
        database.withTransaction {
          if (!belongsToLiveAccount(accountId, epoch)) return@withTransaction
          val old = coachDao.pendingProposal(workoutId) ?: return@withTransaction
          if (old.accountId != accountId) return@withTransaction
          suspend fun invalidate(state: String, text: String) {
            coachDao.setProposalState(old.id, state)
            updateContext(accountId, workoutId) { it.copy(initiativePendingInteraction = false) }
            val id = journalId("${old.id}:$state")
            coachDao.saveMessage(
                com.valerochka1337.valerochkagym.data.db.entity.CoachMessageEntity(
                    id,
                    accountId,
                    workoutId,
                    "system",
                    text,
                    System.currentTimeMillis(),
                )
            )
            saveJournal(id, accountId, workoutId, "decision", "system", text)
            if (!belongsToLiveAccount(accountId, epoch)) throw StaleCommand()
          }
          if (old.expiresAt <= System.currentTimeMillis()) {
            invalidate("EXPIRED", "Срок предложения истёк. Изменения не применены.")
            return@withTransaction
          }
          val current =
              CoachWorkoutReader(database, restTimer, sessions)
                  .snapshot(accountId, workoutId, epoch)
          if (!belongsToLiveAccount(accountId, epoch)) return@withTransaction
          if (current == null) {
            invalidate("STALE", "Предложение больше не актуально. Изменения не применены.")
            return@withTransaction
          }
          val packet = json.decodeFromString(WorkoutChangeSet.Packet.serializer(), old.packetJson)
          val proof = packet.autoregulation
          if (proof == null) {
            val staleRest =
                packet.operations.filterIsInstance<WorkoutChangeSet.Operation.Rest>().any {
                  it.expectedRestStartId != null &&
                      (it.expectedRestStartId != current.rest?.startId ||
                          current.rest.remainingSeconds == 0)
                }
            if (old.baseRevision != current.revision || staleRest)
                invalidate(
                    "STALE",
                    "Предложение потеряло актуальность: тренировка изменилась. Изменения не применены.",
                )
            return@withTransaction
          }
          val result =
              com.valerochka1337.valerochkagym.domain.autoregulation.AutoregulationEngine.calculate(
                  current,
                  proof.options,
              )
          if (old.baseRevision == current.revision && packet == result.packet())
              return@withTransaction
          CoachDiagnostics.event(
              "editor.proposal.refresh",
              "decision" to "replace_stale",
              "revision" to current.revision,
              "recommendation" to result.kind,
          )
          coachDao.setProposalState(old.id, "STALE")
          val replacement = result.packet()
          if (replacement == null) {
            updateContext(accountId, workoutId) { it.copy(initiativePendingInteraction = false) }
            return@withTransaction
          }
          val calculated = calculate(accountId, workoutId, replacement)
          val names = calculated.catalogue.mapValues { it.value.name }
          val preview = WorkoutApprovalFormatter.describe(calculated.steps, names)
          val summary = WorkoutChangeSummary.describe(calculated.steps, names)
          val proposal =
              WorkoutProposal(
                  accountId = accountId,
                  workoutId = workoutId,
                  baseRevision = current.revision,
                  beforeSummary = summary.before,
                  afterSummary = preview.text(),
                  packet = replacement,
                  expiresAt = old.expiresAt,
              )
          coachDao.saveProposal(
              old.copy(
                  id = proposal.id,
                  baseRevision = current.revision,
                  beforeSummary = summary.before,
                  afterSummary = preview.text(),
                  previewJson = json.encodeToString(WorkoutApprovalPreview.serializer(), preview),
                  packetJson =
                      json.encodeToString(WorkoutChangeSet.Packet.serializer(), replacement),
              )
          )
          saveProposalJournal(proposal, replacement)
          check(belongsToLiveAccount(accountId, epoch))
        }
      }

  private fun restSnapshot(): SnapshotRest? =
      when (val rest = restTimer.state.value) {
        is RestTimerState.Timed ->
            SnapshotRest(
                restTimer.currentStartId() ?: return null,
                rest.totalSec,
                rest.remainingSec,
                rest.endsAtMillis - rest.totalSec * 1_000L,
                rest.endsAtMillis,
            )
        is RestTimerState.HeartRate ->
            SnapshotRest(
                restTimer.currentStartId() ?: return null,
                null,
                null,
                rest.startedAtMillis,
            )
        null -> null
      }

  private class StaleCommand : IllegalStateException()

  private class ProposalUnavailable : IllegalStateException()
}
