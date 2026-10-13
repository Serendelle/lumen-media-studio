/*
 * Adapted from a 21st.dev pricing section:
 *  - next/link → the landing bridge's <Link> (vue-router), framer-motion → motion/react (same API)
 *  - starfield follows the pointer through motion values instead of React state, so moving the mouse
 *    no longer re-renders 150 stars per event
 *  - no monthly/annual toggle (single price per plan); the price counts up with NumberFlow on view
 */
import { useEffect, useRef, useState, type MouseEvent, type RefObject } from 'react'
import { motion, useInView, useMotionValue, useSpring, type MotionValue } from 'motion/react'
import NumberFlow from '@number-flow/react'
import { Check, Star as StarIcon } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Link } from '@/react/bridge'

export interface PricingPlan {
  name: string
  price: number
  /** e.g. "月" → rendered as "/ 月" */
  period: string
  description: string
  features: string[]
  buttonText: string
  href: string
  isPopular?: boolean
}

export function useMediaQuery(query: string) {
  const [value, setValue] = useState(false)
  useEffect(() => {
    const result = matchMedia(query)
    const onChange = (e: MediaQueryListEvent) => setValue(e.matches)
    result.addEventListener('change', onChange)
    setValue(result.matches)
    return () => result.removeEventListener('change', onChange)
  }, [query])
  return value
}

// --- interactive starfield ---

type Pointer = { x: MotionValue<number | null>; y: MotionValue<number | null> }

function Star({ pointer, containerRef }: { pointer: Pointer; containerRef: RefObject<HTMLElement | null> }) {
  // fixed per star for its lifetime
  const [seed] = useState(() => ({
    top: Math.random() * 100,
    left: Math.random() * 100,
    size: 1 + Math.random() * 2,
    duration: 2 + Math.random() * 3,
    delay: Math.random() * 5,
  }))
  const spring = { stiffness: 100, damping: 15, mass: 0.1 }
  const springX = useSpring(0, spring)
  const springY = useSpring(0, spring)

  useEffect(() => {
    const update = () => {
      const px = pointer.x.get()
      const py = pointer.y.get()
      const box = containerRef.current?.getBoundingClientRect()
      if (!box || px === null || py === null) {
        springX.set(0)
        springY.set(0)
        return
      }
      const dx = px - (box.left + (seed.left / 100) * box.width)
      const dy = py - (box.top + (seed.top / 100) * box.height)
      const distance = Math.hypot(dx, dy)
      const radius = 600 // magnetic influence
      const force = distance < radius ? 1 - distance / radius : 0
      springX.set(dx * force * 0.5)
      springY.set(dy * force * 0.5)
    }
    const offX = pointer.x.on('change', update)
    const offY = pointer.y.on('change', update)
    return () => {
      offX()
      offY()
    }
  }, [pointer, containerRef, seed, springX, springY])

  return (
    <motion.div
      className="bg-foreground absolute rounded-full"
      style={{ top: `${seed.top}%`, left: `${seed.left}%`, width: seed.size, height: seed.size, x: springX, y: springY }}
      initial={{ opacity: 0 }}
      animate={{ opacity: [0, 1, 0] }}
      transition={{ duration: seed.duration, repeat: Infinity, delay: seed.delay }}
    />
  )
}

export function InteractiveStarfield({
  pointer,
  containerRef,
  count = 150,
}: {
  pointer: Pointer
  containerRef: RefObject<HTMLElement | null>
  count?: number
}) {
  return (
    <div aria-hidden="true" className="pointer-events-none absolute inset-0 h-full w-full overflow-hidden">
      {Array.from({ length: count }, (_, i) => (
        <Star key={i} pointer={pointer} containerRef={containerRef} />
      ))}
    </div>
  )
}

/** Pointer position as motion values; spread `handlers` onto the starfield's container. */
export function usePointer() {
  const x = useMotionValue<number | null>(null)
  const y = useMotionValue<number | null>(null)
  const handlers = {
    onMouseMove: (e: MouseEvent) => {
      x.set(e.clientX)
      y.set(e.clientY)
    },
    onMouseLeave: () => {
      x.set(null)
      y.set(null)
    },
  }
  return { pointer: { x, y }, handlers }
}

// --- pricing card ---

export function PricingCard({ plan, index, popularLabel = 'Most Popular' }: { plan: PricingPlan; index: number; popularLabel?: string }) {
  const isDesktop = useMediaQuery('(min-width: 768px)')
  const ref = useRef<HTMLDivElement>(null)
  const inView = useInView(ref, { once: true, margin: '-80px' })

  return (
    <motion.div
      ref={ref}
      initial={{ y: 50, opacity: 0 }}
      whileInView={{ y: plan.isPopular && isDesktop ? -20 : 0, opacity: 1 }}
      viewport={{ once: true }}
      transition={{ duration: 0.6, type: 'spring', stiffness: 100, damping: 20, delay: index * 0.15 }}
      className={cn(
        'bg-background/70 relative flex flex-col rounded-2xl p-8 backdrop-blur-sm',
        plan.isPopular ? 'border-primary border-2 shadow-[0_0_80px_-20px_rgba(255,255,255,0.25)]' : 'border-border border',
      )}
    >
      {plan.isPopular && (
        <div className="absolute top-0 left-1/2 -translate-x-1/2 -translate-y-1/2">
          <div className="bg-primary flex items-center gap-1.5 rounded-full px-4 py-1.5">
            <StarIcon className="text-primary-foreground size-4 fill-current" />
            <span className="text-primary-foreground text-sm font-semibold whitespace-nowrap">{popularLabel}</span>
          </div>
        </div>
      )}
      <div className="flex flex-1 flex-col text-center">
        <h3 className="text-foreground text-xl font-semibold">{plan.name}</h3>
        <p className="text-muted-foreground mt-2 text-sm">{plan.description}</p>
        <div className="mt-6 flex items-baseline justify-center gap-x-1">
          <span className="text-foreground text-5xl font-bold tracking-tight">
            {/* counts up from 0 the first time the card is seen */}
            <NumberFlow
              value={inView ? plan.price : 0}
              locales="zh-CN"
              format={{ style: 'currency', currency: 'CNY', minimumFractionDigits: 0, maximumFractionDigits: 2 }}
              className="tabular-nums"
            />
          </span>
          <span className="text-muted-foreground text-sm leading-6 font-semibold tracking-wide">/ {plan.period}</span>
        </div>

        <ul role="list" className="text-muted-foreground mt-8 space-y-3 text-left text-sm leading-6">
          {plan.features.map((feature) => (
            <li key={feature} className="flex gap-x-3">
              <Check className="text-primary h-6 w-5 flex-none" aria-hidden="true" />
              {feature}
            </li>
          ))}
        </ul>

        <div className="mt-auto pt-8">
          <Link
            to={plan.href}
            className={cn(
              'inline-flex h-11 w-full items-center justify-center rounded-full px-8 text-sm font-medium whitespace-nowrap transition-colors',
              plan.isPopular
                ? 'bg-primary text-primary-foreground hover:bg-primary/90'
                : 'border-input bg-background hover:bg-accent hover:text-accent-foreground border',
            )}
          >
            {plan.buttonText}
          </Link>
        </div>
      </div>
    </motion.div>
  )
}
