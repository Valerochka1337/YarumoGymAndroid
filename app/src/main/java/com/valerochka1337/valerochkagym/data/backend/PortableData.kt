package com.valerochka1337.valerochkagym.data.backend

import android.content.ContentValues
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.valerochka1337.valerochkagym.data.calendar.CalendarTimeResolver
import com.valerochka1337.valerochkagym.data.profile.ProfileValidator
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import kotlinx.serialization.json.*

/** Portable aggregate mapping. Room row IDs stay on this device; all wire references use UUIDs. */
class PortableData(private val db: SupportSQLiteDatabase) {
  private val json = Json {
    ignoreUnknownKeys = false
    explicitNulls = true
    encodeDefaults = true
  }
  private val booleans =
      setOf("isCustom", "needsMuscleMapReview", "inventoryConfigured", "isCompleted")
  private val localFields =
      setOf("id", "syncId", "uploadStatus", "uploadError", "origin", "archived")

  private val workoutFields =
      setOf("name", "note", "routineId", "startedAt", "finishedAt", "coachRevision")
  private val sectionFields = setOf("exerciseId", "sectionId", "position")
  private val loadFields = listOf("WeightKg", "Reps", "DurationSec", "SpeedKmh", "InclinePct")
  private val setFields =
      setOf(
          "note",
          "syncId",
          "setIndex",
          "weightKg",
          "reps",
          "durationSec",
          "speedKmh",
          "inclinePct",
          "isCompleted",
          "completedAt",
          "setType",
          "reportedFeelingsJson",
          "restSnapshotJson",
          "coachMutationRevision",
      ) +
          listOf("original", "target", "actual").flatMap { prefix ->
            loadFields.map { prefix + it }
          }

  private fun rows(
      table: String,
      where: String = "",
      args: Array<out Any?> = emptyArray(),
  ): List<JsonObject> =
      db.query("SELECT * FROM $table $where", args).use { c ->
        buildList {
          while (c.moveToNext()) add(
              buildJsonObject {
                c.columnNames.forEachIndexed { i, name ->
                  put(
                      name,
                      when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> JsonNull
                        Cursor.FIELD_TYPE_INTEGER ->
                            if (name in booleans) JsonPrimitive(c.getLong(i) != 0L)
                            else JsonPrimitive(c.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(c.getDouble(i))
                        else -> JsonPrimitive(c.getString(i))
                      },
                  )
                }
              }
          )
        }
      }

  private fun JsonObject.s(key: String) = getValue(key).jsonPrimitive.content

  private fun JsonObject.portable(vararg exclude: String) =
      filterKeys { it !in localFields && it !in exclude }.toMutableMap()

  private fun array(values: List<JsonElement>) = JsonArray(values)

