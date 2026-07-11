#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    // WebKitGTK on Linux: disable DMABUF renderer to avoid GBM buffer creation
    // failures on systems where DRM device access is restricted (e.g. AppImage sandbox)
    #[cfg(target_os = "linux")]
    {
        std::env::set_var("WEBKIT_DISABLE_DMABUF_RENDERER", "1");
    }
    rms_discord_lib::run()
}
