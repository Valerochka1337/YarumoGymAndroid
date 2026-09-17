package com.valerochka1337.valerochkagym.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class WorkoutApprovalPreview(val actions: List<ApprovalAction>, val version: Int = 1) {
  fun text(): String =
      actions.joinToString("\n\n") { (listOf(it.title) + it.details).joinToString("\n") }

  companion object {
    fun decode(value: String?): WorkoutApprovalPreview? =
        value
            ?.let { runCatching { Json.decodeFromString<WorkoutApprovalPreview>(it) }.getOrNull() }
            ?.takeIf { it.version == 1 }
  }
}

@Serializable
data class ApprovalAction(
    val kind: String,
    val title: String,
    val details: List<String> = emptyList(),
)

/** Presentation only: identity and final values come from the editor's calculated packet. */
object WorkoutApprovalFormatter {
  fun describe(
      steps: List<WorkoutChangeSummary.Step>,
      names: Map<Long, String>,
  ): WorkoutApprovalPreview {
    if (steps.isEmpty()) return WorkoutApprovalPreview(emptyList())
    val before = steps.first().before
    val after = steps.last().after
    val actions = mutableListOf<ApprovalAction>()
    val consumed = mutableSetOf<String>()
    val replacements =
        steps.mapNotNull { it.operation as? WorkoutChangeSet.Operation.ReplaceRemaining }
    val oldSections = before.exercises.associateBy { it.sectionId }
    val newSections = after.exercises.associateBy { it.sectionId }
    fun name(id: Long) =
        names[id]
            ?: (before.exercises + after.exercises).firstOrNull { it.exerciseId == id }?.name
            ?: "Упражнение"
    fun label(e: SnapshotExercise): String =
        if (
            (before.exercises + after.exercises)
                .filter { it.name == e.name }
                .map { it.sectionId }
                .distinct()
                .size > 1
        )
            "${e.name} (позиция ${e.position + 1})"
        else e.name
    for (replacement in replacements) {
      if (replacement.sourceSectionId in consumed) continue
      val source = oldSections[replacement.sourceSectionId] ?: continue
      // Follow chained replacements to the final surviving destination.
      var destinationId = replacement.destinationSectionId
      val visited = mutableSetOf(source.sectionId)
      while (visited.add(destinationId)) {
        val next = replacements.firstOrNull { it.sourceSectionId == destinationId } ?: break
        if (destinationId !in newSections) consumed += destinationId
        destinationId = next.destinationSectionId
      }
      val destination = newSections[destinationId] ?: continue
      consumed += source.sectionId
      consumed += destinationId
      val completed = source.sets.count { it.completed }
      val surviving = newSections[source.sectionId]
      val preservedCompleted =
          surviving?.sets?.count {
            it.completed && source.sets.any { old -> old.syncId == it.syncId && old.completed }
          } ?: 0
      actions +=
          ApprovalAction(
              "replace",
              if (completed == 0) "Заменить «${label(source)}» на «${destination.name}»"
              else "Заменить оставшиеся подходы «${label(source)}» на «${destination.name}»",
              loadGroups(destination.sets) +
                  listOf("Позиция: ${destination.position + 1}") +
                  listOfNotNull(
                      if (preservedCompleted > 0)
                          "Выполненные подходы «${source.name}» сохранятся: $preservedCompleted"
                      else null
                  ),
          )
      // Compare the retained source separately: additions or result edits must stay visible.
      val retainedSource = source.copy(sets = source.sets.filter { it.completed })
      if (surviving != null) actions += sectionChanges(retainedSource, surviving, label(source))
      else if (retainedSource.sets.isNotEmpty())
          actions +=
              ApprovalAction(
                  "delete",
                  "${label(source)} · убрать подходы ${indices(retainedSource.sets)}",
              )
    }
    for (old in before.exercises.sortedBy { it.position }) {
      if (old.sectionId in consumed) continue
      val updated = newSections[old.sectionId]
      if (updated == null) {
        actions +=
            ApprovalAction(
                "delete",
                "Убрать «${label(old)}» из тренировки",
                listOf("Удаляемые подходы: ${old.sets.size}"),
            )
        continue
      }
      actions += sectionChanges(old, updated, label(old))
    }
    for (added in
        after.exercises
            .sortedBy { it.position }
            .filter { it.sectionId !in oldSections && it.sectionId !in consumed }) {
      actions +=
          ApprovalAction(
              "add",
              "Добавить «${added.name}»",
              listOf("Позиция: ${added.position + 1}") + loadGroups(added.sets),
          )
    }
    // A replacement hides load edits, but must not hide recorded results or feelings.
    val oldSets = before.exercises.flatMap { it.sets }.associateBy { it.syncId }
    after.exercises.forEach { exercise ->
      exercise.sets.forEach setLoop@{ set ->
        val original = oldSets[set.syncId]
        val oldSection = oldSections[exercise.sectionId]
        val alreadyDescribed =
            oldSection?.sets?.any { it.syncId == set.syncId } == true &&
                (exercise.sectionId !in consumed || original?.completed == true)
        if (alreadyDescribed) return@setLoop
        val details = mutableListOf<String>()
        if ((original?.completed ?: false) != set.completed)
            details += if (set.completed) "Отметить выполненным" else "Отметить невыполненным"
        if (original?.reportedFeelings.orEmpty() != set.reportedFeelings)
            details +=
                "Ощущения: ${feelings(original?.reportedFeelings.orEmpty())} → ${feelings(set.reportedFeelings)}"
        if (details.isNotEmpty())
            actions +=
                ApprovalAction("edit", "${exercise.name} · подход ${set.setIndex + 1}", details)
      }
    }
    actions += orderChanges(before, after, steps)
    if (before.availableTimeMinutes != after.availableTimeMinutes)
        actions +=
            ApprovalAction(
                "time",
                "Доступное время: ${before.availableTimeMinutes?.let { "$it мин" } ?: "не задано"} → ${after.availableTimeMinutes?.let { "$it мин" } ?: "без ограничения"}",
            )
    val excluded = after.excludedExerciseIds - before.excludedExerciseIds
    val restored = before.excludedExerciseIds - after.excludedExerciseIds
    if (excluded.isNotEmpty())
        actions +=
            ApprovalAction(
                "exclude",
                "Исключить до конца тренировки: ${excluded.sorted().joinToString { name(it) }}",
            )
    if (restored.isNotEmpty())
        actions +=
            ApprovalAction(
                "exclude",
                "Снять исключение: ${restored.sorted().joinToString { name(it) }}",
            )
    // Rest is an effect, so preserve its ordered commands rather than infer it from a timer tick.
    if (before.futureRestSeconds != after.futureRestSeconds)
        actions +=
            ApprovalAction(
                "rest",
                "Следующие паузы: ${before.futureRestSeconds?.let { "$it с" } ?: "по настройкам тренировки"} → ${after.futureRestSeconds?.let { "$it с" } ?: "по настройкам тренировки"}",
            )
    steps
        .filter {
          (it.operation as? WorkoutChangeSet.Operation.Rest)?.action?.let { action ->
            action != RestAction.FUTURE_DURATION
          } == true
        }
        .forEach {
          actions += ApprovalAction("rest", WorkoutChangeSummary.describe(listOf(it), names).after)
        }
    if (
        steps.any {
          it.operation == WorkoutChangeSet.Operation.UndoLast ||
              it.operation is WorkoutChangeSet.Operation.RestoreWorkout
        }
    ) {
      if (actions.isNotEmpty())
          actions.add(0, ApprovalAction("undo", "Отменить последнее изменение"))
    }
    return WorkoutApprovalPreview(actions).also { require(it.text().length <= 40000) }
  }

