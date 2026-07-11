mod oauth;

use std::collections::HashMap;
use std::sync::Mutex;
use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_global_shortcut::{GlobalShortcutExt, Shortcut, ShortcutState as HotKeyState};
use tauri_plugin_store::StoreExt;

#[derive(Default)]
pub struct ShortcutManager {
    pub current_toggle: Option<String>,
    pub current_mic: Option<String>,
}

#[tauri::command]
fn get_shortcuts(app: AppHandle) -> HashMap<String, String> {
    match app.store("settings.json") {
        Ok(s) => {
            let shortcuts = s.get("shortcuts");
            match shortcuts {
                Some(serde_json::Value::Object(map)) => {
                    let mut result = HashMap::new();
                    for (k, v) in map {
                        if let Some(s) = v.as_str() {
                            result.insert(k, s.to_string());
                        }
                    }
                    result
                }
                _ => default_shortcuts(),
            }
        }
        Err(_) => default_shortcuts(),
    }
}

fn default_shortcuts() -> HashMap<String, String> {
    let mut m = HashMap::new();
    m.insert("toggleWindow".to_string(), "CommandOrControl+Alt+K".to_string());
    m.insert("toggleMic".to_string(), "CommandOrControl+Alt+M".to_string());
    m
}

fn save_shortcuts_to_store(app: &AppHandle, key: &str, accelerator: &str) {
    if let Ok(s) = app.store("settings.json") {
        let mut shortcuts = s.get("shortcuts").unwrap_or_else(|| {
            serde_json::json!({
                "toggleWindow": "CommandOrControl+Alt+K",
                "toggleMic": "CommandOrControl+Alt+M"
            })
        });
        if let Some(obj) = shortcuts.as_object_mut() {
            obj.insert(key.to_string(), serde_json::json!(accelerator));
        }
        s.set("shortcuts", shortcuts);
    }
}

#[tauri::command]
fn set_shortcut(
    key: String,
    accelerator: String,
    app: AppHandle,
    state: tauri::State<'_, Mutex<ShortcutManager>>,
) -> Result<HashMap<String, bool>, String> {
    save_shortcuts_to_store(&app, &key, &accelerator);

    let mut st = state.lock().unwrap();
    let plugin = app.global_shortcut();

    match key.as_str() {
        "toggleWindow" => {
            if let Some(old) = st.current_toggle.take() {
                if let Ok(sc) = old.parse::<Shortcut>() {
                    let _ = plugin.unregister(sc);
                }
            }
            if accelerator.is_empty() {
                return Ok([("ok".to_string(), false)].into_iter().collect());
            }
            match accelerator.parse::<Shortcut>() {
                Ok(sc) => {
                    let app_clone = app.clone();
                    let result = plugin.on_shortcut(sc, move |_app, _shortcut, event| {
                        if event.state == HotKeyState::Pressed {
                            if let Some(window) = app_clone.get_webview_window("main") {
                                if window.is_visible().unwrap_or(false) {
                                    let _ = window.hide();
                                } else {
                                    let _ = window.show();
                                    let _ = window.set_focus();
                                }
                            }
                        }
                    });
                    match result {
                        Ok(()) => {
                            st.current_toggle = Some(accelerator);
                            Ok([("ok".to_string(), true)].into_iter().collect())
                        }
                        Err(_e) => Ok([("ok".to_string(), false)].into_iter().collect()),
                    }
                }
                Err(e) => Err(format!("Failed to parse shortcut: {}", e)),
            }
        }
        "toggleMic" => {
            if let Some(old) = st.current_mic.take() {
                if let Ok(sc) = old.parse::<Shortcut>() {
                    let _ = plugin.unregister(sc);
                }
            }
            if accelerator.is_empty() {
                return Ok([("ok".to_string(), false)].into_iter().collect());
            }
            match accelerator.parse::<Shortcut>() {
                Ok(sc) => {
                    let app_clone = app.clone();
                    let result = plugin.on_shortcut(sc, move |_app, _shortcut, event| {
                        if event.state == HotKeyState::Pressed {
                            let _ = app_clone.emit("mic-toggle", ());
                        }
                    });
                    match result {
                        Ok(()) => {
                            st.current_mic = Some(accelerator);
                            Ok([("ok".to_string(), true)].into_iter().collect())
                        }
                        Err(_e) => Ok([("ok".to_string(), false)].into_iter().collect()),
                    }
                }
                Err(e) => Err(format!("Failed to parse shortcut: {}", e)),
            }
        }
        _ => Ok([("ok".to_string(), true)].into_iter().collect()),
    }
}

