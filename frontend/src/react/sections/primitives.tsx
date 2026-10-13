import type { ReactNode } from 'react'
import { motion } from 'motion/react'
import { cn } from '@/lib/utils'

/** Full-width page band with consistent vertical rhythm; `id` is the nav anchor. */
export function Section({ id, className, children }: { id?: string; className?: string; children: ReactNode }) {
  return (
    <section id={id} className={cn('border-border scroll-mt-16 border-t px-6 py-24 md:px-8 md:py-36', className)}>
      <div className="mx-auto w-full max-w-6xl">{children}</div>
    </section>
  )
}

/** Eyebrow + big title + lede, the heading block every section opens with. */
export function SectionHeading({
  eyebrow,
  title,
  lede,
  align = 'center',
}: {
  eyebrow: string
  title: ReactNode
  lede?: ReactNode
  align?: 'center' | 'left'
}) {
  return (
    <Reveal className={cn('mb-14 flex flex-col md:mb-20', align === 'center' ? 'items-center text-center' : 'items-start')}>
      <p className="text-muted-foreground mb-4 text-xs font-medium tracking-[0.18em] uppercase">{eyebrow}</p>
      <h2 className="text-foreground max-w-3xl text-4xl font-semibold tracking-tight text-balance md:text-6xl">{title}</h2>
      {lede && <p className="text-muted-foreground mt-5 max-w-2xl text-base text-pretty md:text-lg">{lede}</p>}
    </Reveal>
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
