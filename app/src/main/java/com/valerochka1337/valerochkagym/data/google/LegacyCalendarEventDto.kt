package com.valerochka1337.valerochkagym.data.google

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

// Read-only codec for legacy journals; there is no Google Calendar client.
/**
 * Тело `events.insert`. [start]/[end] задаются как `{"dateTime": "<ISO-8601 со смещением>"}`; при
 * наличии offset поле `timeZone` не требуется. [reminders] отключает дефолтные напоминания и
 * оставляет один popup за 30 минут до начала.
 *
 * [recurrence] — список RRULE-строк (например `"RRULE:FREQ=WEEKLY;BYDAY=MO"`) для повторяющихся
 * событий недельного расписания; у одиночных ad-hoc событий = null. Общий `Json` в NetworkModule
 * настроен с `encodeDefaults = true`, поэтому поле помечено `@EncodeDefault(NEVER)` — при null оно
 * НЕ сериализуется, и одиночные события (а также все Sheets-DTO) остаются без `recurrence`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CalendarEventDto(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val id: String? = null,
    val summary: String,
    val start: EventDateTimeDto,
    val end: EventDateTimeDto,
    val reminders: EventRemindersDto,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val recurrence: List<String>? = null,
)

/**
 * `{"dateTime": "<ISO-8601 со смещением>"}`. [timeZone] (IANA, напр. `Europe/Moscow`) обязателен
 * для повторяющихся (RRULE) событий — без него Google отвечает 400; у одиночных событий = null и не
 * сериализуется (см. `@EncodeDefault(NEVER)`).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class EventDateTimeDto(
    val dateTime: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val timeZone: String? = null,
)

@Serializable
data class EventRemindersDto(
    val useDefault: Boolean,
    val overrides: List<EventReminderOverrideDto>,
)

@Serializable
data class EventReminderOverrideDto(
    val method: String,
    val minutes: Int,
)

/** Ответ `events.insert`; используется только `id` созданного события. */
@Serializable
data class CalendarEventResponseDto(
    val id: String,
)
