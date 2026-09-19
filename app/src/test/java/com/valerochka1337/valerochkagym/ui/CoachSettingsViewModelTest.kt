package com.valerochka1337.valerochkagym.ui

import android.app.Activity
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.valerochka1337.valerochkagym.data.ai.*
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendTokens
import com.valerochka1337.valerochkagym.data.backup.ClearDataUseCase
import com.valerochka1337.valerochkagym.data.backup.DatabaseExporter
import com.valerochka1337.valerochkagym.data.backup.ExportResult
import com.valerochka1337.valerochkagym.data.google.*
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.ui.settings.SettingsViewModel
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import com.valerochka1337.valerochkagym.worker.UploadScheduler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CoachSettingsViewModelTest {
  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  @Test
  fun `selected model commits before its probe can run`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val sessions = FakeSessions("owner", 7)
        val store = DelayedStore()
        val settings = SettingsRepository(store)
        val catalog = FakeCatalog { owner, _ ->
          CoachModelCatalog(true, "$owner-default", listOf("$owner-default", "$owner-tool"))
        }
        val gateway = ProbeGateway()
        val viewModel =
            viewModel(
                settings,
                sessions,
                catalog,
                CoachModelProbe(CoachRunsClient(gateway), settings),
            )
        collect(viewModel)
        advanceUntilIdle()

        assertEquals(
            listOf("owner-default", "owner-tool"),
            viewModel.uiState.value.coachModel.models,
        )
        viewModel.selectCoachModel("owner-tool")
        viewModel.verifyCoachModel()
        runCurrent()

        assertEquals(0, gateway.calls.size)
        assertTrue(viewModel.uiState.value.coachModel.savingSelection)
        assertFalse(viewModel.uiState.value.coachModel.checking)
        store.release.complete(Unit)
        advanceUntilIdle()
        assertEquals("owner-tool", settings.coachModel("owner").first())
        assertEquals("owner-tool", viewModel.uiState.value.coachModel.selectedModel)
        assertFalse(viewModel.uiState.value.coachModel.savingSelection)

        viewModel.verifyCoachModel()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.coachModel.checking)
        assertTrue(viewModel.uiState.value.coachModel.status!!.isNotBlank())
        assertEquals(listOf("owner" to 7L), gateway.calls)
      }

  @Test
  fun `late model catalog cannot replace a newer account session`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val sessions = FakeSessions("first", 1)
        val first = CompletableDeferred<CoachModelCatalog>()
        val catalog = FakeCatalog { owner, _ ->
          if (owner == "first") first.await()
          else CoachModelCatalog(true, "second-default", listOf("second-default"))
        }
        val viewModel = viewModel(SettingsRepository(FakeStore()), sessions, catalog, null)
        collect(viewModel)
        runCurrent()

        sessions.set("second", 2)
        advanceUntilIdle()
        first.complete(CoachModelCatalog(true, "first-default", listOf("first-default")))
        advanceUntilIdle()

        assertEquals("second", viewModel.uiState.value.coachModel.catalogOwner)
        assertEquals(2L, viewModel.uiState.value.coachModel.catalogEpoch)
        assertEquals(listOf("second-default"), viewModel.uiState.value.coachModel.models)
      }

  @Test
  fun `in flight catalog cannot clear a model selected after its request started`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val old = CompletableDeferred<CoachModelCatalog>()
        val answers = ArrayDeque<suspend () -> CoachModelCatalog>()
        answers += { CoachModelCatalog(true, "default", listOf("default", "tool")) }
        answers += { old.await() }
        val sessions = FakeSessions("owner", 7)
        val settings = SettingsRepository(FakeStore())
        val viewModel =
            viewModel(settings, sessions, FakeCatalog { _, _ -> answers.removeFirst()() }, null)
        collect(viewModel)
        advanceUntilIdle()

        viewModel.refreshCoachModels()
        runCurrent()
        viewModel.selectCoachModel("tool")
        advanceUntilIdle()
        old.complete(CoachModelCatalog(true, "default", listOf("default")))
        advanceUntilIdle()

        assertEquals("tool", viewModel.uiState.value.coachModel.selectedModel)
        assertEquals("tool", settings.coachModel("owner").first())
      }

  private fun kotlinx.coroutines.test.TestScope.collect(viewModel: SettingsViewModel) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
  }

  private fun viewModel(
      settings: SettingsRepository,
      sessions: FakeSessions,
      catalog: CoachModelCatalogSource,
      probe: CoachModelProbe?,
  ) =
      SettingsViewModel(
          settingsRepository = settings,
          googleAuth = NoOpGoogleAuth,
          uploadScheduler = NoOpUploadScheduler,
          importRepository = NoOpImportRepository,
          databaseExporter = NoOpExporter,
          clearDataUseCase = NoOpClear,
          coachModels = catalog,
          coachProbe = probe,
          backendSessions = sessions,
      )

  private class FakeSessions(owner: String, epoch: Long) : BackendSessionStore {
    private val state = MutableStateFlow<BackendTokens?>(tokens(owner))
    override val session: StateFlow<BackendTokens?> = state
    override var sessionEpoch = epoch

    fun set(owner: String, epoch: Long) {
      sessionEpoch = epoch
      state.value = tokens(owner)
    }

    override fun save(tokens: BackendTokens?) {
      state.value = tokens
      sessionEpoch++
    }

    private fun tokens(owner: String) =
        BackendTokens(owner, "$owner@example.com", "access", "refresh")
  }

  private class FakeCatalog(private val answer: suspend (String, Long?) -> CoachModelCatalog) :
      CoachModelCatalogSource {
    override suspend fun catalog(expectedOwner: String, expectedSessionEpoch: Long?) =
        answer(expectedOwner, expectedSessionEpoch)
  }

  private class ProbeGateway : com.valerochka1337.valerochkagym.data.backend.BackendTransport {
    override val json = kotlinx.serialization.json.Json
    val calls = mutableListOf<Pair<String, Long?>>()

    override suspend fun public(
        method: String,
        path: String,
        body: kotlinx.serialization.json.JsonElement?,
    ): kotlinx.serialization.json.JsonElement = error("unused")

    override suspend fun authorized(
        method: String,
        path: String,
        body: kotlinx.serialization.json.JsonElement?,
    ): kotlinx.serialization.json.JsonElement = error("unused")

    override suspend fun authorizedRawResponse(
        method: String,
        path: String,
        rawBody: ByteArray,
        headers: Map<String, String>,
        expectedOwner: String?,
        expectedSessionEpoch: Long?,
        retryOnUnauthorized: Boolean,
        maxResponseBytes: Int?,
    ): com.valerochka1337.valerochkagym.data.backend.BackendResponse {
      calls += expectedOwner!! to expectedSessionEpoch
      return com.valerochka1337.valerochkagym.data.backend.BackendResponse(
          json.parseToJsonElement("""{"success":false,"message":"Проверка завершена"}"""),
          byteArrayOf(),
          emptySet(),
          expectedOwner,
          expectedSessionEpoch!!,
      )
    }
  }

  private class FakeStore : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(mutablePreferencesOf())
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
        transform(state.value).also { state.value = it }
  }

  private class DelayedStore : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(mutablePreferencesOf())
    val release = CompletableDeferred<Unit>()
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
        release.await().let { transform(state.value).also { state.value = it } }
  }

  private object NoOpGoogleAuth : GoogleAuth {
    override suspend fun selectAccount(activity: Activity) =
        Result.failure<String>(IllegalStateException())

    override suspend fun authorizeForAccount(activity: Activity, expectedEmail: String) =
        AuthorizeOutcome.Failed(null)

    override suspend fun revokeCalendarAccess(expectedEmail: String) =
        Result.failure<Unit>(IllegalStateException())

    override suspend fun signIn(activity: Activity) =
        Result.failure<String>(IllegalStateException())

    override suspend fun authorize(activity: Activity) = AuthorizeOutcome.Failed(null)

    override suspend fun getAccessToken() = TokenResult.Failed(null)

    override suspend fun getAccessTokenForAccount(expectedEmail: String) = TokenResult.Failed(null)

    override suspend fun signOut() = Unit
  }

  private object NoOpUploadScheduler : UploadScheduler {
    override fun schedule(workoutId: String) = Unit

    override suspend fun retry(workoutId: String) = Unit

    override suspend fun scheduleAllPending() = 0
  }

  private object NoOpImportRepository : WorkoutImportRepository {
    override suspend fun importAll() = ImportResult.NothingToImport
  }

  private object NoOpExporter : DatabaseExporter {
    override suspend fun export(target: Uri) = ExportResult.Success
  }

  private object NoOpClear : ClearDataUseCase {
    override suspend fun invoke() = Unit
  }
}

/** Completed-only fixture; streaming behavior uses explicit event fakes below. */
private interface CoachSettingsViewModelTestGateway :
    com.valerochka1337.valerochkagym.data.ai.CoachModelGateway {
  override suspend fun systemPrompt(expectedOwner: String, expectedSessionEpoch: Long?) =
      "Server coach prompt"

  suspend fun complete(
      expectedOwner: String,
      expectedSessionEpoch: Long?,
      messages: List<com.valerochka1337.valerochkagym.data.ai.AiApiMessage>,
      tools: List<com.valerochka1337.valerochkagym.data.ai.AiApiTool>,
  ): com.valerochka1337.valerochkagym.data.ai.AiApiChatResponse

  override fun stream(
      expectedOwner: String,
      expectedSessionEpoch: Long?,
      messages: List<com.valerochka1337.valerochkagym.data.ai.AiApiMessage>,
      tools: List<com.valerochka1337.valerochkagym.data.ai.AiApiTool>,
  ) =
      kotlinx.coroutines.flow.flow {
        emit(
            com.valerochka1337.valerochkagym.data.ai.CoachModelEvent.Completed(
                complete(expectedOwner, expectedSessionEpoch, messages, tools)
            )
        )
      }
}
