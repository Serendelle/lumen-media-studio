/// <reference lib="webworker" />
import { lockdown, MAX_OUTPUT } from './lockdown'

// CPython compiled to WebAssembly (Pyodide), loaded from this site. The network is removed once it has loaded.
let pyodide: any = null
let size = 0

async function boot() {
  if (pyodide) return pyodide
  const { loadPyodide } = await import(/* @vite-ignore */ `${self.location.origin}/pyodide/pyodide.mjs`)
  pyodide = await loadPyodide({ indexURL: `${self.location.origin}/pyodide/` })
  const emit = (type: 'out' | 'err') => (s: string) => {
    if (size > MAX_OUTPUT) return
    size += s.length
    postMessage({ type, text: size > MAX_OUTPUT ? s.slice(0, 200) + '\n…（输出过长，已截断）' : s })
  }
  pyodide.setStdout({ batched: emit('out') })
  pyodide.setStderr({ batched: emit('err') })
  lockdown() // after this no fetch / XHR / WebSocket exists, so `import js; js.fetch(...)` has nothing to call
  return pyodide
}

onmessage = async (e: MessageEvent<{ type: 'boot' } | { type: 'run'; code: string }>) => {
  try {
    const py = await boot()
    if (e.data.type === 'boot') {
      postMessage({ type: 'ready' })
      return
    }
    size = 0
    postMessage({ type: 'started' })
    const started = performance.now()
    try {
      // a fresh namespace per run, so one run's variables do not leak into the next
      const ns = py.globals.get('dict')()
      await py.runPythonAsync(e.data.code, { globals: ns })
      ns.destroy()
      postMessage({ type: 'done', ms: Math.round(performance.now() - started) })
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err)
      // keep the user-relevant tail of the traceback (Pyodide's own frames are noise)
      const lines = msg.split('\n')
      const i = lines.findIndex((l) => l.includes('File "<exec>"'))
      postMessage({ type: 'error', text: (i >= 0 ? lines.slice(i) : lines.slice(-6)).join('\n'), ms: Math.round(performance.now() - started) })
    }
  } catch (err) {
    postMessage({ type: 'fatal', text: `Python 运行环境加载失败：${err instanceof Error ? err.message : String(err)}` })
  }
}
