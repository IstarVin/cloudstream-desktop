package desktop.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
    val good = Color(0xFF53D3A3)
    val danger = Color(0xFFFF6170)
}

private val colors = darkColorScheme(
    primary = Palette.accent,
    onPrimary = Palette.accentInk,
    primaryContainer = Palette.accent.copy(alpha = 0.18f),
    onPrimaryContainer = Palette.accent,
    secondary = Palette.accent,
    onSecondary = Palette.accentInk,
    secondaryContainer = Palette.accent.copy(alpha = 0.14f),
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
    outlineVariant = Color(0x1AFFFFFF),
    error = Palette.danger,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
