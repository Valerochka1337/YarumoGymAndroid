package com.valerochka1337.valerochkagym.ui.calendar

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.ui.components.*
import java.time.*

@Composable
internal fun ManualPlanningForm(
    onClose: () -> Unit,
    viewModel: CalendarViewModel = hiltViewModel(),
) {
  val routines by viewModel.routines.collectAsStateWithLifecycle()
  val status by viewModel.calendarStatus.collectAsStateWithLifecycle()
  var routineId by rememberSaveable { mutableStateOf<String?>(null) }
  var date by rememberSaveable { mutableStateOf(LocalDate.now().plusDays(1).toString()) }
  var time by rememberSaveable { mutableStateOf("18:00") }
  val busy by viewModel.isPlanning.collectAsStateWithLifecycle()
  var completedPlan by rememberSaveable { mutableStateOf<String?>(null) }
  var pendingPlan by rememberSaveable { mutableStateOf<String?>(null) }
  val formKey = "$routineId|$date|$time"
  val scheduled = completedPlan == formKey
  var message by rememberSaveable { mutableStateOf<String?>(null) }
  val zone = ZoneId.systemDefault().id
  LaunchedEffect(viewModel) {
    viewModel.events.collect {
      message = it
      if (it == "Запланировано") completedPlan = pendingPlan
    }
  }
  GymCard(Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Тренировка из программы", style = MaterialTheme.typography.titleMedium)
      PlanningChoiceField(
          "Программа",
          routines.map { it.id.toString() to it.name },
          setOfNotNull(routineId),
          { routineId = it },
          "Выберите программу",
          singleChoice = true,
      )
      PlanningDateTimeFields(
          date,
          time,
          zone,
          { date = it },
          { time = it },
          {},
          allowZoneSelection = false,
      )
      val instant =
          runCatching {
                LocalDate.parse(date)
                    .atTime(LocalTime.parse(time))
                    .atZone(ZoneId.of(zone))
                    .toInstant()
                    .toEpochMilli()
              }
              .getOrNull()
      if (message != null) Text(message!!, style = MaterialTheme.typography.bodyMedium)
      if (!status.editingEnabled)
          Text(
              "Дождитесь подготовки календаря. Если она не завершается, проверьте синхронизацию в настройках."
          )
      PillButton(
          if (busy) "Добавляем…"
          else if (scheduled) "Добавлено в календарь" else "Добавить в календарь",
          {
            message = null
            pendingPlan = formKey
            viewModel.schedule(routineId!!.toLong(), instant!!)
          },
          enabled =
              !busy &&
                  !scheduled &&
                  status.editingEnabled &&
                  routines.any { it.id.toString() == routineId } &&
                  instant != null &&
                  instant > System.currentTimeMillis(),
          modifier = Modifier.fillMaxWidth(),
      )
      TextButton(onClick = onClose, enabled = !busy) { Text("Закрыть") }
    }
  }
}
