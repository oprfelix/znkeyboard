package dev.zain.znkeyboard

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal val ZainAppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * The settings palette, the same in light and dark as the iOS app: one blue accent over cool
 * greys, white grouped cards on a pale page. surfaceContainer is the card; the higher containers
 * are the fills inside a card (key previews, chips, the track of a switch).
 */
private val LightSettingsColors = lightColorScheme(
    primary = Color(0xFF2F6BFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6EDFF),
    onPrimaryContainer = Color(0xFF0D3AA8),
    secondary = Color(0xFF4F5B7A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6EDFF),
    onSecondaryContainer = Color(0xFF0D3AA8),
    tertiary = Color(0xFF6C5CE7),
    background = Color(0xFFF4F5FA),
    onBackground = Color(0xFF12141C),
    surface = Color(0xFFF4F5FA),
    onSurface = Color(0xFF12141C),
    surfaceVariant = Color(0xFFEDEFF5),
    onSurfaceVariant = Color(0xFF666B7C),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF1F2F7),
    surfaceContainerHighest = Color(0xFFE4E6EE),
    outline = Color(0xFFB9BDCB),
    outlineVariant = Color(0xFFE7E9F0),
    error = Color(0xFFE5484D),
    errorContainer = Color(0xFFFFE4E4),
    onErrorContainer = Color(0xFF9F1D22),
)

private val DarkSettingsColors = darkColorScheme(
    primary = Color(0xFF6F98FF),
    onPrimary = Color(0xFF071A4D),
    primaryContainer = Color(0xFF1F2A48),
    onPrimaryContainer = Color(0xFFD5E0FF),
    secondary = Color(0xFFAAB4D0),
    onSecondary = Color(0xFF1A2135),
    secondaryContainer = Color(0xFF1F2A48),
    onSecondaryContainer = Color(0xFFD5E0FF),
    tertiary = Color(0xFFA89BFF),
    background = Color(0xFF0E0F14),
    onBackground = Color(0xFFF1F2F6),
    surface = Color(0xFF0E0F14),
    onSurface = Color(0xFFF1F2F6),
    surfaceVariant = Color(0xFF22242D),
    onSurfaceVariant = Color(0xFFA0A5B5),
    surfaceContainerLowest = Color(0xFF1A1C24),
    surfaceContainerLow = Color(0xFF1A1C24),
    surfaceContainer = Color(0xFF1A1C24),
    surfaceContainerHigh = Color(0xFF23252F),
    surfaceContainerHighest = Color(0xFF2D303B),
    outline = Color(0xFF4A4E5C),
    outlineVariant = Color(0xFF2A2D38),
    error = Color(0xFFFF6B6F),
    errorContainer = Color(0xFF4A1E22),
    onErrorContainer = Color(0xFFFFD9DA),
)

@Composable
internal fun ZnKeyboardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkSettingsColors else LightSettingsColors,
        shapes = ZainAppShapes,
        content = content,
    )
}

/** Semantic aliases over the Material color scheme, kept so existing screens restyle in one place. */
internal object ZnKeyboardColors {
    val Background: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surface
    /** A grouped card. */
    val Surface: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainer
    val Key: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHigh
    val FunctionKey: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHighest
    val Accent: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
    /** Behind a row's icon, and the selected tab. */
    val AccentSoft: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primaryContainer
    val OnSurface: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface
    val Muted: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val Divider: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outlineVariant
    val DeleteBackground: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.errorContainer
    val DeleteContent: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onErrorContainer
    /** "Works" green for status dots; Material's scheme has no success role, so it is fixed per theme. */
    val Success: Color
        @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) Color(0xFF5BD08A) else Color(0xFF1F9D55)
    /** "Needs setting up" orange, as on iOS. */
    val Warning: Color
        @Composable @ReadOnlyComposable get() = Color(0xFFF08A24)
    /** The two corners of the faint wash behind the page title. */
    val WashStart: Color
        @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) Color(0xFF18213D) else Color(0xFFDFE8FF)
    val WashEnd: Color
        @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) Color(0xFF241A3A) else Color(0xFFEFE6FF)
}
