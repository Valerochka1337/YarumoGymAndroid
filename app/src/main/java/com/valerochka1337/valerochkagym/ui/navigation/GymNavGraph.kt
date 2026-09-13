package com.valerochka1337.valerochkagym.ui.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.valerochka1337.valerochkagym.ui.active.ActiveWorkoutScreen
import com.valerochka1337.valerochkagym.ui.active.ActiveWorkoutViewModel
import com.valerochka1337.valerochkagym.ui.analysis.AnalysisScreen
import com.valerochka1337.valerochkagym.ui.calendar.CalendarScreen
import com.valerochka1337.valerochkagym.ui.calendarai.CalendarAiScreen
import com.valerochka1337.valerochkagym.ui.coach.CoachChatScreen
import com.valerochka1337.valerochkagym.ui.coachrelation.*
import com.valerochka1337.valerochkagym.ui.exercise.ExerciseDetailScreen
import com.valerochka1337.valerochkagym.ui.gyms.GymDetailScreen
import com.valerochka1337.valerochkagym.ui.gyms.GymEditorScreen
import com.valerochka1337.valerochkagym.ui.gyms.GymsScreen
import com.valerochka1337.valerochkagym.ui.history.WorkoutDetailScreen
import com.valerochka1337.valerochkagym.ui.library.ExerciseLibraryScreen
import com.valerochka1337.valerochkagym.ui.measurements.MeasurementEditorScreen
import com.valerochka1337.valerochkagym.ui.measurements.MeasurementsScreen
import com.valerochka1337.valerochkagym.ui.profile.ProfileScreen
import com.valerochka1337.valerochkagym.ui.routine.RoutineDetailScreen
import com.valerochka1337.valerochkagym.ui.routine.RoutineEditorScreen
import com.valerochka1337.valerochkagym.ui.routine.RoutineEditorViewModel
import com.valerochka1337.valerochkagym.ui.settings.SettingsScreen
import com.valerochka1337.valerochkagym.ui.summary.WorkoutSummaryScreen
import com.valerochka1337.valerochkagym.ui.theme.GymMotion
import com.valerochka1337.valerochkagym.ui.trainingproposal.*
import com.valerochka1337.valerochkagym.ui.update.AppUpdateUiState
import com.valerochka1337.valerochkagym.ui.workouts.WorkoutsScreen

/**
 * All navigation routes in the app. The three tab roots use plain constants; parameterized routes
 * expose a builder that fills in the argument.
 */
object GymRoutes {
  const val GYM_IDS_ARG = "gymIds"
  const val WORKOUT_ID_ARG = "workoutId"
  const val WORKOUTS = "workouts"
  const val CALENDAR = "calendar"
  const val ANALYSIS = "analysis"
  const val MEASUREMENTS = "measurements"
  const val SETTINGS = "settings"
  const val PROFILE = "profile"
  const val GYMS = "gyms"
  const val LIBRARY = "library?$GYM_IDS_ARG={$GYM_IDS_ARG}&$WORKOUT_ID_ARG={$WORKOUT_ID_ARG}"
  const val ACTIVE_WORKOUT = "active_workout"
  const val COACH_CHAT = "coach_chat/{$WORKOUT_ID_ARG}"

  const val ROUTINE_ID_ARG = "routineId"
  const val MEASUREMENT_ID_ARG = "measurementId"
  const val EXERCISE_ID_ARG = "exerciseId"
  private const val LEGACY_EXECUTION_GROUP_ARG = "executionGroup"
  const val GYM_ID_ARG = "gymId"
  const val GYM_COPY_SOURCE_ARG = "copyGymId"

  /** Ключ savedStateHandle, через который библиотека-пикер возвращает выбранное упражнение. */
  const val SELECTED_EXERCISE_ID = "selected_exercise_id"

