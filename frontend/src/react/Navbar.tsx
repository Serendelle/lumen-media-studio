import { useEffect, useState } from 'react'
import { cn } from '@/lib/utils'
import { Link, useBridge } from './bridge'

/** Shared glass L mark, also used by the Vue views and browser tab. */
function Mark() {
  return (
    <img
      src="/favicon.svg?v=lumen-glass"
      width={36}
      height={36}
      alt=""
      aria-hidden="true"
      className="size-9 shrink-0 object-contain"
    />
  )
}

const SECTIONS = [
  { href: '#product', label: '产品' },
  { href: '#features', label: '能力' },
  { href: '#modes', label: '两种模式' },
  { href: '#workflow', label: '智能工作流' },
  { href: '#pricing', label: '价格' },
]

export default function Navbar() {
  const { isAuthenticated } = useBridge()
  const [scrolled, setScrolled] = useState(false)

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 8)
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [])

  return (
    <header
      className={cn(
        'fixed inset-x-0 top-0 z-50 border-b border-transparent transition-[background-color,border-color] duration-300',
        scrolled && 'bg-background/70 border-border backdrop-blur-md',
      )}
    >
      <div className="relative flex h-16 w-full items-center justify-between px-5 md:px-8">
        <Link to="/" aria-label="Lumen 首页" className="flex items-center gap-2.5">
          <Mark />
          <span className="text-foreground text-lg font-semibold tracking-tight">Lumen</span>
          <span className="text-muted-foreground hidden text-xs tracking-[0.2em] sm:inline">流明智媒</span>
        </Link>

        <nav aria-label="页面导航" className="absolute left-1/2 hidden -translate-x-1/2 items-center gap-8 md:flex">
          {SECTIONS.map((s) => (
            <a key={s.href} href={s.href} className="text-muted-foreground hover:text-foreground text-sm transition-colors">
              {s.label}
            </a>
          ))}
        </nav>

        <nav aria-label="账户" className="flex items-center gap-2">
          {isAuthenticated ? (
            <Link
              to="/workspace"
              className="bg-primary text-primary-foreground inline-flex h-9 items-center rounded-full px-4 text-sm font-medium transition-opacity hover:opacity-90"
            >
              进入工作台
            </Link>
          ) : (
            <>
              <Link
                to="/login"
                className="text-muted-foreground hover:text-foreground inline-flex h-9 items-center rounded-full px-4 text-sm font-medium transition-colors"
              >
                登录
              </Link>
              <Link
                to="/register"
                className="bg-primary text-primary-foreground inline-flex h-9 items-center rounded-full px-4 text-sm font-medium transition-opacity hover:opacity-90"
              >
                注册
              </Link>
            </>
          )}
        </nav>
      </div>
    </header>
  )
}
