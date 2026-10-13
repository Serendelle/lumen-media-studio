import { useRef } from 'react'
import { motion, useReducedMotion, useScroll, useTransform, type MotionValue } from 'motion/react'
import { Section, SectionHeading } from './primitives'

const STEPS = [
  { n: '01', who: '需求理解官', what: '读懂你的一句话，识别文件与目标。' },
  { n: '02', who: '任务规划官', what: '拆解成可执行的步骤，评估可行性。' },
  { n: '03', who: '你', what: '查看计划，确认后才会开始执行。', you: true },
  { n: '04', who: '处理执行官', what: '调用真实引擎逐步处理，进度实时可见。' },
  { n: '05', who: '质量审核官', what: '独立核验结果是否达成目标。' },
]

const clamp01 = (n: number) => Math.min(1, Math.max(0, n))

/** A step that lights up once the scroll-driven progress line reaches it. */
function Step({ step, index, progress, reduce }: { step: (typeof STEPS)[number]; index: number; progress: MotionValue<number>; reduce: boolean | null }) {
  const lit = useTransform(progress, (p) => clamp01((p - index / STEPS.length) * STEPS.length))
  const opacity = useTransform(lit, (v) => 0.25 + 0.75 * v)
  const y = useTransform(lit, (v) => (1 - v) * 24)
  return (
    <li className={step.you ? 'bg-secondary' : 'bg-card'}>
      <motion.div style={reduce ? undefined : { opacity, y }} className="flex h-full flex-col p-6 md:min-h-56">
        <span className="text-muted-foreground font-mono text-xs">{step.n}</span>
        <h3 className="text-foreground mt-8 text-lg font-semibold tracking-tight">{step.who}</h3>
        <p className="text-muted-foreground mt-2 text-sm leading-relaxed">{step.what}</p>
      </motion.div>
    </li>
  )
}

/** Smart-mode pipeline: four agents with the user's confirmation in the middle, lit in order by scroll. */
export default function Workflow() {
  const ref = useRef<HTMLDivElement>(null)
  const reduce = useReducedMotion()
  const { scrollYProgress } = useScroll({ target: ref, offset: ['start 0.85', 'end 0.55'] })
  const scaleX = useTransform(scrollYProgress, (p) => p)

  return (
    <Section id="workflow">
      <SectionHeading
        eyebrow="Smart mode"
        title="Four agents. You stay in charge."
        lede="智能模式不会擅自动手：每一步都摊开给你看，确认之后才执行。"
      />
      <div ref={ref} className="relative">
        {/* progress line, filled by scroll */}
        <div aria-hidden="true" className="mb-4 h-px w-full bg-white/10">
          <motion.div
            style={reduce ? undefined : { scaleX }}
            className="h-px origin-left bg-white shadow-[0_0_12px_rgba(255,255,255,0.8)]"
          />
        </div>
        <ol className="grid gap-px overflow-hidden rounded-2xl border border-white/10 bg-white/10 md:grid-cols-5">
          {STEPS.map((s, i) => (
            <Step key={s.n} step={s} index={i} progress={scrollYProgress} reduce={reduce} />
          ))}
        </ol>
      </div>
    </Section>
  )
}
