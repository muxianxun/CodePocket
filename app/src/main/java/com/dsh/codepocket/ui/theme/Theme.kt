package com.dsh.codepocket.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** CodePocket is a code editor / terminal: it is always dark. */
private val Bg = Color(0xFF1E1E2E)
private val Surface1 = Color(0xFF252537)
private val Surface2 = Color(0xFF2E2E44)
private val Accent = Color(0xFF7DD3FC)
private val Accent2 = Color(0xFFA5B4FC)
private val OnBg = Color(0xFFE4E4E7)
private val Muted = Color(0xFFB4B4C6)
private val Outline = Color(0xFF3F3F5A)

private val CodePocketColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF0B1220),
    primaryContainer = Surface2,
    onPrimaryContainer = Accent,
    secondary = Accent2,
    onSecondary = Color(0xFF12122A),
    secondaryContainer = Surface2,
    onSecondaryContainer = Accent2,
    tertiary = Color(0xFF86EFAC),
    background = Bg,
    onBackground = OnBg,
    surface = Surface1,
    onSurface = OnBg,
    surfaceVariant = Surface2,
    onSurfaceVariant = Muted,
    outline = Outline,
    outlineVariant = Outline,
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0A0A),
)

@Composable
fun CodePocketTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CodePocketColors, content = content)
}
