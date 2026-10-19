import { ArrowRight, ChevronDown } from 'lucide-react'
import { useRef } from 'react'
import { motion, useReducedMotion, useScroll, useTransform } from 'motion/react'
import { SonarGrid } from '@/components/ui/sonar-grid'
import { Link } from '../bridge'

/** First screen — 21st.dev SonarGrid with the headline and the single CTA. */
export default function Hero() {
  const reduce = useReducedMotion()
  const enter = (delay: number) =>
    reduce
      ? {}
      : {
          initial: { opacity: 0, y: 14, filter: 'blur(6px)' },
          animate: { opacity: 1, y: 0, filter: 'blur(0px)' },
          transition: { duration: 0.6, delay, ease: [0.22, 1, 0.36, 1] as const },
        }

  // Scroll-out: as the hero leaves, its copy sinks slower than the page, shrinks and fades (parallax).
  const ref = useRef<HTMLDivElement>(null)
  const { scrollYProgress } = useScroll({ target: ref, offset: ['start start', 'end start'] })
  const y = useTransform(scrollYProgress, [0, 1], [0, 180])
  const scale = useTransform(scrollYProgress, [0, 1], [1, 0.9])
  // A function transform on purpose: motion offloads range-mapped opacity to a native ScrollTimeline and
  // drops the trailing hold keyframe, so the copy faded back in near the end of the range.
  const opacity = useTransform(scrollYProgress, (p) => Math.max(0, 1 - p / 0.65))

  return (
    <SonarGrid
      ref={ref}
      pingArea={[0.22, 0.18, 0.78, 0.82]}
      className="bg-background flex min-h-[max(560px,100svh)] w-full flex-col"
    >
      {/* A soft wash behind the copy keeps it legible while rings pass underneath. */}
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 -z-10 bg-[radial-gradient(ellipse_34%_30%_at_50%_50%,var(--color-background)_0%,transparent_100%)]"
      />
      <motion.div
        style={reduce ? undefined : { y, scale, opacity }}
        className="mx-auto flex w-full max-w-6xl flex-1 flex-col items-center justify-center px-8 py-24 text-center"
      >
        <div className="flex max-w-3xl flex-col items-center">
          <motion.p
            {...enter(0)}
            className="text-muted-foreground border-border mb-5 inline-flex items-center gap-2 rounded-full border px-3 py-1 text-xs font-medium"
          >
            <span aria-hidden="true" className="bg-primary size-1.5 rounded-full" />
            AI media studio for creators
          </motion.p>
          <motion.h1
            {...enter(0.08)}
            className="text-foreground text-5xl font-semibold tracking-tight text-balance sm:text-6xl md:text-7xl"
          >
            Every frame,
            <br />
            in its best light.
          </motion.h1>
          <motion.p {...enter(0.16)} className="text-muted-foreground mt-6 max-w-xl text-base text-pretty sm:text-lg">
            压缩、转码、提取音轨、清理隐私信息——用专业参数精确掌控，或者直接说出你的想法，让 AI 规划、确认、执行。
          </motion.p>
          <motion.div {...enter(0.24)} className="mt-9 flex items-center justify-center">
            {/* /workspace is auth-guarded: signed-out visitors are sent to login and returned here afterwards */}
            <Link
              to="/workspace"
              className="group bg-primary text-primary-foreground focus-visible:ring-ring/50 inline-flex h-11 cursor-pointer items-center gap-2 rounded-full px-6 text-sm font-medium shadow-sm transition-[transform,box-shadow] duration-200 outline-none hover:shadow-md focus-visible:ring-[3px] active:scale-[0.98]"
            >
              Start working
              <ArrowRight
                aria-hidden="true"
                className="size-4 transition-transform duration-200 group-hover:translate-x-0.5"
              />
            </Link>
          </motion.div>
        </div>
      </motion.div>

      {/* scroll cue */}
      <a
        href="#product"
        aria-label="向下浏览"
        className="text-muted-foreground hover:text-foreground absolute bottom-8 left-1/2 -translate-x-1/2 transition-colors"
      >
        <ChevronDown className="size-5 motion-safe:animate-bounce" />
      </a>
    </SonarGrid>
  )
}
