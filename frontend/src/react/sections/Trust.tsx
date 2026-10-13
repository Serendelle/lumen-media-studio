import { HardDrive, KeyRound, ScanSearch } from 'lucide-react'
import { Reveal, Section, SectionHeading } from './primitives'

const ENGINES = ['FFmpeg', 'ImageMagick', 'ExifTool', 'Ghostscript', 'rembg', 'DeepSeek']

const PROMISES = [
  { icon: HardDrive, title: '本地处理', desc: '所有媒体处理都由部署 Lumen 的这台机器上的引擎完成。' },
  { icon: KeyRound, title: '密钥不出后端', desc: 'API Key 只保存在本机 .env，前端与日志中都不会出现。' },
  { icon: ScanSearch, title: '结果独立核验', desc: '隐私清理与 AI 任务完成后，都会单独再检查一遍。' },
]

/** Engines strip + privacy promises. */
export default function Trust() {
  return (
    <Section id="trust">
      <SectionHeading eyebrow="Built on" title="Proven engines. Private by design." />
      <Reveal>
        <ul className="text-muted-foreground flex flex-wrap items-center justify-center gap-x-10 gap-y-4 text-lg font-medium md:text-xl">
          {ENGINES.map((e) => (
            <li key={e}>{e}</li>
          ))}
        </ul>
      </Reveal>
      <div className="mt-16 grid gap-8 md:mt-24 md:grid-cols-3">
        {PROMISES.map((p, i) => (
          <Reveal key={p.title} delay={i * 0.06}>
            <p.icon aria-hidden="true" className="text-foreground size-5" strokeWidth={1.5} />
            <h3 className="text-foreground mt-4 font-semibold">{p.title}</h3>
            <p className="text-muted-foreground mt-1 text-sm leading-relaxed">{p.desc}</p>
          </Reveal>
        ))}
      </div>
    </Section>
  )
}
