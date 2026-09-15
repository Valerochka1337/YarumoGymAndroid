package com.valerochka1337.valerochkagym.domain.analysis

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.relation.AnalyticsSetRow
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты [AnalyticsEngine]. Движок чистый, поэтому ни Room, ни Robolectric не нужны: подходы
 * собираются вручную, «сейчас» и таймзона задаются явно. Все даты отсчитываются от [today] через
 * [daysAgo], чтобы тесты не зависели от календарной даты запуска.
 */
class AnalyticsEngineTest {

  @Test
  fun `upper and lower chest use per set maximum for push balance`() {
    val report =
        analyze(
            sets = listOf(set(BENCH, weight = 100.0, reps = 5, daysAgo = 1)),
            muscles =
                mapOf(
                    BENCH to
                        listOf(
                            MuscleLoad(Muscle.UPPER_CHEST, 100),
                            MuscleLoad(Muscle.LOWER_CHEST, 50),
                        )
                ),
            period = AnalysisPeriod.ALL_TIME,
        )
    assertEquals(1.0, report.balances.first { it.id == BalanceId.PUSH_PULL }.leftSets, 1e-6)
    assertEquals(1.0, report.balances.first { it.id == BalanceId.UPPER_LOWER }.leftSets, 1e-6)
    assertEquals(
        1.0,
        report.balances.first { it.id == BalanceId.ANTERIOR_POSTERIOR }.leftSets,
        1e-6,
    )
  }

  private val engine = AnalyticsEngine()
  private val zone: ZoneId = ZoneId.of("UTC")
  private val today: LocalDate = LocalDate.of(2026, 6, 10)
  private val nowMillis = today.atTime(20, 0).atZone(zone).toInstant().toEpochMilli()

  // region effective sets

  @Test
  fun `effective sets preserve globally comparable muscle contribution`() {
    val report =
        analyze(
            sets =
                listOf(
                    set(BENCH, weight = 100.0, reps = 5, daysAgo = 1),
                    set(BENCH, weight = 100.0, reps = 5, daysAgo = 1),
                ),
            muscles =
                mapOf(
                    BENCH to
                        listOf(
                            MuscleLoad(Muscle.UPPER_CHEST, 100),
                            MuscleLoad(Muscle.TRICEPS, 65),
                            MuscleLoad(Muscle.FRONT_DELTS, 40),
                            MuscleLoad(Muscle.ABS, 10),
                        ),
                ),
        )

    assertEquals(2.0, report.totalFor(Muscle.UPPER_CHEST), 1e-6) // целевая — полный подход
    assertEquals(2.0, report.totalFor(Muscle.TRICEPS), 1e-6)
    assertEquals(1.0, report.totalFor(Muscle.FRONT_DELTS), 1e-6)
    assertEquals(0.0, report.totalFor(Muscle.ABS), 1e-6) // стабилизатор объёма не даёт
  }

  @Test
  fun `warm-up sets below sixty percent of the top weight are ignored`() {
    val report =
        analyze(
            sets =
                listOf(
                    set(BENCH, weight = 40.0, reps = 10, daysAgo = 1), // разминка: 40% от рабочего
                    set(BENCH, weight = 100.0, reps = 5, daysAgo = 1),
                    set(BENCH, weight = 100.0, reps = 5, daysAgo = 1),
                ),
            muscles = mapOf(BENCH to listOf(MuscleLoad(Muscle.UPPER_CHEST, 100))),
        )

    assertEquals(2.0, report.totalHardSets, 1e-6)
    assertEquals(2.0, report.totalFor(Muscle.UPPER_CHEST), 1e-6)
  }

  @Test
  fun `bodyweight sets without a weight all count as hard sets`() {
    val report =
        analyze(
            sets =
                listOf(
                    set(PULLUP, weight = null, reps = 10, daysAgo = 1),
                    set(PULLUP, weight = null, reps = 8, daysAgo = 1),
                ),
            muscles = mapOf(PULLUP to listOf(MuscleLoad(Muscle.LATS, 100))),
        )

    assertEquals(2.0, report.totalHardSets, 1e-6)
  }

