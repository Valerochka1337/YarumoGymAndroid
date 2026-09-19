package com.valerochka1337.valerochkagym.data.backend

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class BackendTokens(
    val userId: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int = 900,
)

data class GuestTransferState(
    val phase: GuestSyncPhase,
    val owner: String?,
    val mergeId: String?,
    val initialMergeAcknowledged: Boolean,
)

data class GuestPreservationFootprint(
    val records: Int,
    val baselineRecords: Int,
    val hasOutbox: Boolean,
    val hasCatalogJournal: Boolean,
    val tombstones: Int,
    val fingerprint: String,
)

sealed interface GuestClaimResult {
  data class Claimed(val state: GuestTransferState) : GuestClaimResult

  data class Blocked(val footprint: GuestPreservationFootprint) : GuestClaimResult
}

@Serializable
data class CloudRecord(
    val kind: String,
    val id: String,
    val revision: Long,
    val deleted: Boolean = false,
    val payload: JsonObject? = null,
) {
  val key: String
    get() = "$kind:$id"
}

@Serializable data class CloudSnapshot(val revision: Long, val records: List<CloudRecord>)

@Serializable
data class CloudChange(
    val kind: String,
    val id: String,
    val baseRevision: Long,
    val deleted: Boolean = false,
    val payload: JsonObject? = null,
)

@Serializable
data class CloudPush(
    val operationId: String,
    val changes: List<CloudChange>,
    val catalogRevision: Long? = null,
)

@Serializable data class CloudAck(val revision: Long)

@Serializable
data class BackendSession(
    val id: String,
    val deviceName: String,
    val createdAt: String,
    val current: Boolean,
)

@Serializable
data class CoachJournalEntry(
    val id: String,
    val workoutId: String,
    val deviceId: String,
    val createdAt: Long,
    val payload: JsonObject,
)

@Serializable data class CoachJournalPush(val entries: List<CoachJournalEntry>)

@Serializable data class CoachJournalAck(val accepted: Int)

@Serializable
data class CoachJournalPage(
    val entries: List<CoachJournalEntry>,
    val nextCursor: String? = null,
    val watermark: Long,
)

class BackendException(
    val status: Int,
    val code: String,
    override val message: String,
    internal val fromHttpResponse: Boolean = false,
) : Exception(message)

/** Three-way comparison preserves unrelated remote changes and refuses silent overwrites. */
object CloudMerge {
  fun conflicts(
      local: Map<String, JsonObject>,
      baseline: Map<String, CloudRecord>,
      remote: List<CloudRecord>,
  ): Set<String> =
      remote
          .filter { r ->
            val old = baseline[r.key]
            val localPayload = local[r.key]
            val previous = old?.payload
            val cloud = r.payload
            old?.revision != r.revision &&
                localPayload != previous &&
                localPayload != cloud &&
                // First login: installed built-ins defer to the existing account catalogue.
                !(old == null &&
                    r.kind == "exercise" &&
                    localPayload?.get("isCustom")?.toString() == "false")
          }
          .map { it.key }
          .toSet()
}
