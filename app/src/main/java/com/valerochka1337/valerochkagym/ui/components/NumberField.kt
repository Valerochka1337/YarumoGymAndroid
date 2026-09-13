package com.valerochka1337.valerochkagym.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType

/**
 * Числовое поле поверх [OutlinedTextField]. Ввод фильтруется до цифр (и одной точки при [decimal]);
 * пустая строка соответствует "нет значения". Значение приходит и уходит строкой — вызывающий сам
 * маппит пустую строку в null и обратно.
 *
 * Пока поле в фокусе, внешние обновления [value] игнорируются: это позволяет спокойно набирать
 * промежуточные состояния вроде "1." без того, чтобы каноничное значение из состояния перетёрло
 * набранный текст. По потере фокуса поле синхронизируется с [value].
 */
@Composable
fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    decimal: Boolean = false,
    enabled: Boolean = true,
    signed: Boolean = false,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val focused by interactionSource.collectIsFocusedAsState()
  var text by remember { mutableStateOf(value) }
  if (!focused && text != value) {
    text = value
  }

  OutlinedTextField(
      value = text,
      onValueChange = { raw ->
        val filtered = filterNumeric(raw, decimal, signed)
        text = filtered
        onValueChange(filtered)
      },
      modifier = modifier,
      enabled = enabled,
      singleLine = true,
      interactionSource = interactionSource,
      label = label?.let { { Text(it, maxLines = 1, softWrap = false) } },
      placeholder = placeholder?.let { { Text(it, maxLines = 1, softWrap = false) } },
      keyboardOptions =
          KeyboardOptions(
              keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
          ),
  )
}

/** Оставляет только цифры и, при [decimal], первую точку. */
private fun filterNumeric(raw: String, decimal: Boolean, signed: Boolean): String {
  var dotSeen = false
  return buildString {
    for (ch in raw) {
      when {
        ch.isDigit() -> append(ch)
        signed && ch == '-' && isEmpty() -> append(ch)
        decimal && (ch == '.' || ch == ',') && !dotSeen -> {
          dotSeen = true
          append('.')
        }
      }
    }
  }
}
