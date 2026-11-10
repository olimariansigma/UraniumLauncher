package dev.uranium.launcher.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val UraniumColors = darkColorScheme(
    primary = Color(0xFF9BE564),
    onPrimary = Color(0xFF10250A),
    primaryContainer = Color(0xFF274D1A),
    onPrimaryContainer = Color(0xFFC9F5A8),
    secondary = Color(0xFF6FD6B8),
    onSecondary = Color(0xFF05241C),
    secondaryContainer = Color(0xFF1D3B33),
    onSecondaryContainer = Color(0xFFB6EEDD),
    tertiary = Color(0xFFE6C867),
    background = Color(0xFF0F1512),
    onBackground = Color(0xFFE3E9E4),
    surface = Color(0xFF0F1512),
    onSurface = Color(0xFFE3E9E4),
    surfaceVariant = Color(0xFF222B26),
    onSurfaceVariant = Color(0xFFA9B5AD),
    surfaceContainerLowest = Color(0xFF0B100D),
    surfaceContainerLow = Color(0xFF151C18),
    surfaceContainer = Color(0xFF19211D),
    surfaceContainerHigh = Color(0xFF1F2823),
    surfaceContainerHighest = Color(0xFF27312C),
    outline = Color(0xFF4A5750),
    outlineVariant = Color(0xFF2C3731),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0907),
    errorContainer = Color(0xFF4A1512),
    onErrorContainer = Color(0xFFFFDAD5),
)

private val UraniumShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun UraniumTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = UraniumColors, shapes = UraniumShapes, content = content)
}