  const val ROUTINE_EDITOR = "routine_editor?$ROUTINE_ID_ARG={$ROUTINE_ID_ARG}"
  const val ROUTINE_DETAIL = "routine_detail/{$ROUTINE_ID_ARG}"
  const val WORKOUT_SUMMARY = "workout_summary/{$WORKOUT_ID_ARG}"
  const val WORKOUT_DETAIL = "workout_detail/{$WORKOUT_ID_ARG}"
  const val MEASUREMENT_EDITOR = "measurement_editor?$MEASUREMENT_ID_ARG={$MEASUREMENT_ID_ARG}"
  const val EXERCISE_DETAIL = "exercise_detail/{$EXERCISE_ID_ARG}"
  const val GYM_DETAIL = "gym_detail/{$GYM_ID_ARG}"
  const val LEGACY_EXERCISE_DETAIL =
      "exercise_detail/{$EXERCISE_ID_ARG}/{$LEGACY_EXECUTION_GROUP_ARG}"
  const val GYM_EDITOR =
      "gym_editor?$GYM_ID_ARG={$GYM_ID_ARG}&$GYM_COPY_SOURCE_ARG={$GYM_COPY_SOURCE_ARG}"

  fun routineEditor(routineId: String? = null) =
      if (routineId != null) "routine_editor?$ROUTINE_ID_ARG=$routineId" else "routine_editor"

  fun routineDetail(routineId: Long) = "routine_detail/$routineId"

  fun workoutSummary(workoutId: String) = "workout_summary/$workoutId"

  fun workoutDetail(workoutId: String) = "workout_detail/$workoutId"

  fun coachChat(workoutId: String) = "coach_chat/${Uri.encode(workoutId)}"

  fun exerciseDetail(exerciseId: Long) = "exercise_detail/$exerciseId"

  fun gymDetail(gymId: String) = "gym_detail/${Uri.encode(gymId)}"

  fun measurementEditor(measurementId: String? = null) =
      if (measurementId == null) "measurement_editor"
      else "measurement_editor?$MEASUREMENT_ID_ARG=$measurementId"

  fun gymEditor(gymId: String? = null, copySourceGymId: String? = null): String {
    val arguments = buildList {
      gymId?.let { add("$GYM_ID_ARG=${Uri.encode(it)}") }
      copySourceGymId?.let { add("$GYM_COPY_SOURCE_ARG=${Uri.encode(it)}") }
    }
    return if (arguments.isEmpty()) "gym_editor" else "gym_editor?${arguments.joinToString("&")}"
  }

  fun library(
      gymIds: Set<String> = emptySet(),
      workoutId: String? = null,
  ): String {
    val arguments = buildList {
      if (gymIds.isNotEmpty()) {
        add("$GYM_IDS_ARG=${Uri.encode(gymIds.sorted().joinToString(","))}")
      }
      if (workoutId != null) add("$WORKOUT_ID_ARG=${Uri.encode(workoutId)}")
    }
    return if (arguments.isEmpty()) "library" else "library?${arguments.joinToString("&")}"
  }
}

/** The tab root that the app opens on and that back navigation returns to. */
const val GYM_START_DESTINATION = GymRoutes.WORKOUTS

// Спеки переходов — из GymMotion (ui/theme/Motion.kt): единственное место, где они объявлены.
private val NavSlideSpec = GymMotion.NavSlideSpec
private val NavFadeSpec = GymMotion.NavFadeSpec
private val TabFadeSpec = GymMotion.TabFadeSpec

/** Порядок нижних вкладок слева направо; -1 — маршрут не является вкладкой. */
private fun tabIndex(route: String?): Int =
    when (route) {
      GymRoutes.WORKOUTS -> 0
      GymRoutes.CALENDAR -> 1
      GymRoutes.ANALYSIS -> 2
      else -> -1
    }

/**
 * Полноэкранные модальные маршруты: всплывают снизу поверх неподвижного фона. Фон под ними НЕ
 * должен уезжать/затухать — иначе сквозь всплывающую панель мелькает пустой скаффолд.
 */
private fun isModalRoute(route: String?): Boolean =
    when (route) {
      GymRoutes.ACTIVE_WORKOUT,
      GymRoutes.ROUTINE_EDITOR,
      GymRoutes.WORKOUT_SUMMARY,
      GymRoutes.MEASUREMENT_EDITOR,
      GymRoutes.GYM_EDITOR -> true
      else -> false
    }

