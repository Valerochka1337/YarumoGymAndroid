package com.valerochka1337.valerochkagym.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Fully-rounded pill button — the app's primary action. Flat solid primary fill with a dark
 * onPrimary label. Add fillMaxWidth via [modifier] to span the full width. [compact] is reserved
 * for dense top-bar actions such as «Замеры»; normal primary actions stay at the 56dp touch target
 * used throughout forms and screens.
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    compact: Boolean = false,
    supportingText: String? = null,
) {
  Button(
      onClick = onClick,
      enabled = enabled,
      modifier = modifier.heightIn(min = if (compact) 48.dp else 56.dp),
      shape = CircleShape,
      colors =
          ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.primary,
              contentColor = MaterialTheme.colorScheme.onPrimary,
          ),
      contentPadding = ButtonDefaults.ContentPadding,
  ) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      if (leadingIcon != null) {
        Icon(
            imageVector = leadingIcon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
      }
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = text,
            style =
                if (compact) MaterialTheme.typography.labelLarge
                else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        supportingText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
      }
    }
  }
}
