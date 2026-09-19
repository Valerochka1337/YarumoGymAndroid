package com.valerochka1337.valerochkagym.data.ai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.valerochka1337.valerochkagym.data.backend.*
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CoachModelProbeTest {
  @Test
  fun `probe sends selected model and captured login without personal data`() = runTest {
    val transport = ProbeTransport()
    val settings = SettingsRepository(ProbeStore())
    settings.setCoachModel("owner", "selected")
    val result = CoachModelProbe(CoachRunsClient(transport), settings).verify("owner", 7)
    assertTrue(result.success)
    assertEquals("POST /coach/model-check", transport.path)
    assertEquals("owner", transport.owner)
    assertEquals(7L, transport.epoch)
    assertEquals("{\"model\":\"selected\"}", transport.body)
  }

  @Test
  fun `late response from another login cannot pass probe`() = runTest {
    val transport = ProbeTransport().apply { responseEpoch = 8 }
    assertFalse(
        CoachModelProbe(CoachRunsClient(transport), SettingsRepository(ProbeStore()))
            .verify("owner", 7)
            .success
    )
  }
}

private class ProbeStore : DataStore<Preferences> {
  private val state = MutableStateFlow<Preferences>(mutablePreferencesOf())
  override val data: Flow<Preferences> = state

  override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
      transform(state.value).also { state.value = it }
}

private class ProbeTransport : BackendTransport {
  override val json = Json
  var path = ""
  var body = ""
  var owner: String? = null
  var epoch: Long? = null
  var responseEpoch = 7L

  override suspend fun public(method: String, path: String, body: JsonElement?): JsonElement =
      error("unused")

  override suspend fun authorized(method: String, path: String, body: JsonElement?): JsonElement =
      error("unused")

  override suspend fun authorizedRawResponse(
      method: String,
      path: String,
      rawBody: ByteArray,
      headers: Map<String, String>,
      expectedOwner: String?,
      expectedSessionEpoch: Long?,
      retryOnUnauthorized: Boolean,
      maxResponseBytes: Int?,
  ): BackendResponse {
    this.path = "$method $path"
    body = rawBody.decodeToString()
    owner = expectedOwner
    epoch = expectedSessionEpoch
    return BackendResponse(
        buildJsonObject {
          put("success", true)
          put("message", "Проверено")
        },
        byteArrayOf(),
        emptySet(),
        expectedOwner,
        responseEpoch,
    )
  }
}
