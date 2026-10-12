import { gsap } from 'gsap'
import { ScrollTrigger } from 'gsap/ScrollTrigger'
import { onBeforeUnmount, onMounted, ref } from 'vue'

gsap.registerPlugin(ScrollTrigger)

// dev-only handle so motion can be inspected/stepped from devtools (stripped from production builds)
if (import.meta.env.DEV) (window as unknown as Record<string, unknown>).__lumenMotion = { gsap, ScrollTrigger }

export function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/**
 * Runs GSAP setup inside a context that is reverted on unmount (no leaked ScrollTriggers/tweens).
 * When the user prefers reduced motion, setup receives reduced=true and should show final states directly.
 */
export function useGsap(setup: (ctx: { reduced: boolean }) => void) {
  const reduced = ref(prefersReducedMotion())
  let ctx: gsap.Context | null = null
  onMounted(() => {
    ctx = gsap.context(() => setup({ reduced: reduced.value }))
  })
  onBeforeUnmount(() => ctx?.revert())
  return { reduced }
}

export { gsap, ScrollTrigger }
