package com.valerochka1337.valerochkagym.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Refreshes device-zone dependent planning displays after travel or returning to the app. */
@Composable
internal fun rememberDeviceTimeZone(): ZoneId {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  var zone by remember { mutableStateOf(ZoneId.systemDefault()) }

  DisposableEffect(context, lifecycleOwner) {
    fun refresh() {
      zone = ZoneId.systemDefault()
    }
    val receiver =
        object : BroadcastReceiver() {
          override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) refresh()
          }
        }
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) refresh()
    }
    context.registerReceiver(
        receiver,
        IntentFilter(Intent.ACTION_TIMEZONE_CHANGED),
        Context.RECEIVER_NOT_EXPORTED,
    )
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      context.unregisterReceiver(receiver)
    }
  }
  return zone
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun PlanningDateTimeFields(
    date: String,
    time: String,
    zone: String,
    onDate: (String) -> Unit,
    onTime: (String) -> Unit,
    onZone: (String) -> Unit,
    allowZoneSelection: Boolean = true,
) {
  var dateOpen by rememberSaveable { mutableStateOf(false) }
  var timeOpen by rememberSaveable { mutableStateOf(false) }
  val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull()
  val parsedTime = runCatching { LocalTime.parse(time) }.getOrNull()
  Column(
      verticalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.padding(top = 12.dp),
  ) {
    PlanningPickerField(
        "Дата",
        parsedDate?.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru")))
            ?: "Выбрать дату",
        Icons.Rounded.CalendarMonth,
        { dateOpen = true },
    )
    PlanningPickerField(
        "Время начала",
        parsedTime?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "Выбрать",
        Icons.Rounded.Schedule,
        { timeOpen = true },
    )
  }
  if (dateOpen) {
    val picker =
        rememberDatePickerState(
            initialSelectedDateMillis =
                parsedDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        )
    DatePickerDialog(
        onDismissRequest = { dateOpen = false },
        confirmButton = {
          TextButton(
              enabled = picker.selectedDateMillis != null,
              onClick = {
                picker.selectedDateMillis?.let {
                  onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString())
                }
                dateOpen = false
              },
          ) {
            Text("Готово")
          }
        },
        dismissButton = { TextButton(onClick = { dateOpen = false }) { Text("Отмена") } },
    ) {
      DatePicker(picker, modifier = Modifier.verticalScroll(rememberScrollState()))
    }
  }
  if (timeOpen) {
    val picker =
        rememberTimePickerState(
            initialHour = parsedTime?.hour ?: 18,
            initialMinute = parsedTime?.minute ?: 0,
            is24Hour = true,
        )
    AlertDialog(
        onDismissRequest = { timeOpen = false },
        title = { Text("Время начала") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { TimeInput(picker) } },
        confirmButton = {
          TextButton(
              onClick = {
                onTime(
                    LocalTime.of(picker.hour, picker.minute)
                        .format(DateTimeFormatter.ofPattern("HH:mm"))
                )
                timeOpen = false
              }
          ) {
            Text("Готово")
          }
        },
        dismissButton = { TextButton(onClick = { timeOpen = false }) { Text("Отмена") } },
    )
  }
}

@Composable
private fun PlanningPickerField(
    label: String,
    value: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
  val haptics = gymHaptics()
  Surface(
      onClick = {
        haptics.tap()
        onClick()
      },
      enabled = enabled,
      shape = MaterialTheme.shapes.medium,
      color = MaterialTheme.colorScheme.surfaceContainerHighest,
      modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
        modifier = Modifier.heightIn(min = 64.dp).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
      Column(Modifier.weight(1f)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
      }
      if (enabled)
          Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}