#[tauri::command]
fn quit_app(app: AppHandle) {
    app.exit(0);
}

/// Detect available Chromium-based browser and return its executable path.
/// Used by the frontend to open the web app in app mode when WebKitGTK lacks WebRTC.
#[tauri::command]
fn find_chromium_browser() -> Option<String> {
    let candidates = [
        "google-chrome",
        "google-chrome-stable",
        "chromium",
        "chromium-browser",
        "brave-browser",
        "microsoft-edge",
        "vivaldi",
    ];

    for cmd in &candidates {
        if let Ok(output) = std::process::Command::new("which")
            .arg(cmd)
            .output()
        {
            if output.status.success() {
                let path = String::from_utf8_lossy(&output.stdout).trim().to_string();
                if !path.is_empty() {
                    return Some(path);
                }
            }
        }
    }
    None
}

/// Open a URL in Chromium app mode (chromeless standalone window).
#[tauri::command]
fn open_in_chromium_app(browser_path: String, url: String) -> Result<(), String> {
    std::process::Command::new(&browser_path)
        .arg("--app")
        .arg(&url)
        .arg("--new-window")
        .spawn()
        .map_err(|e| format!("Failed to launch browser: {}", e))?;
    Ok(())
}

fn register_default_shortcuts(app: &AppHandle) {
    let shortcuts = get_shortcuts(app.clone());
    let state = app.state::<Mutex<ShortcutManager>>();
    let mut st = state.lock().unwrap();

    let plugin = app.global_shortcut();

    if let Some(acc) = shortcuts.get("toggleWindow") {
        if let Ok(sc) = acc.parse::<Shortcut>() {
            let app_clone = app.clone();
            if plugin
                .on_shortcut(sc, move |_app, _shortcut, event| {
                    if event.state == HotKeyState::Pressed {
                        if let Some(window) = app_clone.get_webview_window("main") {
                            if window.is_visible().unwrap_or(false) {
                                let _ = window.hide();
                            } else {
                                let _ = window.show();
                                let _ = window.set_focus();
                            }
                        }
                    }
                })
                .is_ok()
            {
                st.current_toggle = Some(acc.clone());
            }
        }
    }

    if let Some(acc) = shortcuts.get("toggleMic") {
        if let Ok(sc) = acc.parse::<Shortcut>() {
            let app_clone = app.clone();
            if plugin
                .on_shortcut(sc, move |_app, _shortcut, event| {
                    if event.state == HotKeyState::Pressed {
                        let _ = app_clone.emit("mic-toggle", ());
                    }
                })
                .is_ok()
            {
                st.current_mic = Some(acc.clone());
            }
        }
    }
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_global_shortcut::Builder::new().build())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_store::Builder::default().build())
        .plugin(tauri_plugin_process::init())
        .setup(|app| {
            let callback_state = Mutex::new(oauth::CallbackServerState::default());
            oauth::start_callback_server(app.handle().clone(), &callback_state)?;
            app.manage(callback_state);
            app.manage(Mutex::new(ShortcutManager::default()));
            register_default_shortcuts(app.handle());

            // Enable WebRTC and media stream in WebKitGTK (Linux only)
            // WebKitGTK disables these by default. On distros that compile
            // WebKitGTK with -DENABLE_WEB_RTC=ON, this exposes RTCPeerConnection.
            // On distros without WebRTC compiled in, the frontend falls back to
            // opening the web app in a system Chromium browser for voice.
            #[cfg(target_os = "linux")]
            {
                if let Some(webview) = app.get_webview_window("main") {
                    let _ = webview.with_webview(|wv| {
                        use webkit2gtk::{PermissionRequestExt, SettingsExt, WebViewExt};
                        let webview = wv.inner();

                        if let Some(settings) = webview.settings() {
                            settings.set_enable_webrtc(true);
                            settings.set_enable_media_stream(true);
                            settings.set_enable_mediasource(true);
                            settings.set_enable_media(true);
                            settings.set_media_playback_requires_user_gesture(false);
                        }

                        webview.connect_permission_request(|_wv, req| {
                            req.allow();
                            true
                        });
                    });
                }
            }

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            oauth::get_callback_url,
            oauth::open_external,
            get_shortcuts,
            set_shortcut,
            quit_app,
            find_chromium_browser,
            open_in_chromium_app,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
