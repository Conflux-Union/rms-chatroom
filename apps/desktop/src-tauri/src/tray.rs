use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

use tauri::image::Image;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Manager, UserAttentionType};

/// Shared flag telling the flash loop whether unviewed notifications exist.
#[derive(Clone)]
pub struct FlashState(pub Arc<AtomicBool>);

impl FlashState {
    pub fn new() -> Self {
        Self(Arc::new(AtomicBool::new(false)))
    }
}

impl Default for FlashState {
    fn default() -> Self {
        Self::new()
    }
}

const FLASH_INTERVAL_MS: u64 = 600;

fn show_main_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

pub fn setup_tray(app: &tauri::App) -> tauri::Result<()> {
    let show = MenuItem::with_id(app, "show", "显示主窗口", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&show, &quit])?;

    let mut builder = TrayIconBuilder::with_id("main")
        .tooltip("RMS Chat")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "show" => show_main_window(app),
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                show_main_window(tray.app_handle());
            }
        });

    if let Some(icon) = app.default_window_icon().cloned() {
        builder = builder.icon(icon);
    }

    builder.build(app)?;
    Ok(())
}

/// Toggle the tray icon between the app icon and a fully transparent image
/// while unread notifications exist, so the tray appears to blink. Restores
/// the normal icon once the flag clears.
pub fn spawn_flash_loop(app: AppHandle, state: FlashState) {
    std::thread::spawn(move || {
        // 32x32 RGBA, all zero bytes = fully transparent.
        let transparent = Image::new_owned(vec![0u8; 32 * 32 * 4], 32, 32);
        let mut transparent_on = false;
        let mut restored = true;

        loop {
            std::thread::sleep(Duration::from_millis(FLASH_INTERVAL_MS));

            let Some(tray) = app.tray_by_id("main") else {
                continue;
            };

            if state.0.load(Ordering::Relaxed) {
                restored = false;
                transparent_on = !transparent_on;
                if transparent_on {
                    let _ = tray.set_icon(Some(transparent.clone()));
                } else if let Some(icon) = app.default_window_icon().cloned() {
                    let _ = tray.set_icon(Some(icon));
                }
            } else if !restored {
                restored = true;
                transparent_on = false;
                if let Some(icon) = app.default_window_icon().cloned() {
                    let _ = tray.set_icon(Some(icon));
                }
            }
        }
    });
}

/// Called by the frontend when unviewed notifications appear or are cleared.
#[tauri::command]
pub fn set_attention(app: AppHandle, state: tauri::State<'_, FlashState>, has_unread: bool) {
    state.0.store(has_unread, Ordering::Relaxed);

    // FlashWindowEx needs a taskbar button, so skip it while hidden to tray.
    if has_unread {
        if let Some(window) = app.get_webview_window("main") {
            if window.is_visible().unwrap_or(false) {
                let _ = window.request_user_attention(Some(UserAttentionType::Critical));
            }
        }
    }
}
