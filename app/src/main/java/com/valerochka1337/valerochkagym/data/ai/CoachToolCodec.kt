package com.valerochka1337.valerochkagym.data.ai

import com.valerochka1337.valerochkagym.data.db.dao.CoachHistorySet
import com.valerochka1337.valerochkagym.domain.FoundCoachExercise
import com.valerochka1337.valerochkagym.domain.WorkoutSnapshot
import java.util.UUID
import kotlinx.serialization.json.*

/**
 * Wire intents contain portable identifiers only. The host resolves them within the pinned owner.
 */
sealed interface CoachToolRequest {
  data class Autoregulation(
      val options: com.valerochka1337.valerochkagym.domain.autoregulation.AutoregulationOptions?
  ) : CoachToolRequest

  data object State : CoachToolRequest

  data class Find(
      val query: String?,
      val equipmentIds: Set<String>?,
      val muscleIds: Set<String>?,
      val muscleGroups: Set<String>? = null,
      val limit: Int = 10,
  ) : CoachToolRequest

  data class History(val exerciseId: String) : CoachToolRequest

  data class Submit(
      val baseRevision: Long,
      val operations: List<CoachChangeIntent>,
      val reason: String?,
  ) : CoachToolRequest
}

sealed interface CoachChangeIntent {
  data class Autoregulate(
      val options: com.valerochka1337.valerochkagym.domain.autoregulation.AutoregulationOptions?
  ) : CoachChangeIntent

  data class AddExercise(val exerciseId: String, val position: Int? = null) : CoachChangeIntent

  data class RemoveRemaining(val sectionId: String) : CoachChangeIntent

  data class Move(val sectionId: String, val position: Int) : CoachChangeIntent

  data class Swap(val first: String, val second: String) : CoachChangeIntent

  data class Reorder(val sectionIds: List<String>) : CoachChangeIntent

  data class Replace(
      val sectionId: String,
      val exerciseId: String,
      val remainingSetIds: List<String>,
      val weightKg: Double?,
  ) : CoachChangeIntent

  data class AddSet(val sectionId: String) : CoachChangeIntent

  data class DeleteSet(val setId: String) : CoachChangeIntent

  data class EditSet(val setId: String, val values: CoachSetValues, val recordResult: Boolean) :
      CoachChangeIntent

  data class Complete(val setId: String, val completed: Boolean) : CoachChangeIntent

  data class Rest(val action: CoachRestAction, val seconds: Int?, val startId: String?) :
      CoachChangeIntent

  data class AvailableTime(val minutes: Int) : CoachChangeIntent

  data class ExcludedExercises(val ids: Set<String>) : CoachChangeIntent

  data class Feelings(val setId: String, val feelings: Set<String>) : CoachChangeIntent

  data object Undo : CoachChangeIntent
}

enum class CoachRestAction {
  START,
  EXTEND,
  SKIP,
  FUTURE_DURATION,
}

/** Supplied fields distinguish an omitted value from an explicit request to clear it. */
data class CoachSetValues(
    val supplied: Set<String>,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val durationSec: Int? = null,
    val speedKmh: Double? = null,
    val inclinePct: Double? = null,
    val actualRir: Int? = null,
    val setType: String? = null,
)

class CoachToolValidationException(message: String) : IllegalArgumentException(message)

