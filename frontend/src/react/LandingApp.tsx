import { MotionConfig } from 'motion/react'
import Navbar from './Navbar'
import { useSmoothScroll } from './smooth-scroll'
import Hero from './sections/Hero'
import Showcase from './sections/Showcase'
import Capabilities from './sections/Capabilities'
import Modes from './sections/Modes'
import Workflow from './sections/Workflow'
import Trust from './sections/Trust'
import Pricing from './sections/Pricing'
import Closing, { Footer } from './sections/Closing'
import StarfieldBackdrop from './StarfieldBackdrop'

// fractal-noise tile for the film-grain overlay
const GRAIN = `url("data:image/svg+xml,${encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" width="220" height="220"><filter id="n"><feTurbulence type="fractalNoise" baseFrequency=".85" numOctaves="3" stitchTiles="stitch"/></filter><rect width="100%" height="100%" filter="url(#n)"/></svg>',
)}")`

/**
 * Lumen landing page. Each section is its own file under ./sections, so any one can be swapped for a
 * 21st.dev component without touching the rest. Visual slots still to fill are <Placeholder>s.
 */
export default function LandingApp() {
  useSmoothScroll()

  return (
    <MotionConfig reducedMotion="user">
      <Navbar />
      <main id="main">
        <Hero />
        <StarfieldBackdrop>
          <Showcase />
          <Capabilities />
          <Modes />
          <Workflow />
          <Trust />
          <Pricing />
          <Closing />
        </StarfieldBackdrop>
      </main>
      <Footer />
      {/* film grain over the whole page: keeps the pure black from looking flat */}
      <div
        aria-hidden="true"
        style={{ backgroundImage: GRAIN }}
        className="pointer-events-none fixed -inset-1/2 z-[60] opacity-[0.07] motion-safe:animate-[lumen-grain_0.9s_steps(4)_infinite]"
      />
    </MotionConfig>
  )
}
