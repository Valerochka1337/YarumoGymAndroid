package com.valerochka1337.valerochkagym.data.trainingproposal

import com.valerochka1337.valerochkagym.data.backend.BackendException
import com.valerochka1337.valerochkagym.data.backend.BackendSessionSnapshot
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendTransport
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString

@Singleton
class TrainingProposalApi
@Inject
constructor(
    private val api: BackendTransport,
    private val sessions: BackendSessionStore,
) {
  suspend fun list(session: BackendSessionSnapshot, cursor: String? = null): ProposalListResponse {
    require(cursor == null || cursor.length in 1..4096)
    val path =
        ROOT +
            "?limit=50" +
            (cursor?.let { "&cursor=${URLEncoder.encode(it, UTF_8.name())}" } ?: "")
    val result = ProposalWire.decode<ProposalListResponse>(request(session, "GET", path))
    require(
        result.items.size <= 50 &&
            result.items.map { it.proposalId }.distinct().size == result.items.size
    )
    require(result.nextCursor == null || result.nextCursor.length in 1..4096)
    require(result.items.all { ProposalWire.valid(it) && it.recipientId == session.tokens.userId })
    return result
  }

  suspend fun detail(session: BackendSessionSnapshot, proposalId: String): TrainingProposal {
    val result = ProposalWire.decode<TrainingProposal>(request(session, "GET", path(proposalId)))
    require(
        ProposalWire.valid(result) &&
            result.proposalId == proposalId &&
            result.recipientId == session.tokens.userId
    )
    return result
  }

  suspend fun explanation(
      session: BackendSessionSnapshot,
      proposal: TrainingProposal,
  ): PlannerExplanation {
    val result =
        ProposalWire.decode<PlannerExplanation>(
            request(session, "GET", path(proposal.proposalId) + "/planner-explanation")
        )
    require(result.validFor(proposal))
    return result
  }

  /** Only accepts caller's already journalled bytes. No encode/rebuild happens in this path. */
  suspend fun approve(
      session: BackendSessionSnapshot,
      proposalId: String,
      bytes: ByteArray,
  ): AcceptedProposalResult {
    require(bytes.size in 1..ProposalWire.REQUEST_LIMIT)
    val bound = ProposalWire.decode<ApprovalRequest>(bytes)
    require(
        ProposalWire.uuid(bound.operationId) &&
            bound.version > 0 &&
            ProposalWire.validDraft(bound.draft)
    )
    val result =
        ProposalWire.decode<AcceptedProposalResult>(
            request(session, "POST", path(proposalId) + "/approve", bytes)
        )
    require(
        ProposalWire.valid(result) &&
            result.proposalId == proposalId &&
            result.version == bound.version
    )
    return result
  }

  suspend fun acceptedResult(
      session: BackendSessionSnapshot,
      proposalId: String,
  ): AcceptedProposalResult {
    val result =
        ProposalWire.decode<AcceptedProposalResult>(
            request(session, "GET", path(proposalId) + "/accepted-result")
        )
    require(ProposalWire.valid(result) && result.proposalId == proposalId)
    return result
  }

  suspend fun reject(
      session: BackendSessionSnapshot,
      proposalId: String,
      version: Int,
      reason: String? = null,
  ): ProposalDecision {
    require(version > 0 && (reason == null || reason.codePointCount(0, reason.length) <= 500))
    val bytes =
        ProposalWire.json.encodeToString(ProposalRejectRequest(version, reason)).encodeToByteArray()
    val result =
        ProposalWire.decode<ProposalDecision>(
            request(session, "POST", path(proposalId) + "/reject", bytes)
        )
    require(
        result.proposalId == proposalId &&
            result.version == version &&
            result.status == ProposalStatus.REJECTED &&
            result.updatedAt >= 0
    )
    return result
  }

  private fun path(id: String): String {
    require(ProposalWire.uuid(id))
    return "$ROOT/$id"
  }

  private suspend fun request(
      session: BackendSessionSnapshot,
      method: String,
      path: String,
      bytes: ByteArray = ByteArray(0),
  ): ByteArray {
    assertSession(session)
    val response =
        api.authorizedRawResponse(
            method = method,
            path = path,
            rawBody = bytes,
            headers = mapOf("X-Gym-Capabilities" to "calendar-plans"),
            expectedOwner = session.tokens.userId,
            expectedSessionEpoch = session.epoch,
            retryOnUnauthorized = method == "GET",
            maxResponseBytes = ProposalWire.RESPONSE_LIMIT,
        )
    assertSession(session)
    if (response.owner != session.tokens.userId || response.sessionEpoch != session.epoch)
        throw BackendException(401, "owner_changed", "Аккаунт изменился")
    return response.rawBody
  }

  private fun assertSession(expected: BackendSessionSnapshot) {
    val current = sessions.snapshot()
    if (current?.tokens?.userId != expected.tokens.userId || current.epoch != expected.epoch)
        throw BackendException(401, "owner_changed", "Аккаунт изменился")
  }

  companion object {
    const val ROOT = "/training-proposals"
  }
}