/** Strict local parser remains authoritative even for providers that ignore JSON Schema. */
object CoachToolCodec {
  fun snapshotJson(snapshot: WorkoutSnapshot): String =
      json.encodeToString(
          buildJsonObject {
            put("workout_id", snapshot.workoutId)
            put("revision", snapshot.revision)
            put("elapsed_seconds", snapshot.elapsedSeconds)
            snapshot.availableTimeMinutes?.let { put("available_time_minutes", it) }
            put(
                "excluded_exercise_ids",
                buildJsonArray {
                  snapshot.excludedExerciseIds.sorted().forEach { add(JsonPrimitive(it)) }
                },
            )
            put(
                "profile",
                buildJsonObject {
                  snapshot.profile.trainingGoal?.let { put("training_goal", it) }
                  snapshot.profile.experienceLevel?.let { put("experience_level", it) }
                  snapshot.profile.constraints?.let { put("constraints", it) }
                  put("equipment_preferences", stringArray(snapshot.profile.equipmentIds))
                  snapshot.profile.preferredRepMin?.let { put("preferred_rep_min", it) }
                  snapshot.profile.preferredRepMax?.let { put("preferred_rep_max", it) }
                },
            )
            put(
                "decisions",
                Json.parseToJsonElement(
                    com.valerochka1337.valerochkagym.domain.CoachDecisionMemory.encode(
                        snapshot.coachDecisions
                    )
                ),
            )
            put("feelings", stringArray(snapshot.feelings))
            snapshot.pulse?.let { pulse ->
              put(
                  "pulse",
                  buildJsonObject {
                    put("bpm", pulse.bpm)
                    put("measured_at_millis", pulse.measuredAtMillis)
                  },
              )
            }
            snapshot.currentSetId?.let { put("current_set_id", it) }
            snapshot.previousSetId?.let { put("previous_set_id", it) }
            snapshot.nextSetId?.let { put("next_set_id", it) }
            snapshot.rest?.let { rest ->
              put(
                  "rest",
                  buildJsonObject {
                    put("start_id", rest.startId)
                    rest.plannedSeconds?.let { put("planned_seconds", it) }
                    rest.remainingSeconds?.let { put("remaining_seconds", it) }
                  },
              )
            }
            put(
                "exercises",
                buildJsonArray {
                  snapshot.exercises.forEach { exercise ->
                    add(
                        buildJsonObject {
                          put("section_id", exercise.sectionId)
                          put("exercise_id", exercise.exerciseSyncId)
                          put("name", exercise.name)
                          put("position", exercise.position)
                          put("muscles", stringArray(exercise.muscleIds))
                          put("equipment", stringArray(exercise.equipmentIds))
                          put(
                              "sets",
                              buildJsonArray {
                                exercise.sets.forEach { set ->
                                  add(
                                      buildJsonObject {
                                        put("set_id", set.syncId)
                                        put("index", set.setIndex)
                                        put("completed", set.completed)
                                        set.completedAt?.let { put("completed_at", it) }
                                        set.weightKg?.let { put("weight_kg", it) }
                                        set.reps?.let { put("reps", it) }
                                        set.durationSec?.let { put("duration_sec", it) }
                                        set.speedKmh?.let { put("speed_kmh", it) }
                                        set.inclinePct?.let { put("incline_pct", it) }
                                        put("set_type", set.setType)
                                        set.originalWeightKg?.let { put("original_weight_kg", it) }
                                        set.originalReps?.let { put("original_reps", it) }
                                        set.originalDurationSec?.let {
                                          put("original_duration_sec", it)
                                        }
                                        set.originalSpeedKmh?.let { put("original_speed_kmh", it) }
                                        set.originalInclinePct?.let {
                                          put("original_incline_pct", it)
                                        }
                                        set.targetWeightKg?.let { put("target_weight_kg", it) }
                                        set.targetReps?.let { put("target_reps", it) }
                                        set.targetDurationSec?.let {
                                          put("target_duration_sec", it)
                                        }
                                        set.targetSpeedKmh?.let { put("target_speed_kmh", it) }
                                        set.targetInclinePct?.let { put("target_incline_pct", it) }
                                        set.actualWeightKg?.let { put("actual_weight_kg", it) }
                                        set.actualReps?.let { put("actual_reps", it) }
                                        set.actualDurationSec?.let {
                                          put("actual_duration_sec", it)
                                        }
                                        set.actualSpeedKmh?.let { put("actual_speed_kmh", it) }
                                        set.actualInclinePct?.let { put("actual_incline_pct", it) }
                                        if (set.actualRirAtLeastFour)
                                            put("actual_rir_at_least_four", true)
                                        put("reported_feelings", stringArray(set.reportedFeelings))
                                        put(
                                            "actual_rir",
                                            set.actualRir?.let(::JsonPrimitive) ?: JsonNull,
                                        )
                                      }
                                  )
                                }
                              },
                          )
                          put(
                              "history",
                              buildJsonArray {
                                exercise.history.forEach { row ->
                                  add(
                                      buildJsonObject {
                                        put("completed_at", row.completedAt)
                                        put("set_index", row.setIndex)
                                        row.weightKg?.let { put("weight_kg", it) }
                                        row.reps?.let { put("reps", it) }
                                        row.durationSec?.let { put("duration_sec", it) }
                                        row.speedKmh?.let { put("speed_kmh", it) }
                                        row.inclinePct?.let { put("incline_pct", it) }
                                        put("set_type", row.setType)
                                        row.actualRir?.let { put("actual_rir", it) }
                                        if (row.actualRirAtLeastFour)
                                            put("actual_rir_at_least_four", true)
                                      }
                                  )
                                }
                              },
                          )
                        }
                    )
                  }
                },
            )
          },
      )

