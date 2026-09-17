package com.valerochka1337.valerochkagym.domain

/** A closed grammar grants authority only to the exact operation and send-time UUIDs it parsed. */
object LocalWorkoutCommandParser {
  data class ParsedCommand(val packet: WorkoutChangeSet.Packet, val authority: CommandAuthority)

  fun parse(text: String, snapshot: WorkoutSnapshot): ParsedCommand? {
    val anchors =
        CommandAuthority.Anchors(snapshot.currentSetId, snapshot.previousSetId, snapshot.nextSetId)
    val section =
        snapshot.exercises
            .singleOrNull { exercise -> exercise.sets.any { it.syncId == snapshot.currentSetId } }
            ?.sectionId
    parse(text, anchors, section, snapshot.rest?.startId)?.let {
      return it
    }
    val normalized = normalize(text)
    val all = snapshot.exercises.flatMap { it.sets }.associateBy { it.syncId }
    Regex("прошлый подход rir (10|[0-9])").matchEntire(normalized)?.let { match ->
      val previous = all[anchors.previousSetId]?.takeIf { it.completed } ?: return null
      return grant(
          WorkoutChangeSet.Operation.EditSet(
              previous.syncId,
              actualRir = match.groupValues[1].toInt(),
              setType = "WORK",
          ),
          anchors,
      )
    }
    // "Instead of" is only unambiguous if one anchored candidate has that stated reference.
    val result = Regex("сделал (\\d+) вместо (\\d+)").matchEntire(normalized) ?: return null
    val reps = result.groupValues[1].toIntOrNull()?.takeIf { it in 0..10000 } ?: return null
    val expected = result.groupValues[2].toIntOrNull() ?: return null
    val target =
        listOfNotNull(anchors.currentSetId, anchors.previousSetId)
            .distinct()
            .mapNotNull(all::get)
            .filter { (it.targetReps ?: it.reps) == expected }
            .singleOrNull() ?: return null
    return grant(
        WorkoutChangeSet.Operation.EditSet(target.syncId, reps = reps, recordResult = true),
        anchors,
    )
  }

