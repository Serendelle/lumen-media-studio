import { useRef, type ReactNode } from 'react'
import { InteractiveStarfield, usePointer } from '@/components/ui/starfield'

/**
 * Shared starfield behind every section between the hero and the closing call to action.
 * The stars sit in a viewport-sized sticky layer, so density stays constant however long the page gets
 * (150 stars total, not 150 per screen) and they drift toward the pointer anywhere in the band.
 */
export default function StarfieldBackdrop({ children }: { children: ReactNode }) {
  const layer = useRef<HTMLDivElement>(null)
  const { pointer, handlers } = usePointer()
  return (
    <div {...handlers} className="relative">
      <div aria-hidden="true" className="pointer-events-none absolute inset-0">
        <div ref={layer} className="sticky top-0 h-svh w-full overflow-hidden">
          <InteractiveStarfield pointer={pointer} containerRef={layer} />
        </div>
      </div>
      {children}
    </div>
  )
}