  @Test
  fun `cardio sets never count as hard sets but do count as minutes`() {
    val report =
        analyze(
            sets =
                listOf(
                    set(
                            TREADMILL,
                            weight = null,
                            reps = null,
                            daysAgo = 1,
                            type = ExerciseType.CARDIO,
                        )
                        .copy(durationSec = 1_800, speedKmh = 10.0),
                ),
            muscles = mapOf(TREADMILL to listOf(MuscleLoad(Muscle.QUADS, 100))),
        )

    assertEquals(0.0, report.totalHardSets, 1e-6)
    assertEquals(0.0, report.totalFor(Muscle.QUADS), 1e-6)
    assertEquals(30, report.cardioMinutes)
    assertTrue("МЕТ-минуты должны появиться", report.aerobicMetMinutesPerWeek > 0.0)
  }

  // endregion

  // region volume zones

  @Test
  fun `a muscle with no work lands in the low volume zone`() {
    val report =
        analyze(
            sets = listOf(set(BENCH, weight = 100.0, reps = 5, daysAgo = 1)),
            muscles = mapOf(BENCH to listOf(MuscleLoad(Muscle.UPPER_CHEST, 100))),
        )

    val calves = report.muscleLoads.first { it.muscle == Muscle.CALVES }
    assertEquals(VolumeZone.LOW, calves.zone)
    assertEquals(0.0, calves.weeklySets, 1e-6)
  }

  @Test
  fun `weekly sets are averaged over the window, not summed`() {
    // 12 подходов на неделю в течение 4 недель: среднее должно остаться 12, а не стать 48.
    val sets =
        (0..3).flatMap { week ->
          (1..12).map { set(BENCH, weight = 100.0, reps = 5, daysAgo = week * 7L + 1) }
        }
    val report =
        analyze(
            sets = sets,
            muscles = mapOf(BENCH to listOf(MuscleLoad(Muscle.UPPER_CHEST, 100))),
            period = AnalysisPeriod.WEEKS_4,
            historyStartsDaysAgo = 90,
        )

    val chest = report.muscleLoads.first { it.muscle == Muscle.UPPER_CHEST }
    assertEquals(12.0, chest.weeklySets, 0.01)
    assertEquals(VolumeZone.GROWTH_GUIDE, chest.zone)
  }

  @Test
  fun `ten or more weekly sets reach the growth guide zone`() {
    val sets =
        (0..3).flatMap { week ->
          (1..30).map { set(BENCH, weight = 100.0, reps = 5, daysAgo = week * 7L + 1) }
        }
    val report =
        analyze(
            sets = sets,
            muscles = mapOf(BENCH to listOf(MuscleLoad(Muscle.UPPER_CHEST, 100))),
            period = AnalysisPeriod.WEEKS_4,
            historyStartsDaysAgo = 90,
        )

    assertEquals(
        VolumeZone.GROWTH_GUIDE,
        report.muscleLoads.first { it.muscle == Muscle.UPPER_CHEST }.zone,
    )
  }

  @Test
  fun `muscle summary reports the exercises that loaded it most`() {
    val report =
        analyze(
            sets =
                listOf(
                    set(BENCH, weight = 100.0, reps = 5, daysAgo = 1),
                    set(BENCH, weight = 100.0, reps = 5, daysAgo = 1),
                    set(PULLUP, weight = null, reps = 8, daysAgo = 2),
                ),
            muscles =
                mapOf(
                    BENCH to listOf(MuscleLoad(Muscle.UPPER_CHEST, 100)),
                    PULLUP to listOf(MuscleLoad(Muscle.UPPER_CHEST, 100)),
                ),
        )

    val chest = report.muscleLoads.first { it.muscle == Muscle.UPPER_CHEST }
    assertEquals(listOf("Жим лёжа", "Подтягивания"), chest.topExercises)
    assertEquals(1, chest.daysSinceLast) // последний стимул — вчерашний жим
  }

  // endregion

  // region window

  @Test
  fun `the window drops older sets`() {
    val report =
        analyze(
            sets =
                listOf(
                    set(BENCH, weight = 100.0, reps = 5, daysAgo = 3),
                    set(BENCH, weight = 100.0, reps = 5, daysAgo = 200),
                ),
            muscles = mapOf(BENCH to listOf(MuscleLoad(Muscle.UPPER_CHEST, 100))),
            period = AnalysisPeriod.WEEKS_4,
        )

    assertEquals(1.0, report.totalHardSets, 1e-6)
  }

