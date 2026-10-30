package dev.zain.znkeyboard

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

internal val ZainAppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Material You: the settings app follows the wallpaper palette and system light/dark, like the keyboard. */
@Composable
internal fun ZnKeyboardTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = ZainAppShapes,
        content = content,
    )
}

/** Semantic aliases over the Material color scheme, kept so existing screens restyle in one place. */
internal object ZnKeyboardColors {
    val Background: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surface
    val Surface: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainer
    val Key: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHigh
    val FunctionKey: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHighest
    val Accent: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
    val OnSurface: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface
    val Muted: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val DeleteBackground: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.errorContainer
    val DeleteContent: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onErrorContainer
    /** "Works" green for status dots; Material's scheme has no success role, so it is fixed per theme. */
    val Success: Color
        @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) Color(0xFF81C995) else Color(0xFF1E8E3E)
}

