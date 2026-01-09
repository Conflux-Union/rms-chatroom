import { ref } from 'vue'

const STORAGE_KEY = 'rms-discord-read-positions'

interface ReadPositions {
  [channelId: number]: {
    messageId: number
    timestamp: number
  }
}

function getStoredPositions(): ReadPositions {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    return stored ? JSON.parse(stored) : {}
  } catch {
    return {}
  }
}

function savePositions(positions: ReadPositions) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(positions))
  } catch {
    // localStorage might be full or disabled
  }
}

export function useReadPosition() {
  const lastReadMessageId = ref<number | null>(null)
  const showContinueReading = ref(false)

  function saveReadPosition(channelId: number, messageId: number) {
    const positions = getStoredPositions()
    positions[channelId] = {
      messageId,
      timestamp: Date.now(),
    }
    savePositions(positions)
  }

  function getReadPosition(channelId: number): number | null {
    const positions = getStoredPositions()
    return positions[channelId]?.messageId ?? null
  }

  function clearReadPosition(channelId: number) {
    const positions = getStoredPositions()
    delete positions[channelId]
    savePositions(positions)
  }

  function initForChannel(channelId: number, latestMessageId: number | null) {
    const savedId = getReadPosition(channelId)

    // Show continue reading button if:
    // 1. We have a saved position
    // 2. The saved position is different from the latest message
    if (savedId && latestMessageId && savedId < latestMessageId) {
      lastReadMessageId.value = savedId
      showContinueReading.value = true
    } else {
      lastReadMessageId.value = null
      showContinueReading.value = false
    }
  }

  function dismissContinueReading() {
    showContinueReading.value = false
  }

  return {
    lastReadMessageId,
    showContinueReading,
    saveReadPosition,
    getReadPosition,
    clearReadPosition,
    initForChannel,
    dismissContinueReading,
  }
}
