import { useAuthStore } from '../stores/auth'
import { reportFetchError } from './requestIdFeedback'

/**
 * Fetch wrapper that adds Bearer token and retries once on 401 via
 * the auth store's session recovery flow. doRefreshToken() is internally
 * deduped, so concurrent 401s from both authFetch and axios share one
 * recovery attempt.
 *
 * `expectedStatuses` lists non-2xx statuses the caller handles itself as
 * business outcomes (e.g. 409 conflict): they skip the request-id error
 * card that would otherwise fire for unexpected failures.
 */
export async function authFetch(
  url: string,
  options: RequestInit & { expectedStatuses?: number[] } = {}
): Promise<Response> {
  const { expectedStatuses, ...init } = options
  const auth = useAuthStore()
  const headers = new Headers(init.headers)
  if (auth.token) {
    headers.set('Authorization', `Bearer ${auth.token}`)
  }

  let response = await fetch(url, { ...init, headers })

  if (response.status === 401 && auth.canRecoverSession()) {
    try {
      const newToken = await auth.doRefreshToken()
      headers.set('Authorization', `Bearer ${newToken}`)
      response = await fetch(url, { ...init, headers })
    } catch {
      auth.logout()
      throw new Error('Authentication failed')
    }
  }

  reportFetchError(response, expectedStatuses)

  return response
}
