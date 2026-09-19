package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val DarkColorScheme =
  darkColorScheme(
    primary = DarkPrimary,
    secondary = DarkSecondary,
    tertiary = DarkTertiary,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onPrimary = Color(0xFF00363A),
    onSecondary = Color(0xFF00344D),
    onTertiary = Color(0xFF003544),
    onBackground = Color(0xFFE2E8F0),
    onSurface = Color(0xFFE7EDF7),
    onSurfaceVariant = Color(0xFF9BAAC0),
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF1E293B),
    surfaceContainer = Color(0xFF111A2B),
    surfaceContainerHigh = Color(0xFF172236),
    error = Color(0xFFFF6B7A),
    onError = Color(0xFF3B0710)
  )

private val LightColorScheme =
  lightColorScheme(
    primary = LightPrimary,
    secondary = LightSecondary,
    tertiary = LightTertiary,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A)
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true,
  // Off by default so SpaceFit looks identical on every Android device.
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
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
    shapes = Shapes(
      extraSmall = RoundedCornerShape(8.dp),
      small = RoundedCornerShape(12.dp),
      medium = RoundedCornerShape(16.dp),
      large = RoundedCornerShape(24.dp),
      extraLarge = RoundedCornerShape(30.dp)
    ),
    content = content
  )
}
