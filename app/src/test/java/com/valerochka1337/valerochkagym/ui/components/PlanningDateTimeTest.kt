package com.valerochka1337.valerochkagym.ui.components

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class PlanningDateTimeTest {

  private val berlin = ZoneId.of("Europe/Berlin")

  @Test
  fun `new planning form defaults to tomorrow at six in the device zone`() {
    val now = LocalDateTime.of(2026, 10, 20, 23, 30).atZone(berlin).toInstant().toEpochMilli()

    assertEquals(
        PlanningDateTimeParts("2026-10-21", "18:00"),
        tomorrowAtSix(now, berlin),
    )
  }

  @Test
  fun `saved instant is displayed in the current device zone without moving it`() {
    val instant =
        LocalDateTime.of(2026, 6, 1, 18, 0).atOffset(ZoneOffset.UTC).toInstant().toEpochMilli()

    assertEquals(
        PlanningDateTimeParts("2026-06-01", "20:00"),
        displayPlanningDateTime(instant, berlin),
    )
  }

  @Test
  fun `spring forward gap is rejected`() {
    assertEquals(
        PlanningDateTimeResolution.Gap,
        resolvePlanningDateTime("2026-03-29", "02:30", berlin),
    )
  }

  @Test
  fun `unchanged autumn overlap retains its original instant`() {
    val laterOffsetInstant =
        LocalDateTime.of(2026, 10, 25, 2, 30)
            .atOffset(ZoneOffset.ofHours(1))
            .toInstant()
            .toEpochMilli()

    assertEquals(
        PlanningDateTimeResolution.Resolved(laterOffsetInstant),
        resolvePlanningDateTime(
            date = "2026-10-25",
            time = "02:30",
            zone = berlin,
            unchangedInstantMillis = laterOffsetInstant,
        ),
    )
  }

  @Test
  fun `unchanged minute retains saved seconds and milliseconds`() {
    val instant =
        LocalDateTime.of(2026, 6, 1, 20, 15, 42, 123_000_000)
            .atZone(berlin)
            .toInstant()
            .toEpochMilli()

    assertEquals(
        PlanningDateTimeResolution.Resolved(instant),
        resolvePlanningDateTime(
            date = "2026-06-01",
            time = "20:15",
            zone = berlin,
            unchangedInstantMillis = instant,
        ),
    )
  }
}
