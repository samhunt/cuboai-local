package com.cuboai.local.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NightScheme = darkColorScheme(
    primary = Color(0xFF8ECFBE),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF1B4A40),
    secondary = Color(0xFFB7C7EA),
    background = Color(0xFF121418),
    surface = Color(0xFF1C1F26),
    surfaceVariant = Color(0xFF2A303A),
    onBackground = Color(0xFFE8EEF4),
    onSurface = Color(0xFFE8EEF4),
    error = Color(0xFFFFB4AB),
)

@Composable
fun CuboLocalTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NightScheme, content = content)
}
