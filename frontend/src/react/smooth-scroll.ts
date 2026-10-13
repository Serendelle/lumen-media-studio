import { useEffect } from 'react'
import Lenis from 'lenis'
import 'lenis/dist/lenis.css'

/**
 * Inertial page scrolling (Lenis) for the landing page only. Destroying it on unmount removes its classes
 * from <html>, so the Vue pages scroll natively. Lenis itself stands down under prefers-reduced-motion.
 */
export function useSmoothScroll() {
  useEffect(() => {
    const lenis = new Lenis({ autoRaf: true, lerp: 0.1 })

    // In-page anchors glide through Lenis instead of the browser's jump (Lenis' own `anchors` option
    // doesn't cancel the native jump, so the two would fight). scroll-margin-top is respected.
    const onClick = (e: MouseEvent) => {
      if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey) return
      const href = (e.target as Element | null)?.closest?.('a[href^="#"]')?.getAttribute('href')
      const target = href && href.length > 1 ? document.querySelector<HTMLElement>(href) : null
      if (!target) return
      e.preventDefault()
      lenis.scrollTo(target)
    }
    document.addEventListener('click', onClick)

    return () => {
      document.removeEventListener('click', onClick)
      lenis.destroy()
    }
  }, [])
}
