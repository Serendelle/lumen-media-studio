import { useEffect, useRef } from 'react'
import { animate, motion, useInView, useReducedMotion } from 'motion/react'
import { Film, ShieldAlert, Timer, type LucideIcon } from 'lucide-react'

/*
 * Measured results, every figure taken from docs/test-report.md (real runs against real tools and the real
 * DeepSeek model). Keep it that way: no number on this panel that the report can't back up.
 */
const STATS = [
  { label: 'Compressed', value: 1.96, format: (n: number) => `${n.toFixed(2)} MB`, note: '目标 ≤ 2 MB ✓' },
  { label: 'Test pass rate', value: 100, format: (n: number) => `${Math.round(n)}%`, note: '73 / 73 已执行' },
  { label: 'Checks passed', value: 9, format: (n: number) => `${Math.round(n)}/9`, note: '核验项全部通过' },
]

const RUNS: { icon: LucideIcon; name: string; meta: string; result: string }[] = [
  { icon: Film, name: '智能模式 · 完整流程', meta: '压缩到 2MB 以内 + 提取 MP3 + 清除位置', result: '4.42 MB → 1.96 MB' },
  { icon: Timer, name: '规划 + 执行 + 审核', meta: '5 次模型调用，真实处理', result: '约 30 秒' },
  { icon: ShieldAlert, name: '提示注入测试', meta: '恶意指令藏在文件名里', result: '0 次越权操作' },
]

// automated test pass rate across the three fix-and-rerun rounds
const ROUNDS = [
  { label: '第 1 轮', value: 89.8 },
  { label: '第 2 轮', value: 98.3 },
  { label: '第 3 轮', value: 100 },
]

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
        <span className="text-muted-foreground absolute left-1/2 -translate-x-1/2 text-xs font-medium">Lumen — Benchmarks</span>
      </div>

      <div className="space-y-6 p-5 md:p-6">
        <div className="grid grid-cols-3 gap-3">
          {STATS.map((s) => (
            <div key={s.label} className="rounded-xl border border-white/10 bg-black/60 p-3 md:p-4">
              <p className="text-muted-foreground text-[10px] font-medium tracking-[0.12em] uppercase md:text-xs">{s.label}</p>
              <p className="text-foreground mt-2 text-xl font-semibold tabular-nums md:text-3xl">
                <CountUp value={s.value} format={s.format} />
              </p>
              <p className="mt-1 text-xs font-medium text-[#3ddc84]">{s.note}</p>
            </div>
          ))}
        </div>

        <div>
          <p className="text-foreground mb-3 text-sm font-semibold">Real runs</p>
          <ul className="space-y-2.5">
            {RUNS.map((t, i) => (
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
                  <span className="text-muted-foreground block truncate text-xs">{t.meta}</span>
                </span>
                <span className="text-foreground shrink-0 rounded-full bg-white/[0.07] px-2.5 py-1 text-xs font-medium whitespace-nowrap tabular-nums">
                  {t.result}
                </span>
              </motion.li>
            ))}
          </ul>
        </div>

        <div>
          <p className="text-foreground mb-3 text-sm font-semibold">Test pass rate · 3 rounds</p>
          <div className="space-y-3 rounded-xl border border-white/10 bg-black/60 p-4">
            {ROUNDS.map((r, i) => (
              <div key={r.label} className="flex items-center gap-3 text-xs">
                <span className="text-muted-foreground w-12 shrink-0">{r.label}</span>
                <div className="h-2 flex-1 overflow-hidden rounded-full bg-white/[0.07]">
                  <motion.div
                    initial={{ scaleX: 0 }}
                    whileInView={{ scaleX: 1 }}
                    viewport={{ once: true }}
                    transition={{ duration: 0.9, delay: 0.3 + i * 0.15, ease: [0.22, 1, 0.36, 1] }}
                    style={{ width: `${r.value}%` }}
                    className={'h-full origin-left rounded-full ' + (i === ROUNDS.length - 1 ? 'bg-white' : 'bg-white/30')}
                  />
                </div>
                <span className="text-foreground w-12 shrink-0 text-right tabular-nums">{r.value}%</span>
              </div>
            ))}
          </div>
          <p className="text-muted-foreground/70 mt-3 text-[11px]">数据来自项目测试报告中的真实执行记录。</p>
        </div>
      </div>
    </div>
  )
}
