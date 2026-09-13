package com.valerochka1337.valerochkagym.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics

/** Large catalogs stay in a searchable, lazy sheet; the form only shows a summary. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlanningChoiceField(
    title: String,
    choices: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    emptyText: String = "Ничего не выбрано",
    singleChoice: Boolean = false,
) {
  var open by rememberSaveable { mutableStateOf(false) }
  val haptics = gymHaptics()
  val names = choices.filter { it.first in selected }.map { it.second }
  ListItem(
      headlineContent = { Text(title) },
      supportingContent = {
        Text(
            if (names.isEmpty()) emptyText
            else
                names.take(2).joinToString() +
                    if (names.size > 2) " · ещё ${names.size - 2}" else ""
        )
      },
      trailingContent = { Icon(Icons.Rounded.ChevronRight, null) },
      colors =
          ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
      modifier =
          Modifier.fillMaxWidth()
              .clickable(
                  role = Role.Button,
                  onClick = {
                    haptics.tap()
                    open = true
                  },
              ),
  )
  if (open) {
    PlanningChoiceSheet(title, choices, selected, onToggle, { open = false }, singleChoice)
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlanningChoiceSheet(
    title: String,
    choices: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
    singleChoice: Boolean = false,
) {
  var query by rememberSaveable { mutableStateOf("") }
  var selectedOnly by rememberSaveable { mutableStateOf(false) }
  val haptics = gymHaptics()
  val filtered =
      remember(choices, query, selectedOnly, selected) {
        choices
            .filter { (_, name) -> name.contains(query.trim(), ignoreCase = true) }
            .filter { !selectedOnly || it.first in selected }
      }
  ModalBottomSheet(
      onDismissRequest = onDismiss,
      sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
  ) {
    Column(
        Modifier.fillMaxWidth().fillMaxHeight(0.9f).imePadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(title, style = MaterialTheme.typography.titleLarge)
      OutlinedTextField(
          query,
          { query = it },
          Modifier.fillMaxWidth(),
          singleLine = true,
          label = { Text("Поиск") },
          leadingIcon = { Icon(Icons.Rounded.Search, null) },
      )
      if (!singleChoice) {
        GymFilterChip(
            selected = selectedOnly,
            onClick = { selectedOnly = !selectedOnly },
            label = "Выбрано: ${selected.size}",
        )
      }
      LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
        if (filtered.isEmpty())
            item {
              Text(
                  if (choices.isEmpty()) "Список пока пуст"
                  else "Ничего не найдено. Измените поиск или фильтр.",
                  Modifier.padding(vertical = 24.dp),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
        items(filtered, key = { it.first }) { (id, name) ->
          Row(
              Modifier.fillMaxWidth()
                  .heightIn(min = 56.dp)
                  .toggleable(
                      value = id in selected,
                      role = if (singleChoice) Role.RadioButton else Role.Checkbox,
                      onValueChange = {
                        haptics.tap()
                        onToggle(id)
                        if (singleChoice) onDismiss()
                      },
                  ),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            if (singleChoice) RadioButton(id in selected, null) else Checkbox(id in selected, null)
            Text(
                name,
                Modifier.weight(1f).padding(12.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
          }
        }
      }
      PillButton("Готово", onDismiss, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp))
    }
  }
}
