package com.valerochka1337.valerochkagym.data.trainingproposal

import com.valerochka1337.valerochkagym.data.health.HealthWireJson
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
data class ProposalPlannedSet(
    val weightKg: Double?,
    val reps: Int?,
    val durationSec: Int?,
    val speedKmh: Double?,
    val inclinePct: Double?,
)

@Serializable
data class ProposalPlannedExercise(
    val exerciseId: String,
    val restSeconds: Int?,
    val plannedSets: List<ProposalPlannedSet>,
)

@Serializable
data class ApprovalDraft(
    val name: String,
    val gymIds: List<String>,
    val exercises: List<ProposalPlannedExercise>,
    val startsAtMillis: Long,
    val timeZoneId: String,
)

@Serializable
data class ApprovalRequest(val operationId: String, val version: Int, val draft: ApprovalDraft)

@Serializable
enum class ProposalSource {
  AI
}

@Serializable
enum class ProposalStatus {
  PENDING,
  APPROVED,
  REJECTED,
  REVOKED,
  STALE,
}

@Serializable data class ProposalAuthor(val kind: ProposalSource, val accountId: String?)

@Serializable
data class ProposalSnapshot(
    val version: Int,
    val draft: ApprovalDraft,
    val ownerRevision: Long,
    val catalogRevision: Long,
    val createdAt: Long,
)

@Serializable
data class TrainingProposal(
    val proposalId: String,
    val author: ProposalAuthor,
    val recipientId: String,
    val source: ProposalSource,
    val status: ProposalStatus,
    val currentVersion: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long,
    val snapshot: ProposalSnapshot,
)

@Serializable
data class ProposalListResponse(val items: List<TrainingProposal>, val nextCursor: String?)

@Serializable
data class AcceptedProposalResult(
    val proposalId: String,
    val version: Int,
    val routineId: String,
    val calendarPlanId: String,
    val revision: Long,
    val approvedAt: Long,
)

@Serializable data class ProposalRejectRequest(val version: Int, val reason: String?)

@Serializable
data class ProposalDecision(
    val proposalId: String,
    val version: Int,
    val status: ProposalStatus,
    val updatedAt: Long,
)

