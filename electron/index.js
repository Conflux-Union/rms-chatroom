// index.js
const { app, BrowserWindow, session, ipcMain, globalShortcut, shell } = require("electron");
const path = require("path");
const http = require("http");
const https = require("https");
const fs = require("fs");
const Store = require("electron-store").default;
const { autoUpdater } = require("electron-updater");

// =====================
// 你要改的：两个镜像源（目录下必须有 latest.yml）
// 例： https://download-cn.xxx.com/rms-chat/win/   （里面有 latest.yml、exe、blockmap）
// =====================
const UPDATE_FEED_CN = "https://api.hurrybili1016hjh.cc/RMS-Chatroom/Win/";
const UPDATE_FEED_INTL = "https://api.hurrybili1016hjh.cc/RMS-Chatroom/Win/";

// GitHub 最低优先级（public 可不填 token；private 需要 GH_TOKEN/GITHUB_TOKEN）
const GITHUB_OWNER = "RMS-Server";
const GITHUB_REPO = "RMS-ChatRoom";
const GITHUB_TOKEN = process.env.GH_TOKEN || process.env.GITHUB_TOKEN || "";

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
// 强制更新判断（保留你原逻辑）
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
  if (filenameIncludesSecurityOrForced(info)) return true;
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
// Updater：智能选源（你要的核心）
// =====================
let currentUpdateSource = null; // "CN" | "INTL" | "GITHUB" | null

function sendUpdaterStatus(payload) {
  if (!mainWin || mainWin.isDestroyed()) return;
  mainWin.webContents.send("updater:status", payload);
}

// --- 简单 GET（支持重定向 + 超时）---
function httpGetText(url, timeoutMs = 2500) {
  const doReq = (u, redirectsLeft) =>
    new Promise((resolve, reject) => {
      const parsed = new URL(u);
      const mod = parsed.protocol === "https:" ? https : http;

      const req = mod.request(
        {
          method: "GET",
          hostname: parsed.hostname,
          port: parsed.port || (parsed.protocol === "https:" ? 443 : 80),
          path: parsed.pathname + parsed.search,
          headers: {
            "User-Agent": "RMS-Chat-Updater",
            "Cache-Control": "no-cache",
          },
        },
        (res) => {
          // follow redirect
          if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirectsLeft > 0) {
            res.resume();
            const nextUrl = new URL(res.headers.location, u).toString();
            return resolve(doReq(nextUrl, redirectsLeft - 1));
          }

          let data = "";
          res.setEncoding("utf8");
          res.on("data", (chunk) => (data += chunk));
          res.on("end", () => {
            if (res.statusCode >= 200 && res.statusCode < 300) return resolve(data);
            reject(new Error(`HTTP ${res.statusCode} ${u}`));
          });
        }
      );

      req.on("error", reject);
      req.setTimeout(timeoutMs, () => req.destroy(new Error(`Timeout ${timeoutMs}ms ${u}`)));
      req.end();
    });

  return doReq(url, 3);
}

// --- 探活：能否拿到 latest.yml（连不上就算不可用）---
async function probeFeedOk(feedBaseUrl) {
  try {
    const base = feedBaseUrl.endsWith("/") ? feedBaseUrl : feedBaseUrl + "/";
    await httpGetText(base + "latest.yml", 2500);
    return true;
  } catch (_) {
    return false;
  }
}

// --- IP 判断：大陆/非大陆（失败默认当大陆，保证国内用户走国内源）---
async function detectMainlandChina() {
  const apis = [
    "https://ipapi.co/json/",
    "https://ipinfo.io/json",
    "https://api.ip.sb/geoip",
    "https://geojs.io/v1/ip/geo.json",
  ];

  for (const api of apis) {
    try {
      const txt = await httpGetText(api, 2500);
      const j = JSON.parse(txt);

      const country = String(j.country_code || j.country || j.countryCode || "").toUpperCase();
      const region = String(j.region || j.region_name || j.regionName || j.province || "").toUpperCase();

      if (country === "CN") {
        // 排除港澳台（不当大陆）
        const notMainland = ["HONG KONG", "HK", "MACAU", "MO", "TAIWAN", "TW"];
        if (notMainland.some((x) => region.includes(x))) return false;
        return true;
      }
      if (country) return false;
    } catch (_) {}
  }

  // 都失败：默认当大陆（你要“国内优先”）
  return true;
}

