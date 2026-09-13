package cn.net.rms.chatroom.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The zhimo paper-and-ink palette as consumed by app code, resolved to the
 * active theme. This is the Compose twin of zhimo-ui tokens.css: screens read
 * semantic slots (paper, ink, seal, ...) instead of picking a fixed dark or
 * light value, the same way the web reads var(--zhimo-*). The M3 mirror of the
 * same tokens lives in Theme.kt's color schemes.
 */
@Immutable
class ZhimoColors(
    val paper: Color,
    val paperSubtle: Color,
    val paperHover: Color,
    val paperRaised: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val border: Color,
    val borderStrong: Color,
    val seal: Color,
    val sealHover: Color,
    val danger: Color,
    val dangerContainer: Color,
    val success: Color,
    val warning: Color,
)

fun zhimoDarkColors() = ZhimoColors(
    paper = PaperDark,
    paperSubtle = PaperDarkSubtle,
    paperHover = PaperDarkHover,
    paperRaised = PaperDarkRaised,
    ink = InkDark,
    inkMuted = InkDarkMuted,
    inkFaint = InkDarkFaint,
    border = BorderDark,
    borderStrong = BorderStrongDark,
    seal = SealDark,
    sealHover = SealDarkHover,
    danger = DangerDark,
    dangerContainer = DangerContainerDark,
    success = SuccessDark,
    warning = WarningDark
)

fun zhimoLightColors() = ZhimoColors(
    paper = PaperLight,
    paperSubtle = PaperLightSubtle,
    paperHover = PaperLightHover,
    paperRaised = PaperLightRaised,
    ink = InkLight,
    inkMuted = InkLightMuted,
    inkFaint = InkLightFaint,
    border = BorderLight,
    borderStrong = BorderStrongLight,
    seal = SealLight,
    sealHover = SealLightHover,
    danger = DangerLight,
    dangerContainer = DangerContainerLight,
    success = SuccessLight,
    warning = WarningLight
)

// Dark default so standalone usage (previews, tests) renders the app's
// historical night-reading look before a provider is installed.
val LocalZhimoColors = staticCompositionLocalOf { zhimoDarkColors() }

/** Semantic zhimo palette of the active theme; the Compose twin of var(--zhimo-*). */
object Zhimo {
    val paper: Color
        @Composable @ReadOnlyComposable get() = LocalZhimoColors.current.paper
    val paperSubtle: Color
        @Composable @ReadOnlyComposable get() = LocalZhimoColors.current.paperSubtle
    val paperHover: Color
        @Composable @ReadOnlyComposable get() = LocalZhimoColors.current.paperHover
    val paperRaised: Color
        @Composable @ReadOnlyComposable get() = LocalZhimoColors.current.paperRaised
    val ink: Color
        @Composable @ReadOnlyComposable get() = LocalZhimoColors.current.ink
    val inkMuted: Color
        @Composable @ReadOnlyComposable get() = LocalZhimoColors.current.inkMuted
    val inkFaint: Color
        @Composable @ReadOnlyComposable get() = LocalZhimoColors.current.inkFaint
    val border: Color
        @Composable @ReadOnlyComposable get() = LocalZhimoColors.current.border
    val borderStrong: Color
        @Composable @ReadOnlyComposable get() = LocalZhimoColors.current.borderStrong
    val seal: Color
        @Composable @ReadOnlyComposable get() = LocalZhimoColors.current.seal
    val sealHover: Color
        @Composable @ReadOnlyComposable get() = LocalZhimoColors.current.sealHover
    val danger: Color
        @Composable @ReadOnlyComposable get() = LocalZhimoColors.current.danger
    val dangerContainer: Color
        @Composable @ReadOnlyComposable get() = LocalZhimoColors.current.dangerContainer
    val success: Color
        @Composable @ReadOnlyComposable get() = LocalZhimoColors.current.success
    val warning: Color
        @Composable @ReadOnlyComposable get() = LocalZhimoColors.current.warning
}
