package com.valerochka1337.valerochkagym.ui.calendarai

import com.valerochka1337.valerochkagym.data.backend.BackendException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class CalendarAiErrorTest {
  @Test
  fun `known backend failures explain distinct reasons without server messages`() {
    val cases =
        mapOf(
            "workout_active" to "активную тренировку",
            "ai_context_stale" to "синхронизацию",
            "ai_sync_failed" to "синхронизацию",
            "unauthorized" to "Войдите",
            "ai_unavailable" to "временно занят",
            "ai_timeout" to "не успел",
            "ai_invalid_response" to "не прошёл проверку",
            "ai_no_candidates" to "Нет подходящих упражнений",
            "ai_gym_unavailable" to "зал больше недоступен",
            "ai_invalid_intent" to "Проверьте дату",
        )
    for ((code, expected) in cases) {
      val message = calendarAiErrorMessage(BackendException(409, code, "SECRET notes token trace"))
      assertTrue(message, message.contains(expected))
      assertTrue(message.endsWith("Код: $code"))
      assertFalse(message.contains("SECRET"))
    }
  }

  @Test
  fun `unknown codes and exception messages never reach UI`() {
    for (error in
        listOf(
            BackendException(400, "SECRET", "SECRET"),
            IllegalArgumentException("SECRET"),
            IOException("SECRET"),
            SocketTimeoutException("SECRET"),
        )) {
      assertFalse(calendarAiErrorMessage(error).contains("SECRET"))
    }
    assertTrue(calendarAiErrorMessage(IOException()).contains("network_error"))
    assertTrue(calendarAiErrorMessage(SocketTimeoutException()).contains("network_timeout"))
    assertTrue(
        calendarAiErrorMessage(BackendException(401, "SECRET", "SECRET"))
            .contains("ai_auth_required")
    )
  }

  @Test
  fun `cancellation is never converted to an error message`() {
    val error = CancellationException("SECRET")
    assertSame(error, runCatching { calendarAiErrorMessage(error) }.exceptionOrNull())
  }
}
