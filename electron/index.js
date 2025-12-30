// index.js
const { app, BrowserWindow, session, ipcMain, globalShortcut, shell } = require("electron");
const path = require("path");
const http = require("http");
const fs = require("fs");
const Store = require("electron-store").default;
const { autoUpdater } = require("electron-updater");

// =====================
// Store（默认快捷键）
// =====================
const store = new Store({
  defaults: {
    shortcuts: {
      toggleWindow: "CommandOrControl+Alt+K",
      toggleMic: "CommandOrControl+Alt+M",
    },
  },
});

let mainWin = null;
let currentToggleShortcut = null;
let currentMicShortcut = null;

// =====================
// SSO 回调服务器
// =====================
let callbackServer = null;
let callbackUrl = null;

function sendHtmlFile(res, filename, statusCode = 200) {
  const filePath = path.join(__dirname, filename);
  fs.readFile(filePath, "utf8", (err, html) => {
    if (err) {
      res.writeHead(500, { "Content-Type": "text/plain; charset=utf-8" });
      return res.end(`Cannot read file: ${filename}`);
    }
    res.writeHead(statusCode, { "Content-Type": "text/html; charset=utf-8" });
    res.end(html);
  });
}

function startCallbackServer(win) {
  return new Promise((resolve) => {
    if (callbackServer && callbackUrl) return resolve(callbackUrl);

    callbackServer = http.createServer((req, res) => {
      try {
        const u = new URL(req.url, "http://127.0.0.1");

        if (u.pathname === "/callback") {
          const token = u.searchParams.get("token");
          const code = u.searchParams.get("code");
          const state = u.searchParams.get("state");

          if (win && !win.isDestroyed()) {
            win.webContents.send("auth:callback", {
              token: token || null,
              code: code || null,
              state: state || null,
              raw: req.url,
            });
          }

          sendHtmlFile(res, "./statusWebpage/loginSuccess.html", 200);
          return;
        }

        sendHtmlFile(res, "./statusWebpage/404.html", 404);
      } catch (e) {
        sendHtmlFile(res, "./statusWebpage/serverError.html", 500);
      }
    });

    callbackServer.listen(0, "127.0.0.1", () => {
      const { port } = callbackServer.address();
      callbackUrl = `http://127.0.0.1:${port}/callback`;
      resolve(callbackUrl);
    });

    callbackServer.on("error", () => {
      callbackUrl = null;
      resolve(null);
    });
  });
}

// =====================
// 创建窗口
// =====================
function createWindow() {
  const win = new BrowserWindow({
    width: 1200,
    height: 800,
    icon: path.join(__dirname, "assets", "icon.png"),
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      preload: path.join(__dirname, "preload.js"),
    },
  });

  const distDir = path.join(__dirname, "frontend", "dist");
  const indexHtml = path.join(distDir, "index.html");
  win.loadFile(indexHtml);

  return win;
}

// =====================
// 快捷键：显示/隐藏窗口
// =====================
function toggleMainWindow() {
  if (!mainWin) return;
  if (mainWin.isVisible()) mainWin.hide();
  else {
    mainWin.show();
    mainWin.focus();
  }
}

function registerToggleShortcut(accelerator) {
  if (currentToggleShortcut) {
    globalShortcut.unregister(currentToggleShortcut);
    currentToggleShortcut = null;
  }
  if (!accelerator) return { ok: false, error: "快捷键为空" };

  const ok = globalShortcut.register(accelerator, toggleMainWindow);
  if (!ok) return { ok: false, error: "注册失败：可能被系统/其他软件占用，换个组合键" };

  currentToggleShortcut = accelerator;
  return { ok: true };
}

// =====================
// 快捷键：麦克风 toggle（发事件给渲染层）
// =====================
function toggleMic() {
  if (!mainWin || mainWin.isDestroyed()) return;
  mainWin.webContents.send("mic:toggle");
}

function registerMicShortcut(accelerator) {
  if (currentMicShortcut) {
    globalShortcut.unregister(currentMicShortcut);
    currentMicShortcut = null;
  }
  if (!accelerator) return { ok: false, error: "快捷键为空" };

  const ok = globalShortcut.register(accelerator, toggleMic);
  if (!ok) return { ok: false, error: "注册失败：可能被系统/其他软件占用，换个组合键" };

  currentMicShortcut = accelerator;
  return { ok: true };
}

// =====================
// CSP（你原来白名单那套）
// =====================
function installCSP() {
  const CSP =
    "default-src 'self'; " +
    "script-src 'self'; " +
    "style-src 'self' 'unsafe-inline'; " +
    "img-src 'self' data: blob: file: https:; " +
    "connect-src 'self' " +
    "https://preview-chatroom.rms.net.cn " +
    "http://preview-chatroom.rms.net.cn " +
    "wss://preview-chatroom.rms.net.cn " +
    "ws://preview-chatroom.rms.net.cn " +
    "http://localhost:8000 " +
    "http://127.0.0.1:8000 " +
    "ws://localhost:8000 " +
    "ws://127.0.0.1:8000;";

  session.defaultSession.webRequest.onHeadersReceived((details, callback) => {
    const headers = { ...(details.responseHeaders || {}) };

    delete headers["Content-Security-Policy"];
    delete headers["content-security-policy"];
    delete headers["Content-Security-Policy-Report-Only"];
    delete headers["content-security-policy-report-only"];

    headers["Content-Security-Policy"] = [CSP];
    callback({ responseHeaders: headers });
  });
}

