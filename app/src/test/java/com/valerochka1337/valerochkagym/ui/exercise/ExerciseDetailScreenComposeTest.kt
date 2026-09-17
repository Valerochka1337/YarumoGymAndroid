package com.valerochka1337.valerochkagym.ui.exercise

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad
import com.valerochka1337.valerochkagym.domain.ExerciseEquipmentRequirements
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w420dp-h800dp-xhdpi")
class ExerciseDetailScreenComposeTest {

  @get:Rule val compose = createComposeRule()

  @Test
  fun `standard exercise moves personal copy into the top bar menu`() {
    var copies = 0
    compose.setContent {
      GymTheme {
        ExerciseHeader(
            exercise =
                ExerciseEntity(
                    id = 1L,
                    name = "Стандартное упражнение",
                    muscleGroup = MuscleGroup.LEGS,
                    type = ExerciseType.STRENGTH,
                    origin = "STANDARD",
                ),
            requirements = ExerciseEquipmentRequirements.ExplicitNone,
            onBack = {},
            onEdit = {},
            onCopy = { copies++ },
        )
      }
    }

    compose.onNodeWithText("Стандартное").assertDoesNotExist()
    compose.onNodeWithText("Создать личную копию").assertDoesNotExist()
    compose.onNodeWithContentDescription("Редактировать упражнение").assertDoesNotExist()
    compose.onNodeWithContentDescription("Меню упражнения").performClick()
    compose.onNodeWithText("Создать личную копию").performClick()

    assertEquals(1, copies)
  }

  @Test
  fun `personal exercise keeps edit without origin labels or overflow menu`() {
    var edits = 0
    compose.setContent {
      GymTheme {
        ExerciseHeader(
            exercise =
                ExerciseEntity(
                    id = 1L,
                    name = "Моё упражнение",
                    muscleGroup = MuscleGroup.LEGS,
                    type = ExerciseType.STRENGTH,
                    origin = "PERSONAL",
                ),
            requirements = ExerciseEquipmentRequirements.ExplicitNone,
            onBack = {},
            onEdit = { edits++ },
            onCopy = {},
        )
      }
    }

    compose.onNodeWithText("Личное").assertDoesNotExist()
    compose.onNodeWithContentDescription("Меню упражнения").assertDoesNotExist()
    compose.onNodeWithContentDescription("Редактировать упражнение").performClick()

    assertEquals(1, edits)
  }

  @Test
  fun `detail keeps role text and exposes a read-only body map`() {
    compose.setContent {
      GymTheme {
        ExerciseDetailContent(
            exercise =
                ExerciseEntity(
                    id = 1L,
                    name = "Тестовое упражнение",
                    muscleGroup = MuscleGroup.CHEST,
                    type = ExerciseType.STRENGTH,
                    isCustom = true,
                ),
            loads =
                listOf(
                    MuscleLoad(Muscle.UPPER_CHEST, 100),
                    MuscleLoad(Muscle.TRICEPS, 50),
                    MuscleLoad(Muscle.SERRATUS_ANTERIOR, 0),
                ),
            statistics = null,
        )
      }
    }

    compose.onNodeWithText("Основная").fetchSemanticsNode()
    compose.onNodeWithText("Вторичная").fetchSemanticsNode()
    compose.onNodeWithText("Стабилизатор").fetchSemanticsNode()
    val map = compose.onNodeWithContentDescription("Карта тела, спереди").fetchSemanticsNode()
    org.junit.Assert.assertFalse(map.config.contains(SemanticsActions.OnClick))
    org.junit.Assert.assertFalse(map.config.contains(SemanticsActions.CustomActions))
  }

  @Test
  fun `detail exposes a personal hint before the exercise statistics`() {
    compose.setContent {
      GymTheme {
        ExerciseDetailContent(
            exercise =
                ExerciseEntity(
                    id = 1L,
                    name = "Стандартное упражнение",
                    muscleGroup = MuscleGroup.CHEST,
                    type = ExerciseType.STRENGTH,
                    origin = "STANDARD",
                ),
            loads = emptyList(),
            statistics = null,
            personalHint = "Лопатки вместе",
        )
      }
    }

    compose.onNodeWithText("Моя подсказка").fetchSemanticsNode()
    compose.onNodeWithText("Лопатки вместе").fetchSemanticsNode()
    compose.onNodeWithText("Изменить").fetchSemanticsNode()
  }
}
