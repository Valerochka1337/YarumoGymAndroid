package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.backend.BackendApi
import com.valerochka1337.valerochkagym.data.backend.BackendException
import com.valerochka1337.valerochkagym.data.backend.BackendSessionSnapshot
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackendApiTest {
  @Test
  fun `account change and cancellation during backoff prevent the next request`() = runTest {
    for (cancel in listOf(false, true)) {
      val store = Store()
      var calls = 0
      val client =
          OkHttpClient.Builder()
              .addInterceptor { chain ->
                calls++
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("Test")
                    .body("{}".toResponseBody())
                    .build()
              }
              .build()
      try {
        val failure =
            runCatching {
                  BackendApi(store, client, "https://test.invalid/") {
                        if (cancel) throw kotlinx.coroutines.CancellationException("Cancelled")
                        store.save(null)
                      }
                      .authorizedRawResponse("POST", "/ai/exercise-drafts", byteArrayOf())
                }
                .exceptionOrNull()
        assertEquals(1, calls)
        if (cancel) assertTrue(failure is kotlinx.coroutines.CancellationException)
        else assertEquals("owner_changed", (failure as BackendException).code)
      } finally {
        client.dispatcher.executorService.shutdown()
      }
    }
  }

  @Test
  fun `accepted asynchronous AI jobs are not submitted again`() = runTest {
    var calls = 0
    val client =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
              calls++
              Response.Builder()
                  .request(chain.request())
                  .protocol(Protocol.HTTP_1_1)
                  .code(202)
                  .message("Accepted")
                  .body("{}".toResponseBody())
                  .build()
            }
            .build()
    try {
      BackendApi(Store(), client, "https://test.invalid/") { fail("Unexpected backoff") }
          .authorizedRawResponse("POST", "/ai/calendar-draft-jobs", byteArrayOf())
      assertEquals(1, calls)
    } finally {
      client.dispatcher.executorService.shutdown()
    }
  }

  @Test
  fun `AI retries preserve request bytes and stop on success while sync does not retry`() =
      runTest {
        for (path in
            listOf(
                "/ai/calendar-drafts",
                "/ai/calendar-draft-jobs",
                "/ai/exercise-drafts",
                "/ai/inbody-drafts",
                "/ai/coach-turn",
                "/ai/coach-models",
                "/sync",
            )) {
          val bodies = mutableListOf<String>()
          val waits = mutableListOf<Long>()
          var calls = 0
          val client =
              OkHttpClient.Builder()
                  .addInterceptor { chain ->
                    calls++
                    bodies += Buffer().also { chain.request().body?.writeTo(it) }.readUtf8()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(if (calls < 4) 503 else 200)
                        .message("Test")
                        .body("{}".toResponseBody())
                        .build()
                  }
                  .build()
          try {
            val result = runCatching {
              BackendApi(Store(), client, "https://test.invalid/") { waits += it }
                  .authorizedRawResponse(
                      "POST",
                      path,
                      """{"requestId":"stable"}""".encodeToByteArray(),
                  )
            }
            if (path.startsWith("/ai/")) {
              assertTrue(result.isSuccess)
              assertEquals(4, calls)
              assertEquals(listOf(1000L, 2000L, 4000L), waits)
              assertEquals(1, bodies.distinct().size)
            } else {
              assertTrue(result.isFailure)
              assertEquals(1, calls)
              assertTrue(waits.isEmpty())
            }
          } finally {
            client.dispatcher.executorService.shutdown()
          }
        }
      }

  @Test
  fun `coach turn extends socket read timeout without changing other backend requests`() = runTest {
    val timeouts = mutableListOf<Int>()
    val client =
        OkHttpClient.Builder()
            .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor { chain ->
              timeouts += chain.readTimeoutMillis()
              Response.Builder()
                  .request(chain.request())
                  .protocol(Protocol.HTTP_1_1)
                  .code(200)
                  .message("OK")
                  .body("{}".toResponseBody())
                  .build()
            }
            .build()
    try {
      val api = BackendApi(Store(), client, "https://test.invalid/")
      for (path in listOf("/ai/coach-turn", "/ai/coach-models", "/sync")) {
        api.authorizedRawResponse(
            "POST",
            path,
            "{}".encodeToByteArray(),
            expectedOwner = "owner-a",
            expectedSessionEpoch = 0,
            retryOnUnauthorized = false,
        )
      }
      assertEquals(listOf(60_000, 2_000, 2_000), timeouts)
    } finally {
      client.dispatcher.executorService.shutdown()
    }
  }

  @Test
  fun `coach cancellation cancels the network call without waiting for provider response`() =
      kotlinx.coroutines.runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<okhttp3.Call>()
        val release = java.util.concurrent.CountDownLatch(1)
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                  entered.complete(chain.call())
                  release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                  Response.Builder()
                      .request(chain.request())
                      .protocol(Protocol.HTTP_1_1)
                      .code(200)
                      .message("OK")
                      .body("{}".toResponseBody())
                      .build()
                }
                .build()
        val api = BackendApi(Store(), client, "https://test.invalid/")
        val request = launch {
          api.authorizedRawResponse(
              "POST",
              "/ai/coach-turn",
              "{}".encodeToByteArray(),
              expectedOwner = "owner-a",
              expectedSessionEpoch = 0,
              retryOnUnauthorized = false,
          )
        }
        try {
          val call = kotlinx.coroutines.withTimeout(3000) { entered.await() }
          assertEquals(
              java.util.concurrent.TimeUnit.SECONDS.toNanos(60),
              call.timeout().timeoutNanos(),
          )
          request.cancel()
          kotlinx.coroutines.withTimeout(3000) { request.join() }
          assertTrue(call.isCanceled())
        } finally {
          release.countDown()
          request.cancel()
          client.dispatcher.executorService.shutdown()
        }
      }

  @Test
  fun `catalog refreshes expired credentials within the pinned login`() = runTest {
    val store = Store()
    val paths = mutableListOf<String>()
    val auth = mutableListOf<String?>()
    val client =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
              val request = chain.request()
              paths += request.url.encodedPath
              auth += request.header("Authorization")
              val refresh = request.url.encodedPath.endsWith("/auth/refresh")
              val expired = !refresh && request.header("Authorization") == "Bearer access"
              Response.Builder()
                  .request(request)
                  .protocol(Protocol.HTTP_1_1)
                  .code(if (expired) 401 else 200)
                  .message("test")
                  .body(
                      (if (refresh)
                              """{"userId":"owner-a","email":"owner@example.com","accessToken":"new-access","refreshToken":"new-refresh"}"""
                          else "{}")
                          .toResponseBody()
                  )
                  .build()
            }
            .build()
    try {
      val result =
          BackendApi(store, client, "https://test.invalid/")
              .authorizedRawResponse(
                  "GET",
                  "/ai/coach-models",
                  byteArrayOf(),
                  expectedOwner = "owner-a",
                  expectedSessionEpoch = 0,
                  retryOnUnauthorized = true,
              )
      assertEquals(listOf("/v1/ai/coach-models", "/v1/auth/refresh", "/v1/ai/coach-models"), paths)
      assertEquals(listOf("Bearer access", null, "Bearer new-access"), auth)
      assertEquals(0L, result.sessionEpoch)
      assertEquals(0L, store.sessionEpoch)
    } finally {
      client.dispatcher.executorService.shutdown()
    }
  }

  @Test
  fun `refresh cannot restore a login replaced while the response is in flight`() = runTest {
    for (replacement in
        listOf(null, BackendTokens("owner-a", "owner@example.com", "access", "refresh"))) {
      val store = Store()
      val paths = mutableListOf<String>()
      val client =
          OkHttpClient.Builder()
              .addInterceptor { chain ->
                val request = chain.request()
                paths += request.url.encodedPath
                val refresh = request.url.encodedPath.endsWith("/auth/refresh")
                if (refresh) store.save(replacement)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (refresh) 200 else 401)
                    .message("test")
                    .body(
                        (if (refresh)
                                """{"userId":"owner-a","email":"owner@example.com","accessToken":"new-access","refreshToken":"new-refresh"}"""
                            else "{}")
                            .toResponseBody()
                    )
                    .build()
              }
              .build()
      try {
        val result = runCatching {
          BackendApi(store, client, "https://test.invalid/")
              .authorizedRawResponse(
                  "GET",
                  "/ai/coach-models",
                  byteArrayOf(),
                  expectedOwner = "owner-a",
                  expectedSessionEpoch = 0,
                  retryOnUnauthorized = true,
              )
        }
        assertTrue(result.exceptionOrNull() is BackendException)
        assertEquals(replacement, store.session.value)
        assertEquals(2, paths.size)
      } finally {
        client.dispatcher.executorService.shutdown()
      }
    }
  }

  private class Store : BackendSessionStore {
    override val session =
        MutableStateFlow<BackendTokens?>(
            BackendTokens("owner-a", "owner@example.com", "access", "refresh"),
        )
    private var epoch = 0L
    override val sessionEpoch: Long
      get() = epoch

    override fun refreshIfCurrent(
        expected: BackendSessionSnapshot,
        replacement: BackendTokens?,
    ): Boolean {
      if (
          snapshot() != expected ||
              (replacement != null && replacement.userId != expected.tokens.userId)
      )
          return false
      if (replacement == null) save(null) else session.value = replacement
      return true
    }

    override fun save(tokens: BackendTokens?) {
      epoch++
      session.value = tokens
    }
  }

  @Test
  fun `ordinary sync advertises health capability without sending health payload`() = runTest {
    var advertised: String? = null
    var bodyPresent = true
    val client =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
              advertised = chain.request().header("X-Gym-Capabilities")
              bodyPresent = chain.request().body != null
              Response.Builder()
                  .request(chain.request())
                  .protocol(Protocol.HTTP_1_1)
                  .code(200)
                  .message("OK")
                  .body("{}".toResponseBody())
                  .build()
            }
            .build()
    BackendApi(Store(), client, "https://test.invalid/").authorizedResponse("GET", "/sync", null)
    assertTrue("health-ledger-v1" in advertised.orEmpty().split(','))
    assertTrue("strength-planner-personalization" in advertised.orEmpty().split(','))
    assertTrue("workout-rir-v1" in advertised.orEmpty().split(','))
    assertEquals(false, bodyPresent)
  }

  @Test
  fun `AI draft POSTs retry unauthorized three times without refreshing`() = runTest {
    val paths = mutableListOf<String>()
    val client =
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                  paths += chain.request().url.encodedPath
                  Response.Builder()
                      .request(chain.request())
                      .protocol(Protocol.HTTP_1_1)
                      .code(401)
                      .message("Unauthorized")
                      .body("{\"code\":\"unauthorized\"}".toResponseBody())
                      .build()
                }
            )
            .build()
    val waits = mutableListOf<Long>()
    val api = BackendApi(Store(), client, "https://test.invalid/") { waits += it }

    listOf("/ai/exercise-drafts", "/ai/inbody-drafts").forEach { path ->
      try {
        api.authorizedResponse(
            method = "POST",
            path = path,
            body = buildJsonObject {},
            retryOnUnauthorized = false,
        )
        fail("Expected an unauthorized response")
      } catch (expected: BackendException) {
        assertEquals(401, expected.status)
      }
    }

    assertEquals(List(4) { "/v1/ai/exercise-drafts" } + List(4) { "/v1/ai/inbody-drafts" }, paths)
    assertEquals(listOf(1000L, 2000L, 4000L, 1000L, 2000L, 4000L), waits)
    client.dispatcher.executorService.shutdown()
  }

  @Test
  fun `raw request rejects a stale session epoch before dispatch`() = runTest {
    var calls = 0
    val client =
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                  calls++
                  Response.Builder()
                      .request(chain.request())
                      .protocol(Protocol.HTTP_1_1)
                      .code(200)
                      .message("OK")
                      .body("{}".toResponseBody())
                      .build()
                }
            )
            .build()
    val api = BackendApi(Store(), client, "https://test.invalid/")

    try {
      api.authorizedRawResponse(
          method = "POST",
          path = "/health-ai-disclosure",
          rawBody = "{\"literal\":true}".encodeToByteArray(),
          expectedOwner = "owner-a",
          expectedSessionEpoch = 1L,
      )
      fail("Expected owner guard to reject")
    } catch (expected: BackendException) {
      assertEquals("owner_changed", expected.code)
    }
    assertEquals(0, calls)
  }

  @Test
  fun `raw request preserves journal bytes and binds the dispatch session`() = runTest {
    var requestBytes = byteArrayOf()
    val client =
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                  val buffer = Buffer()
                  chain.request().body!!.writeTo(buffer)
                  requestBytes = buffer.readByteArray()
                  Response.Builder()
                      .request(chain.request())
                      .protocol(Protocol.HTTP_1_1)
                      .code(200)
                      .message("OK")
                      .body("{}".toResponseBody())
                      .build()
                }
            )
            .build()
    val store = Store()
    val api = BackendApi(store, client, "https://test.invalid/")
    val bytes = "{\"operationId\":\"fixed\", \"enabled\":true}".encodeToByteArray()

    val response =
        api.authorizedRawResponse(
            method = "POST",
            path = "/health-ai-disclosure",
            rawBody = bytes,
            expectedOwner = "owner-a",
            expectedSessionEpoch = store.sessionEpoch,
        )

    assertTrue(requestBytes contentEquals bytes)
    assertEquals("owner-a", response.owner)
    assertEquals(store.sessionEpoch, response.sessionEpoch)
  }
}
