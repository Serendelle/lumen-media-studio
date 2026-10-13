import { Fragment, useRef, type CSSProperties, type PointerEvent, type ReactNode } from 'react'
import { motion, useReducedMotion, useScroll, useTransform } from 'motion/react'
import { cn } from '@/lib/utils'

const clamp01 = (n: number) => Math.min(1, Math.max(0, n))

/**
 * Full-width page band. Thin rails frame the content column and a fading hairline marks the top edge.
 * overflow-x-clip lets cards slide in from the sides without causing horizontal scroll.
 */
export function Section({ id, className, children }: { id?: string; className?: string; children: ReactNode }) {
  return (
    <section id={id} className={cn('relative scroll-mt-16 overflow-x-clip px-6 py-24 md:px-8 md:py-36', className)}>
      <div aria-hidden="true" className="pointer-events-none absolute inset-0 hidden px-8 md:block">
        <div className="mx-auto h-full max-w-6xl border-x border-white/[0.06]" />
      </div>
      <div aria-hidden="true" className="absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-white/15 to-transparent" />
      <div className="relative mx-auto w-full max-w-6xl">{children}</div>
    </section>
  )
}

const EASE = [0.22, 1, 0.36, 1] as const

// Word-by-word entrance, played once when the heading enters view. Time-based on purpose: a scroll-scrubbed
// version left headings half-blurred whenever the reader stopped scrolling mid-way.
const headingVariants = { hidden: {}, shown: { transition: { staggerChildren: 0.07 } } }
const wordVariants = {
  hidden: { opacity: 0, y: '0.35em', filter: 'blur(6px)' },
  shown: { opacity: 1, y: 0, filter: 'blur(0px)', transition: { duration: 0.8, ease: EASE } },
}

/**
 * Eyebrow + title + lede. The title rises in word by word once it enters view; a soft white spotlight
 * behind it blooms with scroll.
 */
export function SectionHeading({
  eyebrow,
  title,
  lede,
  align = 'center',
}: {
  eyebrow: string
  title: string
  lede?: ReactNode
  align?: 'center' | 'left'
}) {
  const ref = useRef<HTMLDivElement>(null)
  const reduce = useReducedMotion()
  const { scrollYProgress } = useScroll({ target: ref, offset: ['start 0.95', 'start 0.35'] })
  const glow = useTransform(scrollYProgress, (p) => p)
  const words = title.split(' ')

  return (
    <div
      ref={ref}
      className={cn('relative isolate mb-14 flex flex-col md:mb-20', align === 'center' ? 'items-center text-center' : 'items-start')}
    >
      <motion.div
        aria-hidden="true"
        style={reduce ? undefined : { opacity: glow }}
        className="pointer-events-none absolute top-1/2 left-1/2 -z-10 h-[360px] w-[min(820px,120vw)] -translate-x-1/2 -translate-y-1/2 bg-[radial-gradient(closest-side,rgba(255,255,255,0.09),transparent)]"
      />
      <p className="text-muted-foreground mb-4 text-xs font-medium tracking-[0.18em] uppercase">{eyebrow}</p>
      <motion.h2
        aria-label={title}
        variants={headingVariants}
        initial={reduce ? false : 'hidden'}
        whileInView="shown"
        viewport={{ once: true, margin: '0px 0px -15% 0px' }}
        className="text-foreground max-w-3xl text-4xl font-semibold tracking-tight text-balance md:text-6xl"
      >
        {words.map((w, i) => (
          // plain spaces between inline-block words keep text-balance working
          <Fragment key={i}>
            {i > 0 && ' '}
            <motion.span aria-hidden="true" variants={wordVariants} className="inline-block">
              {w}
            </motion.span>
          </Fragment>
        ))}
      </motion.h2>
      {lede && (
        <motion.p
          initial={reduce ? false : { opacity: 0, y: 12 }}
          whileInView={{ opacity: 1, y: 0 }}
          viewport={{ once: true, margin: '0px 0px -15% 0px' }}
          transition={{ duration: 0.8, delay: 0.07 * words.length + 0.1, ease: EASE }}
          className="text-muted-foreground mt-5 max-w-2xl text-base text-pretty md:text-lg"
        >
          {lede}
        </motion.p>
      )}
    </div>
  )
}

/** Fades content up once as it scrolls into view. */
export function Reveal({ className, delay = 0, children }: { className?: string; delay?: number; children: ReactNode }) {
  return (
    <motion.div
      className={className}
      initial={{ opacity: 0, y: 24 }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: true, margin: '-80px' }}
      transition={{ duration: 0.7, delay, ease: [0.22, 1, 0.36, 1] }}
    >
      {children}
    </motion.div>
  )
}

