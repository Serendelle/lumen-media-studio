import { useState } from 'react'
import { BillingContext, BillingToggle, PricingCard, type PricingPlan } from '@/components/ui/pricing'
import { useBridge } from '../bridge'
import { SectionHeading } from './primitives'

const PLUS_MONTHLY = 14
const PLUS_YEARLY = 147
const YEARLY_SAVING = PLUS_MONTHLY * 12 - PLUS_YEARLY // ¥21 against twelve monthly payments

/** Free vs Plus — the only difference is smart mode (the AI agents). The starfield comes from LandingApp. */
export default function Pricing() {
  const { isAuthenticated } = useBridge()
  const [isMonthly, setIsMonthly] = useState(true)
  const [selected, setSelected] = useState('Plus')

  // no payment flow exists yet: both buttons lead into the app (signed-in users go straight to the studio)
  const plans: PricingPlan[] = [
    {
      name: 'Free',
      monthlyPrice: 0,
      yearlyPrice: 0,
      description: '专业模式，每个参数由你掌控。',
      monthlyNote: '永久免费',
      yearlyNote: '永久免费',
      features: ['视频压缩与格式转换', '视频水印、音频提取', '图片处理与 AI 抠图', 'PDF 转换', '元数据查看与隐私清理', '处理前后对比、一键下载'],
      buttonText: '免费开始',
      href: isAuthenticated ? '/studio' : '/register',
    },
    {
      name: 'Plus',
      monthlyPrice: PLUS_MONTHLY,
      yearlyPrice: PLUS_YEARLY,
      description: '加上智能模式，一句话交给 AI。',
      monthlyNote: '按月付费',
      yearlyNote: `按年付费，相当于每月 ¥${(PLUS_YEARLY / 12).toFixed(2)}，省 ¥${YEARLY_SAVING}`,
      features: ['包含 Free 的全部功能', '智能模式：用一句话描述想要的结果', '四个 Agent 理解、规划、执行、审核', '执行前计划由你确认', '完成后自动质量审核'],
      buttonText: '升级 Plus',
      href: isAuthenticated ? '/studio?mode=ai' : '/register',
      isPopular: true,
    },
  ]

  return (
    <BillingContext.Provider value={{ isMonthly, setIsMonthly }}>
      <section id="pricing" className="relative scroll-mt-16 px-6 py-24 md:px-8 md:py-36">
        <div aria-hidden="true" className="absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-white/15 to-transparent" />
        <div className="relative mx-auto w-full max-w-4xl">
          <SectionHeading eyebrow="Pricing" title="Simple, honest pricing." lede="两个方案，区别只有一个：要不要 AI。" />
          <BillingToggle monthlyLabel="月付" yearlyLabel="年付" />
          <div role="radiogroup" aria-label="选择方案" className="mt-14 grid grid-cols-1 items-start gap-8 md:grid-cols-2">
            {plans.map((plan, i) => (
              <PricingCard
                key={plan.name}
                plan={plan}
                index={i}
                selected={selected === plan.name}
                onSelect={() => setSelected(plan.name)}
                popularLabel="推荐"
              />
            ))}
          </div>
        </div>
      </section>
    </BillingContext.Provider>
  )
}