  private fun stringArray(values: Set<String>) = buildJsonArray {
    values.sorted().forEach { add(JsonPrimitive(it)) }
  }

  fun foundJson(exercises: List<FoundCoachExercise>): String {
    return json.encodeToString(
        buildJsonObject {
          put(
              "exercises",
              buildJsonArray {
                exercises.forEach { exercise ->
                  add(
                      buildJsonObject {
                        put("exercise_id", exercise.id)
                        put("name", exercise.name)
                        put("muscles", stringArray(exercise.muscles))
                        put("equipment", stringArray(exercise.equipment))
                        put("muscle_group", exercise.muscleGroup)
                        put("type", exercise.type)
                        put("last_used_at", exercise.lastUsedAt?.let(::JsonPrimitive) ?: JsonNull)
                        put("completed_workout_count", exercise.workoutCount)
                        put(
                            "current_section_ids",
                            JsonArray(exercise.currentSectionIds.map(::JsonPrimitive)),
                        )
                        put("last_workout_sets", historyRows(exercise.lastWorkoutSets))
                      }
                  )
                }
              },
          )
        }
    )
  }

  private fun historyRows(history: List<CoachHistorySet>) = buildJsonArray {
    history.forEach { historical ->
      val set = historical.set
      add(
          buildJsonObject {
            put("workout_id", historical.historyWorkoutId)
            put("workout_finished_at", historical.historyWorkoutFinishedAt)
            put("section_history_id", set.workoutExerciseId)
            put("set_index", set.setIndex)
            put("completed_at", set.completedAt?.let(::JsonPrimitive) ?: JsonNull)
            put("set_type", set.setType)
            set.actualRir?.let { put("actual_rir", it) }
            if (set.actualRirAtLeastFour) put("actual_rir_at_least_four", true)
            put("weight_kg", (set.actualWeightKg ?: set.weightKg)?.let(::JsonPrimitive) ?: JsonNull)
            put("reps", (set.actualReps ?: set.reps)?.let(::JsonPrimitive) ?: JsonNull)
            put(
                "duration_sec",
                (set.actualDurationSec ?: set.durationSec)?.let(::JsonPrimitive) ?: JsonNull,
            )
            put("speed_kmh", (set.actualSpeedKmh ?: set.speedKmh)?.let(::JsonPrimitive) ?: JsonNull)
            put(
                "incline_pct",
                (set.actualInclinePct ?: set.inclinePct)?.let(::JsonPrimitive) ?: JsonNull,
            )
          }
      )
    }
  }

  fun historyJson(history: List<CoachHistorySet>): String =
      json.encodeToString(buildJsonObject { put("history", historyRows(history)) })

