// preload.js
const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("electronAPI", {
  // ===== SSO =====
  getCallbackUrl: () => ipcRenderer.invoke("auth:getCallbackUrl"),
  openExternal: (url) => ipcRenderer.invoke("auth:openExternal", url),
  onAuthCallback: (cb) => {
    const handler = (_event, data) => cb(data);
    ipcRenderer.on("auth:callback", handler);
    return () => ipcRenderer.removeListener("auth:callback", handler);
  },

  // ===== mic toggle =====
  onMicToggle: (cb) => {
    const handler = () => cb();
    ipcRenderer.on("mic:toggle", handler);
    return () => ipcRenderer.removeListener("mic:toggle", handler);
  },

  // ===== updater =====
  updaterCheck: () => ipcRenderer.invoke("updater:check"),
  updaterDownload: () => ipcRenderer.invoke("updater:download"),
  updaterInstall: () => ipcRenderer.invoke("updater:install"),
  onUpdaterStatus: (cb) => {
    const handler = (_event, data) => cb(data);
    ipcRenderer.on("updater:status", handler);
    return () => ipcRenderer.removeListener("updater:status", handler);
  },

  // ===== app =====
  quitApp: () => ipcRenderer.invoke("app:quit"),
});

// 你原来的快捷键接口（保持不变）
contextBridge.exposeInMainWorld("hotkey", {
  get: () => ipcRenderer.invoke("shortcuts:get"),
  set: (key, accelerator) => ipcRenderer.invoke("shortcuts:set", key, accelerator),
});
