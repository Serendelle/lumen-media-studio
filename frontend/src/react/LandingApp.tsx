import { motion } from 'motion/react'
import { ArrowRight, Sparkles } from 'lucide-react'
import { Link, useBridge } from './bridge'

/**
 * Placeholder React landing page — proves React + Tailwind + motion + lucide all work.
 * Replace the sections with the chosen 21st.dev components.
 */
export default function LandingApp() {
  const { isAuthenticated } = useBridge()

  return (
    <main id="main" className="relative flex min-h-svh flex-col items-center justify-center overflow-hidden px-6 text-center">
      <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(60%_50%_at_50%_0%,rgba(255,255,255,0.12),transparent_70%)]" />

      <motion.span
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.6 }}
        className="mb-6 inline-flex items-center gap-2 rounded-full border border-border px-3 py-1 text-xs text-muted-foreground"
      >
        <Sparkles className="size-3.5" /> React 首页预览 · 框架已就绪
      </motion.span>

      <motion.h1
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.8, delay: 0.1 }}
        className="max-w-3xl text-5xl font-semibold tracking-tight md:text-7xl"
      >
        让每一帧，都恰到好处。
      </motion.h1>

      <motion.p
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ duration: 0.8, delay: 0.3 }}
        className="mt-6 max-w-xl text-base text-muted-foreground md:text-lg"
      >
        这里将替换为从 21st.dev 挑选的组件。
      </motion.p>

      <div className="mt-10 flex gap-3">
        <Link
          to={isAuthenticated ? '/studio' : '/register'}
          className="inline-flex h-11 items-center gap-2 rounded-full bg-primary px-6 text-sm font-medium text-primary-foreground transition hover:opacity-90"
        >
          {isAuthenticated ? '进入工作台' : '免费开始'} <ArrowRight className="size-4" />
        </Link>
        <Link
          to="/"
          className="inline-flex h-11 items-center rounded-full border border-border px-6 text-sm transition hover:bg-secondary"
        >
          查看旧版首页
        </Link>
      </div>
    </main>
  )
}
