package cn.net.rms.chatroom.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * ZhiMo paper-and-ink design tokens, mirroring zhimo-ui tokens.css
 * (node_modules/zhimo-ui/src/tokens.css). The web client consumes the same
 * values through packages/shared/src/assets/theme.css; keep both sides in
 * sync. Seal red is the single accent color; all hue drift beyond the
 * paper/ink neutrals must go through the Seal, Danger, Success and Warning
 * token families.
 */

// Night-reading dark theme (default). Layer order from deepest paper up:
// PaperDark < PaperDarkSubtle < PaperDarkHover < PaperDarkRaised.
val PaperDark = Color(0xFF16140F)
val PaperDarkSubtle = Color(0xFF1D1A14)
val PaperDarkHover = Color(0xFF282318)
val PaperDarkRaised = Color(0xFF332B1F)
val InkDark = Color(0xFFE8E2D4)
val InkDarkMuted = Color(0xFF968D7C)
val InkDarkFaint = Color(0xFF8A8172)
val BorderDark = Color(0xFF353023)
val BorderStrongDark = Color(0xFF4D4634)

// Seal red accent and status hues, dark values.
val SealDark = Color(0xFFD96A52)
val SealDarkHover = Color(0xFFE07E68)
val DangerDark = Color(0xFFD96A52)
val SuccessDark = Color(0xFF7D9A72)
val WarningDark = Color(0xFFCFA050)

// Daylight light theme. Layer order: PaperLight < PaperLightSubtle <
// PaperLightHover < PaperLightRaised.
val PaperLight = Color(0xFFFAF7F0)
val PaperLightSubtle = Color(0xFFF3EEE1)
val PaperLightHover = Color(0xFFEEE8D8)
val PaperLightRaised = Color(0xFFFFFFFF)
val InkLight = Color(0xFF1A1714)
val InkLightMuted = Color(0xFF7A7265)
val InkLightFaint = Color(0xFF9C948A)
val BorderLight = Color(0xFFE0D9C8)
val BorderStrongLight = Color(0xFFC6BCA6)

// Seal red accent and status hues, light values.
val SealLight = Color(0xFFB8432F)
val SealLightHover = Color(0xFFA03A28)
val DangerLight = Color(0xFF9E2F1C)
val SuccessLight = Color(0xFF4A6741)
val WarningLight = Color(0xFFB07D2E)

// Error container: dark warm red-brown derived from the danger hue; not a
// zhimo token but needed so M3 components never fall back to baseline purple.
val DangerContainerDark = Color(0xFF3A241D)
val DangerContainerLight = Color(0xFFF0DDD4)