  private fun sectionChanges(
      old: SnapshotExercise,
      updated: SnapshotExercise,
      exerciseLabel: String,
  ): List<ApprovalAction> {
    val actions = mutableListOf<ApprovalAction>()
    val oldIds = old.sets.map { it.syncId }.toSet()
    val newIds = updated.sets.map { it.syncId }.toSet()
    val removed = old.sets.filter { it.syncId !in newIds }
    val added = updated.sets.filter { it.syncId !in oldIds }
    if (removed.isNotEmpty())
        actions +=
            ApprovalAction(
                "delete",
                "${exerciseLabel} · убрать подходы ${indices(removed)}",
                listOfNotNull(
                    updated.sets
                        .count { it.completed }
                        .takeIf { it > 0 }
                        ?.let { "Выполненные подходы сохранятся: $it" }
                ),
            )
    if (added.isNotEmpty())
        actions +=
            ApprovalAction(
                "add",
                "${exerciseLabel} · добавить подходы ${indices(added)}",
                loadGroups(added),
            )
    actions += edits(old, updated, oldIds intersect newIds, exerciseLabel)
    return actions
  }

  private fun edits(
      old: SnapshotExercise,
      updated: SnapshotExercise,
      ids: Set<String>,
      exerciseLabel: String = old.name,
  ): List<ApprovalAction> {
    val changes =
        old.sets
            .filter { it.syncId in ids }
            .sortedBy { it.setIndex }
            .mapNotNull { set ->
              val new = updated.sets.single { it.syncId == set.syncId }
              val fields = mutableListOf<String>()
              fun field(title: String, a: Any?, b: Any?, unit: String = "") {
                fun value(v: Any?) =
                    if (v == null) "не задано"
                    else (if (v is Double) number(v) else v.toString()) + unit
                if (a != b) fields += "$title: ${value(a)} → ${value(b)}"
              }
              field("Вес", set.weightKg, new.weightKg, " кг")
              field("Повторения", set.reps, new.reps)
              field(
                  "Фактический запас повторений (RIR)",
                  if (set.actualRirAtLeastFour) "4+" else set.actualRir,
                  if (new.actualRirAtLeastFour) "4+" else new.actualRir,
              )
              fun typeLabel(value: String) =
                  when (value) {
                    "WORK" -> "рабочий"
                    "WARMUP" -> "разминочный"
                    "DROP" -> "дроп-сет"
                    "AMRAP" -> "максимум повторений"
                    else -> "не указан"
                  }
              field("Тип подхода", typeLabel(set.setType), typeLabel(new.setType))
              field("Длительность", set.durationSec, new.durationSec, " с")
              field("Скорость", set.speedKmh, new.speedKmh, " км/ч")
              field("Наклон", set.inclinePct, new.inclinePct, "%")
              if (set.completed != new.completed)
                  fields += if (new.completed) "Отметить выполненным" else "Отметить невыполненным"
              if (set.reportedFeelings != new.reportedFeelings)
                  fields +=
                      "Ощущения: ${feelings(set.reportedFeelings)} → ${feelings(new.reportedFeelings)}"
              if (fields.isEmpty()) null else set to fields
            }
    val groups = mutableListOf<MutableList<Pair<SnapshotSet, List<String>>>>()
    for (change in changes) {
      val last = groups.lastOrNull()?.lastOrNull()
      if (
          last != null &&
              last.second == change.second &&
              last.first.setIndex + 1 == change.first.setIndex
      )
          groups.last() += change
      else groups += mutableListOf(change)
    }
    return groups.map {
      ApprovalAction(
          "edit",
          "$exerciseLabel · ${if (it.size == 1) "подход" else "подходы"} ${indices(it.map { row -> row.first })}",
          it.first().second,
      )
    }
  }

