import { useEffect, useState } from 'react'
import { cn } from '@/lib/utils'
import { Link, useBridge } from './bridge'

/** Lumen's mark: the brand's soft-light orb on a dark tile. */
function Mark() {
  return (
    <svg viewBox="0 0 32 32" aria-hidden="true" className="size-7 shrink-0">
      <defs>
        <radialGradient id="lumen-nav-g" cx="38%" cy="34%" r="72%">
          <stop offset="0" stopColor="#F3E3D7" />
          <stop offset=".55" stopColor="#D9EDE4" />
          <stop offset="1" stopColor="#DCE8F0" />
        </radialGradient>
      </defs>
      <rect width="32" height="32" rx="9" fill="#1a1a1a" />
      <rect x=".5" y=".5" width="31" height="31" rx="8.5" fill="none" stroke="rgba(255,255,255,.12)" />
      <circle cx="16" cy="16" r="8.5" fill="url(#lumen-nav-g)" />
      <path d="M16 7.5v17" stroke="#1a1a1a" strokeWidth="1.3" opacity=".35" />
    </svg>
  )
}

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
      <div className="mx-auto flex h-16 max-w-6xl items-center justify-between px-6 md:px-8">
        <Link to="/" aria-label="Lumen 首页" className="flex items-center gap-2.5">
          <Mark />
          <span className="text-foreground text-lg font-semibold tracking-tight">Lumen</span>
          <span className="text-muted-foreground hidden text-xs tracking-[0.2em] sm:inline">流明智媒</span>
        </Link>

        <nav aria-label="账户" className="flex items-center gap-2">
          {isAuthenticated ? (
            <Link
              to="/studio"
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
