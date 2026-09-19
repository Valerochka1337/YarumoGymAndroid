package com.valerochka1337.valerochkagym.data.ai

import com.valerochka1337.valerochkagym.domain.WorkoutSnapshot
import javax.inject.Inject

/**
 * Checks the selected provider using an isolated synthetic host with no repository dependencies.
 */
class CoachModelProbe @Inject constructor(private val agent: CoachAgent) {
  suspend fun verify(
      expectedOwner: String = "synthetic",
      expectedSessionEpoch: Long? = null,
  ): CoachModelProbeResult {
    var readState = false
    var changedSyntheticState = false
    val result =
        agent.reply(
            WorkoutSnapshot(expectedOwner, WORKOUT, 0, emptyList()),
            "Это проверка инструментов на синтетической тренировке. Сначала вызови get_workout_state, затем submit_workout_changes с одним add_set для единственной секции из состояния и base_revision из него. Все данные тестовые. Не задавай уточняющий вопрос и не ограничивайся текстовым ответом.",
            CoachToolCodec.tools,
            expectedSessionEpoch = expectedSessionEpoch,
        ) { call ->
          val request =
              try {
                CoachToolCodec.decode(call)
              } catch (_: CoachToolValidationException) {
                return@reply CoachToolOutcome("{\"error\":\"invalid_arguments\"}")
              }
          when (request) {
            is CoachToolRequest.Autoregulation ->
                CoachToolOutcome("Расчёт доступен в активной тренировке.")
            CoachToolRequest.State -> {
              readState = true
              CoachToolOutcome(
                  """{"workout_id":"$WORKOUT","revision":0,"synthetic":true,"name":"Проверка модели","exercises":[{"section_id":"$SECTION","exercise_id":"$EXERCISE","name":"Тестовое упражнение","sets":[{"set_id":"$SET","weight_kg":20,"reps":8,"completed":false}]}],"rest":null,"pulse":null}"""
              )
            }
            is CoachToolRequest.Find ->
                CoachToolOutcome(
                    """{"exercises":[{"exercise_id":"$EXERCISE","name":"Тестовое упражнение"}]}"""
                )
            is CoachToolRequest.History -> CoachToolOutcome("{\"sessions\":[]}")
            is CoachToolRequest.Submit -> {
              changedSyntheticState =
                  readState &&
                      request.baseRevision == 0L &&
                      request.operations == listOf(CoachChangeIntent.AddSet(SECTION))
              if (changedSyntheticState)
                  CoachToolOutcome(
                      "Тестовый подход добавлен в изолированное состояние.",
                      CoachRunStatus.APPLIED,
                  )
              else
                  CoachToolOutcome(
                      "Модель вернула другое действие для тестовой тренировки.",
                      CoachRunStatus.ERROR,
                  )
            }
          }
        }
    val success = readState && changedSyntheticState && result.status == CoachRunStatus.APPLIED
    return CoachModelProbeResult(
        success,
        when {
          success ->
              "Модель поддерживает чтение тренировки и действия. Проверка пройдена на тестовых данных."
          result.status == CoachRunStatus.ERROR || result.status == CoachRunStatus.LIMIT ->
              result.text
          else -> "Модель не выполнила тестовые вызовы. Выберите модель с поддержкой инструментов."
        },
    )
  }

  private companion object {
    const val WORKOUT = "10000000-0000-4000-8000-000000000001"
    const val SECTION = "10000000-0000-4000-8000-000000000002"
    const val EXERCISE = "10000000-0000-4000-8000-000000000003"
    const val SET = "10000000-0000-4000-8000-000000000004"
  }
}

data class CoachModelProbeResult(val success: Boolean, val message: String)
