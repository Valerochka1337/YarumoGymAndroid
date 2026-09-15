package com.valerochka1337.valerochkagym.ui.routineshare

import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.backend.BackendException
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendTokens
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithCount
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithExercises
import com.valerochka1337.valerochkagym.data.routineshare.CreatedRoutineShare
import com.valerochka1337.valerochkagym.data.routineshare.ImportedRoutineShare
import com.valerochka1337.valerochkagym.data.routineshare.RoutineShareDataSource
import com.valerochka1337.valerochkagym.data.routineshare.RoutineShareLink
import com.valerochka1337.valerochkagym.data.routineshare.RoutineSharePreview
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoutineShareViewModelTest {
  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  @Test
  fun `login resumes the saved import operation for the same link`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val token = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-ABCDE"
        val operation = "00000000-0000-0000-0000-000000000012"
        val routineSyncId = "00000000-0000-0000-0000-000000000011"
        val source = FakeShareSource(routineSyncId)
        val sessions = FakeSessions()
        val handle =
            SavedStateHandle(
                mapOf(
                    GymRoutes.ROUTINE_SHARE_TOKEN to token,
                    "import_operation" to operation,
                )
            )
        val viewModel =
            RoutineShareViewModel(handle, FakeRoutineDao(routineSyncId), source, sessions)
        runCurrent()

        viewModel.import()
        assertTrue(viewModel.previewState.value.signInRequested)
        assertTrue(source.importOperations.isEmpty())

        sessions.signIn()
        viewModel.resumeAfterSignIn()
        advanceUntilIdle()

        assertEquals(listOf(token to operation), source.importOperations)
        assertFalse(viewModel.previewState.value.signInRequested)
        assertEquals(null, viewModel.previewState.value.error)
        assertEquals(5L, viewModel.previewState.value.importedRoutineId)
      }

  @Test
  fun `late preview response preserves imported routine state`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val token = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-ABCDE"
        val routineSyncId = "00000000-0000-0000-0000-000000000011"
        val previewGate = CompletableDeferred<Unit>()
        val source = FakeShareSource(routineSyncId, previewGate)
        val sessions = FakeSessions().apply { signIn() }
        val viewModel =
            RoutineShareViewModel(
                SavedStateHandle(mapOf(GymRoutes.ROUTINE_SHARE_TOKEN to token)),
                FakeRoutineDao(routineSyncId),
                source,
                sessions,
            )
        runCurrent()

        viewModel.import()
        advanceUntilIdle()
        assertEquals(5L, viewModel.previewState.value.importedRoutineId)
        previewGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(5L, viewModel.previewState.value.importedRoutineId)
      }

  @Test
  fun `stale create context is replaced after recovery returns no links`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val routineSyncId = "00000000-0000-0000-0000-000000000011"
        val operation = "00000000-0000-0000-0000-000000000012"
        val source =
            FakeShareSource(routineSyncId).apply {
              createFailure = BackendException(409, "routine_share_stale", "Данные изменились")
              listResponses.addLast(emptyList())
              listResponses.addLast(emptyList())
            }
        val handle =
            SavedStateHandle(
                mapOf(
                    GymRoutes.ROUTINE_ID_ARG to 9L,
                    "create_operation" to operation,
                    "create_expected_revision" to 17L,
                    "create_catalog_revision" to 23L,
                )
            )
        val viewModel =
            RoutineShareViewModel(
                handle,
                FakeRoutineDao(routineSyncId, ownerId = 9L),
                source,
                FakeSessions(),
            )
        advanceUntilIdle()

        viewModel.create()
        advanceUntilIdle()

        assertEquals(listOf(operation), source.createOperations)
        assertNotEquals(operation, handle.get<String>("create_operation"))
        assertNull(handle.get<Long>("create_expected_revision"))
        assertNull(handle.get<Long>("create_catalog_revision"))
      }

  @Test
  fun `ambiguous create failure keeps operation when recovery finds an unrelated link`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val routineSyncId = "00000000-0000-0000-0000-000000000011"
        val operation = "00000000-0000-0000-0000-000000000012"
        val unrelated =
            RoutineShareLink(
                shareId = "00000000-0000-0000-0000-000000000013",
                routineId = routineSyncId,
                url =
                    "https://api.valerochkagym.tech/r/AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-ABCDE",
                createdAt = 1L,
                active = true,
            )
        val source =
            FakeShareSource(routineSyncId).apply {
              createFailure = java.io.IOException("Нет сети")
              listResponses.addLast(emptyList())
              listResponses.addLast(listOf(unrelated))
            }
        val handle =
            SavedStateHandle(
                mapOf(
                    GymRoutes.ROUTINE_ID_ARG to 9L,
                    "create_operation" to operation,
                    "create_expected_revision" to 17L,
                    "create_catalog_revision" to 23L,
                )
            )
        val viewModel =
            RoutineShareViewModel(
                handle,
                FakeRoutineDao(routineSyncId, ownerId = 9L),
                source,
                FakeSessions(),
            )
        advanceUntilIdle()

        viewModel.create()
        advanceUntilIdle()

        assertEquals(listOf(operation), source.createOperations)
        assertEquals(operation, handle.get<String>("create_operation"))
        assertEquals(17L, handle.get<Long>("create_expected_revision"))
        assertEquals(23L, handle.get<Long>("create_catalog_revision"))
        assertEquals(listOf(unrelated), viewModel.ownerState.value.links)
      }

  private class FakeSessions : BackendSessionStore {
    override val session = MutableStateFlow<BackendTokens?>(null)
    private var epoch = 0L
    override val sessionEpoch: Long
      get() = epoch

    override fun save(tokens: BackendTokens?) {
      epoch++
      session.value = tokens
    }

    fun signIn() = save(BackendTokens("user-a", "a@example.com", "access", "refresh"))
  }

  private class FakeShareSource(
      private val importedRoutineId: String,
      private val previewGate: CompletableDeferred<Unit>? = null,
  ) : RoutineShareDataSource {
    val importOperations = mutableListOf<Pair<String, String>>()
    val createOperations = mutableListOf<String>()
    val listResponses = ArrayDeque<List<RoutineShareLink>>()
    var createFailure: Exception? = null

    override suspend fun create(
        routineId: String,
        operationId: String,
        expectedRevision: Long?,
        catalogRevision: Long?,
        onRequestPrepared: (Long, Long) -> Unit,
    ): CreatedRoutineShare {
      createOperations += operationId
      onRequestPrepared(expectedRevision ?: 1L, catalogRevision ?: 1L)
      createFailure?.let { throw it }
      return CreatedRoutineShare(
          shareId = "00000000-0000-0000-0000-000000000013",
          url = "https://api.valerochkagym.tech/r/AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-ABCDE",
          routineId = routineId,
          createdAt = 1L,
      )
    }

    override suspend fun list(routineId: String): List<RoutineShareLink> =
        if (listResponses.isEmpty()) emptyList() else listResponses.removeFirst()

    override suspend fun revoke(shareId: String, operationId: String) = Unit

    override suspend fun preview(token: String): RoutineSharePreview {
      previewGate?.await()
      return RoutineSharePreview("Ноги", 90, emptyList())
    }

    override suspend fun import(token: String, operationId: String): ImportedRoutineShare {
      importOperations += token to operationId
      return ImportedRoutineShare(importedRoutineId, 1, 1, alreadyImported = false)
    }
  }

  private class FakeRoutineDao(
      private val importedSyncId: String,
      private val ownerId: Long? = null,
  ) : RoutineDao {
    override fun observeRoutinesWithCount(): Flow<List<RoutineWithCount>> = flowOf(emptyList())

    override fun observeRoutinesFull(): Flow<List<RoutineWithExercises>> = flowOf(emptyList())

    override suspend fun getRoutineWithExercises(id: Long): RoutineWithExercises? =
        ownerId
            ?.takeIf { it == id }
            ?.let {
              RoutineWithExercises(
                  routine = RoutineEntity(id = it, syncId = importedSyncId, name = "Моя программа"),
                  exercises = emptyList(),
              )
            }

    override suspend fun getRoutineName(id: Long): String? = null

    override suspend fun getRoutineBySyncId(syncId: String): RoutineEntity? =
        RoutineEntity(id = 5, syncId = importedSyncId, name = "Моя копия").takeIf {
          syncId == importedSyncId
        }

    override suspend fun upsertRoutine(routine: RoutineEntity): Long = routine.id

    override suspend fun deleteRoutine(id: Long) = Unit

    override suspend fun insertRoutineExercises(
        routineExercises: List<RoutineExerciseEntity>
    ): List<Long> = emptyList()

    override suspend fun deleteRoutineExercises(routineId: Long) = Unit
  }
}
