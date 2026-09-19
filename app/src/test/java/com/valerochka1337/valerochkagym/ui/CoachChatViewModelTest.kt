package com.valerochka1337.valerochkagym.ui

import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.ai.CoachRunsClient
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendTokens
import com.valerochka1337.valerochkagym.data.backend.BackendTransport
import com.valerochka1337.valerochkagym.data.db.entity.CoachProposalEntity
import com.valerochka1337.valerochkagym.data.db.entity.CoachSessionContextEntity
import com.valerochka1337.valerochkagym.domain.CoachWorkoutReader
import com.valerochka1337.valerochkagym.domain.WorkoutChangeSet
import com.valerochka1337.valerochkagym.domain.WorkoutEditor
import com.valerochka1337.valerochkagym.domain.WorkoutWriteQueue
import com.valerochka1337.valerochkagym.service.CoachConversationService
import com.valerochka1337.valerochkagym.service.CoachDraft
import com.valerochka1337.valerochkagym.service.DurableCoachCoordinator
import com.valerochka1337.valerochkagym.service.RestTimerEngine
import com.valerochka1337.valerochkagym.service.WallClock
import com.valerochka1337.valerochkagym.ui.coach.CoachChatViewModel
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CoachChatViewModelTest : RoomDaoTest() {
  @get:Rule val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

  @Test
  fun `new message stays highlighted after marking read but not after reopening`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val workout = insertWorkout("unread")
        val message =
            com.valerochka1337.valerochkagym.data.db.entity.CoachMessageEntity(
                "first",
                "user",
                workout,
                "assistant",
                "Продолжай",
                1000,
            )
        db.coachDao().saveMessage(message)
        val vm = viewModel(workout)
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
          vm.uiState.collect()
        }
        vm.uiState.first { it.messages.any { message -> message.unread } }
        vm.markAssistantMessagesRead()
        // Arrives after the captured read snapshot; the pending write must not mark it.
        db.coachDao().saveMessage(message.copy(id = "second", createdAt = 2000))
        vm.uiState.first { it.messages.any { message -> message.id == "first" && !message.unread } }
        assertTrue(vm.uiState.value.messages.first { it.id == "first" }.isNew)
        assertEquals(null, db.coachDao().messages(workout).first { it.id == "second" }.readAt)
        vm.uiState.first { it.messages.any { message -> message.id == "second" } }
        vm.markAssistantMessagesRead()
        vm.uiState.first { it.messages.all { message -> !message.unread } }
        assertTrue(vm.uiState.value.messages.all { it.isNew })
        vm.clearNewMessageHighlights()
        vm.uiState.first { it.messages.all { message -> !message.isNew } }
        val reopened = viewModel(workout)
        assertTrue(reopened.uiState.first { it.messages.size == 2 }.messages.none { it.isNew })
      }

  @Test
  fun `reopened view model receives draft and deduplicates the committed answer`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val workout = insertWorkout("stream")
        db.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO backend_state (id, owner, generation, phase, initialMergeAcknowledged) VALUES (1, 'user', 0, 'OWNED', 1)"
        )
        lateinit var remote: DurableCoachCoordinator
        val service = conversation { remote = it }
        val first = viewModel(workout, service)
        val collector =
            backgroundScope.launch(
                kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)
            ) {
              first.uiState.collect()
            }
        first.uiState.first { !it.readOnly }
        remote.running.value = setOf(workout)
        remote.drafts.value =
            mapOf(workout to CoachDraft("answer", "request", workout, "user", 0, "Продолжай"))
        val streamed =
            first.uiState.first { it.messages.lastOrNull()?.streaming == true }.messages.last()
        collector.cancel()
        val reopened = viewModel(workout, service)
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
          reopened.uiState.collect()
        }
        val restored = reopened.uiState.first { it.messages.lastOrNull()?.streaming == true }
        assertEquals(streamed.id, restored.messages.last().id)
        assertEquals("Продолжай", restored.messages.last().text)
        db.coachDao()
            .saveMessage(
                com.valerochka1337.valerochkagym.data.db.entity.CoachMessageEntity(
                    "answer",
                    "user",
                    workout,
                    "assistant",
                    "Продолжай",
                    1000,
                )
            )
        remote.drafts.value = emptyMap()
        remote.running.value = emptySet()
        val completed =
            reopened.uiState.first { !it.busy && it.messages.lastOrNull()?.streaming == false }
        assertEquals(1, completed.messages.count { it.id == streamed.id })
        assertEquals("Продолжай", completed.messages.last().text)
      }

  @Test
  fun `finished workout changes an open chat to read only`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val workoutId = insertWorkout("finished")
        val viewModel = viewModel(workoutId)
        assertFalse(viewModel.uiState.first { !it.readOnly }.readOnly)

        db.workoutDao().setFinishedAt(workoutId, 123L)
        assertTrue(viewModel.uiState.first { it.readOnly }.readOnly)
      }

  @Test
  fun `missing workout route stays read only before a foreground service can start`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel = viewModel("missing")

        assertTrue(viewModel.uiState.first { it.readOnly }.readOnly)
      }

  @Test
  fun `pending proposal and undo state restore from Room`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val workoutId = insertWorkout("proposal")
        val packet =
            WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.SetAvailableTime(20)))
        db.coachDao()
            .saveContext(CoachSessionContextEntity(workoutId, "user", lastUndoRevision = 4L))
        db.coachDao()
            .saveProposal(
                CoachProposalEntity(
                    id = "proposal",
                    accountId = "user",
                    workoutId = workoutId,
                    baseRevision = 0,
                    beforeSummary = "Было",
                    afterSummary = "Станет",
                    packetJson = Json.encodeToString(WorkoutChangeSet.Packet.serializer(), packet),
                    expiresAt = Long.MAX_VALUE,
                )
            )
        val viewModel = viewModel(workoutId)
        val state = viewModel.uiState.first { it.proposal != null && it.canUndo }
        assertEquals("proposal", state.proposal?.id)
        assertEquals("Было", state.proposal?.before)
        assertTrue(state.canUndo)
      }

  @Test
  fun `double confirmation accepts only one action while the first is busy`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val workoutId = insertWorkout("confirm")
        db.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO backend_state (id, owner, generation, phase, initialMergeAcknowledged) VALUES (1, 'user', 0, 'OWNED', 1)",
        )
        val packet =
            WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.SetAvailableTime(20)))
        db.coachDao()
            .saveProposal(
                CoachProposalEntity(
                    id = "proposal",
                    accountId = "user",
                    workoutId = workoutId,
                    baseRevision = 0,
                    beforeSummary = "Было",
                    afterSummary = "Станет",
                    packetJson = Json.encodeToString(WorkoutChangeSet.Packet.serializer(), packet),
                    expiresAt = Long.MAX_VALUE,
                )
            )
        val viewModel = viewModel(workoutId)
        viewModel.uiState.first { it.proposal?.id == "proposal" }

        viewModel.confirm("proposal")
        viewModel.confirm("proposal")
        db.coachDao().observePendingProposal(workoutId).first { it == null }
        assertEquals(
            "CONFIRMED",
            db.coachDao().pendingProposalForId("proposal")?.let { "PENDING" } ?: "CONFIRMED",
        )
        assertEquals(1, db.coachDao().messages(workoutId).count { it.role == "system" })
      }

  @Test
  fun `contextual replies restore from Room and disappear after a newer user message`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val workout = insertWorkout("replies")
        db.coachDao()
            .saveMessage(
                com.valerochka1337.valerochkagym.data.db.entity.CoachMessageEntity(
                    "answer",
                    "user",
                    workout,
                    "assistant",
                    "Заменить жим?",
                    1L,
                    quickRepliesJson = """["Да, замени","Нет"]""",
                )
            )
        val model = viewModel(workout)
        val restored = model.uiState.first { it.messages.isNotEmpty() }
        assertEquals(listOf("Да, замени", "Нет"), restored.quickReplies)
        db.coachDao()
            .saveMessage(
                com.valerochka1337.valerochkagym.data.db.entity.CoachMessageEntity(
                    "reply",
                    "user",
                    workout,
                    "user",
                    "Нет",
                    2L,
                )
            )
        assertTrue(model.uiState.first { it.messages.size == 2 }.quickReplies.isEmpty())
      }

  private fun TestScope.viewModel(
      workoutId: String,
      service: CoachConversationService = conversation(),
  ): CoachChatViewModel =
      CoachChatViewModel(
          SavedStateHandle(mapOf(GymRoutes.WORKOUT_ID_ARG to workoutId)),
          db.coachDao(),
          db.workoutDao(),
          service,
          com.valerochka1337.valerochkagym.service.CoachAlertNotifier(
              androidx.test.core.app.ApplicationProvider.getApplicationContext()
          ),
      )

  private fun TestScope.conversation(
      configure: (DurableCoachCoordinator) -> Unit = {}
  ): CoachConversationService {
    val timer = RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime })
    val coordinator =
        WorkoutEditor(
            db,
            db.workoutDao(),
            db.coachDao(),
            timer,
            session,
            WorkoutWriteQueue(),
        )
    val control = CoachWorkoutReader(db, timer, session)
    val remote =
        DurableCoachCoordinator(CoachRunsClient(UnusedTransport), db, control, coordinator, session)
    configure(remote)
    return CoachConversationService(remote, control, coordinator, db, session)
  }

  private val session = FakeSession()

  private class FakeSession : BackendSessionStore {
    private val state =
        MutableStateFlow<BackendTokens?>(
            BackendTokens("user", "user@example.com", "access", "refresh")
        )
    override val session: StateFlow<BackendTokens?> = state

    override fun save(tokens: BackendTokens?) {
      state.value = tokens
    }
  }

  private object UnusedTransport : BackendTransport {
    override val json = Json

    override suspend fun public(
        method: String,
        path: String,
        body: kotlinx.serialization.json.JsonElement?,
    ): kotlinx.serialization.json.JsonElement = error("Unexpected network call")

    override suspend fun authorized(
        method: String,
        path: String,
        body: kotlinx.serialization.json.JsonElement?,
    ): kotlinx.serialization.json.JsonElement = error("Unexpected network call")
  }
}
