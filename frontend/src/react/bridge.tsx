import { createContext, useContext, type AnchorHTMLAttributes } from 'react'

/** What the React landing page needs from the Vue app — passed in by ReactLandingView.vue. */
export interface LandingBridge {
  isAuthenticated: boolean
  /** In-app navigation through vue-router (no full page reload). */
  navigate: (path: string) => void
}

export const BridgeContext = createContext<LandingBridge>({
  isAuthenticated: false,
  navigate: (path) => location.assign(path),
})

export const useBridge = () => useContext(BridgeContext)

/** Drop-in for <a href> that routes through vue-router; hash links scroll normally. */
export function Link({ to, onClick, ...rest }: AnchorHTMLAttributes<HTMLAnchorElement> & { to: string }) {
  const { navigate } = useBridge()
  return (
    <a
      href={to}
      onClick={(e) => {
        onClick?.(e)
        if (e.defaultPrevented || to.startsWith('#') || e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return
        e.preventDefault()
        navigate(to)
      }}
      {...rest}
    />
  )
}
