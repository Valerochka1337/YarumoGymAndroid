package com.valerochka1337.valerochkagym.diagnostics

import android.util.Log
import com.valerochka1337.valerochkagym.BuildConfig
import com.valerochka1337.valerochkagym.data.backend.BackendException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

/** Debug-only metadata. Never pass prompts, replies, tool arguments, snapshots or credentials. */
internal object CoachDiagnostics {
  private val writer = CoachDiagnosticWriter(BuildConfig.DEBUG) { line -> Log.d("LiveCoach", line) }
  private val sequence = AtomicLong()

  fun event(name: String, vararg fields: Pair<String, Any?>) = writer.event(name, *fields)

  fun failure(name: String, error: Throwable, vararg fields: Pair<String, Any?>) =
      writer.failure(name, error, *fields)

  fun span(name: String, vararg fields: Pair<String, Any?>): Span =
      Span(name, sequence.incrementAndGet(), System.nanoTime()).also {
        event("$name.started", "trace" to it.id, *fields)
      }

  suspend fun <T> trace(
      name: String,
      vararg fields: Pair<String, Any?>,
      block: suspend () -> T,
  ): T {
    val span = span(name, *fields)
    try {
      return block().also { span.finish() }
    } catch (error: Exception) {
      span.failed(error)
      throw error
    }
  }

  class Span
  internal constructor(private val name: String, val id: Long, private val started: Long) {
    fun finish(vararg fields: Pair<String, Any?>) =
        event("$name.finished", "trace" to id, "elapsed_ms" to elapsed(), *fields)

    fun failed(error: Throwable) {
      if (error is CancellationException)
          event("$name.cancelled", "trace" to id, "elapsed_ms" to elapsed())
      else failure("$name.failed", error, "trace" to id, "elapsed_ms" to elapsed())
    }

    private fun elapsed() = (System.nanoTime() - started) / 1_000_000
  }
}

/** The sink is isolated so diagnostics cannot change business results, even in plain JVM tests. */
internal class CoachDiagnosticWriter(
    private val enabled: Boolean,
    private val sink: (String) -> Unit,
) {
  fun event(name: String, vararg fields: Pair<String, Any?>) {
    if (!enabled) return
    runCatching {
      sink(
          (listOf("event=${clean(name)}") +
                  fields.take(32).map { (key, value) -> "${clean(key)}=${clean(value)}" })
              .joinToString(" ")
              .take(3500)
      )
    }
  }

  fun failure(name: String, error: Throwable, vararg fields: Pair<String, Any?>) {
    if (!enabled) return
    // Throwable messages/toString may include response bodies or user input. Log only types and
    // source locations, including nested causes, to retain a useful stack without those contents.
    runCatching {
      val locations = mutableListOf<Pair<String, Any?>>()
      var cause: Throwable? = error
      repeat(3) { index ->
        val current = cause ?: return@repeat
        locations += "error_$index" to current.javaClass.simpleName
        if (current is BackendException) {
          locations += "http_status_$index" to current.status
          locations += "http_reason_$index" to coachHttpFailureReason(current)
        }
        current.stackTrace.take(4).forEachIndexed { frame, location ->
          locations += "at_${index}_$frame" to location.toString()
        }
        cause = current.cause.takeUnless { it === current }
      }
      event(name, *fields, *locations.toTypedArray())
    }
  }

  private fun clean(value: Any?): String =
      when (value) {
            null -> "unknown"
            is String,
            is Number,
            is Boolean,
            is Enum<*> -> value.toString()
            else -> value.javaClass.simpleName // Never serialize domain objects accidentally.
          }
          .replace(Regex("[\\s\\p{Cntrl}]+"), "_")
          .take(180)
}

// Compare complete server strings with known contract errors; never print arbitrary response text.
internal fun coachHttpFailureReason(error: BackendException): String =
    when (error.message) {
      "Незавершённый диалог инструментов" -> "unfinished_tool_dialog"
      "Нет результата инструмента" -> "missing_tool_result"
      "Некорректный результат инструмента" -> "invalid_tool_result"
      "Некорректная ссылка на инструмент" -> "invalid_tool_reference"
      "Некорректные вызовы инструментов" -> "invalid_tool_calls"
      "Повторный вызов инструмента" -> "duplicate_tool_call"
      "Неизвестный инструмент" -> "unknown_tool"
      "Некорректный тип инструмента",
      "Некорректный инструмент" -> "invalid_tool_type"
      "Некорректные инструменты тренера" -> "invalid_tool_set"
      "Некорректная схема инструмента" -> "invalid_tool_schema"
      "Некорректные сообщения тренера" -> "invalid_messages"
      "Некорректная роль сообщения" -> "invalid_role"
      "Пустое сообщение" -> "empty_message"
      "Некорректная длина поля" -> "invalid_field_length"
      "Некорректное текстовое поле" -> "invalid_text_field"
      "Неизвестные поля запроса тренера" -> "unknown_request_fields"
      "Некорректный идентификатор запроса" -> "invalid_request_id"
      "Выберите доступную модель тренера" -> "model_not_available"
      "Некорректный запрос тренера" -> "invalid_request_shape"
      else ->
          when (error.code) {
            "invalid_request",
            "http_error",
            "unauthorized",
            "owner_changed",
            "ai_unavailable",
            "ai_timeout",
            "provider_error",
            "payload_too_large",
            "response_too_large",
            "coach_unconfigured" -> error.code
            else -> "unclassified"
          }
    }
