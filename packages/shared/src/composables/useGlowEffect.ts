import { onMounted, onUnmounted } from 'vue'

export function useGlowEffect() {
  function handleMouseMove(e: MouseEvent) {
    const elements = document.querySelectorAll('.glow-effect')
    elements.forEach((el) => {
      const rect = (el as HTMLElement).getBoundingClientRect()
      const x = e.clientX - rect.left
      const y = e.clientY - rect.top
      ;(el as HTMLElement).style.setProperty('--x', `${x}px`)
      ;(el as HTMLElement).style.setProperty('--y', `${y}px`)
    })
  }

  onMounted(() => {
    document.addEventListener('mousemove', handleMouseMove)
  })

  onUnmounted(() => {
    document.removeEventListener('mousemove', handleMouseMove)
  })

  return { handleMouseMove }
}
