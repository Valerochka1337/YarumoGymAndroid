package com.valerochka1337.valerochkagym.domain

/** Approval text is derived from host-resolved operations, never from a model's explanation. */
object WorkoutChangeSummary {
  data class Step(
      val operation: WorkoutChangeSet.Operation,
      val before: WorkoutSnapshot,
      val after: WorkoutSnapshot,
  )

  data class Summary(val before: String, val after: String)

  fun describe(
      steps: List<Step>,
      exerciseNames: Map<Long, String> = emptyMap(),
      equipmentNames: Map<String, String> = emptyMap(),
  ): Summary {
    val packet = steps.map { it.operation }
    fun section(state: WorkoutSnapshot, id: String) =
        requireNotNull(state.exercises.singleOrNull { it.sectionId == id }) { "Unknown section" }
    fun exerciseName(state: WorkoutSnapshot, id: Long) =
        exerciseNames[id]
            ?: state.exercises.firstOrNull { it.exerciseId == id }?.name
            ?: error("Unknown exercise")
    fun set(state: WorkoutSnapshot, id: String): Pair<SnapshotExercise, SnapshotSet> {
      val exercise =
          requireNotNull(state.exercises.singleOrNull { e -> e.sets.any { it.syncId == id } }) {
            "Unknown set"
          }
      return exercise to exercise.sets.single { it.syncId == id }
    }
    fun row(exercise: SnapshotExercise, set: SnapshotSet) =
        "${exercise.name}, подход ${set.setIndex + 1}: ${load(set)}; ${if (set.completed) "выполнен" else "не выполнен"}"
    fun order(state: WorkoutSnapshot, ids: List<String>) =
        ids.mapIndexed { index, id -> "${index +1}. ${section(state, id).name}" }.joinToString("\n")
    val parts =
        steps.mapIndexed { step, calculatedStep ->
          val operation = calculatedStep.operation
          val state = calculatedStep.before
          val after = calculatedStep.after
          val summary =
              when (operation) {
                is WorkoutChangeSet.Operation.EditSet -> {
                  val (exercise, old) = set(state, operation.setSyncId)
                  val (_, updated) = set(after, operation.setSyncId)
                  Summary(row(exercise, old), row(exercise, updated))
                }
                is WorkoutChangeSet.Operation.AddSet -> {
                  val exercise = section(state, operation.sectionId)
                  Summary(
                      "${exercise.name}: ${exercise.sets.size} подходов",
                      "${exercise.name}: добавить один подход" +
                          (section(after, operation.sectionId).sets.lastOrNull()?.let {
                            " (${load(it)})"
                          } ?: "; значения пока не заданы"),
                  )
                }
                is WorkoutChangeSet.Operation.DeleteSet -> {
                  val (exercise, target) = set(state, operation.setSyncId)
                  require(!target.completed) { "Completed result cannot be deleted" }
                  Summary(
                      row(exercise, target),
                      "${exercise.name}, подход ${target.setIndex + 1}: удалить незавершённый подход",
                  )
                }
                is WorkoutChangeSet.Operation.AddExercise -> {
                  val added =
                      after.exercises.single { candidate ->
                        state.exercises.none { it.sectionId == candidate.sectionId }
                      }
                  Summary(
                      "${exerciseName(state, operation.exerciseId)}: новой секции нет",
                      "Добавить «${exerciseName(state, operation.exerciseId)}» на позицию ${added.position + 1}; незавершённые подходы:\n" +
                          added.sets.joinToString("\n") { "${it.setIndex + 1}. ${load(it)}" },
                  )
                }
                is WorkoutChangeSet.Operation.DeleteExercise -> {
                  val exercise = section(state, operation.sectionId)
                  require(exercise.sets.none { it.completed }) {
                    "Completed exercise cannot be deleted"
                  }
                  Summary(
                      "${exercise.name}: ${exercise.sets.size} подходов",
                      "Убрать секцию «${exercise.name}»",
                  )
                }
                is WorkoutChangeSet.Operation.RemoveRemaining -> {
                  val exercise = section(state, operation.sectionId)
                  Summary(
                      "${exercise.name}: ${exercise.sets.count { !it.completed }} незавершённых подходов",
                      "Убрать незавершённые подходы «${exercise.name}»; сохранить ${exercise.sets.count { it.completed }} выполненных",
                  )
                }
                is WorkoutChangeSet.Operation.ReplaceRemaining -> {
                  val exercise = section(state, operation.sourceSectionId)
                  val targets = exercise.sets.filterNot { it.completed }.sortedBy { it.setIndex }
                  val replacement = section(after, operation.destinationSectionId)
                  Summary(
                      targets.joinToString("\n") { row(exercise, it) },
                      "Заменить оставшиеся ${targets.size} подходов на «${exerciseName(state, operation.replacementExerciseId)}»:\n" +
                          replacement.sets
                              .mapIndexed { index, target -> "${index + 1}. ${load(target)}" }
                              .joinToString("\n") +
                          if (exercise.sets.any { it.completed })
                              "\nСохранить ${exercise.sets.count { it.completed }} выполненных подходов «${exercise.name}»."
                          else "\nУдалить упражнение «${exercise.name}» из тренировки.",
                  )
                }
                is WorkoutChangeSet.Operation.MoveExercise -> {
                  val before = state.exercises.sortedBy { it.position }.map { it.sectionId }
                  val afterOrder = after.exercises.sortedBy { it.position }.map { it.sectionId }
                  Summary(order(state, before), order(after, afterOrder))
                }
                is WorkoutChangeSet.Operation.ReorderExercises -> {
                  require(
                      operation.sectionIds.size == state.exercises.size &&
                          operation.sectionIds.toSet() ==
                              state.exercises.map { it.sectionId }.toSet()
                  )
                  Summary(
                      order(state, state.exercises.sortedBy { it.position }.map { it.sectionId }),
                      order(after, after.exercises.sortedBy { it.position }.map { it.sectionId }),
                  )
                }
                is WorkoutChangeSet.Operation.Rest -> {
                  val before =
                      if (operation.action == RestAction.FUTURE_DURATION)
                          "Следующие паузы: по текущим настройкам тренировки"
                      else
                          state.rest?.remainingSeconds?.let { "Осталось отдыха: $it с" }
                              ?: "Активный отдых не известен"
                  Summary(
                      before,
                      when (operation.action) {
                        RestAction.START ->
                            "Запустить отдых на ${requireNotNull(operation.seconds)} с"
                        RestAction.EXTEND ->
                            "Добавить ${requireNotNull(operation.seconds)} с к текущему отдыху"
                        RestAction.SKIP -> "Завершить текущий отдых"
                        RestAction.FUTURE_DURATION ->
                            "Установить следующие паузы этой тренировки: ${requireNotNull(operation.seconds)} с"
                      },
                  )
                }
                is WorkoutChangeSet.Operation.SetAvailableTime ->
                    Summary(
                        state.availableTimeMinutes?.let { "Доступное время: $it мин" }
                            ?: "Доступное время не задано",
                        after.availableTimeMinutes?.let { "Оставшееся доступное время: $it мин" }
                            ?: "Убрать ограничение времени",
                    )
                is WorkoutChangeSet.Operation.SetExcludedExercises ->
                    Summary(
                        "Исключены: ${state.excludedExerciseIds.map { exerciseName(state, it) }.joinToString().ifEmpty { "нет" }}",
                        "Исключить до конца тренировки: ${operation.exerciseIds.map { exerciseName(state, it) }.joinToString().ifEmpty { "нет" }}",
                    )
                is WorkoutChangeSet.Operation.ReportFeelings -> {
                  val (exercise, target) = set(state, operation.setSyncId)
                  Summary(
                      "${exercise.name}, подход ${target.setIndex + 1}: ${feelings(target.reportedFeelings)}",
                      "${exercise.name}, подход ${target.setIndex + 1}: ${feelings(operation.feelings)}",
                  )
                }
                WorkoutChangeSet.Operation.UndoLast,
                is WorkoutChangeSet.Operation.RestoreWorkout ->
                    Summary(
                        state.exercises
                            .sortedBy { it.position }
                            .joinToString("\n") { e -> e.sets.joinToString("\n") { row(e, it) } },
                        after.exercises
                            .sortedBy { it.position }
                            .joinToString("\n") { e -> e.sets.joinToString("\n") { row(e, it) } },
                    )
              }
          if (packet.size == 1) summary
          else Summary("Шаг ${step + 1}:\n${summary.before}", "Шаг ${step + 1}:\n${summary.after}")
        }
    return Summary(
            parts.joinToString("\n\n") { it.before },
            parts.joinToString("\n\n") { it.after },
        )
        .also {
          require(it.before.length + it.after.length <= 40000) { "Approval preview too large" }
        }
  }

  private fun number(value: Double) =
      if (value == value.toLong().toDouble()) value.toLong().toString()
      else value.toString().replace('.', ',')

  private fun load(set: SnapshotSet) =
      listOfNotNull(
              set.weightKg?.let { "${number(it)} кг" },
              set.reps?.let { "$it повт." },
              set.durationSec?.let { "$it с" },
              set.speedKmh?.let { "${number(it)} км/ч" },
              set.inclinePct?.let { "наклон ${number(it)}%" },
          )
          .joinToString(" · ")
          .ifEmpty { "значения не заданы" }

  private fun feelings(values: Set<String>) =
      values
          .sorted()
          .joinToString {
            when (it) {
              "PAIN" -> "боль"
              "FATIGUE" -> "усталость"
              "TECHNIQUE_BREAKDOWN" -> "нарушение техники"
              "INTERRUPTED" -> "подход прерван"
              "PLANNED_EFFORT" -> "усилие было запланировано"
              "HARDER_THAN_EXPECTED" -> "стало тяжелее ожидаемого"
              else -> error("Unknown feeling")
            }
          }
          .ifEmpty { "ощущения не указаны" }
}
