package com.stealth.onewifi

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val OneUiBlack = Color(0xFF000000)
val OneUiCardDark = Color(0xFF17171A)
val OneUiPopupDark = Color(0xFF252528)
val OneUiBlue = Color(0xFF387AFF)
val OneUiGreen = Color(0xFF28C76F)
val OneUiTextPrimary = Color(0xFFF4F4F5)
val OneUiTextSecondary = Color(0xFF9E9EA7)
val OneUiDivider = Color(0xFF2C2C30)

private val OneUiDarkColorScheme = darkColorScheme(
    primary = OneUiBlue,
    onPrimary = Color.White,
    background = OneUiBlack,
    onBackground = OneUiTextPrimary,
    surface = OneUiCardDark,
    onSurface = OneUiTextPrimary,
    surfaceVariant = OneUiPopupDark,
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

