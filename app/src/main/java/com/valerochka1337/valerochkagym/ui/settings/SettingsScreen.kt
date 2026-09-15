package com.valerochka1337.valerochkagym.ui.settings

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.data.settings.GymSettings
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.theme.PaletteMode
import com.valerochka1337.valerochkagym.ui.theme.ThemeMode
import com.valerochka1337.valerochkagym.ui.update.AppUpdateRetry
import com.valerochka1337.valerochkagym.ui.update.AppUpdateStatus
import com.valerochka1337.valerochkagym.ui.update.AppUpdateUiState
import com.valerochka1337.valerochkagym.ui.update.formatUpdateBytes

internal enum class SettingsCategory(
    val label: String,
    val supportingText: String,
) {
  ACCOUNT("Аккаунт", "Email, устройства и выход из аккаунта"),
  WORKOUT("Тренировка", "Отдых, пульс, звук и уведомления"),
  APPEARANCE("Вид и отклик", "Тема, палитра и виброотклик"),
  CONNECTIONS("Подключения", "Календарь и распознавание InBody"),
  DATA_APP("О приложении", "Обновления и версия"),
}

/** Шаг степпера отдыха по умолчанию (секунды) — совпадает с шагом внутри [SettingsViewModel]. */
private const val REST_STEP_SECONDS = 15
private const val HEART_RATE_REST_THRESHOLD_STEP_BPM = 5
private const val HEART_RATE_REST_HOLD_STEP_SECONDS = 5