  @Test
  fun `the all-time window keeps everything`() {
    val report =
        analyze(
            sets =
                listOf(
                    set(BENCH, weight = 100.0, reps = 5, daysAgo = 3),
                    set(BENCH, weight = 100.0, reps = 5, daysAgo = 400),
                ),
            muscles = mapOf(BENCH to listOf(MuscleLoad(Muscle.UPPER_CHEST, 100))),
            period = AnalysisPeriod.ALL_TIME,
        )

    assertEquals(2.0, report.totalHardSets, 1e-6)
  }

  @Test
  fun `seven day preset has seven inclusive dates and excludes the eighth`() {
    val report =
        analyze(
            sets =
                listOf(
                    set(BENCH, weight = 100.0, reps = 5, daysAgo = 6),
                    set(BENCH, weight = 100.0, reps = 5, daysAgo = 7),
                ),
            muscles = mapOf(BENCH to listOf(MuscleLoad(Muscle.UPPER_CHEST, 100))),
            period = AnalysisPeriod.LAST_7_DAYS,
        )

    assertEquals(today.minusDays(6), report.range.start)
    assertEquals(today, report.range.endInclusive)
    assertEquals(1.0, report.totalHardSets, 1e-6)
    assertEquals(1, report.weeklyPoints.size)
    assertFalse(report.weeklyPoints.single().partial)
  }

  @Test
  fun `custom range excludes data outside its dates and anchors pauses to its end`() {
    val end = today.minusDays(7)
    val report =
        analyze(
            sets =
                listOf(
                    set(BENCH, weight = 100.0, reps = 5, daysAgo = 9),
                    set(BENCH, weight = 100.0, reps = 5, daysAgo = 2),
                    set(BENCH, weight = 100.0, reps = 5, daysAgo = 22),
                ),
            muscles = mapOf(BENCH to listOf(MuscleLoad(Muscle.UPPER_CHEST, 100))),
            period = AnalysisPeriod.Custom(start = end.minusDays(13), endInclusive = end),
        )

    assertEquals(1.0, report.totalHardSets, 1e-6)
    assertEquals(2, report.daysSinceLast)
    assertEquals(2, report.muscleLoads.first { it.muscle == Muscle.UPPER_CHEST }.daysSinceLast)
  }

  @Test
  fun `weekly points use seven day buckets from custom range start`() {
    val start = today.minusDays(20)
    val end = start.plusDays(13)
    val report =
        analyze(
            sets =
                listOf(
                    set(BENCH, weight = 100.0, reps = 5, daysAgo = 20),
                    set(BENCH, weight = 100.0, reps = 5, daysAgo = 13),
                    set(BENCH, weight = 100.0, reps = 5, daysAgo = 7),
                ),
            muscles = mapOf(BENCH to listOf(MuscleLoad(Muscle.UPPER_CHEST, 100))),
            period = AnalysisPeriod.Custom(start = start, endInclusive = end),
        )

    assertEquals(2, report.weeklyPoints.size)
    assertEquals(start, report.weeklyPoints[0].weekStart)
    assertEquals(start.plusDays(6), report.weeklyPoints[0].weekEndInclusive)
    assertEquals(start.plusDays(7), report.weeklyPoints[1].weekStart)
    assertEquals(end, report.weeklyPoints[1].weekEndInclusive)
    assertEquals(1.0, report.weeklyPoints[0].hardSets, 1e-6)
    assertEquals(2.0, report.weeklyPoints[1].hardSets, 1e-6)
    assertFalse(report.weeklyPoints.any { it.partial })
  }

  @Test
  fun `an empty history produces an empty report`() {
    val report =
        engine.analyze(
            AnalyticsInput(emptyList(), emptyList(), emptyMap(), nowMillis, zone),
            AnalysisPeriod.WEEKS_12,
        )

    assertFalse(report.hasData)
    assertTrue(report.muscleLoads.isEmpty())
    assertTrue(report.weeklyPoints.isEmpty())
  }

  // endregion

  // region exercise progress

  @Test
  fun `session best is the set with the highest estimated one rep max, not the heaviest`() {
    val report =
        analyze(
            sets =
                listOf(
                    set(BENCH, weight = 100.0, reps = 5, daysAgo = 1), // e1RM ≈ 116.7
                    set(BENCH, weight = 110.0, reps = 1, daysAgo = 1), // e1RM = 110
                ),
            muscles = mapOf(BENCH to listOf(MuscleLoad(Muscle.UPPER_CHEST, 100))),
        )

    val point = report.exercises.single().points.single()
    assertEquals(100.0 * (1 + 5 / 30.0), point.bestE1rm, 1e-6)
    assertEquals(110.0, point.bestWeightKg, 1e-6)
  }

