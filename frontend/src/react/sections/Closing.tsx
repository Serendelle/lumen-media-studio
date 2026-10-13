import { ArrowRight } from 'lucide-react'
import { Link } from '../bridge'
import { Reveal } from './primitives'

/** Final call to action + footer. */
export default function Closing() {
  return (
    <>
      <section className="border-border border-t px-6 py-28 md:py-40">
        <Reveal className="mx-auto flex max-w-3xl flex-col items-center text-center">
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
        </Reveal>
      </section>

      <footer className="border-border text-muted-foreground border-t px-6 py-10 text-sm md:px-8">
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
    </>
  )
}
