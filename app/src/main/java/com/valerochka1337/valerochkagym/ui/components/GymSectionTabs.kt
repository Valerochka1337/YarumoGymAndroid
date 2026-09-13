package com.valerochka1337.valerochkagym.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.ui.theme.GymMotion

/** Equal, accessible tabs with a single sliding pill; height follows the largest label. */
@Composable
fun <T> GymSectionTabs(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
  require(options.isNotEmpty() && selected in options)
  val position by
      animateFloatAsState(
          targetValue = options.indexOf(selected).toFloat(),
          animationSpec = GymMotion.spatialFast(),
          label = "Section indicator",
      )
  val indicatorColor = MaterialTheme.colorScheme.secondaryContainer
  Row(
      modifier =
          modifier
              .fillMaxWidth()
              .height(IntrinsicSize.Min)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.surfaceContainerLow)
              .drawBehind {
                val inset = 4.dp.toPx()
                val slot = size.width / options.size
                val logicalPosition = position.coerceIn(0f, options.lastIndex.toFloat())
                val visualPosition =
                    if (layoutDirection == LayoutDirection.Rtl) {
                      options.lastIndex - logicalPosition
                    } else logicalPosition
                drawRoundRect(
                    color = indicatorColor,
                    topLeft = Offset(visualPosition * slot + inset, inset),
                    size =
                        Size(
                            (slot - inset * 2).coerceAtLeast(0f),
                            (size.height - inset * 2).coerceAtLeast(0f),
                        ),
                    cornerRadius = CornerRadius(size.height / 2),
                )
              }
              .selectableGroup(),
  ) {
    options.forEach { option ->
      val isSelected = option == selected
      val textColor by
          animateColorAsState(
              targetValue =
                  if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                  else MaterialTheme.colorScheme.onSurfaceVariant,
              animationSpec = GymMotion.effectsFast(),
              label = "Section label",
          )
      Box(
          modifier =
              Modifier.weight(1f)
                  .fillMaxHeight()
                  .heightIn(min = 56.dp)
                  .clip(CircleShape)
                  .selectable(
                      selected = isSelected,
                      role = Role.Tab,
                      onClick = { if (!isSelected) onSelect(option) },
                  )
                  .padding(horizontal = 8.dp, vertical = 12.dp),
          contentAlignment = Alignment.Center,
      ) {
        Text(
            text = label(option),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
            textAlign = TextAlign.Center,
        )
      }
    }
  }
}
