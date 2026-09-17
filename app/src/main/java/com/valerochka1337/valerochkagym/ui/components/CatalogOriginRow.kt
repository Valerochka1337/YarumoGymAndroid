package com.valerochka1337.valerochkagym.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.backend.BackendSync
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class CatalogActionsViewModel @Inject constructor(private val sync: BackendSync) : ViewModel() {
  private val mutableMessage = MutableStateFlow<String?>(null)
  val message = mutableMessage.asStateFlow()
  private val mutableBusy = MutableStateFlow(false)
  val busy = mutableBusy.asStateFlow()

  fun copy(kind: String, id: String) {
    if (mutableBusy.value) return
    mutableBusy.value = true
    viewModelScope.launch {
      try {
        sync.personalCopy(kind, id)
        mutableMessage.value = "Личная копия создана. Она доступна в списке для редактирования."
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        mutableMessage.value = e.message ?: "Не удалось создать копию"
      } finally {
        mutableBusy.value = false
      }
    }
  }

  fun clearMessage() {
    mutableMessage.value = null
  }
}

@Composable
fun CatalogOriginRow(
    origin: String,
    kind: String,
    id: String,
    actions: CatalogActionsViewModel = hiltViewModel(),
) {
  val message by actions.message.collectAsStateWithLifecycle()
  val busy by actions.busy.collectAsStateWithLifecycle()
  CatalogOriginContent(origin, busy, message) { actions.copy(kind, id) }
}

@Composable
internal fun CatalogOriginContent(
    origin: String,
    busy: Boolean,
    message: String?,
    onCopy: () -> Unit,
) {
  val haptics = gymHaptics()
  Column {
    Text(
        if (origin == "STANDARD") "Стандартное" else "Личное",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (origin == "STANDARD")
        TextButton(
            onClick = {
              haptics.tap()
              onCopy()
            },
            enabled = !busy,
        ) {
          Text("Создать личную копию")
        }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
  }
}
