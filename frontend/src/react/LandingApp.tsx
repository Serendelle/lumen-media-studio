import { MotionConfig } from 'motion/react'
import Navbar from './Navbar'
import Hero from './sections/Hero'
import Showcase from './sections/Showcase'
import Capabilities from './sections/Capabilities'
import Modes from './sections/Modes'
import Workflow from './sections/Workflow'
import Trust from './sections/Trust'
import Closing from './sections/Closing'

/**
 * Lumen landing page. Each section is its own file under ./sections, so any one can be swapped for a
 * 21st.dev component without touching the rest. Visual slots still to fill are <Placeholder>s.
 */
export default function LandingApp() {
  return (
    <MotionConfig reducedMotion="user">
      <Navbar />
      <main id="main">
        <Hero />
        <Showcase />
        <Capabilities />
        <Modes />
        <Workflow />
        <Trust />
      </main>
      <Closing />
    </MotionConfig>
  )
}