  @Test
  fun `sets above the trusted rep cap do not produce a progress point`() {
    val report =
        analyze(
            sets = listOf(set(BENCH, weight = 40.0, reps = 20, daysAgo = 1)),
            muscles = mapOf(BENCH to listOf(MuscleLoad(Muscle.UPPER_CHEST, 100))),
        )

    assertTrue(report.exercises.isEmpty())
    assertTrue(report.records.isEmpty())
  }

  @Test
  fun `the rep max curve is monotone and reuses heavier long sets`() {
    val report =
        analyze(
            sets =
                listOf(
                    set(BENCH, weight = 100.0, reps = 8, daysAgo = 5),
                    set(BENCH, weight = 120.0, reps = 3, daysAgo = 1),
                ),
            muscles = mapOf(BENCH to listOf(MuscleLoad(Muscle.UPPER_CHEST, 100))),
        )

    val curve = report.exercises.single().repMaxes
    assertEquals(120.0, curve.first { it.reps == 3 }.weightKg, 1e-6)
    assertEquals(100.0, curve.first { it.reps == 8 }.weightKg, 1e-6) // 100×8 закрывает и 5, и 8
    assertEquals(100.0, curve.first { it.reps == 5 }.weightKg, 1e-6)
    assertTrue(
        "кривая должна убывать",
        curve.zipWithNext().all { (a, b) -> a.weightKg >= b.weightKg },
    )
    assertTrue(
        "выше максимума повторов точек нет",
        curve.none { it.reps > OneRepMax.MAX_TRUSTED_REPS },
    )
  }

  @Test
  fun `a rising series is reported as growing`() {
    val sets =
        (0..5).map { index ->
          set(BENCH, weight = 100.0 + index * 5, reps = 5, daysAgo = 35L - index * 7)
        }
    val report =
        analyze(
            sets,
            mapOf(BENCH to listOf(MuscleLoad(Muscle.UPPER_CHEST, 100))),
            AnalysisPeriod.ALL_TIME,
        )

    val progress = report.exercises.single()
    assertEquals(TrendVerdict.GROWING, progress.verdict)
    assertNotNull(progress.trendKgPerMonth)
    assertTrue(progress.trendPercentPerMonth!! > 0.0)
  }

  @Test
  fun `a flat series is reported as stalled`() {
    val sets =
        (0..5).map { index -> set(BENCH, weight = 100.0, reps = 5, daysAgo = 35L - index * 7) }
    val report =
        analyze(
            sets,
            mapOf(BENCH to listOf(MuscleLoad(Muscle.UPPER_CHEST, 100))),
            AnalysisPeriod.ALL_TIME,
        )

    assertEquals(TrendVerdict.STALLED, report.exercises.single().verdict)
  }

  @Test
  fun `a short series gets no verdict`() {
    val sets =
        listOf(
            set(BENCH, weight = 100.0, reps = 5, daysAgo = 14),
            set(BENCH, weight = 105.0, reps = 5, daysAgo = 7),
        )
    val report =
        analyze(
            sets,
            mapOf(BENCH to listOf(MuscleLoad(Muscle.UPPER_CHEST, 100))),
            AnalysisPeriod.ALL_TIME,
        )

    val progress = report.exercises.single()
    assertEquals(TrendVerdict.NOT_ENOUGH_DATA, progress.verdict)
    assertNull(progress.trendKgPerMonth)
  }

  @Test
  fun `records rank exercises by their best estimated one rep max over all history`() {
    val report =
        analyze(
            sets =
                listOf(
                    set(
                        BENCH,
                        weight = 100.0,
                        reps = 5,
                        daysAgo = 400,
                    ), // вне окна, но рекорд остаётся
                    set(PULLUP, weight = 20.0, reps = 5, daysAgo = 2),
                ),
            muscles = emptyMap(),
            period = AnalysisPeriod.WEEKS_4,
        )

    assertEquals(listOf("Жим лёжа", "Подтягивания"), report.records.map { it.name })
  }

  // endregion

  // region workload and balance

  @Test
  fun `workload ratio needs four weeks of history`() {
    val report =
        analyze(
            sets = listOf(set(BENCH, weight = 100.0, reps = 5, daysAgo = 3)),
            muscles = mapOf(BENCH to listOf(MuscleLoad(Muscle.UPPER_CHEST, 100))),
        )

    assertFalse(report.workload.hasEnoughData)
  }

