import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './styles.css'
import { BridgeContext, type LandingBridge } from './bridge'
import LandingApp from './LandingApp'

/** Mounts the React landing page into a Vue-owned element; Vue calls update() when auth changes. */
export function mountLanding(el: HTMLElement, bridge: LandingBridge) {
  const root = createRoot(el)
  const render = (b: LandingBridge) =>
    root.render(
      <StrictMode>
        <BridgeContext.Provider value={b}>
          <LandingApp />
        </BridgeContext.Provider>
      </StrictMode>,
    )
  render(bridge)
  return { update: render, unmount: () => root.unmount() }
}
