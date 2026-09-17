package com.valerochka1337.valerochkagym.data

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.backend.CloudRecord
import com.valerochka1337.valerochkagym.data.backend.PortableData
import com.valerochka1337.valerochkagym.data.backend.SyncSchema
import com.valerochka1337.valerochkagym.data.calendar.CalendarTimeResolver
import com.valerochka1337.valerochkagym.data.db.LocalEquipmentCatalog
import com.valerochka1337.valerochkagym.data.db.entity.CalendarEventAccountLinkEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarEventAccountLinkState
import com.valerochka1337.valerochkagym.data.db.entity.CalendarMigrationMetadataEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarMigrationPhase
import com.valerochka1337.valerochkagym.data.db.entity.CalendarMigrationStateEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarPlanEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExercisePersonalHintEntity
import com.valerochka1337.valerochkagym.data.db.entity.ProfileEntity
import com.valerochka1337.valerochkagym.data.db.entity.ProfileEquipmentPreferenceEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.ScheduledWorkoutEntity
import com.valerochka1337.valerochkagym.domain.ExperienceLevel
import com.valerochka1337.valerochkagym.domain.ProfileSex
import com.valerochka1337.valerochkagym.domain.TrainingGoal
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableDataTest : RoomDaoTest() {
  @Test
  fun `strength planner records use separate canonical wire without changing baseline profile`() =
      runTest {
        val owner = "10000000-0000-4000-8000-000000000001"
        val profileId = "c3439134-6252-3a3d-b458-94b983f5e298"
        val exerciseId = "30000000-0000-4000-8000-000000000003"
        val sql = db.openHelper.writableDatabase
        SyncSchema.install(sql)
        sql.execSQL("UPDATE backend_state SET owner=?,phase='OWNED' WHERE id=1", arrayOf(owner))
        sql.execSQL(
            "INSERT INTO strength_planner_profiles(scope,syncId,updatedAt) VALUES(?,?,?)",
            arrayOf<Any>(owner, profileId, 1800000000000L),
        )
        sql.execSQL(
            "INSERT INTO strength_planner_key_exercises(scope,exerciseSyncId,priority) VALUES(?,?,?)",
            arrayOf(owner, exerciseId, "HIGH"),
        )

        val snapshot = PortableData(sql).snapshot()

        val record = requireNotNull(snapshot["strength_planner_profile:$profileId"])
        assertEquals(profileId, record["syncId"]?.jsonPrimitive?.content)
        assertEquals(
            exerciseId,
            record["keyExercises"]
                ?.jsonArray
                ?.single()
                ?.jsonObject
                ?.get("exerciseId")
                ?.jsonPrimitive
                ?.content,
        )
        assertFalse(snapshot.keys.any { it == "profile:$profileId" })
      }

  @Test
  fun `workout RIR survives portable round trip and legacy omissions remain unknown`() = runTest {
    val workout = insertWorkout("rir-sync", finishedAt = 2000)
    val exercise =
        db.exerciseDao()
            .insert(
                com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity(
                    name = "Press",
                    muscleGroup = com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup.CHEST,
                    type = com.valerochka1337.valerochkagym.data.db.entity.ExerciseType.STRENGTH,
                )
            )
    val section = insertWorkoutExercise(workout, exercise)
    val setId = insertSet(section, 0, weightKg = 50.0, reps = 8, isCompleted = true)
    db.workoutDao()
        .updateSet(db.workoutDao().getSet(setId)!!.copy(legacyTargetRir = 3, actualRir = 0))
    val portable = PortableData(db.openHelper.writableDatabase)
    val payload = portable.snapshot(includeStandard = true).getValue("workout:$workout")
    db.withTransaction {
      portable.apply(listOf(CloudRecord("workout", workout, 1, payload = payload)), emptyList())
    }
    val restored = workoutFull(workout).exercises.single().sets.single()
    assertEquals(3, restored.legacyTargetRir)
    assertEquals(0, restored.actualRir)
    db.workoutDao().updateSet(restored.copy(actualRir = null, actualRirAtLeastFour = true))
    val rangePayload = portable.snapshot(includeStandard = true).getValue("workout:$workout")
    db.workoutDao().updateSet(restored.copy(actualRir = 2, actualRirAtLeastFour = false))
    db.withTransaction {
      portable.apply(
          listOf(CloudRecord("workout", workout, 2, payload = rangePayload)),
          emptyList(),
      )
    }
    assertTrue(workoutFull(workout).exercises.single().sets.single().actualRirAtLeastFour)
    assertEquals(null, workoutFull(workout).exercises.single().sets.single().actualRir)
    fun withoutRir(
        value: kotlinx.serialization.json.JsonElement
    ): kotlinx.serialization.json.JsonElement =
        when (value) {
          is JsonObject ->
              JsonObject(
                  value
                      .filterKeys { it !in setOf("targetRir", "actualRir", "actualRirAtLeastFour") }
                      .mapValues { withoutRir(it.value) }
              )
          is JsonArray -> JsonArray(value.map(::withoutRir))
          else -> value
        }
    db.withTransaction {
      portable.apply(
          listOf(
              CloudRecord("workout", workout, 2, payload = withoutRir(rangePayload) as JsonObject)
          ),
          emptyList(),
      )
    }
    assertEquals(null, workoutFull(workout).exercises.single().sets.single().actualRir)
    assertEquals(null, workoutFull(workout).exercises.single().sets.single().legacyTargetRir)
    assertFalse(workoutFull(workout).exercises.single().sets.single().actualRirAtLeastFour)
  }

  @Test
  fun `profile wire atomically replaces canonical children and rejects wrong scalar types`() =
      runTest {
        val owner = "owner-7"
        val syncId = "9e27b903-8c27-34a4-82fa-164c43cf1212"
        val allEquipment = LocalEquipmentCatalog.entries.take(4).map { it.id }
        assertEquals(4, allEquipment.size)
        val localEquipment = allEquipment.take(2)
        val serverEquipment = allEquipment.drop(2)
        SyncSchema.install(db.openHelper.writableDatabase)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE backend_state SET owner=?,phase='OWNED' WHERE id=1",
            arrayOf(owner),
        )
        db.profileDao()
            .upsert(
                ProfileEntity(
                    owner,
                    syncId,
                    trainingGoal = "OTHER",
                    updatedAt = 1,
                    preferredRepMin = 6,
                    preferredRepMax = 12,
                )
            )
        db.profileDao()
            .upsertEquipment(localEquipment.map { ProfileEquipmentPreferenceEntity(owner, it) })
        val payload = buildJsonObject {
          put("schemaVersion", 1)
          put("syncId", syncId)
          put("updatedAt", 7)
          put("trainingGoal", "STRENGTH")
          put("sex", JsonNull)
          put("birthDate", "2000-02-29")
          put("experienceLevel", "BEGINNER")
          put("plannedSessionsPerWeek", 3)
          put("preferredSessionDurationMinutes", 45)
          put("manualConstraints", "Без прыжков")
          put("equipmentIds", JsonArray(serverEquipment.map(::JsonPrimitive)))
        }
        val portable = PortableData(db.openHelper.writableDatabase)

        db.withTransaction {
          portable.apply(listOf(CloudRecord("profile", syncId, 1, payload = payload)), emptyList())
        }
        assertEquals(serverEquipment.sorted(), db.profileDao().equipmentIds(owner))
        assertEquals("STRENGTH", db.profileDao().get(owner)?.trainingGoal)
        assertEquals(null, db.profileDao().get(owner)?.sex)
        assertEquals(6, db.profileDao().get(owner)?.preferredRepMin)
        assertEquals(12, db.profileDao().get(owner)?.preferredRepMax)
        assertFalse(portable.snapshot().getValue("profile:$syncId").containsKey("preferredRepMin"))

        val malformed = JsonObject(payload + ("plannedSessionsPerWeek" to JsonPrimitive("3")))
        try {
          db.withTransaction {
            portable.apply(
                listOf(CloudRecord("profile", syncId, 2, payload = malformed)),
                emptyList(),
            )
          }
          throw AssertionError("Profile wire types must be strict")
        } catch (_: IllegalStateException) {}
        assertEquals(serverEquipment.sorted(), db.profileDao().equipmentIds(owner))
        assertEquals("STRENGTH", db.profileDao().get(owner)?.trainingGoal)

        val withUnknownField =
            JsonObject(payload + ("unexpected" to JsonPrimitive("must not persist")))
        try {
          db.withTransaction {
            portable.apply(
                listOf(CloudRecord("profile", syncId, 3, payload = withUnknownField)),
                emptyList(),
            )
          }
          throw AssertionError("Profile wire shape must not accept unknown fields")
        } catch (_: IllegalStateException) {}
        assertEquals(serverEquipment.sorted(), db.profileDao().equipmentIds(owner))
        assertEquals("STRENGTH", db.profileDao().get(owner)?.trainingGoal)

        val nonCanonicalDate = JsonObject(payload + ("birthDate" to JsonPrimitive("+02000-02-29")))
        try {
          db.withTransaction {
            portable.apply(
                listOf(CloudRecord("profile", syncId, 4, payload = nonCanonicalDate)),
                emptyList(),
            )
          }
          throw AssertionError("Profile birth dates must be canonical ISO local dates")
        } catch (_: IllegalStateException) {}
        assertEquals(serverEquipment.sorted(), db.profileDao().equipmentIds(owner))
        assertEquals("STRENGTH", db.profileDao().get(owner)?.trainingGoal)

        var revision = 5L
        TrainingGoal.entries.forEach { goal ->
          db.withTransaction {
            portable.apply(
                listOf(
                    CloudRecord(
                        "profile",
                        syncId,
                        revision++,
                        payload =
                            JsonObject(payload + ("trainingGoal" to JsonPrimitive(goal.name))),
                    ),
                ),
                emptyList(),
            )
          }
          assertEquals(goal.name, db.profileDao().get(owner)?.trainingGoal)
        }
        ProfileSex.entries.forEach { sex ->
          db.withTransaction {
            portable.apply(
                listOf(
                    CloudRecord(
                        "profile",
                        syncId,
                        revision++,
                        payload = JsonObject(payload + ("sex" to JsonPrimitive(sex.name))),
                    ),
                ),
                emptyList(),
            )
          }
          assertEquals(sex.name, db.profileDao().get(owner)?.sex)
        }
        ExperienceLevel.entries.forEach { experience ->
          db.withTransaction {
            portable.apply(
                listOf(
                    CloudRecord(
                        "profile",
                        syncId,
                        revision++,
                        payload =
                            JsonObject(
                                payload + ("experienceLevel" to JsonPrimitive(experience.name))
                            ),
                    ),
                ),
                emptyList(),
            )
          }
          assertEquals(experience.name, db.profileDao().get(owner)?.experienceLevel)
        }
      }

  @Test
  fun `personal hint uses stable exercise sync id and remote tombstone removes only that hint`() =
      runTest {
        db.exercisePersonalHintDao().upsert(ExercisePersonalHintEntity("exercise-sync", " cue ", 7))
        val portable = PortableData(db.openHelper.writableDatabase)
        val payload = portable.snapshot().getValue("exercise_hint:exercise-sync")
        assertEquals(" cue ", payload["text"]?.jsonPrimitive?.content)

        db.withTransaction {
          portable.apply(
              emptyList(),
              listOf(CloudRecord("exercise_hint", "exercise-sync", 8, deleted = true)),
          )
        }
        assertEquals(null, db.exercisePersonalHintDao().get("exercise-sync"))
      }

  @Test
  fun `calendar owner links remain device local and absent from wire snapshots`() = runTest {
    val routineId = db.routineDao().upsertRoutine(RoutineEntity(name = "Ноги"))
    val scheduledId =
        db.scheduledWorkoutDao()
            .insert(
                ScheduledWorkoutEntity(
                    routineId = routineId,
                    dateTimeMillis = 1,
                    calendarEventId = "event",
                )
            )
    db.calendarEventAccountLinkDao()
        .insert(
            CalendarEventAccountLinkEntity(
                scheduledId,
                "private-owner@example.com",
                CalendarEventAccountLinkState.OWNED,
            )
        )

    val wire =
        PortableData(db.openHelper.writableDatabase).snapshot(includeStandard = true).toString()

    assertFalse(wire.contains("private-owner@example.com"))
    assertFalse(wire.contains("calendar_event_account_links"))
  }

  @Test
  fun `ready legacy schedule remote upsert and delete preserve both projections through the bridge`() =
      runTest {
        val routineId =
            db.routineDao().upsertRoutine(RoutineEntity(syncId = "routine-a", name = "Ноги"))
        db.calendarPlanDao().insertMigrationState(CalendarMigrationStateEntity())
        db.calendarPlanDao()
            .upsertMigrationMetadata(
                CalendarMigrationMetadataEntity(
                    sourceFingerprint = "source",
                    zoneId = "Europe/Moscow",
                    weeklyStartLocalDate = "2026-09-10",
                    observedOwners = "[null]",
                )
            )
        db.calendarPlanDao().setMigrationPhase(CalendarMigrationPhase.READY)
        val eventId = "remote-event"
        val legacyId =
            UUID.nameUUIDFromBytes("ValerochkaGym.schedule:$eventId".toByteArray(UTF_8)).toString()
        val record =
            CloudRecord(
                "schedule",
                legacyId,
                7,
                payload =
                    buildJsonObject {
                      put("routineId", "routine-a")
                      put("dateTimeMillis", 1_790_000_000_000)
                      put("calendarEventId", eventId)
                    },
            )

        db.withTransaction {
          PortableData(db.openHelper.writableDatabase).apply(listOf(record), emptyList())
        }
        val planId = CalendarTimeResolver.planId(legacyId)
        assertEquals(1, tableCount("scheduled_workouts"))
        assertEquals(planId, db.calendarPlanDao().plan(planId)?.id)
        assertEquals("Europe/Moscow", db.calendarPlanDao().plan(planId)?.timeZoneId)
        assertTrue(
            PortableData(db.openHelper.writableDatabase)
                .snapshot()
                .containsKey("schedule:$legacyId")
        )

        val updated =
            record.copy(
                revision = 8,
                payload =
                    buildJsonObject {
                      put("routineId", "routine-a")
                      put("dateTimeMillis", 1_790_100_000_000)
                      put("calendarEventId", eventId)
                    },
            )
        db.withTransaction {
          PortableData(db.openHelper.writableDatabase).apply(listOf(updated), emptyList())
        }
        assertEquals(1_790_100_000_000, db.calendarPlanDao().plan(planId)?.startsAtMillis)

        db.withTransaction {
          PortableData(db.openHelper.writableDatabase)
              .apply(
                  emptyList(),
                  listOf(updated.copy(deleted = true, payload = null, revision = 9)),
              )
        }
        assertEquals(0, tableCount("scheduled_workouts"))
        assertEquals(0, tableCount("calendar_plans"))
      }

  @Test
  fun `divergent canonical plan survives a later legacy schedule update and delete`() = runTest {
    val routineId =
        db.routineDao().upsertRoutine(RoutineEntity(syncId = "routine-a", name = "Ноги"))
    db.calendarPlanDao().insertMigrationState(CalendarMigrationStateEntity())
    db.calendarPlanDao()
        .upsertMigrationMetadata(
            CalendarMigrationMetadataEntity(
                sourceFingerprint = "source",
                zoneId = "Europe/Moscow",
                weeklyStartLocalDate = "2026-09-10",
                observedOwners = "[null]",
            )
        )
    db.calendarPlanDao().setMigrationPhase(CalendarMigrationPhase.READY)
    val eventId = "remote-event-divergent"
    val legacyId =
        UUID.nameUUIDFromBytes("ValerochkaGym.schedule:$eventId".toByteArray(UTF_8)).toString()
    val planId = CalendarTimeResolver.planId(legacyId)
    fun record(revision: Long, startsAt: Long) =
        CloudRecord(
            "schedule",
            legacyId,
            revision,
            payload =
                buildJsonObject {
                  put("routineId", "routine-a")
                  put("dateTimeMillis", startsAt)
                  put("calendarEventId", eventId)
                },
        )
    db.withTransaction {
      PortableData(db.openHelper.writableDatabase)
          .apply(listOf(record(1, 1_790_000_000_000)), emptyList())
    }
    db.calendarPlanDao()
        .upsertPlan(
            CalendarPlanEntity(
                planId,
                routineId,
                1_799_000_000_000,
                "Europe/Moscow",
                legacyId,
            )
        )

    db.withTransaction {
      PortableData(db.openHelper.writableDatabase)
          .apply(listOf(record(2, 1_790_100_000_000)), emptyList())
    }
    assertEquals(1_799_000_000_000, db.calendarPlanDao().plan(planId)?.startsAtMillis)
    db.withTransaction {
      PortableData(db.openHelper.writableDatabase)
          .apply(emptyList(), listOf(record(3, 0).copy(deleted = true, payload = null)))
    }
    assertEquals(1_799_000_000_000, db.calendarPlanDao().plan(planId)?.startsAtMillis)
  }
}
