import { useRef } from 'react'
import { ArrowRight } from 'lucide-react'
import { motion, useReducedMotion, useScroll, useTransform } from 'motion/react'
import { Link } from '../bridge'

/** Final call to action. Sits inside the starfield band; its own gradient fades the stars out downward. */
export default function Closing() {
  const ref = useRef<HTMLElement>(null)
  const reduce = useReducedMotion()
  // the headline grows into place while a beam of light opens above it
  const { scrollYProgress } = useScroll({ target: ref, offset: ['start end', 'center center'] })
  const scale = useTransform(scrollYProgress, (p) => 0.8 + 0.2 * p)
  const opacity = useTransform(scrollYProgress, (p) => Math.min(1, p * 1.4))
  const beam = useTransform(scrollYProgress, (p) => p)

  return (
    <section ref={ref} className="relative isolate overflow-hidden px-6 py-28 md:py-44">
      {/* stars from the band above stay bright at the top and dissolve toward the footer */}
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 -z-20 bg-[linear-gradient(to_bottom,transparent_0%,rgba(0,0,0,0.55)_50%,#000_85%)]"
      />
      {/* light spilling across the full width, masked in from the top so there's no seam with the band above */}
      <motion.div
        aria-hidden="true"
        style={reduce ? undefined : { opacity: beam, scaleX: beam }}
        className="pointer-events-none absolute inset-x-0 top-0 -z-10 h-[560px] bg-[radial-gradient(ellipse_75%_100%_at_50%_0%,rgba(255,255,255,0.13),rgba(255,255,255,0.03)_55%,transparent_80%)] [mask-image:linear-gradient(to_bottom,transparent,#000_40%)]"
      />
      <motion.div style={reduce ? undefined : { scale, opacity }} className="mx-auto flex max-w-3xl flex-col items-center text-center">
        <h2 className="text-foreground text-4xl font-semibold tracking-tight text-balance md:text-6xl">
          Ready when you are.
        </h2>
        <p className="text-muted-foreground mt-5 text-base md:text-lg">拖入一个文件，或者说一句话，就可以开始。</p>
        <Link
          to="/studio"
          className="group bg-primary text-primary-foreground mt-9 inline-flex h-11 items-center gap-2 rounded-full px-6 text-sm font-medium transition-[transform,box-shadow] duration-200 hover:shadow-md active:scale-[0.98]"
        >
          Start working
          <ArrowRight aria-hidden="true" className="size-4 transition-transform duration-200 group-hover:translate-x-0.5" />
        </Link>
      </motion.div>
    </section>
  )
}

export function Footer() {
  return (
    <footer className="border-border bg-background text-muted-foreground relative border-t px-6 py-10 text-sm md:px-8">
      <div className="mx-auto flex max-w-6xl flex-col items-center justify-between gap-4 md:flex-row">
        <p>
          <span className="text-foreground font-semibold">Lumen</span> · 流明智媒
        </p>
        <nav aria-label="页脚" className="flex gap-6">
          <Link to="/login" className="hover:text-foreground transition-colors">登录</Link>
          <Link to="/register" className="hover:text-foreground transition-colors">注册</Link>
          <Link to="/studio" className="hover:text-foreground transition-colors">工作台</Link>
        </nav>
        <p>© {new Date().getFullYear()} Lumen</p>
      </div>
    </footer>
  )
}
