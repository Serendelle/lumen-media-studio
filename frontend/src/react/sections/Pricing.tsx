import { useRef } from 'react'
import { InteractiveStarfield, PricingCard, usePointer, type PricingPlan } from '@/components/ui/pricing'
import { useBridge } from '../bridge'
import { SectionHeading } from './primitives'

/** Free vs Plus — the only difference is smart mode (the AI agents). */
export default function Pricing() {
  const { isAuthenticated } = useBridge()
  const ref = useRef<HTMLElement>(null)
  const { pointer, handlers } = usePointer()

  // no payment flow exists yet: both buttons lead into the app (signed-in users go straight to the studio)
  const start = isAuthenticated ? '/studio' : '/register'
  const plans: PricingPlan[] = [
    {
      name: 'Free',
      price: 0,
      period: '月',
      description: '专业模式，每个参数由你掌控。',
      features: ['视频压缩与格式转换', '视频水印、音频提取', '图片处理与 AI 抠图', 'PDF 转换', '元数据查看与隐私清理', '处理前后对比、一键下载'],
      buttonText: '免费开始',
      href: start,
    },
    {
      name: 'Plus',
      price: 9.9,
      period: '月',
      description: '加上智能模式，一句话交给 AI。',
      features: ['包含 Free 的全部功能', '智能模式：用一句话描述想要的结果', '四个 Agent 理解、规划、执行、审核', '执行前计划由你确认', '完成后自动质量审核'],
      buttonText: '升级 Plus',
      href: isAuthenticated ? '/studio?mode=ai' : '/register',
      isPopular: true,
    },
  ]

  return (
    <section ref={ref} id="pricing" {...handlers} className="relative scroll-mt-16 overflow-hidden px-6 py-24 md:px-8 md:py-36">
      <div aria-hidden="true" className="absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-white/15 to-transparent" />
      <InteractiveStarfield pointer={pointer} containerRef={ref} />
      <div className="relative z-10 mx-auto w-full max-w-4xl">
        <SectionHeading eyebrow="Pricing" title="Simple, honest pricing." lede="两个方案，区别只有一个：要不要 AI。" />
        <div className="grid grid-cols-1 items-start gap-8 md:grid-cols-2">
          {plans.map((plan, i) => (
            <PricingCard key={plan.name} plan={plan} index={i} popularLabel="推荐" />
          ))}
        </div>
      </div>
    </section>
  )
}
