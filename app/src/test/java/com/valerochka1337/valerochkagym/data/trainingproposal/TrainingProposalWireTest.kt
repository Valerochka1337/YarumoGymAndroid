package com.valerochka1337.valerochkagym.data.trainingproposal

import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TrainingProposalWireTest {
  private val fixture =
      Json.parseToJsonElement(
              requireNotNull(
                      javaClass.classLoader.getResourceAsStream("training-proposals-contract.json")
                  )
                  .bufferedReader()
                  .use { it.readText() }
          )
          .jsonObject

  @Test
  fun `first send matches every frozen JVM byte and hash vector`() {
    fixture.getValue("canonicalApprovalRequest").jsonObject.getValue("vectors").jsonArray.forEach {
        vector ->
      val expected = vector.jsonObject.getValue("utf8").jsonPrimitive.content
      val request = ProposalWire.decode<ApprovalRequest>(expected.encodeToByteArray())
      val actual = ProposalWire.canonical(request)
      assertArrayEquals(expected.encodeToByteArray(), actual)
      assertEquals(
          vector.jsonObject.getValue("sha256").jsonPrimitive.content,
          MessageDigest.getInstance("SHA-256").digest(actual).joinToString("") {
            "%02x".format(it)
          },
      )
    }
  }

  @Test
  fun `normalization keeps explicit weights and original unicode while sorting gyms and positive zero`() {
    val request = vector(1)
    val exercise = request.draft.exercises.single()
    val set = exercise.plannedSets.single().copy(speedKmh = -0.0)
    val modified =
        request.copy(
            draft =
                request.draft.copy(
                    name = "  ${request.draft.name}  ",
                    gymIds = request.draft.gymIds.reversed(),
                    exercises = listOf(exercise.copy(plannedSets = listOf(set))),
                )
        )
    assertArrayEquals(ProposalWire.canonical(request), ProposalWire.canonical(modified))
    val strength = vector(2)
    assertEquals(
        1_000_000.0,
        ProposalWire.decode<ApprovalRequest>(ProposalWire.canonical(strength))
            .draft
            .exercises
            .single()
            .plannedSets
            .single()
            .weightKg,
    )
  }

  @Test
  fun `strict response rejects duplicates missing fields extra fields and malformed UTF8`() {
    val valid =
        """{"proposalId":"11111111-1111-4111-8111-111111111111","version":1,"routineId":"22222222-2222-4222-8222-222222222222","calendarPlanId":"33333333-3333-4333-8333-333333333333","revision":2,"approvedAt":1}"""
    assertTrue(
        ProposalWire.valid(ProposalWire.decode<AcceptedProposalResult>(valid.encodeToByteArray()))
    )
    listOf(
            valid.replace("\"version\":1", "\"version\":\"1\""),
            valid.replace("\"version\":1", "\"version\":1,\"version\":2"),
            valid.replace("\"approvedAt\":1", "\"extra\":1"),
            valid.dropLast(1) + ",\"extra\":1}",
            valid + "{}",
        )
        .forEach {
          assertTrue(
              runCatching { ProposalWire.decode<AcceptedProposalResult>(it.encodeToByteArray()) }
                  .isFailure
          )
        }
    assertTrue(
        runCatching {
              ProposalWire.decode<AcceptedProposalResult>(byteArrayOf(0xc3.toByte(), 0x28))
            }
            .isFailure
    )
  }

  @Test
  fun `draft bounds reject mixed set types nonfinite values duplicate exercises and invalid zones`() {
    val draft = vector(0).draft
    val exercise = draft.exercises.single()
    val set = exercise.plannedSets.single()
    assertTrue(ProposalWire.validDraft(draft))
    listOf(
            draft.copy(exercises = listOf(exercise, exercise)),
            draft.copy(timeZoneId = "not-a-zone"),
            draft.copy(
                exercises = listOf(exercise.copy(plannedSets = listOf(set.copy(durationSec = 3))))
            ),
            draft.copy(
                exercises =
                    listOf(exercise.copy(plannedSets = listOf(set.copy(weightKg = Double.NaN))))
            ),
        )
        .forEach { assertFalse(ProposalWire.validDraft(it)) }
  }

  @Test
  fun `personal AI proposal rejects a human author and retired source`() {
    val personal =
        TrainingProposal(
            proposalId = "11111111-1111-4111-8111-111111111111",
            author = ProposalAuthor(ProposalSource.AI, null),
            recipientId = "22222222-2222-4222-8222-222222222222",
            source = ProposalSource.AI,
            status = ProposalStatus.PENDING,
            currentVersion = 1,
            createdAt = 1,
            updatedAt = 1,
            expiresAt = 2,
            snapshot = ProposalSnapshot(1, vector(0).draft, 0, 0, 1),
        )

    assertTrue(ProposalWire.valid(personal))
    assertFalse(
        ProposalWire.valid(
            personal.copy(
                author = ProposalAuthor(ProposalSource.AI, "33333333-3333-4333-8333-333333333333")
            )
        )
    )
    val retired =
        ProposalWire.json
            .encodeToString(personal)
            .replace("\"source\":\"AI\"", "\"source\":\"COACH\"")
    assertTrue(
        runCatching { ProposalWire.decode<TrainingProposal>(retired.encodeToByteArray()) }.isFailure
    )
  }

  private fun vector(index: Int): ApprovalRequest =
      ProposalWire.decode(
          fixture
              .getValue("canonicalApprovalRequest")
              .jsonObject
              .getValue("vectors")
              .jsonArray[index]
              .jsonObject
              .getValue("utf8")
              .jsonPrimitive
              .content
              .encodeToByteArray()
      )
}
