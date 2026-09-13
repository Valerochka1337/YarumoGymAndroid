package com.valerochka1337.valerochkagym.ui.components

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.time.temporal.ChronoUnit

/**
 * Pure conversion rules shared by the planning form and editable AI proposals.
 *
 * A persisted appointment is always an instant. Local date/time are only a device-zone view of that
 * instant, so travelling to a different zone cannot silently move an unchanged appointment.
 */
internal data class PlanningDateTimeParts(
    val date: String,
    val time: String,
)

internal sealed interface PlanningDateTimeResolution {
  data class Resolved(val instantMillis: Long) : PlanningDateTimeResolution

  data object Invalid : PlanningDateTimeResolution

  /** The requested wall-clock time does not exist because clocks move forward. */
  data object Gap : PlanningDateTimeResolution
}

internal fun tomorrowAtSix(
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): PlanningDateTimeParts =
    PlanningDateTimeParts(
        date = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().plusDays(1).toString(),
        time = "18:00",
    )

internal fun displayPlanningDateTime(
    instantMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): PlanningDateTimeParts {
  val local = Instant.ofEpochMilli(instantMillis).atZone(zone)
  return PlanningDateTimeParts(
      date = local.toLocalDate().toString(),
      time = local.toLocalTime().format(timeFormatter),
  )
}

/**
 * Resolves explicit wall-clock edits in [zone]. A valid [unchangedInstantMillis] wins when it
 * already represents the same local date/time in an autumn overlap, preserving its chosen offset.
 */
internal fun resolvePlanningDateTime(
    date: String,
    time: String,
    zone: ZoneId = ZoneId.systemDefault(),
    unchangedInstantMillis: Long? = null,
): PlanningDateTimeResolution {
  val localDate = runCatching { LocalDate.parse(date.trim(), dateFormatter) }.getOrNull()
  val localTime = runCatching { LocalTime.parse(time.trim(), timeFormatter) }.getOrNull()
  if (localDate == null || localTime == null) return PlanningDateTimeResolution.Invalid

  val local = LocalDateTime.of(localDate, localTime)
  val offsets = zone.rules.getValidOffsets(local)
  if (offsets.isEmpty()) return PlanningDateTimeResolution.Gap

  val unchanged =
      unchangedInstantMillis
          ?.let(Instant::ofEpochMilli)
          ?.atZone(zone)
          ?.takeIf {
            it.toLocalDateTime().truncatedTo(ChronoUnit.MINUTES) == local && it.offset in offsets
          }
          ?.toInstant()
  val instant = unchanged ?: local.atOffset(offsets.first()).toInstant()
  return PlanningDateTimeResolution.Resolved(instant.toEpochMilli())
}

private val dateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)
