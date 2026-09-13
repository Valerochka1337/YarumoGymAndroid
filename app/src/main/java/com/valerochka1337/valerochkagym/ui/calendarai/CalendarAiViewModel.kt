package com.valerochka1337.valerochkagym.ui.calendarai

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.ai.CalendarAiIntent
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendSync
import com.valerochka1337.valerochkagym.data.db.LocalEquipmentCatalog
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.GymDao
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.profile.AiProfilePromptGate
import com.valerochka1337.valerochkagym.domain.displayName
import com.valerochka1337.valerochkagym.service.WallClock
import com.valerochka1337.valerochkagym.ui.components.PlanningDateTimeResolution
import com.valerochka1337.valerochkagym.ui.components.displayPlanningDateTime
import com.valerochka1337.valerochkagym.ui.components.resolvePlanningDateTime
import com.valerochka1337.valerochkagym.ui.components.tomorrowAtSix
import com.valerochka1337.valerochkagym.ui.profile.AiProfilePromptUi
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CalendarAiChoice(val id: String, val label: String, val archived: Boolean = false)

data class CalendarAiForm(
    val date: String,
    val time: String = "18:00",
    val timeZoneId: String,
    /** The user-visible date/time is a device-zone view of this unchanged appointment instant. */
    val preservedInstantMillis: Long? = null,
    val gymIds: Set<String> = emptySet(),
    val excludedExerciseIds: Set<String> = emptySet(),
    val excludedEquipmentIds: Set<String> = emptySet(),
    val priorityMuscles: Set<String> = emptySet(),
    val includeNotes: Boolean = true,
    val availableDurationMinutes: String = "60",
    val currentState: String = "",
    val preferences: String = "",
)

data class CalendarAiUiState(
    val form: CalendarAiForm,
    val gyms: List<CalendarAiChoice> = emptyList(),
    val exercises: List<CalendarAiChoice> = emptyList(),
    val equipment: List<CalendarAiChoice> = emptyList(),
    val muscles: List<CalendarAiChoice> =
        Muscle.entries.map { CalendarAiChoice(it.name, it.displayName()) },
    val generating: Boolean = false,
    val error: String? = null,
    val profilePrompt: AiProfilePromptUi? = null,
)

