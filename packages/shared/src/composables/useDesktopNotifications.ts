/**
 * Desktop-only notification helpers (Tauri). All calls are no-ops on web.
 *
 * - System toast via @tauri-apps/plugin-notification
 * - Tray flashing / taskbar highlight via the `set_attention` Rust command
 */

const TOAST_COOLDOWN_MS = 2500

let permissionGranted: boolean | null = null
let lastToastTime = 0

function isTauriDesktop(): boolean {
  return typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window
}

export function useDesktopNotifications() {
  /**
   * Show a system toast. Throttled so bursts of messages
   * do not stack a wall of notifications.
   */
  function showMessageNotification(title: string, body: string): void {
    if (!isTauriDesktop()) return

    const now = Date.now()
    if (now - lastToastTime < TOAST_COOLDOWN_MS) return
    lastToastTime = now

    import('@tauri-apps/plugin-notification')
      .then(async (mod) => {
        if (permissionGranted === null) {
          let granted = await mod.isPermissionGranted()
          if (!granted) {
            granted = (await mod.requestPermission()) === 'granted'
          }
          permissionGranted = granted
        }
        if (permissionGranted) {
          mod.sendNotification({ title, body })
        }
      })
      .catch((e) => {
        console.warn('[DesktopNotification] Failed to send:', e)
      })
  }

  /**
   * Start/stop tray flashing + taskbar attention.
   * Cleared natively when the window regains focus.
   */
  function setUnreadAttention(hasUnread: boolean): void {
    if (!isTauriDesktop()) return

    import('@tauri-apps/api/core')
      .then(({ invoke }) => invoke('set_attention', { hasUnread }))
      .catch((e) => {
        console.warn('[DesktopNotification] set_attention failed:', e)
      })
  }

  return { showMessageNotification, setUnreadAttention }
}
