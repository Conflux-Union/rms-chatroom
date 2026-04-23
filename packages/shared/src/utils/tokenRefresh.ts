import type { useAuthStore } from '../stores/auth'

type AuthStore = ReturnType<typeof useAuthStore>

// Recover the session if the JWT expires within the next 30 seconds.
// This prevents WS connections from being rejected due to a token
// that was valid at call time but expired during the handshake.
export async function refreshTokenIfExpired(auth: AuthStore): Promise<void> {
  if (!auth.token || !auth.canRecoverSession()) return

  try {
    const payload = JSON.parse(atob(auth.token.split('.')[1]))
    const expiresInMs = payload.exp * 1000 - Date.now()
    if (expiresInMs < 30_000) {
      await auth.doRefreshToken()
    }
  } catch {
    // Non-parseable token or refresh failure — let the connection attempt proceed.
    // If the token is truly invalid the WS upgrade will fail and trigger a normal reconnect.
  }
}
