import { AudioLines, FileText, Film, ImageIcon, ShieldCheck, Sparkles, type LucideIcon } from 'lucide-react'
import { cn } from '@/lib/utils'
import { ScrollRise, Section, SectionHeading, SpotlightCard } from './primitives'

interface Capability {
  icon: LucideIcon
  title: string
  desc: string
  engine: string
  /** wide cards span two columns on desktop */
  wide?: boolean
}

const CAPABILITIES: Capability[] = [
  {
    icon: Film,
    title: '视频',
    desc: '按目标体积压缩（两遍编码，提交前评估可行性）、CRF、指定码率；H.264 / H.265；MP4 / MOV / WebM / MKV / GIF 互转；图片水印。',
    engine: 'FFmpeg',
    wide: true,
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
    wide: true,
  },
]

/** Bento grid of everything Lumen does. Candidate 21st component: "bento grid". */
export default function Capabilities() {
  return (
    <Section id="features">
      <SectionHeading
        eyebrow="Capabilities"
        title="Everything a creator needs."
        lede="六大能力，背后是业界标准的开源引擎。"
      />
      <div className="grid gap-4 md:grid-cols-3">
        {CAPABILITIES.map((c, i) => (
          // each column rises from a different depth, so the grid assembles with a layered parallax
          <ScrollRise key={c.title} from={{ y: 90 + (i % 3) * 60, scale: 0.92 }} end={0.5 - (i % 3) * 0.06} className={cn(c.wide && 'md:col-span-2')}>
            <SpotlightCard>
              <article className="flex h-full min-h-56 flex-col p-7">
                <c.icon aria-hidden="true" className="text-foreground size-6" strokeWidth={1.5} />
                <h3 className="text-foreground mt-6 text-xl font-semibold tracking-tight">{c.title}</h3>
                <p className="text-muted-foreground mt-2 text-sm leading-relaxed">{c.desc}</p>
                <p className="text-muted-foreground/70 mt-auto pt-6 font-mono text-xs">{c.engine}</p>
              </article>
            </SpotlightCard>
          </ScrollRise>
        ))}
      </div>
    </Section>
  )
}
