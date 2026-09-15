package com.valerochka1337.valerochkagym.ui.routineshare

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.ui.account.AccountGate
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.navigation.GymWindowWidthClass
import com.valerochka1337.valerochkagym.ui.routine.RoutineDetailContent

@Composable
fun RoutineShareOwnerScreen(
    onBack: () -> Unit,
    onOpenPreview: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RoutineShareViewModel = hiltViewModel(),
) {
  val state by viewModel.ownerState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current
  val haptics = gymHaptics()
  var pendingRevoke by rememberSaveable { mutableStateOf<String?>(null) }
  LaunchedEffect(Unit) {
    viewModel.effects.collect { effect ->
      when (effect) {
        is RoutineShareEffect.CopyLink -> clipboard.setText(AnnotatedString(effect.url))
        is RoutineShareEffect.OpenSystemShare ->
            context.startActivity(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, effect.url)
                    .let { Intent.createChooser(it, "Поделиться программой") },
            )
      }
    }
  }
  GlowBackground(modifier) {
    Column(Modifier.fillMaxSize()) {
      ShareTopBar(title = "Поделиться программой", onBack = onBack)
      when {
        state.loading -> Loading()
        state.routine == null -> Message(state.error ?: "Программа недоступна", "Назад", onBack)
        else -> {
          val routine = requireNotNull(state.routine)
          state.error?.let { error ->
            Text(
                error,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
          }
          LazyColumn(
              modifier = Modifier.weight(1f),
              contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            item {
              GymCard(Modifier.fillMaxWidth()) {
                Text(routine.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    "Любой, у кого есть ссылка, сможет увидеть снимок программы. Правки программы не изменят снимок. Ссылку можно отозвать в любой момент.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                PillButton(
                    text = if (state.busy) "Создаём ссылку" else "Создать ссылку",
                    onClick = { haptics.tap(); viewModel.create() },
                    enabled = !state.busy && state.links.size < 50,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Создать ссылку на программу" },
                )
                if (state.links.size >= 50)
                    Text(
                        "Достигнут лимит 50 активных ссылок. Отзовите ненужную ссылку.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
              }
            }
            item { Text("Активные ссылки · ${state.links.size}", style = MaterialTheme.typography.titleLarge) }
            items(state.links, key = { it.shareId }) { link ->
              GymCard(Modifier.fillMaxWidth()) {
                Text("Ссылка создана", style = MaterialTheme.typography.titleMedium)
                Text(link.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(
                    onClick = { onOpenPreview(link.url.substringAfterLast('/')) },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Открыть снимок программы" },
                ) { Text("Открыть снимок") }
                TextButton(
                    onClick = { haptics.tap(); viewModel.share(link.url) },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Поделиться ссылкой" },
                ) { Text("Поделиться") }
                TextButton(
                    onClick = { haptics.tap(); viewModel.copy(link.url) },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Скопировать ссылку" },
                ) { Icon(Icons.Rounded.ContentCopy, null); Text("Копировать") }
                TextButton(
                    onClick = { pendingRevoke = link.shareId },
                    enabled = !state.busy,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Отозвать ссылку" },
                ) { Text("Отозвать", color = MaterialTheme.colorScheme.error) }
              }
            }
          }
        }
      }
    }
  }
  pendingRevoke?.let { shareId ->
    AlertDialog(
        onDismissRequest = { if (!state.busy) pendingRevoke = null },
        title = { Text("Отозвать ссылку?") },
        text = { Text("Ссылка перестанет открываться. Уже сохранённые копии программ останутся у получателей.") },
        confirmButton = {
          TextButton(
              onClick = { haptics.tap(); pendingRevoke = null; viewModel.revoke(shareId) },
              enabled = !state.busy,
              modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
          ) { Text("Отозвать", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { pendingRevoke = null }, enabled = !state.busy) { Text("Отмена") } },
    )
  }
}

@Composable
fun RoutineSharePreviewScreen(
    onBack: () -> Unit,
    onOpenImportedRoutine: (Long) -> Unit,
    windowWidthClass: GymWindowWidthClass,
    modifier: Modifier = Modifier,
    viewModel: RoutineShareViewModel = hiltViewModel(),
) {
  val state by viewModel.previewState.collectAsStateWithLifecycle()
  val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = object : DefaultLifecycleObserver {
      override fun onResume(owner: LifecycleOwner) {
        viewModel.refreshPreview()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  LaunchedEffect(state.signInRequested) { viewModel.resumeAfterSignIn() }
  if (state.signInRequested) {
    AccountGate { LaunchedEffect(Unit) { viewModel.resumeAfterSignIn() } }
    return
  }
  GlowBackground(modifier) {
    Column(Modifier.fillMaxSize()) {
      ShareTopBar(title = state.preview?.title ?: "Программа по ссылке", onBack = onBack)
      when {
        state.loading -> Loading()
        state.preview == null -> Message(state.error ?: "Ссылка недоступна", "Повторить", viewModel::refreshPreview)
        else -> {
          val preview = requireNotNull(state.preview)
          RoutineDetailContent(
            routine = preview.toDetailRoutine(),
            onExerciseClick = null,
            windowWidthClass = windowWidthClass,
            showGyms = false,
            originContent = {
              GymCard(Modifier.fillMaxWidth()) {
                Text("Программа по ссылке", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Около ${(preview.estimatedDurationSeconds + 30) / 60} мин",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            },
            actionContent = {
              RoutineShareActions(state, viewModel::import, onOpenImportedRoutine)
            },
          )
        }
      }
    }
  }
}

@Composable
internal fun RoutineShareActions(
    state: RoutineSharePreviewUiState,
    onSave: () -> Unit,
    onOpenImportedRoutine: (Long) -> Unit,
) {
  val haptics = gymHaptics()
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    PillButton(
        text = when {
          state.importing -> "Сохраняем программу"
          state.importedRoutineId != null -> "Открыть мою программу"
          else -> "Сохранить себе"
        },
        onClick = {
          haptics.tap()
          state.importedRoutineId?.let(onOpenImportedRoutine) ?: onSave()
        },
        enabled = !state.importing,
        modifier =
            Modifier.fillMaxWidth().semantics {
              contentDescription =
                  if (state.importedRoutineId == null) "Сохранить программу себе"
                  else "Открыть мою программу"
            },
    )
    Text(
        "После сохранения это будет ваша личная редактируемая программа.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun ShareTopBar(title: String, onBack: () -> Unit) {
  TopAppBar(
      title = { Text(title) },
      navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
      colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
  )
}

@Composable
private fun Loading() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

@Composable
private fun Message(message: String, action: String, onAction: () -> Unit) =
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onAction, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text(action) }
      }
    }
