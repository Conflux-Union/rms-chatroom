package cn.net.rms.chatroom.data.local

/**
 * App-wide color theme choice, mirroring the web client's light | dark | system
 * mode in packages/shared/src/utils/theme.ts.
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    companion object {
        fun fromStored(raw: String?): ThemeMode =
            entries.firstOrNull { it.name == raw } ?: SYSTEM
    }
}
