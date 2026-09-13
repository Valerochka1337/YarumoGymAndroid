package com.valerochka1337.valerochkagym.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Fixed semantic colors requested for proposal decisions, with light/dark contrast. */
val ColorScheme.proposalPending: Color
  get() = if (surface.luminance() < 0.5f) Color(0xFFFFD666) else Color(0xFF806000)

val ColorScheme.proposalApproved: Color
  get() = if (surface.luminance() < 0.5f) Color(0xFF83DB9A) else Color(0xFF216C39)
