package com.valerochka1337.valerochkagym.data.ai

import com.valerochka1337.valerochkagym.data.backend.*
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.LocalEquipmentCatalog
import com.valerochka1337.valerochkagym.data.db.entity.EquipmentRequirementState
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.trainingproposal.*
import com.valerochka1337.valerochkagym.service.WallClock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Only user intent is sent. History, catalog, profile and notes are read by the backend. */
@Serializable
data class CalendarAiIntent(
    val startsAtMillis: Long,
    val timeZoneId: String,
    val gymIds: List<String>,
    val excludedExerciseIds: List<String> = emptyList(),
    val excludedEquipmentIds: List<String> = emptyList(),
    val priorityMuscles: List<String> = emptyList(),
    val includeNotes: Boolean = true,
    val availableDurationMinutes: Int = 60,
    val currentState: String? = null,
    val preferences: String? = null,
) {
  fun valid(nowMillis: Long): Boolean =
      runCatching {
            val zone = ZoneId.of(timeZoneId)
            require(
                timeZoneId in ZoneId.getAvailableZoneIds() &&
                    Instant.ofEpochMilli(startsAtMillis).atZone(zone).year in 1970..2100 &&
                    startsAtMillis > nowMillis
            )
            require(availableDurationMinutes in 10..240)
            require(
                listOf(gymIds, excludedExerciseIds).all {
                  it.size <= 1000 && it.distinct().size == it.size && it.all(ProposalWire::uuid)
                }
            )
            require(
                excludedEquipmentIds.size <= 200 &&
                    excludedEquipmentIds.distinct().size == excludedEquipmentIds.size &&
                    excludedEquipmentIds.all {
                      it.isNotBlank() && it.length <= 255 && it == it.trim()
                    }
            )
            require(
                priorityMuscles.size <= 25 &&
                    priorityMuscles.distinct().size == priorityMuscles.size &&
                    priorityMuscles.all { p -> Muscle.entries.any { it.name == p } }
            )
            require(
                listOf(currentState, preferences).all {
                  it == null ||
                      it.isNotBlank() && it == it.trim() && it.codePointCount(0, it.length) <= 2000
                }
            )
          }
          .isSuccess
}

@Serializable
internal data class CalendarRequest(
    val requestId: String,
    val expectedRevision: Long,
    val expectedCatalogRevision: Long,
    val startsAtMillis: Long,
    val timeZoneId: String,
    val gymIds: List<String>,
    val excludedExerciseIds: List<String>,
    val excludedEquipmentIds: List<String>,
    val priorityMuscles: List<String>,
    val includeNotes: Boolean,
    val availableDurationMinutes: Int,
    val currentState: String?,
    val preferences: String?,
)

@Serializable
internal data class CalendarContext(
    val revision: Long,
    val catalogRevision: Long,
    val capturedAtMillis: Long,
)

@Serializable
internal data class CalendarResponse(
    val requestId: String,
    val context: CalendarContext,
    val proposal: TrainingProposal,
)