/** Strict generated JVM serialization; durable approval bytes are never reconstructed on retry. */
internal object ProposalWire {
  const val REQUEST_LIMIT = 524_288
  const val RESPONSE_LIMIT = 1_048_576
  val json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    allowSpecialFloatingPointValues = false
  }
  private val uuidPattern =
      Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

  fun uuid(value: String): Boolean = uuidPattern.matches(value)

  inline fun <reified T> decode(bytes: ByteArray): T {
    val obj = requireNotNull(HealthWireJson.objectFrom(bytes, RESPONSE_LIMIT))
    val decoded = json.decodeFromString<T>(obj.toString())
    require(sameTypes(obj, json.encodeToJsonElement(decoded)))
    return decoded
  }

  fun sameTypes(raw: JsonElement, encoded: JsonElement): Boolean =
      when {
        raw is JsonNull || encoded is JsonNull -> raw == encoded
        raw is JsonObject && encoded is JsonObject ->
            raw.keys == encoded.keys &&
                raw.all { (key, value) -> sameTypes(value, encoded.getValue(key)) }
        raw is JsonArray && encoded is JsonArray ->
            raw.size == encoded.size && raw.zip(encoded).all { (a, b) -> sameTypes(a, b) }
        raw is JsonPrimitive && encoded is JsonPrimitive -> raw.isString == encoded.isString
        else -> false
      }

  fun canonical(request: ApprovalRequest): ByteArray {
    fun canonicalId(id: String): String = UUID.fromString(id).toString().also { require(uuid(it)) }
    fun Double?.zero(): Double? = this?.let { if (it == 0.0) 0.0 else it }
    val draft =
        request.draft.copy(
            name = request.draft.name.trim(),
            gymIds = request.draft.gymIds.map(::canonicalId).distinct().sorted(),
            exercises =
                request.draft.exercises.map { exercise ->
                  exercise.copy(
                      exerciseId = canonicalId(exercise.exerciseId),
                      plannedSets =
                          exercise.plannedSets.map {
                            it.copy(
                                weightKg = it.weightKg.zero(),
                                speedKmh = it.speedKmh.zero(),
                                inclinePct = it.inclinePct.zero(),
                            )
                          },
                  )
                },
        )
    require(request.version > 0 && validDraft(draft))
    return json
        .encodeToString(request.copy(operationId = canonicalId(request.operationId), draft = draft))
        .encodeToByteArray()
        .also { require(HealthWireJson.objectFrom(it, REQUEST_LIMIT) != null) }
  }

  fun validDraft(draft: ApprovalDraft): Boolean =
      runCatching {
            require(
                draft.name.isNotBlank() &&
                    draft.name == draft.name.trim() &&
                    draft.name.length <= 200
            )
            require(
                draft.gymIds.size <= 1000 &&
                    draft.gymIds.all(::uuid) &&
                    draft.gymIds == draft.gymIds.distinct().sorted()
            )
            require(
                draft.exercises.size in 1..30 &&
                    draft.exercises.map { it.exerciseId }.distinct().size == draft.exercises.size
            )
            require(draft.timeZoneId.length in 1..255)
            val zone = ZoneId.of(draft.timeZoneId)
            require(zone.id == draft.timeZoneId)
            val date = Instant.ofEpochMilli(draft.startsAtMillis).atZone(zone).toLocalDate()
            require(
                draft.startsAtMillis >= 0 &&
                    date >= LocalDate.of(1970, 1, 1) &&
                    date <= LocalDate.of(2100, 12, 31)
            )
            draft.exercises.forEach { exercise ->
              require(
                  uuid(exercise.exerciseId) &&
                      (exercise.restSeconds == null || exercise.restSeconds in 0..86400)
              )
              require(exercise.plannedSets.size in 1..20)
              exercise.plannedSets.forEach { set ->
                fun Double?.bounded(min: Double = 0.0) =
                    this == null || (isFinite() && this in min..1_000_000.0)
                require(
                    set.weightKg.bounded() &&
                        set.speedKmh.bounded() &&
                        set.inclinePct.bounded(-100.0)
                )
                require(set.reps == null || set.reps in 1..1_000_000)
                require(set.durationSec == null || set.durationSec in 1..1_000_000)
                // Exact canonical exercise type is revalidated by the server; reject mixed
                // representations here.
                require(
                    if (set.reps != null)
                        set.durationSec == null && set.speedKmh == null && set.inclinePct == null
                    else set.durationSec != null && set.weightKg == null
                )
              }
            }
            true
          }
          .getOrDefault(false)

  fun valid(proposal: TrainingProposal): Boolean =
      uuid(proposal.proposalId) &&
          uuid(proposal.recipientId) &&
          proposal.source == ProposalSource.AI &&
          proposal.author.kind == ProposalSource.AI &&
          proposal.author.accountId == null &&
          proposal.currentVersion > 0 &&
          proposal.currentVersion == proposal.snapshot.version &&
          proposal.createdAt >= 0 &&
          proposal.updatedAt >= proposal.createdAt &&
          proposal.expiresAt >= proposal.createdAt &&
          proposal.snapshot.createdAt >= 0 &&
          proposal.snapshot.ownerRevision >= 0 &&
          proposal.snapshot.catalogRevision >= 0 &&
          validDraft(proposal.snapshot.draft)

  fun valid(result: AcceptedProposalResult): Boolean =
      uuid(result.proposalId) &&
          uuid(result.routineId) &&
          uuid(result.calendarPlanId) &&
          result.version > 0 &&
          result.revision > 0 &&
          result.approvedAt >= 0
}