/**
 * Настройки аккаунта, тренировок, внешнего вида и подключений. Вход в Calendar и запрос доступа
 * требуют Activity (берём из [LocalActivity]); согласие на OAuth-доступ запускается через launcher,
 * а результат возвращается во ViewModel для повторной авторизации.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenGyms: () -> Unit,
    onOpenProfile: () -> Unit = {},
    appUpdateState: AppUpdateUiState,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onRetryUpdate: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val activity = LocalActivity.current
  val snackbarHostState = remember { SnackbarHostState() }
  var selectedCategory by rememberSaveable { mutableStateOf<SettingsCategory?>(null) }
  var consentNonce by rememberSaveable { mutableStateOf<String?>(null) }

  LaunchedEffect(viewModel) { viewModel.messages.collect { snackbarHostState.showSnackbar(it) } }

  val consentLauncher =
      rememberLauncherForActivityResult(
          contract = ActivityResultContracts.StartIntentSenderForResult(),
      ) { result ->
        // Повторяем авторизацию только если пользователь дал согласие; отмена — без повтора,
        // иначе получился бы бесконечный цикл запросов согласия.
        val nonce = consentNonce ?: return@rememberLauncherForActivityResult
        activity?.let {
          viewModel.consentResolved(it, nonce, result.resultCode == Activity.RESULT_OK)
        }
        consentNonce = null
      }

  LaunchedEffect(viewModel) {
    viewModel.consentRequests.collect { request ->
      consentNonce = request.operationNonce
      consentLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }
  }
  LaunchedEffect(viewModel, activity) { activity?.let(viewModel::resumePendingCalendarOperation) }

  GlowBackground(modifier = modifier) {
    Box(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.fillMaxSize()) {
        SettingsHeader(
            title = selectedCategory?.label ?: "Настройки",
            onBack = { if (selectedCategory == null) onBack() else selectedCategory = null },
        )
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          val settings = state.settings
          if (settings != null) {
            when (selectedCategory) {
              null -> {
                SettingsCategoryList(
                    onSelect = { selectedCategory = it },
                    onOpenGyms = onOpenGyms,
                    onOpenProfile = onOpenProfile,
                )
              }

              SettingsCategory.WORKOUT ->
                  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    RestTimerCard(
                        settings = settings,
                        onChangeRest = viewModel::changeDefaultRest,
                        onToggleAutostart = viewModel::toggleRestAutostart,
                        onToggleHeartRateRest = viewModel::toggleHeartRateRest,
                        onChangeHeartRateRestThreshold = viewModel::changeHeartRateRestThreshold,
                        onChangeHeartRateRestHoldSeconds =
                            viewModel::changeHeartRateRestHoldSeconds,
                        onToggleSound = viewModel::toggleSound,
                        onToggleVibration = viewModel::toggleVibration,
                    )
                    CoachModelCard(
                        state = state.coachModel,
                        onSelect = viewModel::selectCoachModel,
                        onVerify = viewModel::verifyCoachModel,
                        onRefresh = viewModel::refreshCoachModels,
                    )
                  }

              SettingsCategory.ACCOUNT -> com.valerochka1337.valerochkagym.ui.account.AccountCard()

              SettingsCategory.CONNECTIONS -> {
                GoogleAccountCard(
                    connectedEmail = settings.connectedCalendarEmail,
                    authBusy = state.authBusy,
                    authError = state.authError,
                    onConnect = { activity?.let(viewModel::connectCalendar) },
                    onDisconnect = viewModel::disconnectCalendar,
                )
              }

              SettingsCategory.APPEARANCE ->
                  AppearanceCard(
                      settings = settings,
                      onThemeModeChange = viewModel::setThemeMode,
                      onPaletteModeChange = viewModel::setPaletteMode,
                      onToggleHaptics = viewModel::toggleHaptics,
                  )

              SettingsCategory.DATA_APP -> {
                AppUpdateCard(
                    state = appUpdateState,
                    onCheck = onCheckUpdate,
                    onDownload = onDownloadUpdate,
                    onInstall = onInstallUpdate,
                    onRetry = onRetryUpdate,
                )
              }
            }
          }
        }
      }
      SnackbarHost(
          hostState = snackbarHostState,
          modifier = Modifier.align(Alignment.BottomCenter),
      )
    }
  }
}

@Composable
private fun GymsSettingsCard(onOpen: () -> Unit) {
  SettingsNavigationCard(
      label = "Тренажёрные залы",
      supportingText = "Упражнения, доступные в каждом зале",
      icon = Icons.Rounded.FitnessCenter,
      onClick = onOpen,
  )
}

@Composable
private fun ProfileSettingsCard(onOpen: () -> Unit) {
  SettingsNavigationCard(
      label = "Профиль",
      supportingText = "Цели, опыт и оборудование",
      icon = Icons.Rounded.AccountCircle,
      onClick = onOpen,
  )
}

@Composable
internal fun CoachModelCard(
    state: CoachModelUiState,
    onSelect: (String?) -> Unit,
    onVerify: () -> Unit,
    onRefresh: () -> Unit,
) {
  val haptics = gymHaptics()
  SectionCard(title = "Live Coach", icon = Icons.Rounded.PlayCircle) {
    when {
      state.loading -> {
        Text("Получаем доступные модели…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      }
      state.available != true -> {
        Text(
            state.status ?: "Серверный тренер пока не настроен.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = {
              haptics.tap()
              onRefresh()
            }
        ) {
          Text("Повторить")
        }
      }
      else -> {
        Text(
            "Модель обрабатывается на сервере. Чат и журнал тренировки синхронизируются между вашими устройствами.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        val active = state.selectedModel ?: state.defaultModel
        Text(
            "Текущая модель: ${active ?: "сервер выберет автоматически"}",
            style = MaterialTheme.typography.titleSmall,
        )
        TextButton(
            onClick = {
              haptics.tap()
              onSelect(null)
            },
            enabled = state.selectedModel != null && !state.checking && !state.savingSelection,
        ) {
          Text("Использовать модель сервера")
        }
        state.models.forEach { model ->
          FilterChip(
              selected = state.selectedModel == model,
              onClick = {
                haptics.tap()
                onSelect(model)
              },
              enabled = !state.checking && !state.savingSelection,
              label = { Text(model, maxLines = 1) },
              modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
          )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
              haptics.tap()
              onVerify()
            },
            enabled = !state.checking && !state.savingSelection,
            modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
              when {
                state.checking -> "Проверяем модель…"
                state.savingSelection -> "Сохраняем модель…"
                else -> "Проверить модель тренера"
              },
          )
        }
        state.status?.let {
          Spacer(Modifier.height(8.dp))
          Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }
  }
}

@Composable
private fun SettingsHeader(title: String, onBack: () -> Unit) {
  Row(
      modifier =
          Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onClick = onBack) {
      Icon(
          Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = "Назад",
          tint = MaterialTheme.colorScheme.onBackground,
      )
    }
    Spacer(Modifier.width(4.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
    )
  }
}

@Composable
internal fun SettingsCategoryList(
    onSelect: (SettingsCategory) -> Unit,
    onOpenGyms: () -> Unit,
    onOpenProfile: () -> Unit,
) {
  SettingsCategory.entries.forEach { category ->
    SettingsNavigationCard(
        label = category.label,
        supportingText = category.supportingText,
        icon =
            when (category) {
              SettingsCategory.WORKOUT -> Icons.Rounded.Timer
              SettingsCategory.ACCOUNT -> Icons.Rounded.AccountCircle
              SettingsCategory.CONNECTIONS -> Icons.Rounded.Link
              SettingsCategory.APPEARANCE -> Icons.Rounded.Palette
              SettingsCategory.DATA_APP -> Icons.Rounded.SystemUpdate
            },
        onClick = { onSelect(category) },
    )
    if (category == SettingsCategory.WORKOUT) {
      GymsSettingsCard(onOpen = onOpenGyms)
    }
    if (category == SettingsCategory.ACCOUNT) ProfileSettingsCard(onOpen = onOpenProfile)
  }
}

@Composable
private fun SettingsNavigationCard(
    label: String,
    supportingText: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
  val haptics = gymHaptics()
  GymCard(
      modifier = Modifier.fillMaxWidth(),
      onClick = {
        haptics.tap()
        onClick()
      },
      contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
          imageVector = icon,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
      )
      Spacer(Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = supportingText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Icon(
          imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
internal fun GoogleAccountCard(
    connectedEmail: String?,
    authBusy: Boolean,
    authError: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
  val haptics = gymHaptics()
  SectionCard(title = "Google Calendar", icon = Icons.Rounded.AccountCircle) {
    if (connectedEmail == null) {
      Text(
          text =
              "Выберите Google-аккаунт для календаря. Он может отличаться от аккаунта входа в приложение.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(12.dp))
      PillButton(
          text = "Подключить календарь",
          onClick = {
            haptics.tap()
            onConnect()
          },
          enabled = !authBusy,
          modifier =
              Modifier.fillMaxWidth().semantics {
                contentDescription = "Подключить Google Calendar"
                stateDescription = "Календарь не подключён"
              },
      )
    } else {
      Text("Подключённый аккаунт", style = MaterialTheme.typography.labelMedium)
      Text(
          text = connectedEmail,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
      )
      Spacer(Modifier.height(4.dp))
      TextButton(
          onClick = {
            haptics.tap()
            onDisconnect()
          },
          enabled = !authBusy,
          modifier =
              Modifier.semantics {
                contentDescription = "Отключить Google Calendar"
                stateDescription = "Подключён к $connectedEmail"
              },
      ) {
        Text("Отключить календарь")
      }
    }
    if (connectedEmail != null) {
      Spacer(Modifier.height(4.dp))
      TextButton(
          onClick = {
            haptics.tap()
            onConnect()
          },
          enabled = !authBusy,
          modifier =
              Modifier.semantics {
                contentDescription = "Подключить другой Google-аккаунт"
                stateDescription = "Подключён к $connectedEmail"
              },
      ) {
        Text("Сменить аккаунт")
      }
    }
    if (authError != null) {
      Spacer(Modifier.height(8.dp))
      Text(
          text = authError,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
      )
    }
  }
}

@Composable
private fun SpreadsheetCard(
    currentId: String?,
    error: Boolean,
    onSave: (String) -> Unit,
    onExportAll: () -> Unit,
) {
  SectionCard(title = "Google Sheets", icon = Icons.Rounded.TableChart) {
    var input by rememberSaveable(currentId) { mutableStateOf(currentId.orEmpty()) }
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Ссылка или ID таблицы") },
        leadingIcon = {
          Icon(
              imageVector = Icons.Rounded.Link,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        },
        isError = error,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSave(input) }),
        supportingText = {
          Text(
              if (error) {
                "Не похоже на ссылку или ID"
              } else {
                "Вставьте ссылку на таблицу или её ID из адресной строки"
              },
          )
        },
    )
    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      OutlinedButton(onClick = { onSave(input) }) { Text("Сохранить") }
      OutlinedButton(onClick = onExportAll, enabled = currentId != null) {
        Icon(
            imageVector = Icons.Rounded.CloudUpload,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text("Выгрузить всё")
      }
    }
  }
}

@Composable
private fun RestTimerCard(
    settings: GymSettings,
    onChangeRest: (Int) -> Unit,
    onToggleAutostart: (Boolean) -> Unit,
    onToggleHeartRateRest: (Boolean) -> Unit,
    onChangeHeartRateRestThreshold: (Int) -> Unit,
    onChangeHeartRateRestHoldSeconds: (Int) -> Unit,
    onToggleSound: (Boolean) -> Unit,
    onToggleVibration: (Boolean) -> Unit,
) {
  SectionCard(title = "Таймер отдыха", icon = Icons.Rounded.Timer) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
          text = "Отдых по умолчанию",
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.weight(1f),
      )
      Row(verticalAlignment = Alignment.CenterVertically) {
        StepperButton(symbol = "−", description = "убавить отдых") {
          onChangeRest(-REST_STEP_SECONDS)
        }
        Text(
            text = formatRest(settings.defaultRestSeconds),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(116.dp),
        )
        StepperButton(symbol = "+", description = "прибавить отдых") {
          onChangeRest(REST_STEP_SECONDS)
        }
      }
    }
    Spacer(Modifier.height(8.dp))
    ToggleRow(
        label = "Автостарт после подхода",
        icon = Icons.Rounded.PlayCircle,
        checked = settings.restAutostart,
        onCheckedChange = onToggleAutostart,
    )
    ToggleRow(
        label = "Отдых по пульсу",
        icon = Icons.Rounded.Favorite,
        checked = settings.heartRateRestEnabled,
        onCheckedChange = onToggleHeartRateRest,
    )
    if (settings.heartRateRestEnabled) {
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
            text = "Завершать при пульсе",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
          StepperButton(symbol = "−", description = "уменьшить порог пульса") {
            onChangeHeartRateRestThreshold(-HEART_RATE_REST_THRESHOLD_STEP_BPM)
          }
          Text(
              text = "≤ ${settings.heartRateRestThresholdBpm} BPM",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface,
              textAlign = TextAlign.Center,
              maxLines = 1,
              softWrap = false,
              modifier = Modifier.width(116.dp),
          )
          StepperButton(symbol = "+", description = "увеличить порог пульса") {
            onChangeHeartRateRestThreshold(HEART_RATE_REST_THRESHOLD_STEP_BPM)
          }
        }
      }
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
            text = "Удерживать ниже",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
          StepperButton(symbol = "−", description = "уменьшить время удержания") {
            onChangeHeartRateRestHoldSeconds(-HEART_RATE_REST_HOLD_STEP_SECONDS)
          }
          Text(
              text = "${settings.heartRateRestHoldSeconds} с",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface,
              textAlign = TextAlign.Center,
              maxLines = 1,
              softWrap = false,
              modifier = Modifier.width(116.dp),
          )
          StepperButton(symbol = "+", description = "увеличить время удержания") {
            onChangeHeartRateRestHoldSeconds(HEART_RATE_REST_HOLD_STEP_SECONDS)
          }
        }
      }
    }
    // Подписи уточняют, что звук и вибрация — про уведомление окончания отдыха,
    // а не про весь интерфейс (общий виброотклик живёт в карточке «Интерфейс»).
    ToggleRow(
        label = "Звук по окончании",
        icon = Icons.AutoMirrored.Rounded.VolumeUp,
        checked = settings.soundEnabled,
        onCheckedChange = onToggleSound,
    )
    ToggleRow(
        label = "Вибрация уведомления",
        icon = Icons.Rounded.Vibration,
        checked = settings.vibrationEnabled,
        onCheckedChange = onToggleVibration,
    )
  }
}

@Composable
private fun AppearanceCard(
    settings: GymSettings,
    onThemeModeChange: (ThemeMode) -> Unit,
    onPaletteModeChange: (PaletteMode) -> Unit,
    onToggleHaptics: (Boolean) -> Unit,
) {
  SectionCard(title = "Вид и отклик", icon = Icons.Rounded.Palette) {
    Text(
        text = "Тема",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      ThemeMode.entries.forEach { mode ->
        FilterChip(
            selected = settings.themeMode == mode,
            onClick = { onThemeModeChange(mode) },
            label = { Text(mode.label) },
            leadingIcon =
                if (settings.themeMode == mode) {
                  {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                  }
                } else {
                  null
                },
        )
      }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        text = "Палитра",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      PaletteMode.entries.forEach { mode ->
        FilterChip(
            selected = settings.paletteMode == mode,
            onClick = { onPaletteModeChange(mode) },
            label = { Text(mode.label) },
            leadingIcon =
                if (settings.paletteMode == mode) {
                  {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                  }
                } else {
                  null
                },
        )
      }
    }
    Text(
        text =
            if (settings.paletteMode == PaletteMode.SYSTEM) {
              "Системная палитра следует цветам обоев Material You."
            } else {
              "Фирменная палитра работает в светлом и тёмном режиме."
            },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    ToggleRow(
        label = "Виброотклик",
        icon = Icons.Rounded.Vibration,
        checked = settings.hapticsEnabled,
        onCheckedChange = onToggleHaptics,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "Лёгкая отдача на нажатия: выполнение подхода, шаги веса, выбор вкладок.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun AppUpdateCard(
    state: AppUpdateUiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
) {
  val haptics = gymHaptics()
  val status = state.status

  SectionCard(title = "Приложение", icon = Icons.Rounded.SystemUpdate) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
          text = "Yarumo coach",
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
          text = "v${state.installedVersionName}",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          softWrap = false,
      )
    }
    Spacer(Modifier.height(8.dp))

    val statusText =
        when (status) {
          AppUpdateStatus.Idle -> "Проверка обновлений доступна вручную"
          AppUpdateStatus.Checking -> "Проверяем наличие обновлений…"
          AppUpdateStatus.UpToDate -> "Установлена последняя версия"
          is AppUpdateStatus.Available ->
              "Доступна v${status.release.versionName} · ${formatUpdateBytes(status.release.apk.sizeBytes)}"

          is AppUpdateStatus.Downloading -> {
            val percent =
                if (status.totalBytes > 0L) {
                  (status.downloadedBytes * 100 / status.totalBytes).coerceIn(0, 100)
                } else {
                  0
                }
            "Скачиваем v${status.release.versionName} · $percent%"
          }

          is AppUpdateStatus.ReadyToInstall -> "v${status.release.versionName} скачана и проверена"
          is AppUpdateStatus.Failed -> status.message
        }
    Text(
        text = statusText,
        style = MaterialTheme.typography.bodyMedium,
        color =
            if (status is AppUpdateStatus.Failed) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
    )

    if (status is AppUpdateStatus.Downloading) {
      Spacer(Modifier.height(12.dp))
      if (status.totalBytes > 0L) {
        LinearProgressIndicator(
            progress = { (status.downloadedBytes.toFloat() / status.totalBytes).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
      } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      }
    }

    Spacer(Modifier.height(12.dp))
    when (status) {
      AppUpdateStatus.Idle,
      AppUpdateStatus.UpToDate ->
          OutlinedButton(
              onClick = {
                haptics.tap()
                onCheck()
              }
          ) {
            Text("Проверить обновление")
          }

      AppUpdateStatus.Checking ->
          OutlinedButton(onClick = {}, enabled = false) { Text("Проверяем…") }

      is AppUpdateStatus.Available ->
          PillButton(
              text = "Обновить до v${status.release.versionName}",
              onClick = {
                haptics.tap()
                onDownload()
              },
              modifier = Modifier.fillMaxWidth(),
              leadingIcon = Icons.Rounded.Download,
          )

      is AppUpdateStatus.Downloading -> Unit
      is AppUpdateStatus.ReadyToInstall ->
          PillButton(
              text = "Установить v${status.release.versionName}",
              onClick = {
                haptics.tap()
                onInstall()
              },
              modifier = Modifier.fillMaxWidth(),
              leadingIcon = Icons.Rounded.SystemUpdate,
          )

      is AppUpdateStatus.Failed -> {
        val label =
            when (status.retry) {
              AppUpdateRetry.CHECK -> "Проверить ещё раз"
              AppUpdateRetry.DOWNLOAD -> "Повторить скачивание"
              AppUpdateRetry.INSTALL -> "Повторить установку"
            }
        if (status.retry == AppUpdateRetry.CHECK) {
          OutlinedButton(
              onClick = {
                haptics.tap()
                onRetry()
              }
          ) {
            Text(label)
          }
        } else {
          PillButton(
              text = label,
              onClick = {
                haptics.tap()
                onRetry()
              },
              modifier = Modifier.fillMaxWidth(),
              leadingIcon =
                  if (status.retry == AppUpdateRetry.DOWNLOAD) {
                    Icons.Rounded.Download
                  } else {
                    Icons.Rounded.SystemUpdate
                  },
          )
        }
      }
    }
  }
}

@Composable
private fun ToggleRow(
    label: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
  Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
          imageVector = icon,
          contentDescription = null,
          tint =
              if (checked) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              },
          modifier = Modifier.size(20.dp),
      )
      Spacer(Modifier.width(8.dp))
      Text(
          text = label,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurface,
      )
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}

@Composable
private fun StepperButton(
    symbol: String,
    description: String,
    onClick: () -> Unit,
) {
  val haptics = gymHaptics()
  IconButton(
      onClick = {
        haptics.step()
        onClick()
      },
      modifier = Modifier.size(48.dp).semantics { contentDescription = description },
  ) {
    Text(
        text = symbol,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
    )
  }
}

@Composable
private fun SectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
  GymCard(modifier = Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
          imageVector = icon,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(20.dp),
      )
      Spacer(Modifier.width(8.dp))
      Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
      )
    }
    Spacer(Modifier.height(12.dp))
    content()
  }
}

/** Отдых в формате «2 мин 00 сек». */
private fun formatRest(seconds: Int): String {
  val minutes = seconds / 60
  val secs = seconds % 60
  return "%d мин %02d сек".format(minutes, secs)
}