@Singleton
class CalendarAiRepository
@Inject
constructor(
    private val api: BackendTransport,
    private val readySource: SyncReadySource,
    private val db: GymDatabase,
    private val clock: WallClock,
) {
  internal suspend fun prepare(
      intent: CalendarAiIntent,
      id: String,
  ): Pair<SyncReady.Ready, CalendarRequest> {
    checkLocal(intent.valid(clock.nowMillis()), "ai_invalid_intent")
    val ready =
        when (val result = readySource.await()) {
          is SyncReady.Ready -> result
          SyncReady.Blocked ->
              throw BackendException(409, "ai_sync_failed", "Сначала завершите синхронизацию")
          is SyncReady.Failure ->
              throw when (val cause = result.cause) {
                is kotlinx.coroutines.CancellationException,
                is BackendException,
                is java.io.IOException -> cause
                else ->
                    BackendException(409, "ai_sync_failed", "Не удалось завершить синхронизацию")
              }
        }
    suspend fun guard() {
      if (db.healthDao().hasActiveWorkout())
          throw BackendException(409, "workout_active", "Сначала завершите тренировку")
      if (!readySource.isCurrent(ready))
          throw BackendException(
              409,
              "ai_context_stale",
              "Сначала завершите тренировку и синхронизацию",
          )
    }
    guard()
    guard()

    val gyms = db.gymDao().getGyms().filterNot { it.archived }
    checkLocal(intent.gymIds.all { id -> gyms.any { it.syncId == id } }, "ai_gym_unavailable")
    val selected = gyms.filter { it.syncId in intent.gymIds }
    val equipment = selected.flatMap { db.gymDao().getGymEquipmentIds(it.id) }.toSet()
    val exercises = db.exerciseDao().getAllOnce().filterNot { it.archived }
    val requirements =
        db.exerciseDao().getRequirements(exercises.map { it.id }).groupBy { it.exerciseId }
    val allowed =
        exercises
            .filter { e ->
              val req = requirements[e.id].orEmpty().map { it.equipmentId }
              e.syncId !in intent.excludedExerciseIds &&
                  req.none { it in intent.excludedEquipmentIds } &&
                  (intent.gymIds.isEmpty() ||
                      e.equipmentRequirementState == EquipmentRequirementState.KNOWN &&
                          req.all { LocalEquipmentCatalog.covers(equipment, it) })
            }
            .map { it.syncId }
            .toSet()
    checkLocal(allowed.isNotEmpty(), "ai_no_candidates")
    val request =
        CalendarRequest(
            id,
            ready.revision,
            ready.catalogRevision,
            intent.startsAtMillis,
            intent.timeZoneId,
            intent.gymIds.sorted(),
            intent.excludedExerciseIds.sorted(),
            intent.excludedEquipmentIds.sorted(),
            intent.priorityMuscles.sorted(),
            intent.includeNotes,
            intent.availableDurationMinutes,
            intent.currentState,
            intent.preferences,
        )
    return ready to request
  }

  suspend fun generate(intent: CalendarAiIntent): TrainingProposal {
    val id = UUID.randomUUID().toString()
    val (ready, request) = prepare(intent, id)
    suspend fun guard() {
      checkLocal(readySource.isCurrent(ready), "ai_context_stale")
    }
    val response =
        api.authorizedRawResponse(
            "POST",
            "/ai/calendar-drafts",
            ProposalWire.json.encodeToString(request).encodeToByteArray(),
            expectedOwner = ready.owner,
            expectedSessionEpoch = ready.sessionEpoch,
            retryOnUnauthorized = false,
            maxResponseBytes = ProposalWire.RESPONSE_LIMIT,
        )
    guard()
    checkResponse(response.owner == ready.owner && response.sessionEpoch == ready.sessionEpoch)
    val result =
        try {
          ProposalWire.decode<CalendarResponse>(response.rawBody)
        } catch (_: IllegalArgumentException) {
          throw BackendException(502, "ai_invalid_response", "Некорректный ответ AI")
        }
    checkResponse(
        result.requestId == id &&
            result.context.revision == ready.revision &&
            result.context.catalogRevision == ready.catalogRevision &&
            result.context.capturedAtMillis >= 0
    )
    return validate(intent, ready, result)
  }

  internal suspend fun validate(
      intent: CalendarAiIntent,
      ready: SyncReady.Ready,
      result: CalendarResponse,
  ): TrainingProposal {
    suspend fun guard() {
      checkLocal(readySource.isCurrent(ready), "ai_context_stale")
      checkLocal(!db.healthDao().hasActiveWorkout(), "workout_active")
    }
    val gyms = db.gymDao().getGyms().filterNot { it.archived }
    checkLocal(intent.gymIds.all { id -> gyms.any { it.syncId == id } }, "ai_gym_unavailable")
    val selected = gyms.filter { it.syncId in intent.gymIds }
    val equipment = selected.flatMap { db.gymDao().getGymEquipmentIds(it.id) }.toSet()
    val exercises = db.exerciseDao().getAllOnce().filterNot { it.archived }
    val requirements =
        db.exerciseDao().getRequirements(exercises.map { it.id }).groupBy { it.exerciseId }
    val allowed =
        exercises
            .filter { e ->
              val req = requirements[e.id].orEmpty().map { it.equipmentId }
              e.syncId !in intent.excludedExerciseIds &&
                  req.none { it in intent.excludedEquipmentIds } &&
                  (intent.gymIds.isEmpty() ||
                      e.equipmentRequirementState == EquipmentRequirementState.KNOWN &&
                          req.all { LocalEquipmentCatalog.covers(equipment, it) })
            }
            .map { it.syncId }
            .toSet()
    checkLocal(allowed.isNotEmpty(), "ai_no_candidates")
    val p = result.proposal
    checkResponse(
        ProposalWire.valid(p) &&
            p.recipientId == ready.owner &&
            p.source == ProposalSource.AI &&
            p.status == ProposalStatus.PENDING &&
            p.author.accountId == null
    )
    checkResponse(
        p.snapshot.ownerRevision == ready.revision &&
            p.snapshot.catalogRevision == ready.catalogRevision
    )
    checkResponse(
        p.snapshot.draft.startsAtMillis == intent.startsAtMillis &&
            p.snapshot.draft.timeZoneId == intent.timeZoneId &&
            p.snapshot.draft.gymIds == intent.gymIds.sorted() &&
            p.snapshot.draft.exercises.all { it.exerciseId in allowed }
    )
    checkResponse(
        p.snapshot.draft.exercises.all { planned ->
          val type = exercises.first { it.syncId == planned.exerciseId }.type.name
          planned.plannedSets.all { set ->
            when (type) {
              "STRENGTH" ->
                  set.reps != null &&
                      set.durationSec == null &&
                      set.speedKmh == null &&
                      set.inclinePct == null
              "TIMED" ->
                  set.reps == null &&
                      set.weightKg == null &&
                      set.durationSec != null &&
                      set.speedKmh == null &&
                      set.inclinePct == null
              "CARDIO" -> set.reps == null && set.weightKg == null && set.durationSec != null
              else -> false
            }
          }
        }
    )
    guard()
    return p
  }
}

private fun checkLocal(valid: Boolean, code: String) {
  if (!valid) throw BackendException(400, code, "Проверьте параметры предложения")
}

private fun checkResponse(valid: Boolean) {
  if (!valid) throw BackendException(502, "ai_invalid_response", "Некорректный ответ AI")
}