  @Test
  fun `a steady load gives a workload ratio around one`() {
    val sets = (0..29).map { day -> set(BENCH, weight = 100.0, reps = 5, daysAgo = day.toLong()) }
    val report =
        analyze(
            sets,
            mapOf(BENCH to listOf(MuscleLoad(Muscle.UPPER_CHEST, 100))),
            AnalysisPeriod.ALL_TIME,
        )

    assertTrue(report.workload.hasEnoughData)
    assertEquals(1.0, report.workload.ratio!!, 0.15)
  }

  @Test
  fun `a spike week pushes the workload ratio above the safe corridor`() {
    val quiet = (7..29).map { day -> set(BENCH, weight = 100.0, reps = 5, daysAgo = day.toLong()) }
    val spike =
        (0..6).flatMap { day ->
          (1..5).map { set(BENCH, weight = 100.0, reps = 5, daysAgo = day.toLong()) }
        }
    val report =
        analyze(
            quiet + spike,
            mapOf(BENCH to listOf(MuscleLoad(Muscle.UPPER_CHEST, 100))),
            AnalysisPeriod.ALL_TIME,
        )

    assertTrue(
        "резкий скачок должен выйти за 1.3, получено ${report.workload.ratio}",
        report.workload.ratio!! > 1.3,
    )
  }

  @Test
  fun `workload windows end at the selected range end and ignore newer data`() {
    val end = today.minusDays(7)
    val withinRange = (7L..34L).map { day -> set(BENCH, weight = 100.0, reps = 5, daysAgo = day) }
    val newerSpike =
        (0L..6L).flatMap { day ->
          (1..5).map { set(BENCH, weight = 100.0, reps = 5, daysAgo = day) }
        }
    val report =
        analyze(
            sets = withinRange + newerSpike,
            muscles = mapOf(BENCH to listOf(MuscleLoad(Muscle.UPPER_CHEST, 100))),
            period = AnalysisPeriod.Custom(start = end.minusDays(27), endInclusive = end),
        )

    assertTrue(report.workload.hasEnoughData)
    assertEquals(1.0, report.workload.ratio!!, 1e-6)
  }

  @Test
  fun `push and pull volume are compared against the balance target`() {
    val report =
        analyze(
            sets =
                listOf(
                    set(BENCH, weight = 100.0, reps = 5, daysAgo = 1),
                    set(BENCH, weight = 100.0, reps = 5, daysAgo = 1),
                    set(PULLUP, weight = null, reps = 8, daysAgo = 2),
                    set(PULLUP, weight = null, reps = 8, daysAgo = 2),
                ),
            muscles =
                mapOf(
                    BENCH to listOf(MuscleLoad(Muscle.UPPER_CHEST, 100)),
                    PULLUP to listOf(MuscleLoad(Muscle.LATS, 100)),
                ),
        )

    val pushPull = report.balances.first { it.id == BalanceId.PUSH_PULL }
    assertEquals(1.0, pushPull.ratio!!, 1e-6)
    assertTrue(pushPull.inTarget)
  }

  @Test
  fun `a pull-only block is flagged as out of balance`() {
    val report =
        analyze(
            sets = (1..6).map { set(PULLUP, weight = null, reps = 8, daysAgo = 1) },
            muscles = mapOf(PULLUP to listOf(MuscleLoad(Muscle.LATS, 100))),
        )

    val pushPull = report.balances.first { it.id == BalanceId.PUSH_PULL }
    assertEquals(0.0, pushPull.ratio!!, 1e-6)
    assertFalse(pushPull.inTarget)
  }

  // endregion

  // region consistency

  @Test
  fun `the streak counts consecutive weeks with a session`() {
    val workouts = listOf(0L, 7L, 14L, 28L).map { workout("w$it", daysAgo = it) }
    val report =
        engine.analyze(
            AnalyticsInput(emptyList(), workouts, emptyMap(), nowMillis, zone),
            AnalysisPeriod.ALL_TIME,
        )

    assertEquals(3, report.streakWeeks) // неделя 21 дня назад пропущена — серия обрывается
    assertEquals(0, report.daysSinceLast)
  }