  private fun orderChanges(
      before: WorkoutSnapshot,
      after: WorkoutSnapshot,
      steps: List<WorkoutChangeSummary.Step>,
  ): List<ApprovalAction> {
    val a = before.exercises.sortedBy { it.position }
    val b = after.exercises.sortedBy { it.position }
    val common = a.map { it.sectionId }.toSet() intersect b.map { it.sectionId }.toSet()
    val old = a.map { it.sectionId }.filter { it in common }
    val new = b.map { it.sectionId }.filter { it in common }
    if (old == new) return emptyList()
    fun exercise(id: String) = b.single { it.sectionId == id }
    fun position(id: String) =
        "${a.single { it.sectionId == id }.position + 1} → ${exercise(id).position + 1}"
    fun moved(id: String): Boolean = old.filterNot { it == id } == new.filterNot { it == id }
    val explicit =
        steps
            .mapNotNull { (it.operation as? WorkoutChangeSet.Operation.MoveExercise)?.sectionId }
            .distinct()
    val changed = new.filter { old.indexOf(it) != new.indexOf(it) }
    val candidates = changed.filter { moved(it) }
    val move = explicit.singleOrNull()?.takeIf { it in candidates } ?: candidates.singleOrNull()
    if (move != null) {
      val index = b.indexOfFirst { it.sectionId == move }
      val destination = if (index == 0) "в начало тренировки" else "после «${b[index - 1].name}»"
      return listOf(
          ApprovalAction(
              "move",
              "Переместить «${exercise(move).name}» $destination",
              listOf("Позиция: ${position(move)}"),
          )
      )
    }
    if (changed.size == 2)
        return listOf(
            ApprovalAction(
                "swap",
                "Поменять местами «${exercise(changed[0]).name}» и «${exercise(changed[1]).name}»",
                changed.map { "${exercise(it).name}: ${position(it)}" },
            )
        )
    return listOf(
        ApprovalAction(
            "move",
            "Изменить порядок упражнений",
            changed.map { "${exercise(it).name}: ${position(it)}" },
        )
    )
  }

  private fun number(value: Double): String =
      if (value == value.toLong().toDouble()) value.toLong().toString()
      else value.toString().replace('.', ',')

  private fun indices(sets: List<SnapshotSet>): String {
    val numbers = sets.map { it.setIndex + 1 }.sorted()
    return if (numbers.size > 1 && numbers.zipWithNext().all { (a, b) -> b == a + 1 })
        "${numbers.first()}–${numbers.last()}"
    else numbers.joinToString(", ")
  }

  private fun load(set: SnapshotSet): String =
      listOfNotNull(
              set.reps?.let { "$it повт." },
              set.weightKg?.let { "${number(it)} кг" }
                  ?: if (set.reps != null) "вес не задан" else null,
              set.durationSec?.let { "$it с" },
              set.speedKmh?.let { "${number(it)} км/ч" },
              set.inclinePct?.let { "наклон ${number(it)}%" },
          )
          .joinToString(" · ")
          .ifEmpty { "нагрузка не задана" }

  private fun loadGroups(sets: List<SnapshotSet>): List<String> {
    val groups = mutableListOf<MutableList<SnapshotSet>>()
    for (set in sets.sortedBy { it.setIndex }) {
      val last = groups.lastOrNull()?.lastOrNull()
      if (last != null && last.setIndex + 1 == set.setIndex && load(last) == load(set))
          groups.last() += set
      else groups += mutableListOf(set)
    }
    return groups.map {
      "${it.size} ${when { it.size % 100 in 11..14 -> "подходов"
 it.size % 10 == 1 -> "подход"
 it.size % 10 in 2..4 -> "подхода"
 else -> "подходов" }} × ${load(it.first())}"
    }
  }

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
              else -> it
            }
          }
          .ifEmpty { "не указаны" }
}
