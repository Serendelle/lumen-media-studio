import { useRef } from 'react'
import { motion, useReducedMotion, useScroll, useTransform } from 'motion/react'
import { ScanSearch } from 'lucide-react'
import Dashboard from './Dashboard'
import { ScrollRise, Section, SectionHeading } from './primitives'

const ENGINES = ['FFmpeg', 'ImageMagick', 'ExifTool', 'Ghostscript', 'rembg', 'DeepSeek']

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

/** Engines + an illustrative studio dashboard. */
export default function Trust() {
  return (
    <Section id="trust">
      <SectionHeading eyebrow="Built on" title="Proven engines. Private by design." />
      <EngineDrift />
      <div className="mt-16 grid items-start gap-10 md:mt-24 md:grid-cols-3">
        <div className="[perspective:1400px] md:col-span-2">
          <ScrollRise from={{ y: 120, scale: 0.9, rotateX: 18 }} end={0.4}>
            <Dashboard />
          </ScrollRise>
        </div>
        <ScrollRise from={{ y: 60, scale: 1 }} end={0.6} className="md:pt-6">
          <ScanSearch aria-hidden="true" className="text-foreground size-5" strokeWidth={1.5} />
          <h3 className="text-foreground mt-4 font-semibold">结果独立核验</h3>
          <p className="text-muted-foreground mt-1 text-sm leading-relaxed">隐私清理与 AI 任务完成后，都会单独再检查一遍。</p>
        </ScrollRise>
      </div>
    </Section>
  )
}