  @Test
  fun `average session length uses finished workouts only`() {
    val workouts =
        listOf(
            workout("a", daysAgo = 1, minutes = 60),
            workout("b", daysAgo = 3, minutes = 40),
        )
    val report =
        engine.analyze(
            AnalyticsInput(emptyList(), workouts, emptyMap(), nowMillis, zone),
            AnalysisPeriod.WEEKS_4,
        )

    assertEquals(50, report.avgSessionMinutes)
    assertEquals(2, report.sessions)
  }

  // endregion

  @Test
  fun `two week period includes fourteen days and normalizes muscle volume`() {
    val report =
        analyze(
            sets = listOf(0L, 13L, 14L).map { set(BENCH, weight = 80.0, reps = 5, daysAgo = it) },
            muscles = mapOf(BENCH to listOf(MuscleLoad(Muscle.UPPER_CHEST, 100))),
            period = AnalysisPeriod.WEEKS_2,
        )
    assertEquals(14L, report.range.days)
    assertEquals(2.0, report.periodWeeks, 1e-6)
    assertEquals(2, report.sessions)
    val chest = report.muscleLoads.first { it.muscle == Muscle.UPPER_CHEST }
    assertEquals(2.0, chest.totalSets, 1e-6)
    assertEquals(1.0, chest.weeklySets, 1e-6)
  }

  // region helpers

  /**
   * Прогоняет движок. Тренировки восстанавливаются из подходов (по одной на день), так что
   * `startedAt` совпадает с реальным временем подходов. [historyStartsDaysAgo] при необходимости
   * добавляет старый подход-«якорь» вне диапазона: так тесты отдельно фиксируют, что старые данные
   * не попадают в выбранный срез, но остаются в рекордах за всю историю.
   */
  private fun analyze(
      sets: List<AnalyticsSetRow>,
      muscles: Map<Long, List<MuscleLoad>>,
      period: AnalysisPeriod = AnalysisPeriod.WEEKS_12,
      historyStartsDaysAgo: Long? = null,
  ): AnalyticsReport {
    val anchor =
        historyStartsDaysAgo
            ?.let { listOf(set(BENCH, weight = 20.0, reps = 5, daysAgo = it)) }
            .orEmpty()
    val all = (sets + anchor).sortedBy { it.completedAt }
    val workouts =
        all.groupBy { it.workoutId }
            .map { (id, group) ->
              val start = group.minOf { it.completedAt }
              WorkoutEntity(
                  id = id,
                  name = "Тренировка",
                  startedAt = start,
                  finishedAt = start + 60 * 60_000L,
                  uploadStatus = UploadStatus.UPLOADED,
              )
            }
    return engine.analyze(
        AnalyticsInput(
            sets = all,
            workouts = workouts,
            muscleMap = muscles,
            nowMillis = nowMillis,
            zone = zone,
        ),
        period,
    )
  }

  private fun AnalyticsReport.totalFor(muscle: Muscle): Double =
      muscleLoads.first { it.muscle == muscle }.totalSets

  private fun daysAgo(days: Long): Long =
      today.minusDays(days).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

  private var setCounter = 0

  /** Подход; каждый вызов попадает в свою «тренировку дня», как это происходит в жизни. */
  private fun set(
      exerciseId: Long,
      weight: Double?,
      reps: Int?,
      daysAgo: Long,
      type: ExerciseType = ExerciseType.STRENGTH,
  ): AnalyticsSetRow {
    setCounter++
    return AnalyticsSetRow(
        workoutId = "w-$daysAgo",
        exerciseId = exerciseId,
        exerciseName = exerciseName(exerciseId),
        exerciseType = type,
        weightKg = weight,
        reps = reps,
        durationSec = null,
        speedKmh = null,
        inclinePct = null,
        completedAt = daysAgo(daysAgo) + setCounter * 1_000L,
    )
  }

  private fun workout(id: String, daysAgo: Long, minutes: Int = 60): WorkoutEntity {
    val start = daysAgo(daysAgo)
    return WorkoutEntity(
        id = id,
        name = "Тренировка",
        startedAt = start,
        finishedAt = start + minutes * 60_000L,
        uploadStatus = UploadStatus.UPLOADED,
    )
  }

  private fun exerciseName(exerciseId: Long): String =
      when (exerciseId) {
        BENCH -> "Жим лёжа"
        PULLUP -> "Подтягивания"
        else -> "Беговая дорожка"
      }

  private companion object {
    const val BENCH = 1L
    const val PULLUP = 2L
    const val TREADMILL = 3L
  }

  // endregion
}
