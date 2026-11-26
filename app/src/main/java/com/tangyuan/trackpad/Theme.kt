package com.tangyuan.trackpad

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F5BFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EAFF),
    onPrimaryContainer = Color(0xFF10246B),
    background = Color(0xFFF4F5F8),
    onBackground = Color(0xFF13161C),
    surface = Color.White,
    onSurface = Color(0xFF13161C),
    surfaceVariant = Color(0xFFEFF1F6),
    onSurfaceVariant = Color(0xFF6B7280),
    outline = Color(0xFFE1E4EB),
    error = Color(0xFFE5484D)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7C96FF),
    onPrimary = Color(0xFF0A1230),
    primaryContainer = Color(0xFF1B2A5E),
    onPrimaryContainer = Color(0xFFD8E0FF),
    background = Color(0xFF0E1015),
    onBackground = Color(0xFFE9EBF0),
    surface = Color(0xFF171A21),
    onSurface = Color(0xFFE9EBF0),
    surfaceVariant = Color(0xFF22262F),
    onSurfaceVariant = Color(0xFF9BA1AE),
    outline = Color(0xFF2C313C),
    error = Color(0xFFFF6B6F)
)

@Composable
fun AppTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content
    )
}