// --- 每次 check：临时监听 available/none/error，返回结果 ---
async function checkOnce(feedConfig, sourceTag) {
  return new Promise((resolve) => {
    const cleanup = () => {
      autoUpdater.removeListener("update-available", onAvail);
      autoUpdater.removeListener("update-not-available", onNone);
      autoUpdater.removeListener("error", onErr);
    };

    const onAvail = (info) => {
      cleanup();
      resolve({ ok: true, available: true, info, source: sourceTag });
    };
    const onNone = (info) => {
      cleanup();
      resolve({ ok: true, available: false, info, source: sourceTag });
    };
    const onErr = (err) => {
      cleanup();
      resolve({ ok: false, error: String(err), source: sourceTag });
    };

    autoUpdater.once("update-available", onAvail);
    autoUpdater.once("update-not-available", onNone);
    autoUpdater.once("error", onErr);

    try {
      autoUpdater.setFeedURL(feedConfig);

      // GitHub 私库需要 token（公库不需要）
      if (feedConfig.provider === "github" && GITHUB_TOKEN) {
        autoUpdater.requestHeaders = { Authorization: `token ${GITHUB_TOKEN}` };
      } else {
        autoUpdater.requestHeaders = null;
      }

      autoUpdater.checkForUpdates().catch(onErr);
    } catch (e) {
      onErr(e);
    }
  });
}

function genericFeed(url) {
  return { provider: "generic", url: url.endsWith("/") ? url : url + "/" };
}
function githubFeed() {
  return { provider: "github", owner: GITHUB_OWNER, repo: GITHUB_REPO };
}

// --- 你的权重逻辑：primary -> secondary -> GitHub(最低) ---
async function smartCheckForUpdates() {
  sendUpdaterStatus({ state: "checking" });
  currentUpdateSource = null;

  const isMainland = await detectMainlandChina();

  const primary = isMainland ? UPDATE_FEED_CN : UPDATE_FEED_INTL;
  const secondary = isMainland ? UPDATE_FEED_INTL : UPDATE_FEED_CN;

  const primaryOk = await probeFeedOk(primary);
  const secondaryOk = await probeFeedOk(secondary);

  // 1) 优先源：能连 && 有新
  if (primaryOk) {
    const r1 = await checkOnce(genericFeed(primary), isMainland ? "CN" : "INTL");
    if (r1.ok && r1.available) return onSelectedUpdate(r1.info, r1.source);
  }

  // 2) 另一个源：能连 && 有新
  if (secondaryOk) {
    const r2 = await checkOnce(genericFeed(secondary), isMainland ? "INTL" : "CN");
    if (r2.ok && r2.available) return onSelectedUpdate(r2.info, r2.source);
  }

  // 3) 两源都没新（或不可用） -> GitHub 最低权重
  const rg = await checkOnce(githubFeed(), "GITHUB");
  if (rg.ok && rg.available) return onSelectedUpdate(rg.info, "GITHUB");

  // 都没新
  sendUpdaterStatus({ state: "none", forced: false });
}

async function onSelectedUpdate(info, source) {
  currentUpdateSource = source;
  const forced = isForcedUpdate(info);

  sendUpdaterStatus({ state: "available", forced, source, info });

  // 强制更新：直接开始下载
  if (forced) {
    try {
      await autoUpdater.downloadUpdate();
    } catch (e) {
      sendUpdaterStatus({ state: "error", forced, source, message: String(e) });
    }
  }
}

// --- 只保留下载/完成/错误事件（checking/available/none 由 smartCheck 发）---
function setupAutoUpdaterSmartEvents() {
  autoUpdater.autoDownload = false;

  autoUpdater.on("download-progress", (p) => {
    sendUpdaterStatus({
      state: "downloading",
      source: currentUpdateSource,
      percent: p.percent,
      transferred: p.transferred,
      total: p.total,
      bytesPerSecond: p.bytesPerSecond,
    });
  });

  autoUpdater.on("update-downloaded", (info) => {
    const forced = isForcedUpdate(info);
    sendUpdaterStatus({ state: "downloaded", forced, source: currentUpdateSource, info });
  });

  autoUpdater.on("error", (err) => {
    sendUpdaterStatus({ state: "error", forced: false, source: currentUpdateSource, message: String(err) });
  });
}

// =====================
// IPC
// =====================
function setupIpc() {
  ipcMain.handle("shortcuts:get", () => store.get("shortcuts"));
  ipcMain.handle("shortcuts:set", (_event, key, accelerator) => {
    store.set(`shortcuts.${key}`, accelerator);
    if (key === "toggleWindow") return registerToggleShortcut(accelerator);
    if (key === "toggleMic") return registerMicShortcut(accelerator);
    return { ok: true };
  });

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

  // ===== 更新：检查(智能选源) / 下载 / 安装 =====
  ipcMain.handle("updater:check", async () => {
    await smartCheckForUpdates();
    return true;
  });

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

  ipcMain.handle("app:quit", async () => {
    app.quit();
    return true;
  });
}

// =====================
// App 生命周期
// =====================
app.whenReady().then(async () => {
  installCSP();
  setupIpc();

  mainWin = createWindow();
  await startCallbackServer(mainWin);

  registerToggleShortcut(store.get("shortcuts.toggleWindow"));
  registerMicShortcut(store.get("shortcuts.toggleMic"));

  // 更新器：事件绑定 + 启动检查
  setupAutoUpdaterSmartEvents();
  await smartCheckForUpdates();
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
