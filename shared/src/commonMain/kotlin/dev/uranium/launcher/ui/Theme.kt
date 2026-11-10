package dev.uranium.launcher.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uranium.launcher.instance.LoaderType
import kotlin.math.absoluteValue

/** Colours that sit outside Material's roles. */
object Palette {
    val Glow = Color(0xFF9BE564)
    val GlowDeep = Color(0xFF2FBF8F)
    val Canvas = Color(0xFF0B0F0D)
    val Panel = Color(0xFF111714)
    val Stroke = Color(0xFF26302B)
    val Online = Color(0xFF59D98E)
    val Warning = Color(0xFFE9C46A)

    fun loader(loader: LoaderType): Color = when (loader) {
        LoaderType.VANILLA -> Color(0xFF8FCB5A)
        LoaderType.FABRIC -> Color(0xFFE3D5B0)
        LoaderType.QUILT -> Color(0xFFC79BFA)
        LoaderType.FORGE -> Color(0xFF8FB4E8)
        LoaderType.NEOFORGE -> Color(0xFFF58A4B)
    }

    /** A stable pair of cover colours for anything with a name, so each instance looks its own. */
    fun cover(seed: String): Pair<Color, Color> {
        val hue = (seed.hashCode().absoluteValue % 360).toFloat()
        return Color.hsl(hue, 0.52f, 0.36f) to Color.hsl((hue + 46f) % 360f, 0.58f, 0.15f)
    }
}

private val UraniumColors = darkColorScheme(
    primary = Palette.Glow,
    onPrimary = Color(0xFF0D2106),
    primaryContainer = Color(0xFF23401A),
    onPrimaryContainer = Color(0xFFCBF6AB),
    secondary = Color(0xFF6FD6B8),
    onSecondary = Color(0xFF05241C),
    secondaryContainer = Color(0xFF1B332C),
    onSecondaryContainer = Color(0xFFB9EFDF),
    tertiary = Palette.Warning,
    background = Palette.Canvas,
    onBackground = Color(0xFFE6ECE7),
    surface = Palette.Canvas,
    onSurface = Color(0xFFE6ECE7),
    surfaceVariant = Color(0xFF1E2622),
    onSurfaceVariant = Color(0xFF9FACA4),
    surfaceContainerLowest = Color(0xFF080B0A),
    surfaceContainerLow = Palette.Panel,
    surfaceContainer = Color(0xFF151C18),
    surfaceContainerHigh = Color(0xFF1B2420),
    surfaceContainerHighest = Color(0xFF232D28),
    outline = Color(0xFF45524B),
    outlineVariant = Palette.Stroke,
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0907),
    errorContainer = Color(0xFF3F1512),
    onErrorContainer = Color(0xFFFFDAD5),
    scrim = Color(0xCC000000),
)

private val UraniumShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private val UraniumType = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
    )
}

@Composable
fun UraniumTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = UraniumColors, shapes = UraniumShapes, typography = UraniumType, content = content)
}