interface RiseFrom {
  x?: number
  y?: number
  scale?: number
  rotate?: number
  rotateX?: number
}

/**
 * Scroll-linked entrance: the child travels from `from` to rest while its top moves from the bottom of
 * the viewport to `end` (fraction of viewport height). Unlike Reveal it tracks the scroll both ways.
 * Function transforms throughout — motion's native ScrollTimeline path mis-clamps opacity ranges.
 */
export function ScrollRise({
  children,
  className,
  from = {},
  end = 0.55,
}: {
  children: ReactNode
  className?: string
  from?: RiseFrom
  end?: number
}) {
  const ref = useRef<HTMLDivElement>(null)
  const reduce = useReducedMotion()
  const { scrollYProgress } = useScroll({ target: ref, offset: ['start end', `start ${end}`] })
  const { x = 0, y = 80, scale = 0.94, rotate = 0, rotateX = 0 } = from
  const toRest = (start: number) => (p: number) => start * (1 - p)
  const style = {
    x: useTransform(scrollYProgress, toRest(x)),
    y: useTransform(scrollYProgress, toRest(y)),
    rotate: useTransform(scrollYProgress, toRest(rotate)),
    rotateX: useTransform(scrollYProgress, toRest(rotateX)),
    scale: useTransform(scrollYProgress, (p) => scale + (1 - scale) * p),
    opacity: useTransform(scrollYProgress, (p) => clamp01(p * 1.6)),
  }
  return (
    <div ref={ref} className={cn('relative', className)}>
      <motion.div style={reduce ? undefined : style} className="h-full">
        {children}
      </motion.div>
    </div>
  )
}

/** Card with a soft white light that follows the pointer, plus a brightened rim under it. */
export function SpotlightCard({ className, children }: { className?: string; children: ReactNode }) {
  const onMove = (e: PointerEvent<HTMLDivElement>) => {
    const r = e.currentTarget.getBoundingClientRect()
    e.currentTarget.style.setProperty('--x', `${e.clientX - r.left}px`)
    e.currentTarget.style.setProperty('--y', `${e.clientY - r.top}px`)
  }
  const light = 'radial-gradient(380px circle at var(--x) var(--y), rgba(255,255,255,0.09), transparent 60%)'
  const rim = 'radial-gradient(260px circle at var(--x) var(--y), rgba(255,255,255,0.45), transparent 60%)'
  return (
    <div
      onPointerMove={onMove}
      style={{ '--x': '50%', '--y': '0%' } as CSSProperties}
      className={cn('group/spot bg-card relative h-full overflow-hidden rounded-2xl border border-white/10', className)}
    >
      <div
        aria-hidden="true"
        style={{ background: light }}
        className="pointer-events-none absolute inset-0 opacity-0 transition-opacity duration-300 group-hover/spot:opacity-100"
      />
      {/* rim: the same light, masked to a 1px ring */}
      <div
        aria-hidden="true"
        style={{
          background: rim,
          padding: 1,
          mask: 'linear-gradient(#000 0 0) content-box exclude, linear-gradient(#000 0 0)',
          WebkitMask: 'linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0)',
          WebkitMaskComposite: 'xor',
        }}
        className="pointer-events-none absolute inset-0 rounded-2xl opacity-0 transition-opacity duration-300 group-hover/spot:opacity-100"
      />
      <div className="relative h-full">{children}</div>
    </div>
  )
}

/**
 * Visual placeholder — marks where a screenshot, video or 21st.dev component goes.
 * Search the codebase for <Placeholder to find every slot still to fill.
 */
export function Placeholder({ label, hint, className }: { label: string; hint?: string; className?: string }) {
  return (
    <div
      className={cn(
        'bg-card relative flex items-center justify-center overflow-hidden rounded-2xl border border-dashed border-white/15',
        'bg-[linear-gradient(135deg,rgba(255,255,255,0.03)_25%,transparent_25%,transparent_50%,rgba(255,255,255,0.03)_50%,rgba(255,255,255,0.03)_75%,transparent_75%)] bg-[length:24px_24px]',
        className,
      )}
    >
      <div className="px-6 text-center">
        <p className="text-foreground/80 text-sm font-medium">{label}</p>
        {hint && <p className="text-muted-foreground mt-1 text-xs">{hint}</p>}
      </div>
    </div>
  )
}
