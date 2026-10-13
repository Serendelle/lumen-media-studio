/*
 * Interactive starfield (from a 21st.dev pricing section): twinkling dots that drift toward the pointer.
 * The pointer lives in motion values, not React state, so moving the mouse never re-renders the stars.
 */
import { useEffect, useState, type MouseEvent, type RefObject } from 'react'
import { motion, useMotionValue, useSpring, type MotionValue } from 'motion/react'

export type Pointer = { x: MotionValue<number | null>; y: MotionValue<number | null> }

/** Pointer position as motion values; spread `handlers` onto the element that should track the mouse. */
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

/** Fills its parent; `containerRef` must point at that parent (stars are placed in % of its box). */
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