  private val json = Json {
    isLenient = false
    ignoreUnknownKeys = false
  }
  private val valueFields =
      setOf(
          "weight_kg",
          "reps",
          "duration_sec",
          "speed_kmh",
          "incline_pct",
          "actual_rir",
          "set_type",
      )
  private val feelingValues =
      setOf(
          "PAIN",
          "FATIGUE",
          "TECHNIQUE_BREAKDOWN",
          "INTERRUPTED",
          "PLANNED_EFFORT",
          "HARDER_THAN_EXPECTED",
      )

  private fun invalid(): Nothing =
      throw CoachToolValidationException("Некорректные аргументы инструмента. Уточните запрос.")

  fun decode(call: AiApiToolCall): CoachToolRequest {
    if (call.type != "function" || call.function.arguments.length > 32_000) invalid()
    val obj =
        try {
          json.parseToJsonElement(call.function.arguments) as? JsonObject ?: invalid()
        } catch (_: Exception) {
          invalid()
        }
    return when (call.function.name) {
      "get_workout_state" -> {
        obj.keys(setOf("autoregulation"))
        if ("autoregulation" in obj) {
          val options = obj["autoregulation"] as? JsonObject ?: invalid()
          options.keys(
              setOf("goal", "exercise_id", "available_weights_kg", "observed_rest_seconds")
          )
          CoachToolRequest.Autoregulation(autoregulationOptions(options))
        } else CoachToolRequest.State
      }
      "find_exercises" -> {
        obj.keys(setOf("query", "equipment_ids", "muscle_ids", "muscle_groups", "limit"))
        CoachToolRequest.Find(
            obj.optionalText("query", 200),
            obj.optionalStrings("equipment_ids"),
            obj.optionalStrings("muscle_ids"),
            obj.optionalStrings("muscle_groups")?.also { groups ->
              if (
                  groups.any { group ->
                    com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup.entries.none {
                      it.name == group
                    }
                  }
              )
                  invalid()
            },
            if ("limit" in obj) obj.integer("limit", 20).toInt().also { if (it < 1) invalid() }
            else 10,
        )
      }
      "get_exercise_history" -> {
        obj.keys(setOf("exercise_id"))
        CoachToolRequest.History(obj.uuid("exercise_id"))
      }
      "submit_workout_changes" -> {
        obj.keys(setOf("base_revision", "operations", "reason"))
        val revision = obj.integer("base_revision", Long.MAX_VALUE)
        val ops = obj["operations"] as? JsonArray ?: invalid()
        if (ops.size !in 1..32) invalid()
        CoachToolRequest.Submit(
            revision,
            ops.map { operation(it as? JsonObject ?: invalid()) },
            obj.optionalText("reason", 1200),
        )
      }
      else -> invalid()
    }
  }

