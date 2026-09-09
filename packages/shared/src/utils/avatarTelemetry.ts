import { reportTelemetryEvent } from './telemetry'

// Diagnostics for the intermittent avatar-fallback bug. Three distinct
// failure modes exist and this module distinguishes them:
//   1. avatar_img_error  — the <img> request actually failed (network layer,
//      e.g. gravatar unreachable from CN clients).
//   2. avatar_missing    — render-time data had no avatar_url at all (WS
//      vacuum window or a push round that dropped the field).
//   3. avatar_img_error with a data-missing prelude — cache stale.
// All events pass through the global telemetry rate budget, so they can never
// flood; sampling here is unnecessary because occurrences are rare.

function urlHost(url: string | undefined): string {
  if (!url) return 'none'
  try {
    return new URL(url).host
  } catch {
    return 'invalid'
  }
}

/** Report a failed avatar <img> load. */
export function reportAvatarImgError(
  where: string,
  url: string | undefined,
  userId: string | number | undefined
): void {
  reportTelemetryEvent('avatar_img_error', 'img load failed', {
    meta: { where, url_host: urlHost(url), user_id: userId }
  })
}

/** Report rendering a participant with no avatar_url in the data. */
export function reportAvatarMissing(
  where: string,
  userId: string | number | undefined,
  meta?: Record<string, unknown>
): void {
  reportTelemetryEvent('avatar_missing', 'no avatar_url in data', {
    meta: { where, user_id: userId, ...meta }
  })
}

/** Report a voice_users_update push where some users lack avatar_url. */
export function reportVoicePushMissingAvatar(
  channelId: number | string,
  missingIds: (string | number)[],
  total: number
): void {
  reportTelemetryEvent('voice_push_missing_avatar', 'push round missing avatar_url', {
    meta: {
      channel_id: channelId,
      missing: missingIds.slice(0, 10).map(String),
      missing_count: missingIds.length,
      total,
    }
  })
}
