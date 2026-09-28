package com.valerochka1337.valerochkagym.ui.calendarai

import com.valerochka1337.valerochkagym.data.backend.BackendException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException

/** Only fixed copy and allowlisted codes may reach the UI; exception messages are untrusted. */
internal fun calendarAiErrorMessage(error: Exception): String {
  if (error is CancellationException) throw error
  if (error is SocketTimeoutException)
      return "Сервер не ответил вовремя. Проверьте подключение и повторите попытку. Код: network_timeout"
  if (error is IOException)
      return "Не удалось связаться с сервером. Проверьте подключение к интернету. Код: network_error"
  if (error !is BackendException)
      return "Не удалось подготовить предложение. Повторите попытку. Код: ai_unknown_error"
  val message =
      when (error.code) {
        "workout_active" -> "Сначала завершите активную тренировку, затем создайте предложение."
        "ai_context_stale",
        "revision_conflict",
        "catalog_transition_required",
        "calendar_migration_pending" ->
            "Данные ещё не готовы. Завершите синхронизацию и повторите попытку."
        "ai_sync_failed" ->
            "Не удалось завершить синхронизацию. Проверьте её состояние в настройках и повторите попытку."
        "owner_changed",
        "unauthorized" -> "Войдите в аккаунт и повторите попытку."
        "ai_unavailable",
        "ai_busy" -> "Сервис планирования временно занят. Попробуйте позже."
        "ai_timeout" -> "Сервис планирования не успел подготовить предложение. Попробуйте ещё раз."
        "ai_invalid_response",
        "response_too_large" ->
            "Ответ сервиса планирования не прошёл проверку. Попробуйте создать предложение ещё раз."
        "ai_no_candidates" -> "Нет подходящих упражнений. Измените выбранные залы или исключения."
        "ai_gym_unavailable" -> "Выбранный зал больше недоступен. Проверьте выбор зала."
        "ai_invalid_intent" -> "Проверьте дату, время, длительность и параметры предложения."
        "ai_context_too_large" ->
            "Данных слишком много для расчёта. Попробуйте ограничить выбор упражнений залом."
        "NO_FEASIBLE_PLAN" ->
            "С заданными условиями нельзя составить выполнимый план. Измените условия или отредактируйте тренировку вручную."
        "PLANNER_LIMIT_REACHED" -> "Расчёт достиг ограничения. Повторите запрос с другим вариантом."
        "PLANNER_TIMEOUT" -> "Расчёт не успел завершиться. Повторите запрос."
        "planner_update_required" -> "Для планирования нужна более новая версия приложения."
        "ai_in_progress" -> "Предложение ещё создаётся. Подождите и повторите попытку."
        "ai_interrupted",
        "ai_request_conflict" ->
            "Попытка создания предложения прервана. Создайте предложение ещё раз."
        else ->
            return when {
              error.status == 401 ->
                  "Сессия истекла. Войдите в аккаунт ещё раз. Код: ai_auth_required"
              error.status == 403 ->
                  "Аккаунту недоступно создание предложения. Проверьте доступ. Код: ai_access_denied"
              error.status == 429 ->
                  "Слишком много запросов. Попробуйте позже. Код: ai_rate_limited"
              error.status in 500..599 ->
                  "Сервер временно недоступен. Попробуйте позже. Код: ai_server_unavailable"
              else -> "Не удалось подготовить предложение. Повторите попытку. Код: ai_unknown_error"
            }
      }
  return "$message Код: ${error.code}"
}
