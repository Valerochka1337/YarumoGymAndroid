package com.valerochka1337.valerochkagym.data.plannermapping

import com.valerochka1337.valerochkagym.data.backend.BackendException
import com.valerochka1337.valerochkagym.data.backend.BackendSessionSnapshot
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendTransport
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalWire
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
enum class PlannerMovementClass {
  HORIZONTAL_PUSH,
  HORIZONTAL_PULL,
  VERTICAL_PUSH,
  VERTICAL_PULL,
  SQUAT,
  HIP_HINGE,
  LUNGE,
  CARRY,
  CORE,
  CARDIO,
  MOBILITY,
}

@Serializable
enum class PlannerExerciseRole {
  PRIMARY,
  ACCESSORY,
  CONDITIONING,
}

@Serializable
enum class PlannerSupportedGoal {
  STRENGTH,
  MUSCLE_GAIN,
  FAT_LOSS,
  GENERAL_FITNESS,
  ENDURANCE,
}

@Serializable
data class PlannerExerciseMapping(
    val exerciseId: String,
    val movementClass: PlannerMovementClass,
    val roles: List<PlannerExerciseRole>,
    val supportedGoals: List<PlannerSupportedGoal>,
    val exerciseType: ExerciseType,
    val equipmentIds: List<String>,
    val revision: Long,
) {
  fun valid(): Boolean =
      ProposalWire.uuid(exerciseId) &&
          roles.isNotEmpty() &&
          roles == roles.distinct().sorted() &&
          supportedGoals.isNotEmpty() &&
          supportedGoals == supportedGoals.distinct().sorted() &&
          equipmentIds == equipmentIds.distinct().sorted() &&
          equipmentIds.all { it.isNotBlank() && it == it.trim() && it.length <= 255 } &&
          revision >= 0
}

@Serializable data class PlannerExerciseMappingList(val items: List<PlannerExerciseMapping>)

@Serializable
private data class PlannerExerciseMappingPut(
    val exerciseId: String,
    val movementClass: PlannerMovementClass,
    val roles: List<PlannerExerciseRole>,
    val supportedGoals: List<PlannerSupportedGoal>,
    val exerciseType: ExerciseType,
    val equipmentIds: List<String>,
)

@Singleton
class PlannerExerciseMappingApi
@Inject
constructor(
    private val transport: BackendTransport,
    private val sessions: BackendSessionStore,
) {
  suspend fun list(session: BackendSessionSnapshot): List<PlannerExerciseMapping> {
    val result = ProposalWire.decode<PlannerExerciseMappingList>(request(session, "GET", ROOT))
    require(result.items.map { it.exerciseId }.distinct().size == result.items.size)
    require(result.items.all(PlannerExerciseMapping::valid))
    return result.items
  }

  suspend fun get(session: BackendSessionSnapshot, exerciseId: String): PlannerExerciseMapping {
    val result =
        ProposalWire.decode<PlannerExerciseMapping>(request(session, "GET", path(exerciseId)))
    require(result.exerciseId == exerciseId && result.valid())
    return result
  }

  suspend fun put(
      session: BackendSessionSnapshot,
      mapping: PlannerExerciseMapping,
  ): PlannerExerciseMapping {
    require(mapping.valid())
    val bytes =
        ProposalWire.json
            .encodeToString(
                PlannerExerciseMappingPut(
                    mapping.exerciseId,
                    mapping.movementClass,
                    mapping.roles,
                    mapping.supportedGoals,
                    mapping.exerciseType,
                    mapping.equipmentIds,
                )
            )
            .encodeToByteArray()
    val result =
        ProposalWire.decode<PlannerExerciseMapping>(
            request(session, "PUT", path(mapping.exerciseId), bytes)
        )
    require(result.exerciseId == mapping.exerciseId && result.valid())
    return result
  }

  suspend fun delete(session: BackendSessionSnapshot, exerciseId: String) {
    request(session, "DELETE", path(exerciseId))
  }

  private suspend fun request(
      session: BackendSessionSnapshot,
      method: String,
      path: String,
      bytes: ByteArray = ByteArray(0),
  ): ByteArray {
    assertSession(session)
    try {
      val response =
          transport.authorizedRawResponse(
              method = method,
              path = path,
              rawBody = bytes,
              headers = HEADERS,
              expectedOwner = session.tokens.userId,
              expectedSessionEpoch = session.epoch,
              retryOnUnauthorized = method == "GET",
              maxResponseBytes = ProposalWire.RESPONSE_LIMIT,
          )
      assertSession(session)
      if (response.owner != session.tokens.userId || response.sessionEpoch != session.epoch)
          throw BackendException(401, "owner_changed", "")
      if (CAPABILITY !in response.acceptedCapabilities)
          throw BackendException(426, "planner_update_required", "")
      return response.rawBody
    } catch (error: BackendException) {
      if (error.status in setOf(404, 405, 426))
          throw BackendException(426, "planner_update_required", "")
      throw error
    }
  }

  private fun assertSession(expected: BackendSessionSnapshot) {
    val current = sessions.snapshot()
    if (current?.tokens?.userId != expected.tokens.userId || current.epoch != expected.epoch)
        throw BackendException(401, "owner_changed", "")
  }

  private fun path(exerciseId: String): String {
    require(ProposalWire.uuid(exerciseId))
    return "$ROOT/$exerciseId"
  }

  private companion object {
    const val ROOT = "/planning/v2/exercise-mappings"
    const val CAPABILITY = "deterministic-workout-planner-v2"
    val HEADERS = mapOf("X-Planner-Protocol" to "2")
  }
}
