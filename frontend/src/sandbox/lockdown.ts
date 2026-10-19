/**
 * Removes every network / loading capability from a worker's global scope before user code runs. Workers have no
 * DOM to begin with; together with the time limit in runners.ts this is the sandbox boundary.
 */
export function lockdown() {
  const g = self as any
  for (const k of ['fetch', 'XMLHttpRequest', 'WebSocket', 'EventSource', 'importScripts', 'BroadcastChannel',
    'indexedDB', 'caches', 'Worker', 'SharedWorker', 'navigator', 'WebTransport']) {
    try {
      Object.defineProperty(g, k, { value: undefined, configurable: false, writable: false })
    } catch {
      try { g[k] = undefined } catch { /* not removable: harmless in a worker */ }
    }
  }
}

export const MAX_OUTPUT = 20_000
