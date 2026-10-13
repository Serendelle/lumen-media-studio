import { Check, MessageSquareText, SlidersHorizontal } from 'lucide-react'
import { Placeholder, ScrollRise, Section, SectionHeading, SpotlightCard } from './primitives'

const MODES = [
  {
    icon: SlidersHorizontal,
    name: '专业模式',
    tagline: 'Every parameter, yours.',
    points: ['编码器、码率、分辨率、帧率逐项可调', '目标体积压缩，提交前评估是否可行', '处理前后对比，结果一键下载'],
    visual: '专业模式界面截图',
  },
  {
    icon: MessageSquareText,
    name: '智能模式',
    tagline: 'Just say what you want.',
    points: ['一句话描述你想要的结果', 'AI 生成执行计划，由你确认后才执行', '执行完成后独立审核结果质量'],
    visual: '智能模式对话截图',
  },
]

/** Pro vs. AI mode, side by side. */
export default function Modes() {
  return (
    <Section id="modes">
      <SectionHeading
        eyebrow="Two ways to work"
        title="Precise control, or plain words."
        lede="两种模式共用同一套真实的处理引擎，随时切换。"
      />
      <div className="grid gap-4 md:grid-cols-2">
        {MODES.map((m, i) => (
          // the two modes slide in from opposite sides and meet in the middle
          <ScrollRise key={m.name} from={{ x: i === 0 ? -140 : 140, y: 40, scale: 0.92, rotate: i === 0 ? -3 : 3 }} end={0.45}>
            <SpotlightCard>
              <article className="flex h-full flex-col">
                <div className="p-8 md:p-10">
                  <div className="text-muted-foreground flex items-center gap-2 text-sm">
                    <m.icon aria-hidden="true" className="size-4" />
                    {m.name}
                  </div>
                  <h3 className="text-foreground mt-4 text-3xl font-semibold tracking-tight">{m.tagline}</h3>
                  <ul className="mt-6 space-y-3">
                    {m.points.map((p) => (
                      <li key={p} className="text-muted-foreground flex gap-3 text-sm">
                        <Check aria-hidden="true" className="text-foreground mt-0.5 size-4 shrink-0" />
                        {p}
                      </li>
                    ))}
                  </ul>
                </div>
                <Placeholder
                  label={m.visual}
                  className="mx-8 mt-auto mb-8 aspect-[4/3] md:mx-10 md:mb-10"
              />
            </article>
            </SpotlightCard>
          </ScrollRise>
        ))}
      </div>
    </Section>
  )
}
