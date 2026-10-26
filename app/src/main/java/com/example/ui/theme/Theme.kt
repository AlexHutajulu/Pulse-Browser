package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

object AdBlockSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

private val ChromeDarkColorScheme = darkColorScheme(
    primary = ChromeBlueDark,
    onPrimary = ChromeOnBlueDark,
    primaryContainer = ChromeBlueContainerDark,
    onPrimaryContainer = ChromeOnBlueContainerDark,
    secondary = ChromeGreenDark,
    onSecondary = Color(0xFF0A3818),
    secondaryContainer = Color(0xFF0F5223),
    onSecondaryContainer = Color(0xFFC4EED0),
    tertiary = GoogleYellow,
    onTertiary = Color(0xFF3E2D00),
    background = ChromeBgDark,
    onBackground = ChromeTextPrimaryDark,
    surface = ChromeSurfaceDark,
    onSurface = ChromeTextPrimaryDark,
    surfaceVariant = ChromeSurfaceContainerDark,
    onSurfaceVariant = ChromeTextSecondaryDark,
    surfaceContainerLowest = ChromeTabStripDark,
    surfaceContainerLow = ChromeSurfaceContainerDark,
    surfaceContainer = ChromeOmniboxDark,
    surfaceContainerHigh = Color(0xFF3B3D3F),
    outline = ChromeOutlineDark,
    outlineVariant = ChromeOutlineVariantDark,
    error = ChromeRedDark,
    onError = Color(0xFF601410)
)

private val ChromeLightColorScheme = lightColorScheme(
    primary = ChromeBlueLight,
    onPrimary = ChromeOnBlueLight,
    primaryContainer = ChromeBlueContainerLight,
    onPrimaryContainer = ChromeOnBlueContainerLight,
    secondary = ChromeGreen,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC4EED0),
    onSecondaryContainer = Color(0xFF072711),
    tertiary = ChromeYellow,
    onTertiary = Color.White,
    background = ChromeBgLight,
    onBackground = ChromeTextPrimaryLight,
    surface = ChromeSurfaceLight,
    onSurface = ChromeTextPrimaryLight,
    surfaceVariant = ChromeSurfaceContainerLight,
    onSurfaceVariant = ChromeTextSecondaryLight,
    surfaceContainerLowest = ChromeTabStripLight,
    surfaceContainerLow = ChromeSurfaceContainerLight,
    surfaceContainer = ChromeOmniboxLight,
    surfaceContainerHigh = Color(0xFFE1E6EE),
    outline = ChromeOutlineLight,
    outlineVariant = ChromeOutlineVariantLight,
    error = ChromeRed,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> ChromeDarkColorScheme
        else -> ChromeLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
