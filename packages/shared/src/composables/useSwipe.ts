import { ref, onMounted, onUnmounted, type Ref } from 'vue'

interface SwipeOptions {
  threshold?: number
}

interface SwipeResult {
  onSwipeLeft: (callback: () => void) => void
  onSwipeRight: (callback: () => void) => void
}

export function useSwipe(
  targetRef: Ref<HTMLElement | null>,
  options: SwipeOptions = {}
): SwipeResult {
  const { threshold = 50 } = options

  const startX = ref(0)
  const startY = ref(0)

  let swipeLeftCallback: (() => void) | null = null
  let swipeRightCallback: (() => void) | null = null

  function handleTouchStart(e: TouchEvent) {
    const touch = e.touches[0]
    if (!touch) return
    startX.value = touch.clientX
    startY.value = touch.clientY
  }

  function handleTouchEnd(e: TouchEvent) {
    const touch = e.changedTouches[0]
    if (!touch) return
    const deltaX = touch.clientX - startX.value
    const deltaY = touch.clientY - startY.value

    // Ignore if vertical movement is greater than horizontal
    if (Math.abs(deltaY) > Math.abs(deltaX)) return

    if (deltaX > threshold && swipeRightCallback) {
      swipeRightCallback()
    } else if (deltaX < -threshold && swipeLeftCallback) {
      swipeLeftCallback()
    }
  }

  onMounted(() => {
    const el = targetRef.value || document.body
    el.addEventListener('touchstart', handleTouchStart, { passive: true })
    el.addEventListener('touchend', handleTouchEnd, { passive: true })
  })

  onUnmounted(() => {
    const el = targetRef.value || document.body
    el.removeEventListener('touchstart', handleTouchStart)
    el.removeEventListener('touchend', handleTouchEnd)
  })

  return {
    onSwipeLeft: (callback: () => void) => {
      swipeLeftCallback = callback
    },
    onSwipeRight: (callback: () => void) => {
      swipeRightCallback = callback
    }
  }
}
