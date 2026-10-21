import { createRoot } from 'react-dom/client'
import '@fontsource-variable/inter'
import './styles.css'
import WorkspaceChrome, { type WorkspaceChromeProps } from '@/components/ui/dashboard-sidebar'

/** React owns the supplied navigation component; Vue retains routed pages and Pinia stores. */
export function mountWorkspace(el: HTMLElement, props: WorkspaceChromeProps) {
  const root = createRoot(el)
  const render = (next: WorkspaceChromeProps) => root.render(<WorkspaceChrome {...next} />)
  render(props)
  return { update: render, unmount: () => root.unmount() }
}
