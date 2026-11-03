package com.stealth.onewifi

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val OneUiBlack = Color(0xFF000000)
val OneUiCardDark = Color(0xFF17171A)
val OneUiBlue = Color(0xFF387AFF)
val OneUiTextPrimary = Color(0xFFF4F4F5)
val OneUiTextSecondary = Color(0xFF9E9EA7)
val OneUiDivider = Color(0xFF27272A)

private val OneUiDarkColorScheme = darkColorScheme(
    primary = OneUiBlue,
    onPrimary = Color.White,
    background = OneUiBlack,
    onBackground = OneUiTextPrimary,
    surface = OneUiCardDark,
    onSurface = OneUiTextPrimary,
    surfaceVariant = OneUiCardDark,
    onSurfaceVariant = OneUiTextSecondary,
    outline = OneUiDivider
)

@Composable
fun OneWiFiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OneUiDarkColorScheme,
        content = content
    )
}
