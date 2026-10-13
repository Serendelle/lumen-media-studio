import { useRef } from 'react'
import { motion, useReducedMotion, useScroll, useTransform } from 'motion/react'
import Dashboard from './Dashboard'
import { ScrollRise, Section, SectionHeading } from './primitives'

const ENGINES = ['FFmpeg', 'ImageMagick', 'ExifTool', 'Ghostscript', 'rembg', 'DeepSeek']

// Each point is backed by behaviour exercised in docs/test-report.md.
const STRENGTHS = [
  { title: '真实处理，不是演示', desc: '每个按钮背后都是 FFmpeg、ImageMagick 等真实引擎在工作。' },
  { title: '先确认，再执行', desc: 'AI 生成的计划必须经你确认，确认前不会创建任何任务。' },
  { title: '做不到会直说', desc: '目标不可实现时（比如压到 50KB），给出可选方案而不是硬做。' },
  { title: '结果独立核验', desc: '处理后用 ffprobe / ExifTool 再查一遍，交付文件也查位置信息。' },
  { title: '不怕提示注入', desc: '藏在文件名里的恶意指令会被完全忽略。' },
  { title: '刷新不丢进度', desc: '中途刷新页面，计划、消息与处理进度都能恢复。' },
]

/** Two oversized rows of engine names drifting in opposite directions as the page scrolls. */
function EngineDrift() {
  const ref = useRef<HTMLDivElement>(null)
  const reduce = useReducedMotion()
  const { scrollYProgress } = useScroll({ target: ref, offset: ['start end', 'end start'] })
  const left = useTransform(scrollYProgress, (p) => `${-p * 30}%`)
  const right = useTransform(scrollYProgress, (p) => `${-30 + p * 30}%`)
  const row = [...ENGINES, ...ENGINES]
  return (
    <div ref={ref} aria-label={ENGINES.join('、')} className="-mx-6 space-y-2 overflow-hidden py-4 md:-mx-8">
      <motion.p
        aria-hidden="true"
        style={reduce ? undefined : { x: left }}
        className="flex gap-10 text-5xl font-semibold tracking-tight whitespace-nowrap text-white/90 md:text-7xl"
      >
        {row.map((e, i) => (
          <span key={i}>{e}</span>
        ))}
      </motion.p>
      <motion.p
        aria-hidden="true"
        style={reduce ? undefined : { x: right, WebkitTextStroke: '1px rgba(255,255,255,0.28)' }}
        className="flex gap-10 text-5xl font-semibold tracking-tight whitespace-nowrap text-transparent md:text-7xl"
      >
        {[...row].reverse().map((e, i) => (
          <span key={i}>{e}</span>
        ))}
      </motion.p>
    </div>
  )
}

/** Engines, measured results, and why Lumen. */
export default function Trust() {
  return (
    <Section id="trust">
      <SectionHeading eyebrow="Built on" title="Proven engines. Private by design." />
      <EngineDrift />
      <div className="mt-16 grid grid-cols-1 items-start gap-10 md:mt-24 md:grid-cols-3">
        <div className="[perspective:1400px] md:col-span-2">
          <ScrollRise from={{ y: 120, scale: 0.9, rotateX: 18 }} end={0.4}>
            <Dashboard />
          </ScrollRise>
        </div>
        <div>
          <p className="text-muted-foreground mb-6 text-xs font-medium tracking-[0.18em] uppercase">Why Lumen</p>
          <motion.ol
            initial="hidden"
            whileInView="shown"
            viewport={{ once: true, margin: '0px 0px -15% 0px' }}
            variants={{ hidden: {}, shown: { transition: { staggerChildren: 0.1 } } }}
            className="space-y-1"
          >
            {STRENGTHS.map((s, i) => (
              <motion.li
                key={s.title}
                variants={{
                  hidden: { opacity: 0, y: 16, filter: 'blur(4px)' },
                  shown: { opacity: 1, y: 0, filter: 'blur(0px)', transition: { duration: 0.6, ease: [0.22, 1, 0.36, 1] } },
                }}
                className="group relative border-l border-white/10 py-3 pl-5 transition-colors hover:border-white"
              >
                <span className="text-muted-foreground font-mono text-[11px]">{String(i + 1).padStart(2, '0')}</span>
                <h3 className="text-foreground mt-1 font-semibold">{s.title}</h3>
                <p className="text-muted-foreground mt-1 text-sm leading-relaxed transition-colors group-hover:text-foreground/80">{s.desc}</p>
              </motion.li>
            ))}
          </motion.ol>
        </div>
      </div>
    </Section>
  )
}
