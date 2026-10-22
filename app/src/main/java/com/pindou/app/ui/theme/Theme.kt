package com.pindou.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF6C4BF4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF1E0A63),
    secondary = Color(0xFFE0457F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFD9E5),
    onSecondaryContainer = Color(0xFF3F0020),
    tertiary = Color(0xFF0E9E90),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFDFBFF),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFDFBFF),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454E),
    outline = Color(0xFF7A757F),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC9BAFF),
    onPrimary = Color(0xFF331E7A),
    primaryContainer = Color(0xFF4A3391),
    onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = Color(0xFFFFB1C8),
    onSecondary = Color(0xFF5E1136),
    tertiary = Color(0xFF6FD8C8),
    onTertiary = Color(0xFF003731),
    background = Color(0xFF131218),
    surface = Color(0xFF131218),
    onSurface = Color(0xFFE6E1E6),
    surfaceVariant = Color(0xFF49454E),
    onSurfaceVariant = Color(0xFFCAC4CF),
)

@Composable
fun PindouTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