// =====================
// 强制更新判断（核心）
// =====================
function textIncludesForceWords(text) {
  const t = String(text || "").toLowerCase();
  const words = [
    "security",
    "forced",
    "force update",
    "mandatory",
    "must update",
    "强制更新",
    "必须更新",
    "安全更新",
  ].map((w) => w.toLowerCase());

  return words.some((w) => t.includes(w));
}

function filenameIncludesSecurityOrForced(info) {
  const files = (info && info.files) || [];
  return files.some((f) => {
    const url = f?.url || f?.path || "";
    const name = String(url).split("/").pop() || "";
    return /security|forced/i.test(name);
  });
}

function isForcedUpdate(info) {
  // 1) 文件名命中 Security/Forced
  if (filenameIncludesSecurityOrForced(info)) return true;

  // 2) releaseName / releaseNotes 命中强制词
  if (textIncludesForceWords(info?.releaseName)) return true;

  const notes = info?.releaseNotes;
  if (Array.isArray(notes)) {
    const joined = notes.map((n) => n?.note || "").join("\n");
    if (textIncludesForceWords(joined)) return true;
  } else {
    if (textIncludesForceWords(notes)) return true;
  }

  return false;
}

// =====================
// Auto Updater（可选更新 + 强制更新）
// =====================
function sendUpdaterStatus(payload) {
  if (!mainWin || mainWin.isDestroyed()) return;
  mainWin.webContents.send("updater:status", payload);
}

function setupAutoUpdater() {
  // 关键：可选更新让用户选，默认不自动下
  autoUpdater.autoDownload = false;

  autoUpdater.on("checking-for-update", () => sendUpdaterStatus({ state: "checking" }));

  autoUpdater.on("update-available", async (info) => {
    const forced = isForcedUpdate(info);

    sendUpdaterStatus({ state: "available", forced, info });

    // 强制更新：一发现就直接开始下载（让用户别多一步）
    if (forced) {
      try {
        await autoUpdater.downloadUpdate();
      } catch (e) {
        sendUpdaterStatus({ state: "error", forced, message: String(e) });
      }
    }
  });

  autoUpdater.on("update-not-available", (info) => {
    sendUpdaterStatus({ state: "none", forced: false, info });
  });

  autoUpdater.on("download-progress", (p) => {
    sendUpdaterStatus({
      state: "downloading",
      percent: p.percent,
      transferred: p.transferred,
      total: p.total,
      bytesPerSecond: p.bytesPerSecond,
    });
  });

  autoUpdater.on("update-downloaded", (info) => {
    const forced = isForcedUpdate(info);
    sendUpdaterStatus({ state: "downloaded", forced, info });
  });

  autoUpdater.on("error", (err) => {
    sendUpdaterStatus({ state: "error", forced: false, message: String(err) });
  });
}

async function checkForUpdatesSafe() {
  try {
    return await autoUpdater.checkForUpdates();
  } catch (e) {
    sendUpdaterStatus({ state: "error", forced: false, message: String(e) });
    return null;
  }
}

// =====================
// IPC（保留旧接口名 + 新增 updater / quit）
// =====================
function setupIpc() {
  // 快捷键
  ipcMain.handle("shortcuts:get", () => store.get("shortcuts"));
  ipcMain.handle("shortcuts:set", (_event, key, accelerator) => {
    store.set(`shortcuts.${key}`, accelerator);
    if (key === "toggleWindow") return registerToggleShortcut(accelerator);
    if (key === "toggleMic") return registerMicShortcut(accelerator);
    return { ok: true };
  });

  // SSO
  ipcMain.handle("auth:getCallbackUrl", async () => {
    if (!callbackUrl && mainWin) await startCallbackServer(mainWin);
    return callbackUrl;
  });

  ipcMain.handle("auth:openExternal", async (_event, url) => {
    try {
      await shell.openExternal(url);
      return { ok: true };
    } catch (e) {
      return { ok: false, error: String(e) };
    }
  });

  // 更新：检查 / 下载 / 安装
  ipcMain.handle("updater:check", async () => checkForUpdatesSafe());
  ipcMain.handle("updater:download", async () => {
    try {
      await autoUpdater.downloadUpdate();
      return { ok: true };
    } catch (e) {
      return { ok: false, error: String(e) };
    }
  });
  ipcMain.handle("updater:install", async () => {
    autoUpdater.quitAndInstall();
    return true;
  });

  // 强制更新不更新就退出：给渲染层一个退出接口
  ipcMain.handle("app:quit", async () => {
    app.quit();
    return true;
  });
}

// =====================
// App 生命周期（只保留一次）
// =====================
app.whenReady().then(async () => {
  installCSP();
  setupIpc();

  mainWin = createWindow();
  await startCallbackServer(mainWin);

  // 注册快捷键
  registerToggleShortcut(store.get("shortcuts.toggleWindow"));
  registerMicShortcut(store.get("shortcuts.toggleMic"));

  // 更新器
  setupAutoUpdater();

  // 启动自动检查一次（可删）
  await checkForUpdatesSafe();
});

app.on("will-quit", () => {
  globalShortcut.unregisterAll();
  try {
    if (callbackServer) callbackServer.close();
  } catch (_) {}
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});

app.on("activate", () => {
  if (BrowserWindow.getAllWindows().length === 0) {
    mainWin = createWindow();
  }
});
