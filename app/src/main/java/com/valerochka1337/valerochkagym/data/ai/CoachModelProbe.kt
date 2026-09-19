package com.valerochka1337.valerochkagym.data.ai

import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

/** The server hosts the isolated synthetic tool check. */
class CoachModelProbe
@Inject
constructor(private val client: CoachRunsClient, private val settings: SettingsRepository) {
  suspend fun verify(expectedOwner: String, expectedSessionEpoch: Long): CoachModelProbeResult =
      try {
        val result =
            client.modelCheck(
                settings.coachModel(expectedOwner).first(),
                expectedOwner,
                expectedSessionEpoch,
            )
        CoachModelProbeResult(
            result["success"]?.jsonPrimitive?.booleanOrNull == true,
            result["message"]?.jsonPrimitive?.content ?: "Не удалось проверить модель",
        )
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        CoachModelProbeResult(false, "Не удалось проверить модель. Повторите попытку.")
      }
}

data class CoachModelProbeResult(val success: Boolean, val message: String)