  private fun operation(obj: JsonObject): CoachChangeIntent {
    val action = obj.text("action")
    // Providers sometimes attach the package explanation to an individual operation.
    // Accept only this bounded metadata; all executable fields remain strictly validated.
    obj.optionalText("reason", 1200)
    fun keys(vararg names: String) = obj.keys(names.toSet() + setOf("action", "reason"))
    return when (action) {
      "autoregulate" -> {
        keys("goal", "exercise_id", "available_weights_kg", "observed_rest_seconds")
        CoachChangeIntent.Autoregulate(autoregulationOptions(obj))
      }
      "add_exercise" -> {
        keys("exercise_id", "position")
        CoachChangeIntent.AddExercise(
            obj.uuid("exercise_id"),
            if ("position" in obj) obj.integer("position", 1000).toInt() else null,
        )
      }
      "remove_remaining" -> {
        keys("section_id")
        CoachChangeIntent.RemoveRemaining(obj.uuid("section_id"))
      }
      "move_exercise" -> {
        keys("section_id", "position")
        CoachChangeIntent.Move(obj.uuid("section_id"), obj.integer("position", 1000).toInt())
      }
      "swap_exercises" -> {
        keys("first_section_id", "second_section_id")
        CoachChangeIntent.Swap(obj.uuid("first_section_id"), obj.uuid("second_section_id"))
      }
      "reorder_exercises" -> {
        keys("section_ids")
        CoachChangeIntent.Reorder(obj.uuids("section_ids", false))
      }
      "replace_remaining" -> {
        keys("section_id", "exercise_id", "remaining_set_ids", "weight_kg")
        CoachChangeIntent.Replace(
            obj.uuid("section_id"),
            obj.uuid("exercise_id"),
            obj.uuids("remaining_set_ids", false),
            obj.optionalNumber("weight_kg"),
        )
      }
      "add_set" -> {
        keys("section_id")
        CoachChangeIntent.AddSet(obj.uuid("section_id"))
      }
      "delete_set" -> {
        keys("set_id")
        CoachChangeIntent.DeleteSet(obj.uuid("set_id"))
      }
      "edit_set",
      "record_result" -> {
        keys("set_id", "values")
        val values = obj["values"] as? JsonObject ?: invalid()
        values.keys(valueFields)
        if (values.isEmpty()) invalid()
        CoachChangeIntent.EditSet(
            obj.uuid("set_id"),
            CoachSetValues(
                values.keys.toSet(),
                values.optionalNumber("weight_kg"),
                values.optionalInt("reps"),
                values.optionalInt("duration_sec"),
                values.optionalNumber("speed_kmh"),
                values.optionalNumber("incline_pct", -100.0),
                values.optionalInt("actual_rir")?.also { if (it !in 0..10) invalid() },
                values.optionalText("set_type", 20)?.also {
                  if (it !in setOf("WORK", "WARMUP", "UNKNOWN", "DROP", "AMRAP")) invalid()
                },
            ),
            action == "record_result",
        )
      }
      "set_completed" -> {
        keys("set_id", "completed")
        val completed =
            (obj["completed"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                ?: invalid()
        CoachChangeIntent.Complete(obj.uuid("set_id"), completed)
      }
      "start_rest",
      "extend_rest",
      "skip_rest",
      "future_rest_duration" -> {
        keys(
            *when (action) {
              "extend_rest" -> arrayOf("seconds", "rest_start_id")
              "skip_rest" -> arrayOf("rest_start_id")
              else -> arrayOf("seconds")
            }
        )
        val seconds =
            if (action == "skip_rest") null
            else obj.integer("seconds", 86_400).toInt().also { if (it <= 0) invalid() }
        val startId =
            if (action == "extend_rest" || action == "skip_rest")
                obj.text("rest_start_id").also { if (it.isBlank() || it.length > 200) invalid() }
            else null
        CoachChangeIntent.Rest(
            when (action) {
              "start_rest" -> CoachRestAction.START
              "extend_rest" -> CoachRestAction.EXTEND
              "skip_rest" -> CoachRestAction.SKIP
              else -> CoachRestAction.FUTURE_DURATION
            },
            seconds,
            startId,
        )
      }
      "available_time" -> {
        keys("minutes")
        CoachChangeIntent.AvailableTime(obj.integer("minutes", 1440).toInt())
      }
      "excluded_exercises" -> {
        keys("exercise_ids")
        CoachChangeIntent.ExcludedExercises(obj.uuids("exercise_ids", true).toSet())
      }
      "report_feelings" -> {
        keys("set_id", "feelings")
        val feelings =
            obj.strings("feelings").also {
              if (it.any { value -> value !in feelingValues }) invalid()
            }
        CoachChangeIntent.Feelings(obj.uuid("set_id"), feelings)
      }
      "undo_last" -> {
        keys()
        CoachChangeIntent.Undo
      }
      else -> invalid()
    }
  }

  private fun JsonObject.keys(allowed: Set<String>) {
    if (keys.any { it !in allowed }) invalid()
  }

  private fun JsonObject.text(key: String): String =
      (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()

  private fun JsonObject.optionalText(key: String, max: Int): String? =
      if (key !in this) null else text(key).also { if (it.length > max) invalid() }

  private fun JsonObject.uuid(key: String): String = canonicalUuid(text(key))

  private fun canonicalUuid(raw: String): String =
      try {
        UUID.fromString(raw).toString().also { if (!it.equals(raw, ignoreCase = true)) invalid() }
      } catch (_: Exception) {
        invalid()
      }

  private fun JsonObject.integer(key: String, max: Long): Long =
      (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in 0..max }
          ?: invalid()

  private fun JsonObject.optionalInt(key: String): Int? =
      if (key !in this || get(key) == JsonNull) null else integer(key, 1_000_000).toInt()

  private fun JsonObject.optionalNumber(key: String, min: Double = 0.0): Double? {
    if (key !in this || get(key) == JsonNull) return null
    return (get(key) as? JsonPrimitive)
        ?.takeIf { !it.isString }
        ?.doubleOrNull
        ?.takeIf { it.isFinite() && it in min..1_000_000.0 } ?: invalid()
  }

  private fun JsonObject.strings(key: String): Set<String> {
    val values = get(key) as? JsonArray ?: invalid()
    if (values.size > 100) invalid()
    val strings =
        values.map {
          (it as? JsonPrimitive)
              ?.takeIf { p -> p.isString }
              ?.content
              ?.takeIf { s -> s.isNotBlank() && s.length <= 120 } ?: invalid()
        }
    if (strings.distinct().size != strings.size) invalid()
    return strings.toSet()
  }

  private fun JsonObject.optionalStrings(key: String): Set<String>? =
      if (key !in this) null else strings(key)

  private fun JsonObject.uuids(key: String, allowEmpty: Boolean): List<String> =
      strings(key).map(::canonicalUuid).also {
        if ((!allowEmpty && it.isEmpty()) || it.distinct().size != it.size) invalid()
      }

  val tools: List<AiApiTool> by lazy {
    listOf(
        tool(
            "get_workout_state",
            "Без параметров: полная активная тренировка, закреплённые ссылки, отдых, доступное оборудование, мышцы и свежий доступный пульс. С объектом autoregulation (допустим пустой): вместо состояния локальный расчёт продолжения. RIR и тип подхода сначала записать как явные сведения пользователя. goal и оборудование передавать только из его данных, иначе опустить. Не выводить RIR, технику или восстановление из пульса и повторов. Для предложения можно использовать edit_set и rest с обоснованными значениями. autoregulate доступен как необязательный локальный расчёт.",
            // The backend contract allows exactly four tool names; extend the read tool.
            schema(mapOf("autoregulation" to schema(autoregulationFields()))),
        ),
        tool(
            "find_exercises",
            "Search catalogue by optional name, muscle_groups (any), muscle_ids (all), equipment_ids (all requirements). Sorted by most recent finished workout, then usage count. Returns full completed sets from the latest finished workout, excluding active workouts. Default 10, max 20 results. Empty history means no recorded experience. Equipment requirements do not prove current availability. Use returned UUIDs only; current_section_ids identifies duplicates.",
            schema(
                mapOf(
                    "query" to stringSchema(),
                    "equipment_ids" to arraySchema(stringSchema()),
                    "muscle_ids" to arraySchema(stringSchema()),
                    "muscle_groups" to
                        arraySchema(
                            buildJsonObject {
                              put("type", "string")
                              put(
                                  "enum",
                                  JsonArray(
                                      com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
                                          .entries
                                          .map { JsonPrimitive(it.name) }
                                  ),
                              )
                            }
                        ),
                    "limit" to
                        buildJsonObject {
                          put("type", "integer")
                          put("minimum", 1)
                          put("maximum", 20)
                        },
                )
            ),
        ),
        tool(
            "get_exercise_history",
            "Informational history: all completed sets from the last three finished workouts containing this exercise. Use for progress questions or deeper comparison only; find_exercises already includes the latest workout for selection and prefilling. Unknown values remain null.",
            schema(mapOf("exercise_id" to uuidSchema()), "exercise_id"),
        ),
        tool(
            "submit_workout_changes",
            "Передать один пакет изменений. add_exercise принимает необязательную position (индекс вставки, по умолчанию в конец) и автоматически предзаполняет полный список выполненных подходов последней завершённой тренировки как незавершённые; без истории создаёт один пустой подход. Приложение проверяет полномочия и сохраняет предложение или результат. Ожидание подтверждения завершает обращение. Для reorder_exercises передавай полный список всех section_id, каждый ровно один раз, включая выполненные упражнения и разминку.",
            schema(
                mapOf(
                    "base_revision" to numberSchema(true),
                    "operations" to
                        buildJsonObject {
                          put("type", "array")
                          put("minItems", 1)
                          put("maxItems", 32)
                          put(
                              "items",
                              buildJsonObject { put("anyOf", JsonArray(operationSchemas())) },
                          )
                        },
                    "reason" to stringSchema(),
                ),
                "base_revision",
                "operations",
            ),
        ),
    )
  }

  private fun operationSchemas(): List<JsonObject> {
    fun op(
        action: String,
        fields: Map<String, JsonElement> = emptyMap(),
        optional: Set<String> = emptySet(),
    ) =
        schema(
            mapOf(
                "action" to
                    buildJsonObject {
                      put("type", "string")
                      put("enum", JsonArray(listOf(JsonPrimitive(action))))
                    }
            ) + fields,
            *(listOf("action") + fields.keys.filter { it !in optional }).toTypedArray(),
        )
    val id = uuidSchema()
    val setValues =
        schema(
            valueFields.associateWith { field ->
              if (field == "set_type")
                  enumSchema(listOf("WORK", "WARMUP", "UNKNOWN", "DROP", "AMRAP"))
              else
                  buildJsonObject {
                    put(
                        "type",
                        JsonArray(
                            listOf(
                                JsonPrimitive(
                                    if (
                                        field in
                                            setOf(
                                                "reps",
                                                "duration_sec",
                                                "actual_rir",
                                            )
                                    )
                                        "integer"
                                    else "number"
                                ),
                                JsonPrimitive("null"),
                            )
                        ),
                    )
                    put("minimum", if (field == "incline_pct") -100 else 0)
                    put("maximum", if (field.endsWith("_rir")) 10 else 1_000_000)
                  }
            }
        )
    return listOf(
        op("autoregulate", autoregulationFields(), autoregulationFields().keys),
        op(
            "add_exercise",
            mapOf("exercise_id" to id, "position" to numberSchema(true)),
            setOf("position"),
        ),
        op("remove_remaining", mapOf("section_id" to id)),
        op("move_exercise", mapOf("section_id" to id, "position" to numberSchema(true))),
        op("swap_exercises", mapOf("first_section_id" to id, "second_section_id" to id)),
        op("reorder_exercises", mapOf("section_ids" to arraySchema(id))),
        op(
            "replace_remaining",
            mapOf(
                "section_id" to id,
                "exercise_id" to id,
                "remaining_set_ids" to arraySchema(id),
                "weight_kg" to numberSchema(false),
            ),
            setOf("weight_kg"),
        ),
        op("add_set", mapOf("section_id" to id)),
        op("delete_set", mapOf("set_id" to id)),
        op("edit_set", mapOf("set_id" to id, "values" to setValues)),
        op("record_result", mapOf("set_id" to id, "values" to setValues)),
        op(
            "set_completed",
            mapOf("set_id" to id, "completed" to buildJsonObject { put("type", "boolean") }),
        ),
        op("start_rest", mapOf("seconds" to numberSchema(true))),
        op(
            "extend_rest",
            mapOf("seconds" to numberSchema(true), "rest_start_id" to stringSchema()),
        ),
        op("skip_rest", mapOf("rest_start_id" to stringSchema())),
        op("future_rest_duration", mapOf("seconds" to numberSchema(true))),
        op("available_time", mapOf("minutes" to numberSchema(true))),
        op("excluded_exercises", mapOf("exercise_ids" to arraySchema(id))),
        op(
            "report_feelings",
            mapOf(
                "set_id" to id,
                "feelings" to
                    arraySchema(
                        buildJsonObject {
                          put("type", "string")
                          put("enum", JsonArray(feelingValues.map(::JsonPrimitive)))
                        }
                    ),
            ),
        ),
        op("undo_last"),
    )
  }

  private fun tool(name: String, description: String, parameters: JsonObject) =
      AiApiTool(function = AiApiToolFunction(name, description, parameters))

  private fun autoregulationFields(): Map<String, JsonElement> =
      mapOf(
          "goal" to
              enumSchema(
                  com.valerochka1337.valerochkagym.domain.autoregulation.TrainingGoal.entries.map {
                    it.name
                  }
              ),
          "exercise_id" to uuidSchema(),
          "available_weights_kg" to arraySchema(numberSchema(false)),
          "observed_rest_seconds" to numberSchema(true),
      )

  private fun autoregulationOptions(
      obj: JsonObject
  ): com.valerochka1337.valerochkagym.domain.autoregulation.AutoregulationOptions? {
    if (
        obj.keys.none {
          it in setOf("goal", "exercise_id", "available_weights_kg", "observed_rest_seconds")
        }
    )
        return null
    val goal =
        obj.optionalText("goal", 30)?.let { value ->
          com.valerochka1337.valerochkagym.domain.autoregulation.TrainingGoal.entries.firstOrNull {
            it.name == value
          } ?: invalid()
        } ?: com.valerochka1337.valerochkagym.domain.autoregulation.TrainingGoal.PRESERVE_PLAN
    val weights =
        if ("available_weights_kg" in obj) {
          val id = obj.uuid("exercise_id")
          val values = obj["available_weights_kg"] as? JsonArray ?: invalid()
          if (values.size !in 1..100) invalid()
          mapOf(
              id to
                  values
                      .map { value ->
                        (value as? JsonPrimitive)
                            ?.takeIf { !it.isString }
                            ?.doubleOrNull
                            ?.takeIf { it.isFinite() && it > 0 && it <= 1000 } ?: invalid()
                      }
                      .distinct()
                      .sorted()
          )
        } else {
          if ("exercise_id" in obj) invalid()
          emptyMap()
        }
    return com.valerochka1337.valerochkagym.domain.autoregulation.AutoregulationOptions(
        goal,
        weights,
        obj.optionalInt("observed_rest_seconds")?.also { if (it !in 0..86400) invalid() },
    )
  }

  private fun enumSchema(values: List<String>): JsonObject = buildJsonObject {
    put("type", "string")
    put("enum", JsonArray(values.map(::JsonPrimitive)))
  }

  private fun schema(fields: Map<String, JsonElement>, vararg required: String) = buildJsonObject {
    put("type", "object")
    put("properties", JsonObject(fields))
    put("additionalProperties", false)
    put("required", JsonArray(required.map(::JsonPrimitive)))
  }

  private fun stringSchema() = buildJsonObject { put("type", "string") }

  private fun uuidSchema() = buildJsonObject {
    put("type", "string")
    put("format", "uuid")
  }

  private fun numberSchema(integer: Boolean) = buildJsonObject {
    put("type", if (integer) "integer" else "number")
    put("minimum", 0)
  }

  private fun arraySchema(items: JsonElement) = buildJsonObject {
    put("type", "array")
    put("items", items)
    put("maxItems", 100)
    put("uniqueItems", true)
  }
}