@HiltViewModel
class CalendarAiViewModel
@Inject
constructor(
    private val repository: com.valerochka1337.valerochkagym.data.ai.WorkoutPreparationRepository,
    private val sessions: BackendSessionStore,
    private val sync: BackendSync,
    private val profileGate: AiProfilePromptGate,
    private val clock: WallClock,
    exercises: ExerciseDao,
    gyms: GymDao,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
  private val restoredForm = restoreSavedForm()
  private val mutableState =
      MutableStateFlow(
          CalendarAiUiState(
              form = restoredForm ?: defaultForm(),
          )
      )
  private val _saved = Channel<Long>(Channel.BUFFERED)
  val saved = _saved.receiveAsFlow().filter { it == sessions.sessionEpoch }

  private val _openProposal = Channel<Pair<Long, String>>(Channel.BUFFERED)
  val openProposal =
      _openProposal.receiveAsFlow().filter { it.first == sessions.sessionEpoch }.map { it.second }
  private val _openProfile = Channel<Long>(Channel.BUFFERED)
  val openProfile = _openProfile.receiveAsFlow().filter { it == sessions.sessionEpoch }

  private var request: Job? = null
  private var generation = 0L
  private var sessionEpoch = sessions.sessionEpoch
  private var transferVersion: Any? = null
  private var formEdited = restoredForm != null

  val uiState: StateFlow<CalendarAiUiState> =
      combine(mutableState, exercises.getAll(), gyms.observeGyms(), LocalEquipmentCatalog.state) {
              state,
              exerciseRows,
              gymRows,
              equipmentRows,
            ->
            state.copy(
                gyms =
                    gymRows.filterNot { it.archived }.map { CalendarAiChoice(it.syncId, it.name) },
                exercises =
                    exerciseRows.map {
                      CalendarAiChoice(it.syncId, it.name, archived = it.archived)
                    },
                equipment =
                    equipmentRows
                        .filterNot { it.archived }
                        .map { CalendarAiChoice(it.equipment.id, it.equipment.name) },
            )
          }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), mutableState.value)

  init {
    viewModelScope.launch {
      val initialEpoch = sessions.sessionEpoch
      val row = repository.current.first()
      val intent =
          row?.let {
            com.valerochka1337.valerochkagym.data.trainingproposal.ProposalWire.json
                .decodeFromString<CalendarAiIntent>(it.intentJson)
          }
      if (
          intent != null &&
              !formEdited &&
              initialEpoch == sessions.sessionEpoch &&
              row.owner == sessions.session.value?.userId
      ) {
        replaceForm(formFromIntent(intent))
      }
    }
    viewModelScope.launch {
      combine(sessions.session, sync.transfer) { _, transfer -> transfer }
          .collect { transfer ->
            val epochChanged = sessionEpoch != sessions.sessionEpoch
            val transferChanged = transferVersion != null && transferVersion != transfer
            sessionEpoch = sessions.sessionEpoch
            transferVersion = transfer
            if (epochChanged) invalidateForContextChange()
          }
    }
  }

  fun setDate(value: String) = updateDateTime { copy(date = value) }

  fun setTime(value: String) = updateDateTime { copy(time = value) }

  /** Kept until all callers stop passing the removed manual zone selector. */
  fun setTimeZone(@Suppress("UNUSED_PARAMETER") value: String) = synchronizeDeviceTimeZone()

  /** Re-renders an unchanged instant in the current device zone without moving it. */
  fun synchronizeDeviceTimeZone() {
    updateForm(userEdited = false) {
      preservedInstantMillis?.let { instant ->
        val displayed = displayPlanningDateTime(instant, deviceZone)
        copy(date = displayed.date, time = displayed.time, timeZoneId = deviceZone.id)
      } ?: copy(timeZoneId = deviceZone.id)
    }
  }

  fun toggleGym(id: String) = updateForm { copy(gymIds = gymIds.toggle(id)) }

  fun toggleExcludedExercise(id: String) = updateForm {
    copy(excludedExerciseIds = excludedExerciseIds.toggle(id))
  }

  fun toggleExcludedEquipment(id: String) = updateForm {
    copy(excludedEquipmentIds = excludedEquipmentIds.toggle(id))
  }

  fun togglePriorityMuscle(id: String) = updateForm {
    copy(priorityMuscles = priorityMuscles.toggle(id))
  }

  fun setIncludeNotes(value: Boolean) = updateForm { copy(includeNotes = value) }

  fun setDuration(value: String) = updateForm { copy(availableDurationMinutes = value) }

  fun setCurrentState(value: String) = updateForm { copy(currentState = value) }

  fun setPreferences(value: String) = updateForm { copy(preferences = value) }

  fun generate() {
    if (mutableState.value.generating || mutableState.value.profilePrompt != null) return
    val intent = intentOrNull() ?: return
    val token = ++generation
    val epoch = sessions.sessionEpoch
    request = viewModelScope.launch { generateIntent(token, epoch, intent) }
  }

  fun acknowledgeProfilePrompt(token: String) {
    viewModelScope.launch { profileGate.acknowledgeVisible(token) }
  }

  fun continueAfterProfilePrompt(token: String, disableFuturePrompts: Boolean = false) {
    val intent = intentOrNull() ?: return
    val tokenGeneration = generation
    val epoch = sessions.sessionEpoch
    viewModelScope.launch {
      if (mutableState.value.profilePrompt?.token != token) return@launch
      val consumed = profileGate.consume(token, disableFuturePrompts)
      if (mutableState.value.profilePrompt?.token != token) return@launch
      mutableState.update { it.copy(profilePrompt = null) }
      if (consumed) generateIntent(tokenGeneration, epoch, intent)
    }
  }

  fun fillProfileFromPrompt(token: String) {
    viewModelScope.launch {
      if (mutableState.value.profilePrompt?.token != token) return@launch
      val consumed = profileGate.consume(token, disableFuturePrompts = false)
      if (mutableState.value.profilePrompt?.token != token) return@launch
      mutableState.update { it.copy(profilePrompt = null) }
      if (consumed) _openProfile.send(sessions.sessionEpoch)
    }
  }

  fun dismissProfilePrompt(token: String) {
    viewModelScope.launch {
      if (mutableState.value.profilePrompt?.token != token) return@launch
      profileGate.cancel(token)
      if (mutableState.value.profilePrompt?.token == token) {
        mutableState.update { it.copy(profilePrompt = null) }
      }
    }
  }

  private suspend fun generateIntent(token: Long, epoch: Long, intent: CalendarAiIntent) {
    if (token != generation || epoch != sessions.sessionEpoch) return
    mutableState.update { it.copy(generating = true, error = null) }
    try {
      repository.enqueue(intent)
      if (token != generation || epoch != sessions.sessionEpoch) return
      mutableState.update { it.copy(generating = false) }
      _saved.send(epoch)
    } catch (error: CancellationException) {
      if (token == generation && epoch == sessions.sessionEpoch) {
        mutableState.update { it.copy(generating = false) }
      }
      throw error
    } catch (error: Exception) {
      if (token == generation && epoch == sessions.sessionEpoch) {
        mutableState.update {
          it.copy(
              generating = false,
              error = calendarAiErrorMessage(error),
          )
        }
      }
    }
  }

  private fun invalidateForContextChange() {
    generation++
    request?.cancel()
    mutableState.value.profilePrompt?.let { prompt ->
      viewModelScope.launch { profileGate.cancel(prompt.token) }
    }
    val form = defaultForm()
    clearSavedForm()
    mutableState.update {
      it.copy(
          form = form,
          generating = false,
          profilePrompt = null,
          error = "Аккаунт или синхронизация изменились. Проверьте форму ещё раз",
      )
    }
    saveForm(form)
  }

  override fun onCleared() {
    mutableState.value.profilePrompt?.token?.let { token ->
      viewModelScope.launch(kotlinx.coroutines.NonCancellable) { profileGate.cancel(token) }
    }
    super.onCleared()
  }

  private fun intentOrNull(): CalendarAiIntent? {
    val form = mutableState.value.form.inCurrentDeviceZone()
    val resolved =
        resolvePlanningDateTime(
            date = form.date,
            time = form.time,
            zone = deviceZone,
            unchangedInstantMillis = form.preservedInstantMillis,
        )
    val startsAtMillis =
        when (resolved) {
          is PlanningDateTimeResolution.Resolved -> resolved.instantMillis
          PlanningDateTimeResolution.Gap -> return showFormError(FormError.Gap)
          PlanningDateTimeResolution.Invalid -> return showFormError(FormError.DateTime)
        }
    if (startsAtMillis <= clock.nowMillis()) return showFormError(FormError.Future)
    val duration =
        form.availableDurationMinutes.toIntOrNull() ?: return showFormError(FormError.Duration)
    if (duration !in 10..240) return showFormError(FormError.Duration)
    val intent =
        CalendarAiIntent(
            startsAtMillis = startsAtMillis,
            timeZoneId = deviceZone.id,
            gymIds = form.gymIds.sorted(),
            excludedExerciseIds = form.excludedExerciseIds.sorted(),
            excludedEquipmentIds = form.excludedEquipmentIds.sorted(),
            priorityMuscles = form.priorityMuscles.sorted(),
            includeNotes = form.includeNotes,
            availableDurationMinutes = duration,
            currentState = form.currentState.trim().ifEmpty { null },
            preferences = form.preferences.trim().ifEmpty { null },
        )
    return intent.takeIf { it.valid(clock.nowMillis()) } ?: showFormError(FormError.Parameters)
  }

  private fun showFormError(error: FormError): Nothing? {
    mutableState.update { it.copy(error = error.message) }
    return null
  }

  private fun updateForm(change: CalendarAiForm.() -> CalendarAiForm) {
    updateForm(userEdited = true, change)
  }

  private fun updateDateTime(change: CalendarAiForm.() -> CalendarAiForm) {
    updateForm {
      val updated = change().copy(timeZoneId = deviceZone.id)
      val unchangedInstant =
          preservedInstantMillis?.takeIf { instant ->
            val displayed = displayPlanningDateTime(instant, deviceZone)
            updated.date == displayed.date && updated.time == displayed.time
          }
      when (
          val resolved =
              resolvePlanningDateTime(
                  updated.date,
                  updated.time,
                  deviceZone,
                  unchangedInstant,
              )
      ) {
        is PlanningDateTimeResolution.Resolved ->
            updated.copy(preservedInstantMillis = resolved.instantMillis)
        else -> updated.copy(preservedInstantMillis = null)
      }
    }
  }

  private fun replaceForm(form: CalendarAiForm) = updateForm(userEdited = false) { form }

  private fun updateForm(
      userEdited: Boolean,
      change: CalendarAiForm.() -> CalendarAiForm,
  ) {
    if (userEdited) formEdited = true
    val updated = mutableState.value.form.change()
    mutableState.update { state -> state.copy(form = updated, error = null) }
    saveForm(updated)
  }

  private fun setError(token: Long, message: String) {
    if (token == generation) mutableState.update { it.copy(error = message) }
  }

  private val deviceZone: ZoneId
    get() = ZoneId.systemDefault()

  private fun defaultForm(): CalendarAiForm {
    val zone = deviceZone
    val parts = tomorrowAtSix(clock.nowMillis(), zone)
    val instant =
        (resolvePlanningDateTime(parts.date, parts.time, zone)
                as? PlanningDateTimeResolution.Resolved)
            ?.instantMillis
    return CalendarAiForm(
        date = parts.date,
        time = parts.time,
        timeZoneId = zone.id,
        preservedInstantMillis = instant,
    )
  }

  private fun formFromIntent(intent: CalendarAiIntent): CalendarAiForm {
    val displayed = displayPlanningDateTime(intent.startsAtMillis, deviceZone)
    return CalendarAiForm(
        date = displayed.date,
        time = displayed.time,
        timeZoneId = deviceZone.id,
        preservedInstantMillis = intent.startsAtMillis,
        gymIds = intent.gymIds.toSet(),
        excludedExerciseIds = intent.excludedExerciseIds.toSet(),
        excludedEquipmentIds = intent.excludedEquipmentIds.toSet(),
        priorityMuscles = intent.priorityMuscles.toSet(),
        includeNotes = intent.includeNotes,
        availableDurationMinutes = intent.availableDurationMinutes.toString(),
        currentState = intent.currentState.orEmpty(),
        preferences = intent.preferences.orEmpty(),
    )
  }

  /**
   * Submission can race a zone broadcast before Compose runs [synchronizeDeviceTimeZone]. The
   * instant remains authoritative until an explicit date or time edit resolves a replacement.
   */
  private fun CalendarAiForm.inCurrentDeviceZone(): CalendarAiForm {
    if (timeZoneId == deviceZone.id) return this
    val instant = preservedInstantMillis ?: return copy(timeZoneId = deviceZone.id)
    val displayed = displayPlanningDateTime(instant, deviceZone)
    return copy(date = displayed.date, time = displayed.time, timeZoneId = deviceZone.id)
  }

  private fun restoreSavedForm(): CalendarAiForm? {
    val owner = savedStateHandle.get<String>(SAVED_OWNER) ?: return null
    val epoch = savedStateHandle.get<Long>(SAVED_EPOCH) ?: return null
    val savedProcessToken = savedStateHandle.get<String>(SAVED_PROCESS_TOKEN)
    // BackendTokenStore's epoch is in-memory. Only compare it inside the same process; a restored
    // form from another process still requires its owner, while new actions take a fresh epoch.
    if (
        owner != sessions.session.value?.userId ||
            (savedProcessToken == processToken && epoch != sessions.sessionEpoch)
    ) {
      clearSavedForm()
      return null
    }
    val date = savedStateHandle.get<String>(SAVED_DATE) ?: return null
    val time = savedStateHandle.get<String>(SAVED_TIME) ?: return null
    val restored =
        CalendarAiForm(
            date = date,
            time = time,
            timeZoneId = deviceZone.id,
            preservedInstantMillis = savedStateHandle.get<Long>(SAVED_INSTANT),
            gymIds = savedStateHandle.stringSet(SAVED_GYMS),
            excludedExerciseIds = savedStateHandle.stringSet(SAVED_EXERCISES),
            excludedEquipmentIds = savedStateHandle.stringSet(SAVED_EQUIPMENT),
            priorityMuscles = savedStateHandle.stringSet(SAVED_MUSCLES),
            includeNotes = savedStateHandle.get<Boolean>(SAVED_NOTES) ?: true,
            availableDurationMinutes = savedStateHandle.get<String>(SAVED_DURATION) ?: "60",
            currentState = savedStateHandle.get<String>(SAVED_CURRENT_STATE).orEmpty(),
            preferences = savedStateHandle.get<String>(SAVED_PREFERENCES).orEmpty(),
        )
    return restored.preservedInstantMillis?.let { instant ->
      val displayed = displayPlanningDateTime(instant, deviceZone)
      restored.copy(date = displayed.date, time = displayed.time)
    } ?: restored
  }

  private fun saveForm(form: CalendarAiForm) {
    val owner = sessions.session.value?.userId ?: return clearSavedForm()
    savedStateHandle[SAVED_OWNER] = owner
    savedStateHandle[SAVED_EPOCH] = sessions.sessionEpoch
    savedStateHandle[SAVED_PROCESS_TOKEN] = processToken
    savedStateHandle[SAVED_DATE] = form.date
    savedStateHandle[SAVED_TIME] = form.time
    form.preservedInstantMillis?.let { savedStateHandle[SAVED_INSTANT] = it }
        ?: savedStateHandle.remove<Long>(SAVED_INSTANT)
    savedStateHandle[SAVED_GYMS] = ArrayList(form.gymIds.sorted())
    savedStateHandle[SAVED_EXERCISES] = ArrayList(form.excludedExerciseIds.sorted())
    savedStateHandle[SAVED_EQUIPMENT] = ArrayList(form.excludedEquipmentIds.sorted())
    savedStateHandle[SAVED_MUSCLES] = ArrayList(form.priorityMuscles.sorted())
    savedStateHandle[SAVED_NOTES] = form.includeNotes
    savedStateHandle[SAVED_DURATION] = form.availableDurationMinutes
    savedStateHandle[SAVED_CURRENT_STATE] = form.currentState
    savedStateHandle[SAVED_PREFERENCES] = form.preferences
  }

  private fun SavedStateHandle.stringSet(key: String): Set<String> =
      get<ArrayList<String>>(key)?.toSet().orEmpty()

  private fun clearSavedForm() {
    savedKeys.forEach { savedStateHandle.remove<Any>(it) }
  }

  private enum class FormError(val message: String) {
    DateTime("Проверьте дату и время."),
    Future("Выберите будущие дату и время."),
    Gap("Это время недоступно из-за перевода часов. Выберите другое время."),
    Duration("Укажите длительность от 10 до 240 минут."),
    Parameters("Проверьте параметры предложения."),
  }

  private companion object {
    val processToken = UUID.randomUUID().toString()
    const val SAVED_OWNER = "calendar_ai_form_owner"
    const val SAVED_EPOCH = "calendar_ai_form_epoch"
    const val SAVED_PROCESS_TOKEN = "calendar_ai_form_process_token"
    const val SAVED_DATE = "calendar_ai_form_date"
    const val SAVED_TIME = "calendar_ai_form_time"
    const val SAVED_INSTANT = "calendar_ai_form_instant"
    const val SAVED_GYMS = "calendar_ai_form_gyms"
    const val SAVED_EXERCISES = "calendar_ai_form_exercises"
    const val SAVED_EQUIPMENT = "calendar_ai_form_equipment"
    const val SAVED_MUSCLES = "calendar_ai_form_muscles"
    const val SAVED_NOTES = "calendar_ai_form_notes"
    const val SAVED_DURATION = "calendar_ai_form_duration"
    const val SAVED_CURRENT_STATE = "calendar_ai_form_current_state"
    const val SAVED_PREFERENCES = "calendar_ai_form_preferences"
    val savedKeys =
        listOf(
            SAVED_OWNER,
            SAVED_EPOCH,
            SAVED_PROCESS_TOKEN,
            SAVED_DATE,
            SAVED_TIME,
            SAVED_INSTANT,
            SAVED_GYMS,
            SAVED_EXERCISES,
            SAVED_EQUIPMENT,
            SAVED_MUSCLES,
            SAVED_NOTES,
            SAVED_DURATION,
            SAVED_CURRENT_STATE,
            SAVED_PREFERENCES,
        )
  }
}

private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value
