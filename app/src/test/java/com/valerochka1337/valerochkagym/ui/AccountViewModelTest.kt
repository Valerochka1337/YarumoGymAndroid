package com.valerochka1337.valerochkagym.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.backend.*
import com.valerochka1337.valerochkagym.ui.account.AccountViewModel
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountViewModelTest : RoomDaoTest() {
  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private class Store : BackendSessionStore {
    override val session = MutableStateFlow<BackendTokens?>(null)
    val firstSave = CompletableDeferred<BackendTokens?>()

    override fun save(tokens: BackendTokens?) {
      session.value = tokens
      firstSave.complete(tokens)
    }
  }

  private class Api : BackendTransport {
    override val json = Json
    var failure: BackendException? = null
    val calls = mutableListOf<String>()

    override suspend fun public(method: String, path: String, body: JsonElement?): JsonElement {
      calls += path
      failure?.let { throw it }
      return buildJsonObject {}
    }

    override suspend fun authorized(method: String, path: String, body: JsonElement?): JsonElement =
        error("Unexpected authorized request")
  }

  private fun model(api: Api): AccountViewModel {
    val store = Store()
    return AccountViewModel(
        api,
        store,
        BackendSync(db, api, store),
        BackendSyncScheduler(ApplicationProvider.getApplicationContext<Context>(), db),
    )
  }

  @Test
  fun `successful registration advances to email verification`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val api = Api()
        val vm = model(api)
        vm.showMode("register")
        vm.submit("register", "test@example.com", "long-password", "")
        advanceUntilIdle()
        assertEquals("verify", vm.mode.value)
        assertEquals(listOf("/auth/register"), api.calls)
        assertFalse(vm.busy.value)
      }

  @Test
  fun `mail failure keeps registration available for retry`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val api =
            Api().apply { failure = BackendException(503, "mail_unavailable", "Повторите позже") }
        val vm = model(api)
        vm.showMode("register")
        vm.submit("register", "test@example.com", "long-password", "")
        advanceUntilIdle()
        assertEquals("register", vm.mode.value)
        assertEquals("Повторите позже", vm.message.value)
        assertFalse(vm.busy.value)
      }

  @Test
  fun `unverified login opens the code form`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val api =
            Api().apply { failure = BackendException(403, "email_unverified", "Подтвердите email") }
        val vm = model(api)
        vm.submit("login", "test@example.com", "long-password", "")
        advanceUntilIdle()
        assertEquals("verify", vm.mode.value)
        assertNull(vm.session.value)
      }

  @Test
  fun `password recovery advances to reset and then back to login`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val vm = model(Api())
        vm.showMode("request-reset")
        vm.submit("request-reset", "test@example.com", "", "")
        advanceUntilIdle()
        assertEquals("reset", vm.mode.value)
        vm.submit("reset", "test@example.com", "new-long-password", "12345678")
        advanceUntilIdle()
        assertEquals("login", vm.mode.value)
        assertNotNull(vm.message.value)
        vm.showMode("register")
        assertNull(vm.message.value)
      }

  @Test
  fun `accepted Google credential creates a backend session`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().build(),
        )
        val api =
            object : BackendTransport {
              override val json = Json

              override suspend fun public(
                  method: String,
                  path: String,
                  body: JsonElement?,
              ): JsonElement =
                  json.encodeToJsonElement(
                      BackendTokens("user", "backend@example.com", "access", "refresh")
                  )

              override suspend fun authorized(method: String, path: String, body: JsonElement?) =
                  error("Unexpected")
            }
        val store = Store()
        val vm =
            AccountViewModel(
                api,
                store,
                BackendSync(db, api, store),
                BackendSyncScheduler(context, db),
            )

        vm.acceptGoogleCredential("id-token", "nonce")

        assertEquals("user", store.session.value?.userId)
      }

  @Test
  fun `rejected Google credential and password paths leave the account signed out`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val api = Api().apply { failure = BackendException(401, "invalid", "Отклонено") }
        val store = Store()
        val vm =
            AccountViewModel(
                api,
                store,
                BackendSync(db, api, store),
                BackendSyncScheduler(context, db),
            )

        runCatching { vm.acceptGoogleCredential("bad", "nonce") }
        assertNull(store.session.value)
        vm.submit("register", "password@example.com", "long-password", "")
        advanceUntilIdle()
      }

  @Test
  fun `registration verification and password login still create a backend session`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val api =
            object : BackendTransport {
              override val json = Json

              override suspend fun public(
                  method: String,
                  path: String,
                  body: JsonElement?,
              ): JsonElement =
                  if (path == "/auth/login") {
                    json.encodeToJsonElement(
                        BackendTokens("user", "backend@example.com", "access", "refresh")
                    )
                  } else {
                    buildJsonObject {}
                  }

              override suspend fun authorized(
                  method: String,
                  path: String,
                  body: JsonElement?,
              ) = error("Unexpected")
            }
        val store = Store()
        val vm =
            AccountViewModel(
                api,
                store,
                BackendSync(db, api, store),
                BackendSyncScheduler(context, db),
            )

        vm.submit("register", "password@example.com", "long-password", "")
        advanceUntilIdle()
        vm.submit("verify", "password@example.com", "", "12345678")
        advanceUntilIdle()
        vm.submit("login", "password@example.com", "long-password", "")
        assertNotNull(store.firstSave.await())
        advanceUntilIdle()

        assertNotNull(store.session.value)
      }

  @Test
  fun `queued account action never adopts a replacement account before dispatch`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        SyncSchema.install(db.openHelper.writableDatabase)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE backend_state SET owner='a',phase='OWNED',initialMergeAcknowledged=1 WHERE id=1"
        )
        val store = Store().also { it.save(BackendTokens("a", "a@e", "a", "a")) }
        val calls = mutableListOf<String>()
        val api =
            object : BackendTransport {
              override val json = Json

              override suspend fun public(method: String, path: String, body: JsonElement?) =
                  error("unused")

              override suspend fun authorized(
                  method: String,
                  path: String,
                  body: JsonElement?,
              ): JsonElement {
                calls += path
                return JsonArray(emptyList())
              }
            }
        val sync = BackendSync(db, api, store)
        val vm =
            AccountViewModel(
                api,
                store,
                sync,
                BackendSyncScheduler(ApplicationProvider.getApplicationContext(), db),
            )
        vm.revoke("a-session")
        db.openHelper.writableDatabase.execSQL(
            "UPDATE backend_state SET owner='b',phase='CLAIMED',mergeId='b' WHERE id=1"
        )
        store.save(BackendTokens("b", "b@e", "b", "b"))
        advanceUntilIdle()
        vm.busy.first { !it }
        assertTrue(calls.isEmpty())
        assertTrue(vm.sessions.value.isEmpty())
      }

  @Test
  fun `revoke chain and result commit finish for A before queued B sign in and never expose A sessions under B`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        SyncSchema.install(db.openHelper.writableDatabase)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE backend_state SET owner='a',phase='OWNED',initialMergeAcknowledged=1 WHERE id=1"
        )
        val store = Store().also { it.save(BackendTokens("a", "a@e", "a", "a")) }
        val switched = CompletableDeferred<Unit>()
        val calls = mutableListOf<Pair<String, String?>>()
        lateinit var sync: BackendSync
        val api =
            object : BackendTransport {
              override val json = Json

              override suspend fun public(method: String, path: String, body: JsonElement?) =
                  error("unused")

              override suspend fun authorized(
                  method: String,
                  path: String,
                  body: JsonElement?,
              ): JsonElement {
                calls += method to store.session.value?.userId
                if (method == "DELETE") {
                  CoroutineScope(currentCoroutineContext()).launch(
                      start = CoroutineStart.UNDISPATCHED
                  ) {
                    sync.signIn(BackendTokens("b", "b@e", "b", "b"))
                    switched.complete(Unit)
                  }
                  return buildJsonObject {}
                }
                return json.encodeToJsonElement(
                    listOf(BackendSession("a-session", "A phone", "date", true))
                )
              }
            }
        sync = BackendSync(db, api, store)
        val vm =
            AccountViewModel(
                api,
                store,
                sync,
                BackendSyncScheduler(ApplicationProvider.getApplicationContext(), db),
            )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.session.collect() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.sessions.collect() }
        vm.revoke("a-session")
        advanceUntilIdle()
        switched.await()
        vm.busy.first { !it }
        advanceUntilIdle()
        assertEquals(listOf("DELETE" to "a", "GET" to "a"), calls)
        assertEquals("b", store.session.value?.userId)
        assertEquals("b", vm.session.value?.userId)
        assertTrue(vm.sessions.value.isEmpty())
      }
}
