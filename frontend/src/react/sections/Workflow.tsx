import { Reveal, Section, SectionHeading } from './primitives'

const STEPS = [
  { n: '01', who: '需求理解官', what: '读懂你的一句话，识别文件与目标。' },
  { n: '02', who: '任务规划官', what: '拆解成可执行的步骤，评估可行性。' },
  { n: '03', who: '你', what: '查看计划，确认后才会开始执行。', you: true },
  { n: '04', who: '处理执行官', what: '调用真实引擎逐步处理，进度实时可见。' },
  { n: '05', who: '质量审核官', what: '独立核验结果是否达成目标。' },
]

/** Smart-mode pipeline: four agents with the user's confirmation in the middle. */
export default function Workflow() {
  return (
    <Section id="workflow">
      <SectionHeading
        eyebrow="Smart mode"
        title="Four agents. You stay in charge."
        lede="智能模式不会擅自动手：每一步都摊开给你看，确认之后才执行。"
      />
      <ol className="grid gap-px overflow-hidden rounded-2xl border border-white/10 bg-white/10 md:grid-cols-5">
        {STEPS.map((s, i) => (
          <li key={s.n} className={s.you ? 'bg-secondary' : 'bg-card'}>
            <Reveal delay={i * 0.06} className="flex h-full flex-col p-6 md:min-h-56">
              <span className="text-muted-foreground font-mono text-xs">{s.n}</span>
              <h3 className="text-foreground mt-8 text-lg font-semibold tracking-tight">{s.who}</h3>
              <p className="text-muted-foreground mt-2 text-sm leading-relaxed">{s.what}</p>
            </Reveal>
          </li>
        ))}
      </ol>
    </Section>
  )
}
