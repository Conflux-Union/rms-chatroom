import type { Message } from '../types'
import { parseUTCDateTime } from './datetime'

// Discord-style consecutive message merging
const MESSAGE_GROUP_ADJACENT_THRESHOLD_MINUTES = 1
const MESSAGE_GROUP_TOTAL_THRESHOLD_MINUTES = 7

export function shouldGroupWithPrevious(messages: Message[], index: number): boolean {
  if (index === 0) return false

  const currentMsg = messages[index]
  const prevMsg = messages[index - 1]

  // Different user, don't group
  if (currentMsg.user_id !== prevMsg.user_id) return false

  // Previous message is deleted, don't group (keep visual separation)
  if (prevMsg.is_deleted) return false

  const currentTime = parseUTCDateTime(currentMsg.created_at).getTime()
  const prevTime = parseUTCDateTime(prevMsg.created_at).getTime()
  const diffMinutes = (currentTime - prevTime) / 1000 / 60

  // Adjacent messages must be within 1 minute
  if (diffMinutes > MESSAGE_GROUP_ADJACENT_THRESHOLD_MINUTES) return false

  // Find the first message in this group (walk backwards)
  let firstMsgIndex = index - 1
  while (firstMsgIndex > 0) {
    const msg = messages[firstMsgIndex]
    const prevMsgInChain = messages[firstMsgIndex - 1]

    // Different user breaks the chain
    if (msg.user_id !== prevMsgInChain.user_id) break
    // Deleted message breaks the chain
    if (prevMsgInChain.is_deleted) break

    const msgTime = parseUTCDateTime(msg.created_at).getTime()
    const prevMsgTime = parseUTCDateTime(prevMsgInChain.created_at).getTime()
    const chainDiff = (msgTime - prevMsgTime) / 1000 / 60

    // Gap > 1 minute breaks the chain
    if (chainDiff > MESSAGE_GROUP_ADJACENT_THRESHOLD_MINUTES) break

    firstMsgIndex--
  }

  // Check total time from first message in group
  const firstMsgTime = parseUTCDateTime(messages[firstMsgIndex].created_at).getTime()
  const totalDiffMinutes = (currentTime - firstMsgTime) / 1000 / 60

  return totalDiffMinutes <= MESSAGE_GROUP_TOTAL_THRESHOLD_MINUTES
}

// Get the latest edited_at timestamp from a message group (for header display)
export function getGroupLatestEditedAt(messages: Message[], index: number): string | undefined {
  // Find the first message in this group (the one with header)
  let firstMsgIndex = index
  while (firstMsgIndex > 0 && shouldGroupWithPrevious(messages, firstMsgIndex)) {
    firstMsgIndex--
  }

  // Find the last message in this group
  let lastMsgIndex = index
  while (lastMsgIndex < messages.length - 1 && shouldGroupWithPrevious(messages, lastMsgIndex + 1)) {
    lastMsgIndex++
  }

  // Find the latest edited_at in the group
  let latestEditedAt: string | undefined
  for (let i = firstMsgIndex; i <= lastMsgIndex; i++) {
    const msg = messages[i]
    if (msg.edited_at) {
      if (!latestEditedAt || new Date(msg.edited_at) > new Date(latestEditedAt)) {
        latestEditedAt = msg.edited_at
      }
    }
  }

  return latestEditedAt
}
