package com.huttsmedia.chess.ui.theme

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

private val DarkColorScheme = darkColorScheme(
    primary = ClubGold,
    onPrimary = ClubFelt,
    secondary = ClubSage,
    onSecondary = ClubFelt,
    tertiary = ClubFrame,
    background = ClubFelt,
    onBackground = ClubCream,
    surface = ClubWalnut,
    onSurface = ClubCream,
    surfaceVariant = Color(0xFF3A2C1E),
    onSurfaceVariant = ClubCream,
    outline = Color(0xFF8A7354),
    primaryContainer = ClubFrame,
    onPrimaryContainer = ClubCream,
)

private val LightColorScheme = lightColorScheme(
    primary = ClubGoldDark,
    onPrimary = Color.White,
    secondary = ClubSageDark,
    onSecondary = Color.White,
    tertiary = ClubFrame,
    background = ClubPaper,
    onBackground = ClubInk,
    surface = Color(0xFFFBF6EA),
    onSurface = ClubInk,
    surfaceVariant = Color(0xFFE8DCC8),
    onSurfaceVariant = ClubInk,
    outline = Color(0xFF8A7354),
    primaryContainer = Color(0xFFE8D5A3),
    onPrimaryContainer = ClubInk,
)

@Composable
fun ChessTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
