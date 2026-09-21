package com.valerochka1337.valerochkagym.ui.routine

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEquipmentEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.relation.RoutineExerciseWithExercise
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithCount
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithExercises
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w420dp-h900dp-xhdpi")
class RoutineEditorScreenTest {

  @get:Rule val composeRule = createComposeRule()

  @Test
  fun `reorder keys map exercise cards without counting service list items`() {
    val exercises =
        listOf(
            editorExercise(editorId = "first", exerciseId = 1),
            editorExercise(editorId = "second", exerciseId = 2),
            editorExercise(editorId = "third", exerciseId = 3),
        )

    assertEquals(0, exercises.indexOfReorderKey("first"))
    assertEquals(2, exercises.indexOfReorderKey("third"))
    assertEquals(-1, exercises.indexOfReorderKey("gym-selection-card"))
  }

  @Test
  fun `tapping an exercise header expands and collapses its sets`() {
    composeRule.setContent {
      GymTheme {
        ExerciseCard(
            exercise =
                EditorExercise(
                    editorId = "bench",
                    exerciseId = 1,
                    exerciseName = "Жим лёжа",
                    exerciseType = ExerciseType.STRENGTH,
                    restSeconds = 90,
                    plannedSets =
                        listOf(
                            PlannedSet(weightKg = 80.0, reps = 8),
                            PlannedSet(weightKg = 80.0, reps = 8),
                            PlannedSet(weightKg = 80.0, reps = 8),
                        ),
                ),
            dragHandle = {},
            onRemove = {},
            onRestChange = {},
            onAddSet = {},
            onRemoveSet = {},
            onSetChange = { _, _ -> },
        )
      }
    }

    val header = composeRule.onNodeWithText("Жим лёжа")
    header.assert(
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Подходы свернуты"),
    )
    composeRule.onNodeWithText("3 подхода · отдых 90 сек").assertIsDisplayed()
    composeRule.onNodeWithText("Отдых, сек").assertDoesNotExist()

    header.performClick()
    composeRule.waitForIdle()

    header.assert(
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Подходы раскрыты"),
    )
    composeRule.onNodeWithText("Отдых, сек").assertIsDisplayed()

    header.performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Отдых, сек").assertDoesNotExist()
  }

  @Test
  fun `direct standard editor renders detail without editing controls`() {
    val viewModel =
        RoutineEditorViewModel(
            SavedStateHandle(mapOf(GymRoutes.ROUTINE_ID_ARG to "7")),
            StandardRoutineDao(),
            EmptyExerciseDao(),
        )
    composeRule.setContent {
      GymTheme { RoutineEditorScreen(onBack = {}, onAddExercise = {}, viewModel = viewModel) }
    }

    composeRule.waitForIdle()

    composeRule.onNodeWithText("Стандартная программа").assertExists()
    composeRule.onNodeWithText("Залы").assertExists()
    composeRule.onNodeWithText("Название программы").assertDoesNotExist()
    composeRule.onNodeWithText("Сохранить").assertDoesNotExist()
    composeRule.onNodeWithText("Упражнение").assertDoesNotExist()
    composeRule.onNodeWithContentDescription("Удалить упражнение").assertDoesNotExist()
  }

  private fun editorExercise(editorId: String, exerciseId: Long) =
      EditorExercise(
          editorId = editorId,
          exerciseId = exerciseId,
          exerciseName = "Упражнение $exerciseId",
          exerciseType = ExerciseType.STRENGTH,
          restSeconds = null,
          plannedSets = listOf(PlannedSet()),
      )

  private class StandardRoutineDao : RoutineDao {
    private val routine =
        RoutineWithExercises(
            routine = RoutineEntity(id = 7, name = "Стандартная программа", origin = "STANDARD"),
            exercises =
                listOf(
                    RoutineExerciseWithExercise(
                        routineExercise =
                            RoutineExerciseEntity(
                                routineId = 7,
                                exerciseId = 1,
                                position = 0,
                                restSeconds = 90,
                                plannedSets = listOf(PlannedSet(reps = 5)),
                            ),
                        exercise =
                            ExerciseEntity(
                                1,
                                "Приседания",
                                MuscleGroup.LEGS,
                                ExerciseType.STRENGTH,
                            ),
                    )
                ),
        )

    override fun observeRoutinesWithCount(): Flow<List<RoutineWithCount>> = flowOf(emptyList())

    override fun observeRoutinesFull(): Flow<List<RoutineWithExercises>> = flowOf(listOf(routine))

    override suspend fun routinesFullOnce(): List<RoutineWithExercises> =
        observeRoutinesFull().first()

    override suspend fun getRoutineWithExercises(id: Long): RoutineWithExercises? =
        routine.takeIf { it.routine.id == id }

    override suspend fun getRoutineName(id: Long): String? =
        routine.takeIf { it.routine.id == id }?.routine?.name

    override suspend fun getRoutineBySyncId(syncId: String): RoutineEntity? = null

    override suspend fun upsertRoutine(routine: RoutineEntity): Long = routine.id

    override suspend fun deleteRoutine(id: Long) = Unit

    override suspend fun insertRoutineExercises(
        routineExercises: List<RoutineExerciseEntity>
    ): List<Long> = emptyList()

    override suspend fun deleteRoutineExercises(routineId: Long) = Unit
  }

  private class EmptyExerciseDao : ExerciseDao {
    override fun getAll(): Flow<List<ExerciseEntity>> = flowOf(emptyList())

    override suspend fun insert(exercise: ExerciseEntity): Long = 0

    override suspend fun update(exercise: ExerciseEntity) = Unit

    override suspend fun insertAll(exercises: List<ExerciseEntity>) = Unit

    override suspend fun count(): Int = 0

    override suspend fun getById(id: Long): ExerciseEntity? = null

    override suspend fun getAllOnce(): List<ExerciseEntity> = emptyList()

    override suspend fun getRequirementIds(exerciseId: Long): List<String> = emptyList()

    override suspend fun getRequirements(exerciseIds: List<Long>): List<ExerciseEquipmentEntity> =
        emptyList()

    override fun observeAllRequirements(): Flow<List<ExerciseEquipmentEntity>> = flowOf(emptyList())

    override suspend fun insertRequirements(requirements: List<ExerciseEquipmentEntity>) = Unit

    override suspend fun deleteRequirements(exerciseId: Long) = Unit
  }
}
