import { onMounted, onUnmounted } from 'vue'

export function useGlowEffect() {
  // Batch mousemove updates using requestAnimationFrame so we update at most once per frame.
  let rafId: number | null = null
  let lastX = 0
  let lastY = 0
  let pending = false

  function updateGlow() {
    pending = false
    rafId = null
    const elements = document.querySelectorAll('.glow-effect')
    // If UI indicates active scrolling, skip glow updates to save frames
    if (typeof document !== 'undefined' && document.body && document.body.classList.contains('rms-scroll-active')) {
      return
    }
    if (!elements || elements.length === 0) return
    if (typeof window !== 'undefined') {
      try {
        const PERF = new URLSearchParams(window.location.search).has('perf')
        if (PERF) performance.mark('glow-rAF-run')
      } catch (e) {
        /* ignore */
      }
    }
    elements.forEach((el) => {
      const rect = (el as HTMLElement).getBoundingClientRect()
      const x = lastX - rect.left
      const y = lastY - rect.top
      ;(el as HTMLElement).style.setProperty('--x', `${x}px`)
      ;(el as HTMLElement).style.setProperty('--y', `${y}px`)
    })
  }

  function handleMouseMove(e: MouseEvent) {
    lastX = e.clientX
    lastY = e.clientY
    if (pending) return
    pending = true
    rafId = requestAnimationFrame(updateGlow)
  }

  onMounted(() => {
    document.addEventListener('mousemove', handleMouseMove, { passive: true })
  })

  onUnmounted(() => {
    document.removeEventListener('mousemove', handleMouseMove)
    if (rafId != null) cancelAnimationFrame(rafId)
  })

  return { handleMouseMove }
}
