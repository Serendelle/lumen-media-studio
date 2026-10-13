import { Placeholder, Reveal, Section, SectionHeading } from './primitives'

/** Product shot — the studio in a browser frame. Candidate 21st component: "container scroll". */
export default function Showcase() {
  return (
    <Section id="product">
      <SectionHeading
        eyebrow="The Studio"
        title="One studio. Every format."
        lede="视频、音频、图片、文档、隐私，一个工作台全部处理。真实的处理引擎，不是演示动画。"
      />
      <Reveal>
        <div className="bg-card overflow-hidden rounded-2xl border border-white/10 shadow-[0_40px_120px_-40px_rgba(255,255,255,0.15)]">
          <div className="flex h-10 items-center gap-2 border-b border-white/10 px-4">
            <span className="size-3 rounded-full bg-white/15" />
            <span className="size-3 rounded-full bg-white/15" />
            <span className="size-3 rounded-full bg-white/15" />
            <span className="text-muted-foreground ml-4 text-xs">lumen · studio</span>
          </div>
          <Placeholder
            label="工作台截图 / 录屏"
            hint="可替换为 21st「container scroll」组件"
            className="aspect-[16/10] rounded-none border-0"
          />
        </div>
      </Reveal>
    </Section>
  )
}
