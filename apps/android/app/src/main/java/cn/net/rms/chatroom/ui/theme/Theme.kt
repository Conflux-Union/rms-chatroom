package cn.net.rms.chatroom.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// Every M3 slot is set explicitly so no component ever falls back to the
// baseline purple scheme. Values come from the zhimo tokens in Color.kt.
private val DarkColorScheme = darkColorScheme(
    primary = SealDark,
    onPrimary = PaperDark,
    primaryContainer = SealLightHover,
    onPrimaryContainer = PaperLight,
    secondary = InkDarkMuted,
    onSecondary = PaperDark,
    secondaryContainer = PaperDarkHover,
    onSecondaryContainer = InkDark,
    tertiary = SuccessDark,
    onTertiary = PaperDark,
    tertiaryContainer = PaperDarkHover,
    onTertiaryContainer = InkDark,
    background = PaperDark,
    onBackground = InkDark,
    surface = PaperDark,
    onSurface = InkDark,
    surfaceVariant = PaperDarkSubtle,
    onSurfaceVariant = InkDarkMuted,
    surfaceTint = PaperDarkHover,
    inverseSurface = InkDark,
    inverseOnSurface = PaperDark,
    inversePrimary = SealLight,
    outline = BorderStrongDark,
    outlineVariant = BorderDark,
    surfaceContainerLowest = PaperDark,
    surfaceContainerLow = PaperDark,
    surfaceContainer = PaperDarkSubtle,
    surfaceContainerHigh = PaperDarkHover,
    surfaceContainerHighest = PaperDarkRaised,
    surfaceDim = PaperDark,
    surfaceBright = PaperDarkHover,
    error = DangerDark,
    onError = PaperDark,
    errorContainer = DangerContainerDark,
    onErrorContainer = DangerDark,
    scrim = Color(0x80000000)
)

private val LightColorScheme = lightColorScheme(
    primary = SealLight,
    onPrimary = PaperLight,
    primaryContainer = PaperLightHover,
    onPrimaryContainer = SealLightHover,
    secondary = InkLightMuted,
    onSecondary = PaperLight,
    secondaryContainer = PaperLightHover,
    onSecondaryContainer = InkLight,
    tertiary = SuccessLight,
    onTertiary = PaperLight,
    tertiaryContainer = PaperLightHover,
    onTertiaryContainer = InkLight,
    background = PaperLight,
    onBackground = InkLight,
    surface = PaperLight,
    onSurface = InkLight,
    surfaceVariant = PaperLightSubtle,
    onSurfaceVariant = InkLightMuted,
    surfaceTint = PaperLightHover,
    inverseSurface = InkLight,
    inverseOnSurface = PaperLight,
    inversePrimary = SealDark,
    outline = BorderStrongLight,
    outlineVariant = BorderLight,
    surfaceContainerLowest = PaperLight,
    surfaceContainerLow = PaperLight,
    surfaceContainer = PaperLightSubtle,
    surfaceContainerHigh = PaperLightHover,
    surfaceContainerHighest = BorderLight,
    surfaceDim = BorderLight,
    surfaceBright = PaperLight,
    error = DangerLight,
    onError = PaperLight,
    errorContainer = DangerContainerLight,
    onErrorContainer = DangerLight,
    scrim = Color(0x80000000)
)

// Print-like sharp corners from zhimo: --zhimo-radius 4px with a 2px small
// step; containers may round up to 8dp but never beyond.
private val ZhimoShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(8.dp)
)

@Composable
fun RMSDiscordTheme(
    darkTheme: Boolean = true, // Discord-like apps read better on dark paper
    dynamicColor: Boolean = false, // Dynamic color would break the paper-and-ink palette
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

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = ZhimoShapes,
        content = content
    )
}
