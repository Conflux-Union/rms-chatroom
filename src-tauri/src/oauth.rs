use axum::{
    extract::{Query, State},
    response::Html,
    routing::get,
    Router,
};
use serde::{Deserialize, Serialize};
use std::sync::Mutex;
use tauri::{AppHandle, Emitter};

const LOGIN_SUCCESS_HTML: &str = r#"<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>登录成功</title>
  <style>
    * { box-sizing: border-box; }
    body {
      margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;
      background: radial-gradient(circle at top, #eef2ff 0%, #f8fafc 45%, #ffffff 100%);
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", Arial, sans-serif;
      color: #0f172a; padding: 24px;
    }
    .card {
      width: min(560px, 92vw); padding: 28px 28px 24px; border-radius: 18px;
      background: rgba(255,255,255,0.8); border: 1px solid rgba(148,163,184,0.35);
      box-shadow: 0 18px 50px rgba(15,23,42,0.12); backdrop-filter: blur(10px); text-align: center;
    }
    .icon {
      width: 54px; height: 54px; margin: 0 auto 14px; border-radius: 999px;
      display: grid; place-items: center;
      background: linear-gradient(135deg, #22c55e 0%, #16a34a 100%);
      box-shadow: 0 10px 24px rgba(34,197,94,0.25); color: #fff; font-size: 28px; line-height: 1;
    }
    h1 { margin: 0 0 10px; font-size: 26px; letter-spacing: 0.5px; }
    p { margin: 0; font-size: 16px; line-height: 1.8; color: #334155; }
    .hint { margin-top: 14px; font-size: 13px; color: #64748b; }
  </style>
</head>
<body>
  <div class="card" role="status" aria-live="polite">
    <div class="icon">✓</div>
    <h1>登录成功</h1>
    <p>可以关闭此网页，返回原来的界面继续使用</p>
    <div class="hint">如无法自动返回，请手动切回原窗口/应用</div>
  </div>
</body>
</html>"#;

#[derive(Default)]
pub struct CallbackServerState {
    pub port: u16,
}

#[derive(Deserialize, Serialize, Clone, Debug)]
pub struct CallbackParams {
    access_token: Option<String>,
    refresh_token: Option<String>,
    token: Option<String>,
    code: Option<String>,
    state: Option<String>,
}

async fn callback_handler(
    Query(params): Query<CallbackParams>,
    State(app): State<AppHandle>,
) -> Html<&'static str> {
    log::info!("OAuth callback received: {:?}", params);
    let _ = app.emit("auth-callback", &params);
    Html(LOGIN_SUCCESS_HTML)
}

pub fn start_callback_server(
    app: AppHandle,
    port_state: &Mutex<CallbackServerState>,
) -> Result<(), Box<dyn std::error::Error>> {
    use std::net::TcpListener;

    let listener = TcpListener::bind("127.0.0.1:0")?;
    let port = listener.local_addr()?.port();
    drop(listener);

    port_state.lock().unwrap().port = port;

    let app_handle = app.clone();
    tauri::async_runtime::spawn(async move {
        let router = Router::new()
            .route("/callback", get(callback_handler))
            .with_state(app_handle);
        match tokio::net::TcpListener::bind(format!("127.0.0.1:{}", port)).await {
            Ok(listener) => {
                if let Err(e) = axum::serve(listener, router).await {
                    log::error!("OAuth callback server error: {}", e);
                }
            }
            Err(e) => {
                log::error!("Failed to bind callback server: {}", e);
            }
        }
    });

    log::info!("OAuth callback server started on port {}", port);
    Ok(())
}

#[tauri::command]
pub fn get_callback_url(state: tauri::State<'_, Mutex<CallbackServerState>>) -> String {
    let port = state.lock().unwrap().port;
    if port == 0 {
        return String::new();
    }
    format!("http://127.0.0.1:{}/callback", port)
}

#[tauri::command]
pub async fn open_external(url: String, app: AppHandle) -> Result<(), String> {
    use tauri_plugin_shell::ShellExt;
    #[allow(deprecated)]
    app.shell()
        .open(url, None)
        .map_err(|e| e.to_string())
}
