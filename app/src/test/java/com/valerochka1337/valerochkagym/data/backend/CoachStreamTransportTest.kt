package com.valerochka1337.valerochkagym.data.backend

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class CoachStreamTransportTest {
  @Test
  fun `stream recovers after HTTP errors and emits each event once`() = runBlocking {
    var calls = 0
    val waits = mutableListOf<Long>()
    val bodies = mutableListOf<String>()
    val client =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
              calls++
              bodies += okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
              response(
                  chain.request(),
                  if (calls < 3) 503 else 200,
                  if (calls < 3) "{}"
                  else "event:text_delta\ndata:x\n\nevent:completed\ndata:{}\n\n",
              )
            }
            .build()
    try {
      val events =
          BackendApi(Store(), client, "https://test.invalid/") { waits += it }
              .authorizedEventStream(
                  "/ai/coach-turn/stream",
                  """{"requestId":"stable"}""".encodeToByteArray(),
                  "owner",
                  0,
              )
              .toList()
      assertEquals(3, calls)
      assertEquals(listOf(1000L, 2000L), waits)
      assertEquals(1, bodies.distinct().size)
      assertEquals(listOf("text_delta", "completed"), events.map { it.event })
    } finally {
      client.dispatcher.executorService.shutdown()
    }
  }

  @Test
  fun `stream sends one pinned post and surfaces deltas and terminal event`() = runBlocking {
    var count = 0
    val client =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
              count++
              assertEquals("POST", chain.request().method)
              assertTrue(chain.request().body!!.isOneShot())
              assertEquals("/v1/ai/coach-turn/stream", chain.request().url.encodedPath)
              assertEquals("Bearer access", chain.request().header("Authorization"))
              assertEquals("text/event-stream", chain.request().header("Accept"))
              assertEquals(60_000, chain.readTimeoutMillis())
              assertEquals(TimeUnit.SECONDS.toNanos(60), chain.call().timeout().timeoutNanos())
              response(
                  chain.request(),
                  200,
                  ":heartbeat\n\nevent:text_delta\ndata:привет 💪\n\nevent:completed\ndata:{}\n\n",
              )
            }
            .build()
    try {
      val events =
          BackendApi(Store(), client, "https://test.invalid/")
              .authorizedEventStream("/ai/coach-turn/stream", "{}".encodeToByteArray(), "owner", 0)
              .toList()
      assertEquals(listOf("text_delta", "completed"), events.map { it.event })
      assertTrue(events.all { it.owner == "owner" && it.sessionEpoch == 0L })
      assertEquals(1, count)
    } finally {
      client.dispatcher.executorService.shutdown()
    }
  }

  @Test
  fun `http errors retry three times while network failure and premature eof never replay`() =
      runBlocking {
        for (status in listOf(401, 403, 404, 429, 503, 200, -1)) {
          val waits = mutableListOf<Long>()
          var count = 0
          val client =
              OkHttpClient.Builder()
                  .retryOnConnectionFailure(true)
                  .addInterceptor { chain ->
                    count++
                    if (status == -1) throw IOException("broken network")
                    response(
                        chain.request(),
                        status,
                        if (status == 200) "event:text_delta\ndata:x\n\n"
                        else "{\"code\":\"test_error\"}",
                    )
                  }
                  .build()
          try {
            val failure =
                runCatching {
                      BackendApi(Store(), client, "https://test.invalid/") { waits += it }
                          .authorizedEventStream("/ai/coach-turn/stream", byteArrayOf(), "owner", 0)
                          .collect()
                    }
                    .exceptionOrNull()
            assertNotNull(failure)
            if (status > 200) assertEquals(status, (failure as BackendException).status)
            assertEquals(if (status > 200) 4 else 1, count)
            assertEquals(
                if (status > 200) listOf(1000L, 2000L, 4000L) else emptyList<Long>(),
                waits,
            )
          } finally {
            client.dispatcher.executorService.shutdown()
          }
        }
      }

  @Test
  fun `cancellation and account change cancel a pending call`() = runBlocking {
    for (changeOwner in listOf(false, true)) {
      val entered = CompletableDeferred<okhttp3.Call>()
      val release = java.util.concurrent.CountDownLatch(1)
      val client =
          OkHttpClient.Builder()
              .addInterceptor { chain ->
                entered.complete(chain.call())
                release.await(5, TimeUnit.SECONDS)
                response(chain.request(), 200, "event:completed\ndata:{}\n\n")
              }
              .build()
      val store = Store()
      var failure: Throwable? = null
      val job = launch {
        try {
          BackendApi(store, client, "https://test.invalid/")
              .authorizedEventStream("/ai/coach-turn/stream", byteArrayOf(), "owner", 0)
              .collect()
        } catch (e: Exception) {
          failure = e
        }
      }
      try {
        val call = withTimeout(3000) { entered.await() }
        if (changeOwner) {
          store.save(null)
          withTimeout(3000) { job.join() }
          assertTrue(failure is BackendException)
        } else job.cancelAndJoin()
        assertTrue(call.isCanceled())
      } finally {
        release.countDown()
        job.cancelAndJoin()
        client.dispatcher.executorService.shutdown()
      }
    }
  }

  @Test
  fun `credential refresh during stream preserves the response without replay`() = runBlocking {
    val store = Store()
    val entered = CompletableDeferred<Unit>()
    val release = java.util.concurrent.CountDownLatch(1)
    var calls = 0
    val events = mutableListOf<BackendStreamEvent>()
    val client =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
              calls++
              entered.complete(Unit)
              release.await(5, TimeUnit.SECONDS)
              response(chain.request(), 200, "event:completed\ndata:{}\n\n")
            }
            .build()
    val job = launch {
      BackendApi(store, client, "https://test.invalid/")
          .authorizedEventStream("/ai/coach-turn/stream", byteArrayOf(), "owner", 0)
          .collect { events += it }
    }
    try {
      withTimeout(3000) { entered.await() }
      store.session.value =
          store.session.value!!.copy(accessToken = "new", refreshToken = "new-refresh")
      release.countDown()
      withTimeout(3000) { job.join() }
      assertEquals(listOf("completed"), events.map { it.event })
      assertEquals(1, calls)
    } finally {
      release.countDown()
      job.cancelAndJoin()
      client.dispatcher.executorService.shutdown()
    }
  }

  private fun response(request: okhttp3.Request, status: Int, body: String) =
      Response.Builder()
          .request(request)
          .protocol(Protocol.HTTP_1_1)
          .code(status)
          .message("Test")
          .body(body.toResponseBody("text/event-stream".toMediaType()))
          .build()

  private class Store : BackendSessionStore {
    override val session =
        MutableStateFlow<BackendTokens?>(
            BackendTokens("owner", "o@example.com", "access", "refresh")
        )
    override var sessionEpoch = 0L

    override fun save(tokens: BackendTokens?) {
      sessionEpoch++
      session.value = tokens
    }
  }
}
