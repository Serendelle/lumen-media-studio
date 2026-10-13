import { useState } from 'react'
import { motion, useReducedMotion } from 'motion/react'
import { AudioLines, Check, FileText, Film, ImageIcon, ShieldCheck, Sparkles, type LucideIcon } from 'lucide-react'
import { Section, SectionHeading, SpotlightCard } from './primitives'

interface Capability {
  icon: LucideIcon
  title: string
  desc: string
  engine: string
}

const CAPABILITIES: Capability[] = [
  {
    icon: Film,
    title: '视频',
    desc: '按目标体积压缩（两遍编码，提交前评估可行性）、CRF、指定码率；H.264 / H.265；MP4 / MOV / WebM / MKV / GIF 互转；图片水印。',
    engine: 'FFmpeg',
  },
  { icon: AudioLines, title: '音频', desc: '从视频中提取 MP3 / AAC / M4A / WAV / FLAC，可设置码率、采样率、声道。', engine: 'FFmpeg' },
  { icon: ImageIcon, title: '图片', desc: '批量压缩、改尺寸、转格式，处理前后对比；AI 一键抠图。', engine: 'ImageMagick · rembg' },
  { icon: FileText, title: '文档', desc: '多张图片合并为 PDF，或把 PDF 转成图片。', engine: 'ImageMagick · Ghostscript' },
  { icon: ShieldCheck, title: '隐私', desc: '查看与编辑元数据，一键清除位置信息或全部元数据，并独立核验。', engine: 'ExifTool · FFmpeg' },
  {
    icon: Sparkles,
    title: '智能模式',
    desc: '一句话描述想要的结果：需求理解 → 任务规划 → 你确认 → 执行 → 质量审核，全程实时可见。',
    engine: 'DeepSeek · 4 Agents',
  },
]

const EASE = [0.22, 1, 0.36, 1] as const
const listVariants = { hidden: {}, shown: { transition: { staggerChildren: 0.12, delayChildren: 0.2 } } }
const rowVariants = {
  hidden: { opacity: 0, x: -28, filter: 'blur(6px)' },
  shown: { opacity: 1, x: 0, filter: 'blur(0px)', transition: { duration: 0.7, ease: EASE } },
}
const barVariants = {
  hidden: { scaleX: 0 },
  shown: { scaleX: 1, transition: { duration: 1.1, delay: 0.35, ease: EASE } },
}

/** One capability as a dashboard row: a load bar sweeps across, then its status flips to ready. */
function Row({ c, index }: { c: Capability; index: number }) {
  const reduce = useReducedMotion()
  const [ready, setReady] = useState(!!reduce)
  return (
    <motion.li variants={rowVariants}>
      <SpotlightCard className="rounded-xl bg-black/60">
        <div className="grid gap-4 p-4 md:grid-cols-[auto_minmax(0,1fr)_auto] md:items-center md:gap-6 md:p-5">
          <div className="flex items-center gap-4">
            <span className="text-muted-foreground w-6 font-mono text-xs">{String(index + 1).padStart(2, '0')}</span>
            <span className="grid size-11 shrink-0 place-items-center rounded-lg bg-white/[0.07] transition-colors duration-300 group-hover/spot:bg-white group-hover/spot:text-black">
              <c.icon aria-hidden="true" className="size-5" strokeWidth={1.5} />
            </span>
          </div>
          <div className="min-w-0">
            <h3 className="text-foreground text-base font-semibold tracking-tight md:text-lg">{c.title}</h3>
            <p className="text-muted-foreground mt-1 text-sm leading-relaxed">{c.desc}</p>
            {/* load bar */}
            <div className="mt-3 h-px w-full overflow-hidden bg-white/10">
              <motion.div
                variants={reduce ? undefined : barVariants}
                onAnimationComplete={() => setReady(true)}
                className="h-full origin-left bg-white/70"
              />
            </div>
          </div>
          <div className="flex items-center gap-2 md:flex-col md:items-end">
            <span className="text-muted-foreground rounded-full bg-white/[0.07] px-2.5 py-1 font-mono text-xs whitespace-nowrap">
              {c.engine}
            </span>
            <span
              className={
                'inline-flex items-center gap-1 text-xs transition-colors duration-500 ' +
                (ready ? 'text-[#3ddc84]' : 'text-muted-foreground')
              }
            >
              {ready ? <Check aria-hidden="true" className="size-3.5" /> : <span className="size-1.5 animate-pulse rounded-full bg-current" />}
              {ready ? 'Ready' : 'Loading'}
            </span>
          </div>
        </div>
      </SpotlightCard>
    </motion.li>
  )
}

/** The six capabilities as rows in a studio window, loading in one after another. */
export default function Capabilities() {
  const reduce = useReducedMotion()
  return (
    <Section id="features">
      <SectionHeading
        eyebrow="Capabilities"
        title="Everything a creator needs."
        lede="六大能力，背后是业界标准的开源引擎。"
      />
      <motion.div
        initial={reduce ? false : 'hidden'}
        whileInView="shown"
        viewport={{ once: true, margin: '0px 0px -20% 0px' }}
        className="relative overflow-hidden rounded-2xl border border-white/10 bg-[#141414] shadow-[0_40px_120px_-40px_rgba(255,255,255,0.12)]"
      >
        {/* window chrome */}
        <div className="relative flex h-11 items-center border-b border-white/10 bg-black/40 px-4">
          <div className="flex gap-2">
            <span className="size-3 rounded-full bg-[#ff5f57]" />
            <span className="size-3 rounded-full bg-[#febc2e]" />
            <span className="size-3 rounded-full bg-[#28c840]" />
          </div>
          <span className="text-muted-foreground absolute left-1/2 -translate-x-1/2 text-xs font-medium">Lumen — Capabilities</span>
        </div>

        {/* scan line: one sweep of light down the window as it opens */}
        {!reduce && (
          <motion.div
            aria-hidden="true"
            variants={{
              hidden: { top: '0%', opacity: 0 },
              shown: { top: ['0%', '100%'], opacity: [0, 1, 1, 0], transition: { duration: 1.8, ease: 'easeInOut' } },
            }}
            className="pointer-events-none absolute inset-x-0 z-10 h-24 -translate-y-1/2 bg-[linear-gradient(to_bottom,transparent,rgba(255,255,255,0.06)_45%,rgba(255,255,255,0.35)_50%,rgba(255,255,255,0.06)_55%,transparent)]"
          />
        )}

        <motion.ul variants={listVariants} className="space-y-2.5 p-3 md:p-5">
          {CAPABILITIES.map((c, i) => (
            <Row key={c.title} c={c} index={i} />
          ))}
        </motion.ul>
      </motion.div>
    </Section>
  )
}
