<template>
  <div v-if="visible" class="mask">
    <div class="box">
      <div class="title">
        {{ forced ? "需要更新才能继续使用" : "发现新版本" }}
      </div>

      <div class="info">
        <div>状态：{{ stateText }}</div>
        <div v-if="sourceText">更新源：{{ sourceText }}</div>
        <div v-if="versionText">版本：{{ versionText }}</div>
      </div>

      <div v-if="state === 'downloading'" class="progress">
        <div>下载中：{{ percent.toFixed(1) }}%</div>
        <div class="sub">{{ formatBytes(transferred) }} / {{ formatBytes(total) }}</div>
      </div>

      <div v-if="state === 'error'" class="error">
        更新出错：{{ message }}
      </div>

      <div class="actions">
        <!-- 强制更新：只给更新 + 退出 -->
        <template v-if="forced">
          <button class="btn primary" :disabled="btnDisabled" @click="forceUpdateAction">
            {{ forceBtnText }}
          </button>
          <button class="btn danger" @click="quit">退出</button>
        </template>

        <!-- 可选更新：下载/安装/稍后 -->
        <template v-else>
          <button
            v-if="state === 'available'"
            class="btn primary"
            :disabled="btnDisabled"
            @click="download"
          >
            下载更新
          </button>

          <button
            v-if="state === 'downloaded'"
            class="btn primary"
            :disabled="btnDisabled"
            @click="install"
          >
            安装并重启
          </button>

          <button class="btn" @click="later">稍后</button>
          <button class="btn" @click="visible = false">关闭</button>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, onBeforeUnmount, ref, computed } from "vue";

const visible = ref(false);

const state = ref("idle"); // idle/checking/available/none/downloading/downloaded/error
const forced = ref(false);

const percent = ref(0);
const transferred = ref(0);
const total = ref(0);
const message = ref("");
const versionText = ref("");

// 新增：显示当前选择的更新源
const sourceText = ref("");

const stateText = computed(() => {
  const map = {
    idle: "空闲",
    checking: "检查更新中",
    available: "有新版本（待下载）",
    none: "已是最新版本",
    downloading: "正在下载",
    downloaded: "下载完成",
    error: "错误",
  };
  return map[state.value] || state.value;
});

const btnDisabled = computed(() => state.value === "checking" || state.value === "downloading");

const forceBtnText = computed(() => {
  if (state.value === "downloaded") return "安装并重启";
  if (state.value === "downloading") return "下载中…";
  if (state.value === "available") return "更新（开始下载）";
  return "更新";
});

function formatBytes(n) {
  if (!Number.isFinite(n) || n <= 0) return "0 B";
  const units = ["B", "KB", "MB", "GB"];
  let i = 0;
  let v = n;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(1)} ${units[i]}`;
}

async function check() {
  await window.electronAPI?.updaterCheck?.();
}

async function download() {
  await window.electronAPI?.updaterDownload?.();
}

async function install() {
  await window.electronAPI?.updaterInstall?.();
}

async function quit() {
  await window.electronAPI?.quitApp?.();
}

// 强制更新按钮：available -> download；downloaded -> install
async function forceUpdateAction() {
  if (state.value === "downloaded") return install();
  if (state.value === "available") return download();
}

function later() {
  visible.value = false;
}

let off = null;

onMounted(() => {
  off = window.electronAPI?.onUpdaterStatus?.((data) => {
    state.value = data.state || "idle";
    forced.value = !!data.forced;

    // 新增：显示源（主进程会发 CN / INTL / GITHUB）
    if (data.source) {
      const s = String(data.source).toUpperCase();
      sourceText.value = s === "CN" ? "国内源" : s === "INTL" ? "海外源" : s === "GITHUB" ? "GitHub" : s;
    }

    // 版本信息
    const v = data?.info?.version || data?.info?.releaseName || "";
    if (v) versionText.value = String(v);

    if (data.percent != null) percent.value = data.percent;
    if (data.transferred != null) transferred.value = data.transferred;
    if (data.total != null) total.value = data.total;
    if (data.message) message.value = data.message;

    // Close dialog when no updates available
    if (state.value === "none") {
      visible.value = false;
      return;
    }

    if (forced.value) {
      if (["available", "downloading", "downloaded", "error"].includes(state.value)) visible.value = true;
    } else {
      if (["available", "downloaded", "error"].includes(state.value)) visible.value = true;
    }
  });
});

onBeforeUnmount(() => {
  if (typeof off === "function") off();
});
</script>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
}
.box {
  width: min(520px, calc(100vw - 32px));
  background: #fff;
  border-radius: 14px;
  padding: 16px;
  border: 1px solid #eee;
}
.title {
  font-weight: 800;
  font-size: 16px;
  margin-bottom: 10px;
}
.info { font-size: 13px; color: #333; }
.progress { margin-top: 10px; font-size: 13px; }
.sub { margin-top: 6px; font-size: 12px; color: #666; }
.error { margin-top: 10px; color: #b00020; font-size: 13px; }
.actions { display: flex; gap: 10px; margin-top: 14px; flex-wrap: wrap; }
.btn {
  padding: 8px 12px;
  border-radius: 10px;
  border: 1px solid #ccc;
  background: #fff;
  cursor: pointer;
}
.primary { border-color: #111; }
.danger { border-color: #b00020; color: #b00020; }
.btn:disabled { opacity: 0.6; cursor: not-allowed; }
</style>