  private fun profileScope(): String =
      db.query("SELECT owner FROM backend_state WHERE id=1").use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else "GUEST"
      }

  fun snapshot(includeStandard: Boolean = false): Map<String, JsonObject> {
    val result = linkedMapOf<String, JsonObject>()
    val exercises = rows("exercises")
    val gyms = rows("gyms")
    val routines = rows("routines")
    val exerciseIds = exercises.associate { it.s("id") to it.s("syncId") }
    val gymIds = gyms.associate { it.s("id") to it.s("syncId") }
    val routineIds = routines.associate { it.s("id") to it.s("syncId") }
    rows("profiles", "WHERE scope=?", arrayOf(profileScope())).firstOrNull()?.let { profile ->
      val equipmentIds =
          rows(
                  "profile_equipment",
                  "WHERE scope=? ORDER BY equipmentId",
                  arrayOf(profile.s("scope")),
              )
              .map { it.getValue("equipmentId") }
      result["profile:${profile.s("syncId")}"] = buildJsonObject {
        put("schemaVersion", profile.getValue("schemaVersion"))
        put("syncId", profile.getValue("syncId"))
        put("updatedAt", profile.getValue("updatedAt"))
        put("trainingGoal", profile["trainingGoal"] ?: JsonNull)
        put("sex", profile["sex"] ?: JsonNull)
        put("birthDate", profile["birthDate"] ?: JsonNull)
        put("experienceLevel", profile["experienceLevel"] ?: JsonNull)
        put("plannedSessionsPerWeek", profile["plannedSessionsPerWeek"] ?: JsonNull)
        put(
            "preferredSessionDurationMinutes",
            profile["preferredSessionDurationMinutes"] ?: JsonNull,
        )
        put("manualConstraints", profile["manualConstraints"] ?: JsonNull)
        put("equipmentIds", array(equipmentIds))
      }
    }
    rows("strength_planner_profiles", "WHERE scope=?", arrayOf(profileScope()))
        .firstOrNull()
        ?.let { profile ->
          val keys =
              rows(
                      "strength_planner_key_exercises",
                      "WHERE scope=? ORDER BY CASE priority WHEN 'HIGH' THEN 0 ELSE 1 END, exerciseSyncId",
                      arrayOf(profile.s("scope")),
                  )
                  .mapNotNull { key ->
                    key["exerciseSyncId"]?.jsonPrimitive?.content?.let { exerciseId ->
                      buildJsonObject {
                        put("exerciseId", exerciseId)
                        put("priority", key.getValue("priority"))
                      }
                    }
                  }
          result["strength_planner_profile:${profile.s("syncId")}"] = buildJsonObject {
            put("schemaVersion", 1)
            put("syncId", profile.getValue("syncId"))
            put("updatedAt", profile.getValue("updatedAt"))
            put("keyExercises", JsonArray(keys))
          }
        }
    fun links(
        table: String,
        ownerColumn: String,
        owner: String,
        target: String,
        map: Map<String, String>,
    ) =
        array(
            rows(table, "WHERE $ownerColumn=?", arrayOf(owner))
                .map { JsonPrimitive(map.getValue(it.s(target))) }
                .sortedBy { it.toString() }
        )
    exercises.forEach { e ->
      val n = e.portable()
      n["muscles"] =
          array(
              rows("exercise_muscles", "WHERE exerciseId=? ORDER BY muscle", arrayOf(e.s("id")))
                  .map { JsonObject(it - "exerciseId") }
          )
      n["equipmentIds"] =
          array(
              rows(
                      "exercise_equipment",
                      "WHERE exerciseId=? ORDER BY equipmentId",
                      arrayOf(e.s("id")),
                  )
                  .map { it.getValue("equipmentId") }
          )
      result["exercise:${e.s("syncId")}"] = JsonObject(n)
    }
    gyms.forEach { g ->
      val n = g.portable()
      n["exerciseIds"] = links("gym_exercises", "gymId", g.s("id"), "exerciseId", exerciseIds)
      n["equipmentIds"] =
          array(
              rows("gym_equipment", "WHERE gymId=? ORDER BY equipmentId", arrayOf(g.s("id"))).map {
                it.getValue("equipmentId")
              }
          )
      result["gym:${g.s("syncId")}"] = JsonObject(n)
    }
    routines.forEach { r ->
      val n = r.portable()
      n["gymIds"] = links("routine_gyms", "routineId", r.s("id"), "gymId", gymIds)
      n["exercises"] =
          array(
              rows(
                      "routine_exercises",
                      "WHERE routineId=? ORDER BY position,id",
                      arrayOf(r.s("id")),
                  )
                  .map { e ->
                    JsonObject(
                        e.portable("routineId", "plannedSetsJson").apply {
                          put("exerciseId", JsonPrimitive(exerciseIds.getValue(e.s("exerciseId"))))
                          put("plannedSets", json.parseToJsonElement(e.s("plannedSetsJson")))
                        }
                    )
                  }
          )
      result["routine:${r.s("syncId")}"] = JsonObject(n)
    }
    rows("workouts").forEach { w ->
      val n = w.filterKeys { it in workoutFields }.toMutableMap()
      n["routineId"] =
          w["routineId"]
              ?.takeUnless { it == JsonNull }
              ?.let { routineIds[it.jsonPrimitive.content] }
              ?.let(::JsonPrimitive) ?: JsonNull
      n["gymIds"] = links("workout_gyms", "workoutId", w.s("id"), "gymId", gymIds)
      n["exercises"] =
          array(
              rows(
                      "workout_exercises",
                      "WHERE workoutId=? ORDER BY position,id",
                      arrayOf(w.s("id")),
                  )
                  .map { e ->
                    JsonObject(
                        e.filterKeys { it in sectionFields }
                            .toMutableMap()
                            .apply {
                              put(
                                  "exerciseId",
                                  JsonPrimitive(exerciseIds.getValue(e.s("exerciseId"))),
                              )
                              put(
                                  "sets",
                                  array(
                                      rows(
                                              "workout_sets",
                                              "WHERE workoutExerciseId=? ORDER BY setIndex,id",
                                              arrayOf(e.s("id")),
                                          )
                                          .map { row ->
                                            // Sets are the sole exception to the generic local
                                            // syncId
                                            // rule: their UUID is an immutable portable identity.
                                            JsonObject(
                                                row.filterKeys { it in setFields }
                                                    .toMutableMap()
                                                    .apply {
                                                      put("syncId", row.getValue("syncId"))
                                                    },
                                            )
                                          }
                                  ),
                              )
                            }
                    )
                  }
          )
      result["workout:${w.s("id")}"] = JsonObject(n)
    }
    rows("workout_efforts", "WHERE scope=?", arrayOf(profileScope())).forEach { effort ->
      result["workout_effort:${effort.s("syncId")}"] = buildJsonObject {
        put("schemaVersion", 1)
        put("syncId", effort.getValue("syncId"))
        put("workoutId", effort.getValue("workoutId"))
        put("updatedAt", effort.getValue("updatedAt"))
        put("effort", effort["effort"] ?: JsonNull)
      }
    }
    rows("body_measurements").forEach {
      result["measurement:${it.s("id")}"] = JsonObject(it.portable())
    }
    rows("exercise_personal_hints").forEach { hint ->
      result["exercise_hint:${hint.s("exerciseSyncId")}"] =
          JsonObject(hint.portable("exerciseSyncId"))
    }
    rows("scheduled_workouts").forEach { s ->
      val n = s.portable()
      n["routineId"] = JsonPrimitive(routineIds.getValue(s.s("routineId")))
      val id =
          UUID.nameUUIDFromBytes("ValerochkaGym.schedule:${s.s("calendarEventId")}".toByteArray())
              .toString()
      result["schedule:$id"] = JsonObject(n)
    }
    rows("calendar_plans").forEach { plan ->
      result["calendar_plan:${plan.s("id")}"] = buildJsonObject {
        put("routineId", JsonPrimitive(routineIds.getValue(plan.s("routineId"))))
        put("startsAtMillis", plan.getValue("startsAtMillis"))
        put("timeZoneId", plan.getValue("timeZoneId"))
        put("legacyScheduleId", plan["legacyScheduleId"] ?: JsonNull)
      }
    }
    rows("calendar_rules").forEach { rule ->
      result["calendar_rule:${rule.s("id")}"] = buildJsonObject {
        put("routineId", JsonPrimitive(routineIds.getValue(rule.s("routineId"))))
        put("isoDay", rule.getValue("isoDay"))
        put("localTime", rule.getValue("localTime"))
        put("timeZoneId", rule.getValue("timeZoneId"))
        put("startLocalDate", rule.getValue("startLocalDate"))
        put("legacyRuleKey", rule["legacyRuleKey"] ?: JsonNull)
      }
    }
    rows("calendar_exceptions").forEach { exception ->
      result["calendar_exception:${exception.s("id")}"] = buildJsonObject {
        put("ruleId", exception.getValue("ruleId"))
        put("instanceKey", exception.getValue("instanceKey"))
        put("kind", exception.getValue("kind"))
        put("movedAtMillis", exception["movedAtMillis"] ?: JsonNull)
      }
    }
    if (!includeStandard) {
      for ((kind, items) in
          listOf("exercise" to exercises, "gym" to gyms, "routine" to routines)) items
          .filter { it["origin"]?.jsonPrimitive?.content == "STANDARD" }
          .forEach { result.remove("$kind:${it.s("syncId")}") }
    }
    return result
  }

  private fun values(n: Map<String, JsonElement>): ContentValues =
      ContentValues().apply {
        n.forEach { (key, value) ->
          val p = value as? JsonPrimitive ?: error("Scalar required: $key")
          when {
            p == JsonNull -> putNull(key)
            p.isString -> put(key, p.content)
            p.booleanOrNull != null -> put(key, if (p.boolean) 1 else 0)
            p.longOrNull != null -> put(key, p.long)
            else -> put(key, p.double)
          }
        }
      }

  private fun insert(table: String, n: Map<String, JsonElement>): Long =
      db.insert(table, 0, values(n))

  private fun stable(table: String, id: String, n: Map<String, JsonElement>): Long {
    val old = rows(table, "WHERE syncId=?", arrayOf(id)).firstOrNull()
    return if (old == null) insert(table, n + mapOf("syncId" to JsonPrimitive(id)))
    else {
      val local = old.s("id").toLong()
      db.update(table, 0, values(n), "id=?", arrayOf(local))
      local
    }
  }

  private fun localId(table: String, id: JsonElement): JsonPrimitive =
      rows(table, "WHERE syncId=?", arrayOf(id.jsonPrimitive.content)).firstOrNull()?.get("id")
          as? JsonPrimitive ?: error("Missing $table reference")

  private fun calendarMigrationReady(): Boolean =
      db.query("SELECT phase FROM calendar_migration_state WHERE id=1").use {
        it.moveToFirst() && it.getString(0) == "READY"
      }

  private fun legacyScheduleId(eventId: String): String =
      UUID.nameUUIDFromBytes("ValerochkaGym.schedule:$eventId".toByteArray()).toString()

  /**
   * Old clients still write schedule records. Once migration has completed, preserve that record
   * and add only the missing deterministic plan; a canonical plan already present wins unchanged.
   */
  private fun bridgeLegacySchedule(
      eventId: String,
      routineId: Long,
      startsAtMillis: Long,
      revision: Long,
  ) {
    if (!calendarMigrationReady()) return
    val legacyId = legacyScheduleId(eventId)
    val planId = CalendarTimeResolver.planId(legacyId)
    // The migration captures this display zone once. A remote legacy record must never produce a
    // different canonical plan just because another device has a different default zone.
    val zoneId = capturedCalendarZoneId() ?: return
    val fingerprint = "$routineId|$startsAtMillis|$zoneId"
    val plan = rows("calendar_plans", "WHERE id=?", arrayOf(planId)).firstOrNull()
    val bridge =
        rows(
                "calendar_google_links",
                "WHERE objectKind=? AND objectId=?",
                arrayOf("legacy_schedule_bridge", planId),
            )
            .firstOrNull()
    val matched =
        plan != null &&
            bridge != null &&
            bridge["error"]?.jsonPrimitive?.content ==
                "${plan.s("routineId")}|${plan.s("startsAtMillis")}|${plan.s("timeZoneId")}" &&
            bridge["error"]?.jsonPrimitive?.content != fingerprint
    if (plan == null) {
      insert(
          "calendar_plans",
          mapOf(
              "id" to JsonPrimitive(planId),
              "routineId" to JsonPrimitive(routineId),
              "startsAtMillis" to JsonPrimitive(startsAtMillis),
              "timeZoneId" to JsonPrimitive(zoneId),
              "legacyScheduleId" to JsonPrimitive(legacyId),
          ),
      )
    } else if (matched) {
      db.update(
          "calendar_plans",
          0,
          values(
              mapOf(
                  "routineId" to JsonPrimitive(routineId),
                  "startsAtMillis" to JsonPrimitive(startsAtMillis),
                  "timeZoneId" to JsonPrimitive(zoneId),
              )
          ),
          "id=?",
          arrayOf(planId),
      )
    } else if (bridge == null) return
    if (plan == null || matched) {
      val linkValues =
          mapOf(
              "objectKind" to JsonPrimitive("legacy_schedule_bridge"),
              "objectId" to JsonPrimitive(planId),
              "ownerEmail" to JsonNull,
              "calendarId" to JsonNull,
              "eventId" to JsonPrimitive(eventId),
              "status" to JsonPrimitive("BRIDGED"),
              "error" to JsonPrimitive(fingerprint),
              "remoteRevision" to JsonPrimitive(revision),
          )
      if (bridge == null) insert("calendar_google_links", linkValues)
      else
          db.update(
              "calendar_google_links",
              0,
              values(linkValues),
              "objectKind=? AND objectId=?",
              arrayOf("legacy_schedule_bridge", planId),
          )
    }
  }

  private fun deleteBridgedLegacySchedule(eventId: String, revision: Long) {
    if (!calendarMigrationReady()) return
    val planId = CalendarTimeResolver.planId(legacyScheduleId(eventId))
    val bridge =
        rows(
                "calendar_google_links",
                "WHERE objectKind=? AND objectId=?",
                arrayOf("legacy_schedule_bridge", planId),
            )
            .firstOrNull() ?: return
    val bridgedRevision = bridge["remoteRevision"]?.jsonPrimitive?.longOrNull ?: return
    if (bridgedRevision > revision) return
    val plan = rows("calendar_plans", "WHERE id=?", arrayOf(planId)).firstOrNull()
    val matchesBridge =
        plan != null &&
            bridge["error"]?.jsonPrimitive?.content ==
                "${plan.s("routineId")}|${plan.s("startsAtMillis")}|${plan.s("timeZoneId")}"
    db.delete(
        "calendar_google_links",
        "objectKind=? AND objectId=?",
        arrayOf("legacy_schedule_bridge", planId),
    )
    if (matchesBridge)
        db.delete("calendar_plans", "id=? AND legacyScheduleId IS NOT NULL", arrayOf(planId))
  }

  private fun capturedCalendarZoneId(): String? =
      db.query("SELECT zoneId FROM calendar_migration_metadata WHERE id=1").use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
      }

  private fun replaceLinks(
      table: String,
      column: String,
      id: JsonPrimitive,
      rows: List<Map<String, JsonElement>>,
  ) {
    db.delete(table, "$column=?", arrayOf(id.content))
    rows.forEach { insert(table, it + mapOf(column to id)) }
  }

  /** Caller owns a Room transaction. Updates preserve local parent IDs and active section IDs. */
  fun apply(upserts: List<CloudRecord>, deletes: List<CloudRecord>) {
    val order =
        listOf(
            "profile",
            "exercise",
            "strength_planner_profile",
            "exercise_hint",
            "gym",
            "routine",
            "workout",
            "workout_effort",
            "measurement",
            "schedule",
            "calendar_plan",
            "calendar_rule",
            "calendar_exception",
        )
    upserts
        .sortedBy { order.indexOf(it.kind) }
        .forEach { r ->
          val n = r.payload!!
          when (r.kind) {
            "profile" -> {
              val scope = profileScope()
              if (scope == "GUEST") error("Remote profile requires an authenticated owner")
              val expectedId =
                  UUID.nameUUIDFromBytes("ValerochkaGym.profile.v1:$scope".toByteArray(UTF_8))
                      .toString()
              if (r.id != expectedId) error("Profile owner identity mismatch")
              val profile =
                  ProfileValidator.wireProfile(n, r.id, System.currentTimeMillis())
                      ?: error("Invalid profile payload")
              val body =
                  mapOf(
                      "scope" to JsonPrimitive(scope),
                      "syncId" to JsonPrimitive(r.id),
                      "schemaVersion" to JsonPrimitive(1),
                      "trainingGoal" to (n["trainingGoal"] ?: JsonNull),
                      "sex" to (n["sex"] ?: JsonNull),
                      "birthDate" to (n["birthDate"] ?: JsonNull),
                      "experienceLevel" to (n["experienceLevel"] ?: JsonNull),
                      "plannedSessionsPerWeek" to (n["plannedSessionsPerWeek"] ?: JsonNull),
                      "preferredSessionDurationMinutes" to
                          (n["preferredSessionDurationMinutes"] ?: JsonNull),
                      "manualConstraints" to (n["manualConstraints"] ?: JsonNull),
                      "updatedAt" to n.getValue("updatedAt"),
                  )
              if (rows("profiles", "WHERE scope=?", arrayOf(scope)).isEmpty())
                  insert("profiles", body)
              else db.update("profiles", 0, values(body), "scope=?", arrayOf(scope))
              db.delete("profile_equipment", "scope=?", arrayOf(scope))
              profile.equipmentIds.sorted().forEach { equipmentId ->
                insert(
                    "profile_equipment",
                    mapOf(
                        "scope" to JsonPrimitive(scope),
                        "equipmentId" to JsonPrimitive(equipmentId),
                    ),
                )
              }
            }
            "strength_planner_profile" -> {
              val scope = profileScope()
              if (scope == "GUEST") error("Remote strength profile requires an authenticated owner")
              val expectedId =
                  UUID.nameUUIDFromBytes(
                          "ValerochkaGym.strength-planner-profile.v1:$scope".toByteArray(UTF_8),
                      )
                      .toString()
              if (
                  r.id != expectedId ||
                      n.keys != setOf("schemaVersion", "syncId", "updatedAt", "keyExercises") ||
                      n["schemaVersion"]?.jsonPrimitive?.intOrNull != 1 ||
                      n["syncId"]?.jsonPrimitive?.content != r.id ||
                      n["updatedAt"]?.jsonPrimitive?.longOrNull?.takeIf { it >= 0 } == null
              )
                  error("Invalid strength planner profile payload")
              val keys = n["keyExercises"]?.jsonArray ?: error("Invalid strength planner keys")
              if (keys.size > 5) error("Too many strength planner keys")
              val mapped =
                  keys.map { item ->
                    val key = item.jsonObject
                    if (key.keys != setOf("exerciseId", "priority"))
                        error("Invalid strength planner key")
                    val exerciseSyncId = key.getValue("exerciseId").jsonPrimitive.content
                    val priority = key["priority"]?.jsonPrimitive?.content
                    if (priority !in setOf("HIGH", "NORMAL"))
                        error("Invalid strength planner priority")
                    exerciseSyncId to requireNotNull(priority)
                  }
              if (mapped.map { it.first }.distinct().size != mapped.size)
                  error("Duplicate strength planner key")
              if (
                  mapped !=
                      mapped.sortedWith(
                          compareBy<Pair<String, String>>(
                              { if (it.second == "HIGH") 0 else 1 },
                              { it.first },
                          ),
                      )
              )
                  error("Noncanonical strength planner key order")
              val body =
                  mapOf(
                      "scope" to JsonPrimitive(scope),
                      "syncId" to JsonPrimitive(r.id),
                      "updatedAt" to n.getValue("updatedAt"),
                  )
              if (rows("strength_planner_profiles", "WHERE scope=?", arrayOf(scope)).isEmpty())
                  insert("strength_planner_profiles", body)
              else
                  db.update("strength_planner_profiles", 0, values(body), "scope=?", arrayOf(scope))
              db.delete("strength_planner_key_exercises", "scope=?", arrayOf(scope))
              mapped.forEach { (exerciseSyncId, priority) ->
                insert(
                    "strength_planner_key_exercises",
                    mapOf(
                        "scope" to JsonPrimitive(scope),
                        "exerciseSyncId" to JsonPrimitive(exerciseSyncId),
                        "priority" to JsonPrimitive(priority),
                    ),
                )
              }
            }
            "exercise" -> {
              val id = JsonPrimitive(stable("exercises", r.id, n - "muscles" - "equipmentIds"))
              replaceLinks(
                  "exercise_muscles",
                  "exerciseId",
                  id,
                  n.getValue("muscles").jsonArray.map { it.jsonObject },
              )
              replaceLinks(
                  "exercise_equipment",
                  "exerciseId",
                  id,
                  n.getValue("equipmentIds").jsonArray.map { mapOf("equipmentId" to it) },
              )
            }
            "gym" -> {
              val id = JsonPrimitive(stable("gyms", r.id, n - "exerciseIds" - "equipmentIds"))
              replaceLinks(
                  "gym_exercises",
                  "gymId",
                  id,
                  n.getValue("exerciseIds").jsonArray.map {
                    mapOf("exerciseId" to localId("exercises", it))
                  },
              )
              replaceLinks(
                  "gym_equipment",
                  "gymId",
                  id,
                  n.getValue("equipmentIds").jsonArray.map { mapOf("equipmentId" to it) },
              )
            }
            "routine" -> {
              val id = JsonPrimitive(stable("routines", r.id, n - "exercises" - "gymIds"))
              replaceLinks(
                  "routine_gyms",
                  "routineId",
                  id,
                  n.getValue("gymIds").jsonArray.map { mapOf("gymId" to localId("gyms", it)) },
              )
              replaceLinks(
                  "routine_exercises",
                  "routineId",
                  id,
                  n.getValue("exercises").jsonArray.map { item ->
                    val e = item.jsonObject
                    (e - "plannedSets") +
                        mapOf(
                            "exerciseId" to localId("exercises", e.getValue("exerciseId")),
                            "plannedSetsJson" to
                                JsonPrimitive(e.getValue("plannedSets").toString()),
                        )
                  },
              )
            }
            "workout" -> {
              val id = JsonPrimitive(r.id)
              val body =
                  n.filterKeys { it in workoutFields } +
                      mapOf(
                          "id" to id,
                          "coachRevision" to (n["coachRevision"] ?: JsonPrimitive(0)),
                          "routineId" to
                              (n["routineId"]
                                  ?.takeUnless { it == JsonNull }
                                  ?.let { localId("routines", it) } ?: JsonNull),
                          "uploadStatus" to JsonPrimitive("UPLOADED"),
                          "uploadError" to JsonNull,
                      )
              if (rows("workouts", "WHERE id=?", arrayOf(r.id)).isEmpty()) insert("workouts", body)
              else db.update("workouts", 0, values(body), "id=?", arrayOf(r.id))
              replaceLinks(
                  "workout_gyms",
                  "workoutId",
                  id,
                  n.getValue("gymIds").jsonArray.map { mapOf("gymId" to localId("gyms", it)) },
              )
              val sections = n.getValue("exercises").jsonArray.map { it.jsonObject }
              val wanted = sections.map { it.s("sectionId") }.toSet()
              rows("workout_exercises", "WHERE workoutId=?", arrayOf(r.id))
                  .filter { it.s("sectionId") !in wanted }
                  .forEach { db.delete("workout_exercises", "id=?", arrayOf(it.s("id"))) }
              sections.forEach { e ->
                val fields =
                    e.filterKeys { it in sectionFields } +
                        mapOf(
                            "workoutId" to id,
                            "exerciseId" to localId("exercises", e.getValue("exerciseId")),
                        )
                val old =
                    rows("workout_exercises", "WHERE sectionId=?", arrayOf(e.s("sectionId")))
                        .firstOrNull()
                val section =
                    if (old == null) insert("workout_exercises", fields)
                    else
                        old.s("id").toLong().also {
                          db.update("workout_exercises", 0, values(fields), "id=?", arrayOf(it))
                        }
                // Never pull into a running local workout; the coordinator enforces this before
                // apply.
                val previousSets =
                    rows("workout_sets", "WHERE workoutExerciseId=?", arrayOf(section))
                        .associateBy { it.s("setIndex") }
                replaceLinks(
                    "workout_sets",
                    "workoutExerciseId",
                    JsonPrimitive(section),
                    e.getValue("sets").jsonArray.map { item ->
                      val incoming = item.jsonObject
                      // Old snapshots contain no goals or sensations. Do not invent them from
                      // results.
                      val defaults =
                          listOf("original", "target", "actual")
                              .flatMap { prefix -> loadFields.map { prefix + it to JsonNull } }
                              .toMap() +
                              mapOf(
                                  "note" to JsonPrimitive(""),
                                  "setType" to JsonPrimitive("UNKNOWN"),
                                  "reportedFeelingsJson" to JsonPrimitive("[]"),
                                  "restSnapshotJson" to JsonNull,
                                  "coachMutationRevision" to JsonPrimitive(0),
                                  "syncId" to
                                      (previousSets[incoming.s("setIndex")]?.get("syncId")
                                          ?: JsonPrimitive(
                                              UUID.nameUUIDFromBytes(
                                                      "ValerochkaGym.legacy-set:${r.id}:${e.s("sectionId")}:${incoming.s("setIndex")}"
                                                          .toByteArray()
                                                  )
                                                  .toString()
                                          )),
                              )
                      defaults + incoming.filterKeys { it in setFields }
                    },
                )
              }
            }
            "workout_effort" -> {
              val scope = profileScope()
              if (scope == "GUEST") error("Remote workout effort requires an authenticated owner")
              val workoutId =
                  n["workoutId"]?.jsonPrimitive?.content ?: error("Missing workout effort workout")
              val expectedId =
                  UUID.nameUUIDFromBytes(
                          "ValerochkaGym.workout-effort.v1:$scope:$workoutId".toByteArray(UTF_8)
                      )
                      .toString()
              if (
                  r.id != expectedId ||
                      n.keys !=
                          setOf("schemaVersion", "syncId", "workoutId", "updatedAt", "effort") ||
                      n["schemaVersion"]?.jsonPrimitive?.intOrNull != 1 ||
                      n["syncId"]?.jsonPrimitive?.content != r.id ||
                      n["updatedAt"]?.jsonPrimitive?.longOrNull?.takeIf { it >= 0 } == null ||
                      n["effort"]?.let {
                        it != JsonNull &&
                            it.jsonPrimitive.content !in setOf("EASY", "MODERATE", "HARD")
                      } == true
              )
                  error("Invalid workout effort payload")
              val workout =
                  rows("workouts", "WHERE id=?", arrayOf(workoutId)).singleOrNull()
                      ?: error("Missing workout effort parent")
              if (workout["finishedAt"] == JsonNull)
                  error("Workout effort needs a completed workout")
              val body =
                  mapOf(
                      "workoutId" to JsonPrimitive(workoutId),
                      "scope" to JsonPrimitive(scope),
                      "syncId" to JsonPrimitive(r.id),
                      "updatedAt" to n.getValue("updatedAt"),
                      "effort" to (n["effort"] ?: JsonNull),
                  )
              if (rows("workout_efforts", "WHERE workoutId=?", arrayOf(workoutId)).isEmpty())
                  insert("workout_efforts", body)
              else
                  db.update(
                      "workout_efforts",
                      0,
                      values(body),
                      "workoutId=? AND scope=?",
                      arrayOf(workoutId, scope),
                  )
            }
            "measurement" -> {
              val body =
                  n +
                      mapOf(
                          "id" to JsonPrimitive(r.id),
                          "uploadStatus" to JsonPrimitive("UPLOADED"),
                          "uploadError" to JsonNull,
                      )
              if (rows("body_measurements", "WHERE id=?", arrayOf(r.id)).isEmpty())
                  insert("body_measurements", body)
              else db.update("body_measurements", 0, values(body), "id=?", arrayOf(r.id))
            }
            "exercise_hint" -> {
              val body = n + mapOf("exerciseSyncId" to JsonPrimitive(r.id))
              if (
                  rows("exercise_personal_hints", "WHERE exerciseSyncId=?", arrayOf(r.id)).isEmpty()
              )
                  insert("exercise_personal_hints", body)
              else
                  db.update(
                      "exercise_personal_hints",
                      0,
                      values(body),
                      "exerciseSyncId=?",
                      arrayOf(r.id),
                  )
            }
            "schedule" -> {
              val body = n + mapOf("routineId" to localId("routines", n.getValue("routineId")))
              val old =
                  rows(
                          "scheduled_workouts",
                          "WHERE calendarEventId=?",
                          arrayOf(n.s("calendarEventId")),
                      )
                      .firstOrNull()
              if (old == null) insert("scheduled_workouts", body)
              else db.update("scheduled_workouts", 0, values(body), "id=?", arrayOf(old.s("id")))
              bridgeLegacySchedule(
                  n.s("calendarEventId"),
                  body.getValue("routineId").jsonPrimitive.long,
                  n.getValue("dateTimeMillis").jsonPrimitive.long,
                  r.revision,
              )
            }
            "calendar_plan" -> {
              val body =
                  n +
                      mapOf(
                          "id" to JsonPrimitive(r.id),
                          "routineId" to localId("routines", n.getValue("routineId")),
                      )
              if (rows("calendar_plans", "WHERE id=?", arrayOf(r.id)).isEmpty())
                  insert("calendar_plans", body)
              else db.update("calendar_plans", 0, values(body), "id=?", arrayOf(r.id))
              // A canonical record arriving alongside an old schedule supersedes bridge-only
              // metadata, while retaining both portable representations.
              db.delete(
                  "calendar_google_links",
                  "objectKind=? AND objectId=?",
                  arrayOf("legacy_schedule_bridge", r.id),
              )
            }
            "calendar_rule" -> {
              val body =
                  n +
                      mapOf(
                          "id" to JsonPrimitive(r.id),
                          "routineId" to localId("routines", n.getValue("routineId")),
                      )
              if (rows("calendar_rules", "WHERE id=?", arrayOf(r.id)).isEmpty())
                  insert("calendar_rules", body)
              else db.update("calendar_rules", 0, values(body), "id=?", arrayOf(r.id))
            }
            "calendar_exception" -> {
              val body = n + mapOf("id" to JsonPrimitive(r.id))
              if (rows("calendar_exceptions", "WHERE id=?", arrayOf(r.id)).isEmpty())
                  insert("calendar_exceptions", body)
              else db.update("calendar_exceptions", 0, values(body), "id=?", arrayOf(r.id))
            }
          }
        }
    deletes
        .sortedByDescending { order.indexOf(it.kind) }
        .forEach { r ->
          if (r.kind in setOf("profile", "strength_planner_profile"))
              error("Profile tombstones are a protocol violation")
          val table =
              when (r.kind) {
                "exercise" -> "exercises"
                "gym" -> "gyms"
                "routine" -> "routines"
                "workout" -> "workouts"
                "workout_effort" -> "workout_efforts"
                "measurement" -> "body_measurements"
                "exercise_hint" -> "exercise_personal_hints"
                "calendar_plan" -> "calendar_plans"
                "calendar_rule" -> "calendar_rules"
                "calendar_exception" -> "calendar_exceptions"
                else -> "scheduled_workouts"
              }
          if (r.kind == "schedule") {
            rows(table)
                .filter { legacyScheduleId(it.s("calendarEventId")) == r.id }
                .forEach {
                  db.delete(table, "id=?", arrayOf(it.s("id")))
                  deleteBridgedLegacySchedule(it.s("calendarEventId"), r.revision)
                }
          } else if (r.kind in setOf("calendar_plan", "calendar_rule", "calendar_exception"))
              db.delete(table, "id=?", arrayOf(r.id))
          else
              db.delete(
                  table,
                  if (r.kind in setOf("workout", "measurement", "workout_effort"))
                      if (r.kind == "workout_effort") "syncId=?" else "id=?"
                  else if (r.kind == "exercise_hint") "exerciseSyncId=?" else "syncId=?",
                  arrayOf(r.id),
              )
        }
  }
}
