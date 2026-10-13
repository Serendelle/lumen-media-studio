/*
 * Adapted from a 21st.dev pricing section:
 *  - next/link → the landing bridge's <Link> (vue-router), framer-motion → motion/react (same API)
 *  - the starfield lives in ./starfield and is shared by the whole landing page
 *  - prices count up with NumberFlow when first seen, and re-flow on the monthly/annual toggle
 */
import { createContext, useContext, useEffect, useRef, useState, type CSSProperties } from 'react'
import { motion, useInView } from 'motion/react'
import NumberFlow from '@number-flow/react'
import confetti from 'canvas-confetti'
import { Check, Star as StarIcon } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Link } from '@/react/bridge'

export interface PricingPlan {
  name: string
  monthlyPrice: number
  yearlyPrice: number
  description: string
  /** small line under the price, per billing period */
  monthlyNote: string
  yearlyNote: string
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

export const BillingContext = createContext<{ isMonthly: boolean; setIsMonthly: (v: boolean) => void }>({
  isMonthly: true,
  setIsMonthly: () => {},
})

/** Monthly / annual switch with a sliding pill; switching to annual fires a burst of confetti. */
export function BillingToggle({ monthlyLabel = 'Monthly', yearlyLabel = 'Annual', yearlyBadge }: { monthlyLabel?: string; yearlyLabel?: string; yearlyBadge?: string }) {
  const { isMonthly, setIsMonthly } = useContext(BillingContext)
  const monthlyRef = useRef<HTMLButtonElement>(null)
  const yearlyRef = useRef<HTMLButtonElement>(null)
  const [pill, setPill] = useState<CSSProperties>({})

  useEffect(() => {
    const btn = (isMonthly ? monthlyRef : yearlyRef).current
    if (btn) setPill({ width: btn.offsetWidth, transform: `translateX(${btn.offsetLeft}px)` })
  }, [isMonthly])

  const choose = (monthly: boolean) => {
    if (isMonthly === monthly) return
    setIsMonthly(monthly)
    const rect = yearlyRef.current?.getBoundingClientRect()
    if (monthly || !rect) return
    void confetti({
      particleCount: 80,
      spread: 80,
      origin: { x: (rect.left + rect.width / 2) / innerWidth, y: (rect.top + rect.height / 2) / innerHeight },
      colors: ['#ffffff', '#d4d4d4', '#737373'],
      ticks: 300,
      gravity: 1.2,
      decay: 0.94,
      startVelocity: 30,
      disableForReducedMotion: true,
    })
  }

  const btn = (on: boolean) =>
    cn('relative z-10 rounded-full px-4 py-2 text-sm font-medium transition-colors sm:px-6', on ? 'text-primary-foreground' : 'text-muted-foreground hover:text-foreground')

  return (
    <div className="flex justify-center">
      <div role="group" aria-label="计费周期" className="bg-muted relative flex w-fit items-center rounded-full p-1">
        <div className="bg-primary absolute top-0 left-0 h-full rounded-full transition-[transform,width] duration-300 ease-out" style={pill} />
        <button ref={monthlyRef} type="button" aria-pressed={isMonthly} onClick={() => choose(true)} className={btn(isMonthly)}>
          {monthlyLabel}
        </button>
        <button ref={yearlyRef} type="button" aria-pressed={!isMonthly} onClick={() => choose(false)} className={btn(!isMonthly)}>
          {yearlyLabel}
          {yearlyBadge && <span className={cn('hidden sm:inline', !isMonthly && 'text-primary-foreground/70')}> {yearlyBadge}</span>}
        </button>
      </div>
    </div>
  )
}

export function PricingCard({ plan, index, popularLabel = 'Most Popular' }: { plan: PricingPlan; index: number; popularLabel?: string }) {
  const { isMonthly } = useContext(BillingContext)
  const isDesktop = useMediaQuery('(min-width: 768px)')
  const ref = useRef<HTMLDivElement>(null)
  const inView = useInView(ref, { once: true, margin: '-80px' })
  const price = isMonthly ? plan.monthlyPrice : plan.yearlyPrice

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
            {/* counts up from 0 the first time the card is seen, then re-flows on the billing toggle */}
            <NumberFlow
              value={inView ? price : 0}
              locales="zh-CN"
              format={{ style: 'currency', currency: 'CNY', minimumFractionDigits: 0, maximumFractionDigits: 2 }}
              className="tabular-nums"
            />
          </span>
          <span className="text-muted-foreground text-sm leading-6 font-semibold tracking-wide">/ {isMonthly ? '月' : '年'}</span>
        </div>
        <p className="text-muted-foreground mt-2 text-xs">{isMonthly ? plan.monthlyNote : plan.yearlyNote}</p>

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