/**
 * Вкладки нижнего меню — сиблинги, а не иерархия, поэтому между ними используем чистый кроссфейд
 * (направление слайда для них не имеет смысла и подтормаживает при restoreState).
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(): Boolean {
  val from = tabIndex(initialState.destination.route)
  val to = tabIndex(targetState.destination.route)
  return from >= 0 && to >= 0 && from != to
}

/**
 * Hosts every destination. [modifier] carries the padding from the enclosing [MainScaffold] so tab
 * content sits above the navigation bar.
 */
@Composable
fun GymNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    windowWidthClass: GymWindowWidthClass = GymWindowWidthClass.Compact,
    appUpdateState: AppUpdateUiState,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onRetryUpdate: () -> Unit,
) {
  NavHost(
      navController = navController,
      startDestination = GYM_START_DESTINATION,
      modifier = modifier,
      // Базовый переход для обычных экранов: сдвиг по горизонтали + затухание.
      enterTransition = {
        if (isTabSwitch()) fadeIn(TabFadeSpec)
        else fadeIn(NavFadeSpec) + slideIntoContainer(SlideDirection.Start, NavSlideSpec)
      },
      exitTransition = {
        when {
          // Уходим под всплывающую модалку — стоим на месте и держим непрозрачность.
          isModalRoute(targetState.destination.route) -> ExitTransition.None
          isTabSwitch() -> fadeOut(TabFadeSpec)
          else -> fadeOut(NavFadeSpec) + slideOutOfContainer(SlideDirection.Start, NavSlideSpec)
        }
      },
      popEnterTransition = {
        when {
          // Модалка над нами уезжает вниз — мы всё это время были под ней, просто остаёмся.
          isModalRoute(initialState.destination.route) -> EnterTransition.None
          isTabSwitch() -> fadeIn(TabFadeSpec)
          else -> fadeIn(NavFadeSpec) + slideIntoContainer(SlideDirection.End, NavSlideSpec)
        }
      },
      popExitTransition = {
        if (isTabSwitch()) fadeOut(TabFadeSpec)
        else fadeOut(NavFadeSpec) + slideOutOfContainer(SlideDirection.End, NavSlideSpec)
      },
  ) {
    composable(GymRoutes.WORKOUTS) {
      WorkoutsScreen(
          onCreateRoutine = { navController.navigate(GymRoutes.routineEditor(null)) },
          onOpenRoutine = { id -> navController.navigate(GymRoutes.routineDetail(id)) },
          onStartWorkout = { navController.navigate(GymRoutes.ACTIVE_WORKOUT) },
          onOpenSettings = { navController.navigate(GymRoutes.SETTINGS) },
      )
    }
    composable("coach_relations") {
      CoachRelationsScreen(
          onBack = { navController.popBackStack() },
          onOpen = { id, clients -> navController.navigate("coach_relation/$id/$clients") },
      )
    }
    composable("coach_relation/{relationId}/{clients}") { entry ->
      CoachRelationDetailScreen(
          entry.arguments?.getString("relationId").orEmpty(),
          entry.arguments?.getString("clients") == "true",
          onBack = { navController.popBackStack() },
      )
    }
    composable("calendar_ai") {
      CalendarAiScreen(
          onBack = { navController.popBackStack() },
          onOpenProposal = { navController.navigate("training_proposal/${Uri.encode(it)}") },
          onOpenProfile = { navController.navigate(GymRoutes.PROFILE) },
      )
    }
    composable("training_proposals") {
      TrainingProposalInboxScreen(
          onCreateAi = { navController.navigate("calendar_ai") },
          onOpen = { navController.navigate("training_proposal/${Uri.encode(it)}") },
          onBack = { navController.popBackStack() },
      )
    }
    composable("training_proposal/{proposalId}") { entry ->
      TrainingProposalDetailScreen(
          requireNotNull(entry.arguments?.getString("proposalId")),
          onBack = { navController.popBackStack() },
      )
    }
    composable(GymRoutes.CALENDAR) {
      CalendarScreen(
          onWorkoutClick = { workoutId ->
            navController.navigate(GymRoutes.workoutDetail(workoutId))
          },
          onStartWorkout = { navController.navigate(GymRoutes.ACTIVE_WORKOUT) },
          onOpenPlanning = { navController.navigate("training_proposals") },
          onOpenSettings = { navController.navigate(GymRoutes.SETTINGS) },
      )
    }
    composable(GymRoutes.ANALYSIS) {
      AnalysisScreen(
          onOpenSettings = { navController.navigate(GymRoutes.SETTINGS) },
          onOpenMeasurements = { navController.navigate(GymRoutes.MEASUREMENTS) },
          onExerciseClick = { id -> navController.navigate(GymRoutes.exerciseDetail(id)) },
      )
    }
    composable(GymRoutes.MEASUREMENTS) {
      MeasurementsScreen(
          onBack = { navController.popBackStack() },
          onCreateMeasurement = { navController.navigate(GymRoutes.measurementEditor()) },
          onEditMeasurement = { id -> navController.navigate(GymRoutes.measurementEditor(id)) },
      )
    }
    composable(GymRoutes.SETTINGS) {
      SettingsScreen(
          onBack = { navController.popBackStack() },
          onOpenGyms = { navController.navigate(GymRoutes.GYMS) },
          onOpenProfile = { navController.navigate(GymRoutes.PROFILE) },
          onOpenRelations = { navController.navigate("coach_relations") },
          appUpdateState = appUpdateState,
          onCheckUpdate = onCheckUpdate,
          onDownloadUpdate = onDownloadUpdate,
          onInstallUpdate = onInstallUpdate,
          onRetryUpdate = onRetryUpdate,
      )
    }

    composable(GymRoutes.PROFILE) { ProfileScreen(onBack = { navController.popBackStack() }) }

    composable(GymRoutes.GYMS) {
      GymsScreen(
          onBack = { navController.popBackStack() },
          onCreateGym = { navController.navigate(GymRoutes.gymEditor()) },
          onOpenGym = { id -> navController.navigate(GymRoutes.gymDetail(id)) },
          windowWidthClass = windowWidthClass,
      )
    }

    composable(
        route = GymRoutes.ROUTINE_DETAIL,
        arguments = listOf(navArgument(GymRoutes.ROUTINE_ID_ARG) { type = NavType.LongType }),
    ) {
      RoutineDetailScreen(
          onBack = { navController.popBackStack() },
          onEditRoutine = { id -> navController.navigate(GymRoutes.routineEditor(id.toString())) },
          onExerciseClick = { id -> navController.navigate(GymRoutes.exerciseDetail(id)) },
          windowWidthClass = windowWidthClass,
      )
    }

    composable(
        route = GymRoutes.GYM_DETAIL,
        arguments = listOf(navArgument(GymRoutes.GYM_ID_ARG) { type = NavType.StringType }),
    ) {
      GymDetailScreen(
          onBack = { navController.popBackStack() },
          onEditGym = { id -> navController.navigate(GymRoutes.gymEditor(id)) },
          onExerciseClick = { id -> navController.navigate(GymRoutes.exerciseDetail(id)) },
          windowWidthClass = windowWidthClass,
      )
    }

    composable(
        route = GymRoutes.GYM_EDITOR,
        arguments =
            listOf(
                navArgument(GymRoutes.GYM_ID_ARG) {
                  type = NavType.StringType
                  nullable = true
                  defaultValue = null
                },
                navArgument(GymRoutes.GYM_COPY_SOURCE_ARG) {
                  type = NavType.StringType
                  nullable = true
                  defaultValue = null
                },
            ),
        enterTransition = { slideIntoContainer(SlideDirection.Up, NavSlideSpec) },
        popExitTransition = { slideOutOfContainer(SlideDirection.Down, NavSlideSpec) },
    ) {
      GymEditorScreen(
          onBack = { navController.popBackStack() },
          windowWidthClass = windowWidthClass,
      )
    }

    composable(
        route = GymRoutes.LIBRARY,
        arguments =
            listOf(
                navArgument(GymRoutes.GYM_IDS_ARG) {
                  type = NavType.StringType
                  nullable = true
                  defaultValue = null
                },
                navArgument(GymRoutes.WORKOUT_ID_ARG) {
                  type = NavType.StringType
                  nullable = true
                  defaultValue = null
                },
            ),
    ) {
      ExerciseLibraryScreen(
          onBack = { navController.popBackStack() },
          onOpenSettings = { navController.navigate(GymRoutes.SETTINGS) },
          onOpenProfile = { navController.navigate(GymRoutes.PROFILE) },
          windowWidthClass = windowWidthClass,
          // Открыта из редактора программы: возвращаем выбранное упражнение назад.
          onExerciseSelected = { exercise ->
            navController.previousBackStackEntry
                ?.savedStateHandle
                ?.set(GymRoutes.SELECTED_EXERCISE_ID, exercise.id)
            navController.popBackStack()
          },
          onExerciseAddedToWorkout = { navController.popBackStack() },
          onExerciseInfo = { exercise ->
            navController.navigate(GymRoutes.exerciseDetail(exercise.id))
          },
      )
    }
    composable(
        GymRoutes.ACTIVE_WORKOUT,
        // Полноэкранный маршрут — выезжает снизу и уезжает вниз.
        enterTransition = { slideIntoContainer(SlideDirection.Up, NavSlideSpec) },
        popExitTransition = { slideOutOfContainer(SlideDirection.Down, NavSlideSpec) },
    ) { backStackEntry ->
      val viewModel = hiltViewModel<ActiveWorkoutViewModel>(backStackEntry)
      val selectedExerciseId by
          backStackEntry.savedStateHandle
              .getStateFlow<Long?>(GymRoutes.SELECTED_EXERCISE_ID, null)
              .collectAsStateWithLifecycle()
      LaunchedEffect(selectedExerciseId) {
        val id = selectedExerciseId ?: return@LaunchedEffect
        viewModel.addExerciseById(id)
        backStackEntry.savedStateHandle[GymRoutes.SELECTED_EXERCISE_ID] = null
      }
      ActiveWorkoutScreen(
          onFinished = { workoutId ->
            navController.navigate(GymRoutes.workoutSummary(workoutId)) {
              popUpTo(GymRoutes.ACTIVE_WORKOUT) { inclusive = true }
            }
          },
          onDiscarded = { navController.popBackStack(GymRoutes.WORKOUTS, inclusive = false) },
          onNavigateBack = { navController.popBackStack() },
          onAddExercise = {
            val gymIds =
                viewModel.uiState.value.workout?.gyms.orEmpty().mapTo(linkedSetOf()) { it.syncId }
            navController.navigate(
                GymRoutes.library(
                    gymIds = gymIds,
                    workoutId = viewModel.uiState.value.workout?.workout?.id,
                ),
            )
          },
          onExerciseClick = { id -> navController.navigate(GymRoutes.exerciseDetail(id)) },
          onOpenCoach = {
            viewModel.uiState.value.workout?.workout?.id?.let {
              navController.navigate(GymRoutes.coachChat(it))
            }
          },
          viewModel = viewModel,
      )
    }

    composable(
        route = GymRoutes.COACH_CHAT,
        arguments = listOf(navArgument(GymRoutes.WORKOUT_ID_ARG) { type = NavType.StringType }),
    ) {
      CoachChatScreen(onBack = { navController.popBackStack() })
    }

    composable(
        route = GymRoutes.ROUTINE_EDITOR,
        arguments =
            listOf(
                navArgument(GymRoutes.ROUTINE_ID_ARG) {
                  type = NavType.StringType
                  nullable = true
                  defaultValue = null
                },
            ),
        // Редактор — полноэкранная модалка, как активная тренировка и итоги:
        // выезжает снизу, уезжает вниз (единое правило для модальных маршрутов).
        enterTransition = { slideIntoContainer(SlideDirection.Up, NavSlideSpec) },
        popExitTransition = { slideOutOfContainer(SlideDirection.Down, NavSlideSpec) },
    ) { backStackEntry ->
      val viewModel = hiltViewModel<RoutineEditorViewModel>(backStackEntry)
      val selectedExerciseId by
          backStackEntry.savedStateHandle
              .getStateFlow<Long?>(GymRoutes.SELECTED_EXERCISE_ID, null)
              .collectAsStateWithLifecycle()
      LaunchedEffect(selectedExerciseId) {
        val id = selectedExerciseId ?: return@LaunchedEffect
        viewModel.addExerciseById(id)
        backStackEntry.savedStateHandle[GymRoutes.SELECTED_EXERCISE_ID] = null
      }
      RoutineEditorScreen(
          onBack = { navController.popBackStack() },
          onAddExercise = {
            navController.navigate(GymRoutes.library(viewModel.uiState.value.selectedGymIds))
          },
          viewModel = viewModel,
      )
    }

    composable(
        route = GymRoutes.WORKOUT_SUMMARY,
        arguments = listOf(navArgument(GymRoutes.WORKOUT_ID_ARG) { type = NavType.StringType }),
        // Итоги — тоже полноэкранные: выезжают снизу, уезжают вниз.
        enterTransition = { slideIntoContainer(SlideDirection.Up, NavSlideSpec) },
        popExitTransition = { slideOutOfContainer(SlideDirection.Down, NavSlideSpec) },
    ) {
      WorkoutSummaryScreen(
          onDone = { navController.popBackStack(GymRoutes.WORKOUTS, inclusive = false) },
          onExerciseClick = { id -> navController.navigate(GymRoutes.exerciseDetail(id)) },
      )
    }

    composable(
        route = GymRoutes.WORKOUT_DETAIL,
        arguments = listOf(navArgument(GymRoutes.WORKOUT_ID_ARG) { type = NavType.StringType }),
    ) {
      WorkoutDetailScreen(
          onBack = { navController.popBackStack() },
          onExerciseClick = { id -> navController.navigate(GymRoutes.exerciseDetail(id)) },
          onOpenCoach = {
            val workoutId = requireNotNull(it.arguments?.getString(GymRoutes.WORKOUT_ID_ARG))
            navController.navigate(GymRoutes.coachChat(workoutId))
          },
      )
    }

    composable(
        route = GymRoutes.EXERCISE_DETAIL,
        arguments = listOf(navArgument(GymRoutes.EXERCISE_ID_ARG) { type = NavType.LongType }),
    ) {
      ExerciseDetailScreen(onBack = { navController.popBackStack() })
    }

    composable(
        route = GymRoutes.LEGACY_EXERCISE_DETAIL,
        arguments =
            listOf(
                navArgument(GymRoutes.EXERCISE_ID_ARG) { type = NavType.LongType },
                navArgument("executionGroup") { type = NavType.StringType },
            ),
    ) {
      ExerciseDetailScreen(onBack = { navController.popBackStack() })
    }

    composable(
        route = GymRoutes.MEASUREMENT_EDITOR,
        arguments =
            listOf(
                navArgument(GymRoutes.MEASUREMENT_ID_ARG) {
                  type = NavType.StringType
                  nullable = true
                  defaultValue = null
                },
            ),
        // Редактор замера — полноэкранная форма, как редакторы программы и расписания.
        enterTransition = { slideIntoContainer(SlideDirection.Up, NavSlideSpec) },
        popExitTransition = { slideOutOfContainer(SlideDirection.Down, NavSlideSpec) },
    ) {
      MeasurementEditorScreen(
          onBack = { navController.popBackStack() },
          onOpenSettings = { navController.navigate(GymRoutes.SETTINGS) },
          onOpenProfile = { navController.navigate(GymRoutes.PROFILE) },
      )
    }
  }
}