  fun parse(
      text: String,
      anchors: CommandAuthority.Anchors,
      currentSectionId: String?,
      restStartId: String?,
  ): ParsedCommand? {
    val value = normalize(text)
    fun id(reference: String): String? =
        when (reference) {
          "текущий",
          "текущем",
          "текущего" -> anchors.currentSetId
          "прошлый",
          "прошлом",
          "прошлого" -> anchors.previousSetId
          "следующий",
          "следующем",
          "следующего" -> anchors.nextSetId
          else -> null
        }
    fun duration(number: String, unit: String): Int? {
      val count = number.toLongOrNull()?.takeIf { it in 1..86400 } ?: return null
      val seconds = count * if (unit.startsWith("мин")) 60 else 1
      return seconds.takeIf { it in 1..86400 }?.toInt()
    }
    val operation: WorkoutChangeSet.Operation =
        when {
          value == "добавь подход" ->
              WorkoutChangeSet.Operation.AddSet(currentSectionId ?: return null)
          value in setOf("отмени последнее изменение", "отмени последнее действие") ->
              WorkoutChangeSet.Operation.UndoLast
          value in setOf("пропусти отдых", "заверши отдых") ->
              WorkoutChangeSet.Operation.Rest(RestAction.SKIP, restStartId ?: return null)
          else -> {
            val rest =
                Regex(
                        "(увеличь отдых на|запусти отдых на|установи последующий отдых на) (\\d+) (секунд[аы]?|минут[аыу]?)"
                    )
                    .matchEntire(value)
            if (rest != null) {
              val action =
                  when (rest.groupValues[1]) {
                    "увеличь отдых на" -> RestAction.EXTEND
                    "запусти отдых на" -> RestAction.START
                    else -> RestAction.FUTURE_DURATION
                  }
              if (action == RestAction.EXTEND && restStartId == null) return null
              return grant(
                  WorkoutChangeSet.Operation.Rest(
                      action,
                      restStartId,
                      duration(rest.groupValues[2], rest.groupValues[3]) ?: return null,
                  ),
                  anchors,
              )
            }
            val time = Regex("(?:осталось|у меня осталось) (\\d+) минут[аыу]?").matchEntire(value)
            if (time != null)
                return grant(
                    WorkoutChangeSet.Operation.SetAvailableTime(
                        time.groupValues[1].toIntOrNull()?.takeIf { it in 0..1440 } ?: return null
                    ),
                    anchors,
                )
            val complete =
                Regex("отметь (текущий|прошлый|следующий) подход выполненным").matchEntire(value)
            if (complete != null)
                return grant(
                    WorkoutChangeSet.Operation.EditSet(
                        id(complete.groupValues[1]) ?: return null,
                        completed = true,
                    ),
                    anchors,
                )
            val uncomplete =
                Regex("сними отметку выполнения (текущего|прошлого|следующего) подхода")
                    .matchEntire(value)
            if (uncomplete != null)
                return grant(
                    WorkoutChangeSet.Operation.EditSet(
                        id(uncomplete.groupValues[1]) ?: return null,
                        completed = false,
                    ),
                    anchors,
                )
            val delete = Regex("удали (текущий|следующий) подход").matchEntire(value)
            if (delete != null)
                return grant(
                    WorkoutChangeSet.Operation.DeleteSet(id(delete.groupValues[1]) ?: return null),
                    anchors,
                )
            val result =
                Regex("в (текущем|прошлом) подходе сделал (\\d+)(?: повторений| повтора| повтор)?")
                    .matchEntire(value)
            if (result != null)
                return grant(
                    WorkoutChangeSet.Operation.EditSet(
                        id(result.groupValues[1]) ?: return null,
                        reps =
                            result.groupValues[2].toIntOrNull()?.takeIf { it in 0..10000 }
                                ?: return null,
                        recordResult = true,
                    ),
                    anchors,
                )
            val edit =
                Regex(
                        "(?:поставь|установи) в (текущем|следующем) подходе (\\d+(?:[.,]\\d+)?) (кг|повторений|повтора|повтор|секунд[аы]?|минут[аыу]?|км/ч|процентов|процента|процент)"
                    )
                    .matchEntire(value) ?: return null
            val set = id(edit.groupValues[1]) ?: return null
            val amount =
                edit.groupValues[2].replace(',', '.').toDoubleOrNull()?.takeIf {
                  it.isFinite() && it in 0.0..1000000.0
                } ?: return null
            when (val unit = edit.groupValues[3]) {
              "кг" -> WorkoutChangeSet.Operation.EditSet(set, weightKg = amount)
              "повторений",
              "повтора",
              "повтор" ->
                  WorkoutChangeSet.Operation.EditSet(
                      set,
                      reps =
                          edit.groupValues[2].toIntOrNull()?.takeIf { it in 0..10000 }
                              ?: return null,
                  )
              "км/ч" -> WorkoutChangeSet.Operation.EditSet(set, speedKmh = amount)
              "процентов",
              "процента",
              "процент" ->
                  WorkoutChangeSet.Operation.EditSet(
                      set,
                      inclinePct = amount.takeIf { it <= 100 } ?: return null,
                  )
              else ->
                  WorkoutChangeSet.Operation.EditSet(
                      set,
                      durationSec = duration(edit.groupValues[2], unit) ?: return null,
                  )
            }
          }
        }
    return grant(operation, anchors)
  }

  private fun normalize(text: String) = text.trim().lowercase().replace(Regex("\\s+"), " ")

  private fun grant(
      operation: WorkoutChangeSet.Operation,
      anchors: CommandAuthority.Anchors,
  ): ParsedCommand {
    val packet = WorkoutChangeSet.Packet(listOf(operation))
    return ParsedCommand(packet, CommandAuthority.local(packet, anchors))
  }
}
