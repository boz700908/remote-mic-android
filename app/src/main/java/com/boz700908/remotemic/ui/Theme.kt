package com.boz700908.remotemic.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RemoteMicColors = darkColorScheme(
    primary = Color(0xFF6FB7FF),
    onPrimary = Color(0xFF06121F),
    secondary = Color(0xFF7BE0B0),
    onSecondary = Color(0xFF06231A),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF161C24),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1F2732),
    onSurfaceVariant = Color(0xFFA8B6C6),
    error = Color(0xFFFF7B72),
)

@Composable
fun RemoteMicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RemoteMicColors,
        typography = Typography(),
        content = content,
    )
}
