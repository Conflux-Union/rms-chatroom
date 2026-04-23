import { useAuthStore } from '../stores/auth'

/**
 * Fetch wrapper that adds Bearer token and retries once on 401 via
 * the auth store's session recovery flow. doRefreshToken() is internally
 * deduped, so concurrent 401s from both authFetch and axios share one
 * recovery attempt.
 */
export async function authFetch(url: string, options: RequestInit = {}): Promise<Response> {
  const auth = useAuthStore()
  const headers = new Headers(options.headers)
  if (auth.token) {
    headers.set('Authorization', `Bearer ${auth.token}`)
  }

  let response = await fetch(url, { ...options, headers })

  if (response.status === 401 && auth.canRecoverSession()) {
    try {
      const newToken = await auth.doRefreshToken()
      headers.set('Authorization', `Bearer ${newToken}`)
      response = await fetch(url, { ...options, headers })
    } catch {
      auth.logout()
      throw new Error('Authentication failed')
    }
  }

  return response
}
