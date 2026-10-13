import { useEffect, useRef } from 'react'
import { animate, motion, useInView, useReducedMotion } from 'motion/react'
import { AudioLines, Film, ShieldCheck, type LucideIcon } from 'lucide-react'

/*
 * Illustrative studio dashboard (a mock-up, not live data). File names match the demo media used in the
 * project's screenshots, so the story lines up with the live demo.
 */
const STATS = [
  { label: 'Tasks today', value: 24, format: (n: number) => String(Math.round(n)), delta: '+3' },
  { label: 'Space saved', value: 3.2, format: (n: number) => `${n.toFixed(1)} GB`, delta: '−87%' },
  { label: 'Verified', value: 100, format: (n: number) => `${Math.round(n)}%`, delta: '↑' },
]

const TASKS: { icon: LucideIcon; name: string; meta: string; result: string }[] = [
  { icon: Film, name: '西湖 散步.mp4', meta: '视频压缩 · 2 分钟前', result: '142 MB → 18 MB' },
  { icon: AudioLines, name: '旅行 vlog.mp4', meta: '音频提取 · 8 分钟前', result: 'MP3 · 192 kbps' },
  { icon: ShieldCheck, name: '西湖 日落.jpg', meta: '隐私清理 · 15 分钟前', result: 'GPS 已清除' },
]

const BARS = [34, 46, 40, 55, 49, 62, 58, 71, 66, 79, 74, 93]

/** Counts up from 0 the first time it scrolls into view; renders the final value otherwise. */
function CountUp({ value, format }: { value: number; format: (n: number) => string }) {
  const ref = useRef<HTMLSpanElement>(null)
  const inView = useInView(ref, { once: true, margin: '-60px' })
  const reduce = useReducedMotion()
  useEffect(() => {
    if (!inView || reduce || !ref.current) return
    const el = ref.current
    const controls = animate(0, value, {
      duration: 1.4,
      ease: [0.22, 1, 0.36, 1],
      onUpdate: (v) => (el.textContent = format(v)),
    })
    return () => controls.stop()
  }, [inView, reduce, value, format])
  return <span ref={ref}>{format(value)}</span>
}

export default function Dashboard() {
  return (
    <div className="overflow-hidden rounded-2xl border border-white/10 bg-[#141414] shadow-[0_40px_120px_-40px_rgba(255,255,255,0.12)]">
      {/* window chrome */}
      <div className="relative flex h-11 items-center border-b border-white/10 bg-black/40 px-4">
        <div className="flex gap-2">
          <span className="size-3 rounded-full bg-[#ff5f57]" />
          <span className="size-3 rounded-full bg-[#febc2e]" />
          <span className="size-3 rounded-full bg-[#28c840]" />
        </div>
        <span className="text-muted-foreground absolute left-1/2 -translate-x-1/2 text-xs font-medium">Lumen — Studio</span>
      </div>

      <div className="space-y-6 p-5 md:p-6">
        <div className="grid grid-cols-3 gap-3">
          {STATS.map((s) => (
            <div key={s.label} className="rounded-xl border border-white/10 bg-black/60 p-3 md:p-4">
              <p className="text-muted-foreground text-[10px] font-medium tracking-[0.12em] uppercase md:text-xs">{s.label}</p>
              <p className="text-foreground mt-2 text-xl font-semibold tabular-nums md:text-3xl">
                <CountUp value={s.value} format={s.format} />
              </p>
              <p className="mt-1 text-xs font-medium text-[#3ddc84]">{s.delta}</p>
            </div>
          ))}
        </div>

        <div>
          <p className="text-foreground mb-3 text-sm font-semibold">Recent tasks</p>
          <ul className="space-y-2.5">
            {TASKS.map((t, i) => (
              <motion.li
                key={t.name}
                initial={{ opacity: 0, x: -16 }}
                whileInView={{ opacity: 1, x: 0 }}
                viewport={{ once: true, margin: '-40px' }}
                transition={{ duration: 0.5, delay: 0.15 + i * 0.1, ease: [0.22, 1, 0.36, 1] }}
                className="flex items-center gap-3 rounded-xl border border-white/10 bg-black/60 p-3"
              >
                <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-white/[0.07]">
                  <t.icon aria-hidden="true" className="text-muted-foreground size-4" />
                </span>
                <span className="min-w-0 flex-1">
                  <span className="text-foreground block truncate text-sm font-medium">{t.name}</span>
                  <span className="text-muted-foreground block text-xs">{t.meta}</span>
                </span>
                <span className="text-muted-foreground shrink-0 rounded-full bg-white/[0.07] px-2.5 py-1 text-xs whitespace-nowrap">
                  {t.result}
                </span>
              </motion.li>
            ))}
          </ul>
        </div>

        <div>
          <p className="text-foreground mb-3 text-sm font-semibold">Monthly stats</p>
          <div className="flex h-24 items-end gap-1.5 rounded-xl border border-white/10 bg-black/60 p-3">
            {BARS.map((h, i) => (
              <motion.span
                key={i}
                initial={{ scaleY: 0 }}
                whileInView={{ scaleY: 1 }}
                viewport={{ once: true }}
                transition={{ duration: 0.6, delay: 0.3 + i * 0.04, ease: [0.22, 1, 0.36, 1] }}
                style={{ height: `${h}%`, transformOrigin: 'bottom' }}
                className={i === BARS.length - 1 ? 'flex-1 rounded-sm bg-white' : 'flex-1 rounded-sm bg-white/20'}
              />
            ))}
          </div>
        </div>
      </div>
    </div>
  )
}
