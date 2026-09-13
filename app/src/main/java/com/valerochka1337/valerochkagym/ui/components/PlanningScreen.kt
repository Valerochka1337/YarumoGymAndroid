package com.valerochka1337.valerochkagym.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The navigation shell already handles system bars. Keep Up visible while forms scroll. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlanningScreen(
    title: String,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
  Scaffold(
      modifier = Modifier.imePadding(),
      containerColor = MaterialTheme.colorScheme.background,
      contentWindowInsets = WindowInsets(0, 0, 0, 0),
      bottomBar = {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
          Box(Modifier.widthIn(max = 840.dp).fillMaxWidth()) { bottomBar() }
        }
      },
      topBar = {
        TopAppBar(
            title = { Text(title) },
            actions = actions,
            windowInsets = WindowInsets(0, 0, 0, 0),
            navigationIcon = {
              IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
            },
        )
      },
  ) { padding ->
    BoxWithConstraints(
        Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.TopCenter,
    ) {
      Column(
          Modifier.widthIn(max = 840.dp)
              .fillMaxSize()
              .verticalScroll(rememberScrollState())
              .padding(horizontal = if (maxWidth < 600.dp) 16.dp else 24.dp, vertical = 16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
          content = content,
      )
    }
  }
}
