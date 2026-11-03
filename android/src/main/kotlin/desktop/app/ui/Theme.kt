package desktop.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The web UI's palette (`web/src/app.css`). */
object Palette {
    val bg = Color(0xFF0E0F13)
    val surface = Color(0xFF16181E)
    val surface2 = Color(0xFF1E2129)
    val surface3 = Color(0xFF282C36)
    val text = Color(0xFFECEEF3)
    val muted = Color(0xFF979CAB)
    val accent = Color(0xFFFF8A4C)
    val accentInk = Color(0xFF1D0E05)
    val accentSoft = accent.copy(alpha = 0.14f)
    val line = Color(0x1AFFFFFF)
    val good = Color(0xFF53D3A3)
    val danger = Color(0xFFFF6170)

    /** Text and icons over artwork: the image behind them is unknown, so they never take the theme's text role. */
    val onImage = Color.White
    val scrim = Color.Black.copy(alpha = 0.55f)
}

private val colors = darkColorScheme(
    primary = Palette.accent,
    onPrimary = Palette.accentInk,
    primaryContainer = Palette.accentSoft,
    onPrimaryContainer = Palette.accent,
    secondary = Palette.accent,
    onSecondary = Palette.accentInk,
    secondaryContainer = Palette.accentSoft,
    onSecondaryContainer = Palette.accent,
    background = Palette.bg,
    onBackground = Palette.text,
    surface = Palette.bg,
    onSurface = Palette.text,
    surfaceVariant = Palette.surface2,
    onSurfaceVariant = Palette.muted,
    surfaceContainerLowest = Palette.bg,
    surfaceContainerLow = Palette.surface,
    surfaceContainer = Palette.surface,
    surfaceContainerHigh = Palette.surface2,
    surfaceContainerHighest = Palette.surface3,
    outline = Color(0x33FFFFFF),
    outlineVariant = Palette.line,
    error = Palette.danger,
    onError = Palette.accentInk,
    errorContainer = Color(0xFF462028),
    onErrorContainer = Color(0xFFFFDADD),
    surfaceTint = Palette.accent,
)

private val base = Typography()

// A product scale on one family: tighter steps than Material's defaults, and nothing under 12sp, so metadata under
// posters and episodes stays readable at the system font size.
private val type = Typography(
    headlineLarge = base.headlineLarge.copy(fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.9).sp),
    headlineMedium = base.headlineMedium.copy(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    headlineSmall = base.headlineSmall.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleMedium = base.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = base.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = base.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = base.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = base.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

private val tvType = type.copy(
    titleMedium = type.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp),
    titleSmall = type.titleSmall.copy(fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = type.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = type.bodyMedium.copy(fontSize = 16.sp, lineHeight = 22.sp),
    bodySmall = type.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = type.labelMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = type.labelSmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Tabular figures for numbers that change in place (times, counts, priorities). */
val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val television = isTelevision()
    CompositionLocalProvider(LocalTv provides television, LocalCatalogLayout provides CatalogLayout(television = television)) {
        MaterialTheme(colorScheme = colors, typography = if (LocalTv.current) tvType else type, shapes = shapes, content = content)
    }
}
