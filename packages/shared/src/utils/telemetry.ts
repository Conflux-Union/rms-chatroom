import type { App } from 'vue'
import type { Router } from 'vue-router'
import { VERSION_NAME, VERSION_CODE } from '../version'
import { useAuthStore } from '../stores/auth'

// Fire-and-forget client telemetry: batches events and POSTs them to
// /api/telemetry/client. Must never break the app — every entry point
// swallows its own errors — and must never leak credentials: messages and
// stacks are sanitized before leaving the client (the backend re-sanitizes
// as defense in depth).

const API_BASE = import.meta.env.VITE_API_BASE || ''
const STORAGE_KEY = 'rms-telemetry-enabled'
const FLUSH_DELAY_MS = 5000
const MAX_BATCH = 20
// Per-event-key floor keeps a render-loop error from flooding the endpoint;
// the global budget bounds total traffic no matter how many distinct errors fire.
const PER_KEY_MIN_INTERVAL_MS = 60_000
const GLOBAL_BUDGET_WINDOW_MS = 60_000
const GLOBAL_BUDGET_MAX_EVENTS = 10

// Duplicated from ../index to avoid an import cycle (index re-exports this module).
const isTauriEnv = typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window

export interface TelemetryEvent {
  type: string
  message?: string
  stack?: string
  meta?: Record<string, unknown>
}

export function isTelemetryEnabled(): boolean {
  try {
    return localStorage.getItem(STORAGE_KEY) !== 'false'
  } catch {
    return false
  }
}

export function setTelemetryEnabled(enabled: boolean) {
  try {
    if (enabled) localStorage.removeItem(STORAGE_KEY)
    else localStorage.setItem(STORAGE_KEY, 'false')
  } catch {
    // Storage unavailable; treat as session-only toggle.
  }
}

const TOKEN_PARAM_RE = /\b(token|access_token|refresh_token)=[^&\s'"]+/gi
const JWT_RE = /\beyJ[\w-]{10,}\.[\w-]{10,}\.[\w-]{10,}\b/g

function sanitize(text: string | undefined): string | undefined {
  if (!text) return text
  return text.replace(TOKEN_PARAM_RE, '$1=[redacted]').replace(JWT_RE, '[redacted-jwt]')
}

let queue: TelemetryEvent[] = []
let flushTimer: number | null = null
const lastSentByKey = new Map<string, number>()
let budgetWindowStart = 0
let budgetUsed = 0

let authTokenProvider: (() => string | null) | null = null
let currentRouteProvider: (() => string | undefined) | null = null

function withinBudget(key: string): boolean {
  const now = Date.now()
  const last = lastSentByKey.get(key)
  if (last !== undefined && now - last < PER_KEY_MIN_INTERVAL_MS) return false
  if (now - budgetWindowStart > GLOBAL_BUDGET_WINDOW_MS) {
    budgetWindowStart = now
    budgetUsed = 0
  }
  if (budgetUsed >= GLOBAL_BUDGET_MAX_EVENTS) return false
  lastSentByKey.set(key, now)
  budgetUsed++
  return true
}

function buildBatchBody(events: TelemetryEvent[]): string {
  return JSON.stringify({
    platform: isTauriEnv ? 'desktop' : 'web',
    app_version: `${VERSION_NAME}(${VERSION_CODE})`,
    events,
  })
}

function flush(useBeacon = false) {
  if (flushTimer) {
    clearTimeout(flushTimer)
    flushTimer = null
  }
  if (queue.length === 0) return
  const events = queue.slice(0, MAX_BATCH)
  queue = []

  const url = `${API_BASE}/api/telemetry/client`
  const body = buildBatchBody(events)

  try {
    if (useBeacon && navigator.sendBeacon) {
      navigator.sendBeacon(url, new Blob([body], { type: 'application/json' }))
      return
    }
    const headers: Record<string, string> = { 'Content-Type': 'application/json' }
    const token = authTokenProvider?.()
    if (token) headers['Authorization'] = `Bearer ${token}`
    fetch(url, { method: 'POST', headers, body, keepalive: true }).catch(() => {})
  } catch {
    // Never let telemetry surface an error of its own.
  }
}

/**
 * Queue a telemetry event. Safe to call from anywhere at any time; drops the
 * event silently when telemetry is disabled or the rate budget is exhausted.
 */
export function reportTelemetryEvent(
  type: string,
  message?: string,
  extra?: { stack?: string; meta?: Record<string, unknown> }
) {
  try {
    if (!isTelemetryEnabled()) return
    if (!withinBudget(`${type}:${message ?? ''}`)) return

    const meta: Record<string, unknown> = { ...extra?.meta }
    const route = currentRouteProvider?.()
    if (route) meta.route = route

    queue.push({
      type,
      message: sanitize(message),
      stack: sanitize(extra?.stack),
      meta: Object.keys(meta).length > 0 ? meta : undefined,
    })
    if (queue.length >= MAX_BATCH) {
      flush()
    } else if (!flushTimer) {
      flushTimer = window.setTimeout(() => flush(), FLUSH_DELAY_MS)
    }
  } catch {
    // Swallow: telemetry must never throw into app code.
  }
}

function errorToTelemetry(type: string, err: unknown, meta?: Record<string, unknown>) {
  const e = err instanceof Error ? err : undefined
  reportTelemetryEvent(type, e?.message ?? String(err), { stack: e?.stack, meta })
}

let installed = false

/**
 * Install global error reporting. Call once per app, after Pinia and the
 * router are installed but before mount.
 */
export function installTelemetry(app: App, router: Router) {
  if (installed) return
  installed = true

  currentRouteProvider = () => {
    const route = router.currentRoute.value
    // Route name only: full paths can embed IDs and query strings.
    return route.name ? String(route.name) : undefined
  }
  // Lazy store access: pinia is active by install time, but guard anyway so a
  // telemetry call can never crash the bootstrap.
  authTokenProvider = () => {
    try {
      return useAuthStore().token ?? null
    } catch {
      return null
    }
  }

  app.config.errorHandler = (err, _instance, info) => {
    errorToTelemetry('vue_error', err, { info })
    console.error(err)
  }
  window.addEventListener('error', (e) => {
    errorToTelemetry('window_error', e.error ?? e.message)
  })
  window.addEventListener('unhandledrejection', (e) => {
    errorToTelemetry('unhandled_rejection', e.reason)
  })
  window.addEventListener('pagehide', () => flush(true))
}
