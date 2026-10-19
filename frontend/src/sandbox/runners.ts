import JsWorker from './js.worker?worker'
import PyWorker from './py.worker?worker'

export type Lang = 'javascript' | 'python'
export interface RunEvent { type: 'out' | 'err'; text: string }
export interface RunResult { ok: boolean; ms?: number; error?: string; timedOut?: boolean }

export const LIMIT_MS: Record<Lang, number> = { javascript: 3000, python: 10_000 }

/** JavaScript: a fresh worker per run, terminated after the limit (so an endless loop cannot hang the page). */
export function runJs(code: string, onEvent: (e: RunEvent) => void): Promise<RunResult> {
  return new Promise((resolve) => {
    const w = new JsWorker()
    const timer = setTimeout(() => {
      w.terminate()
      resolve({ ok: false, timedOut: true, error: `运行超过 ${LIMIT_MS.javascript / 1000} 秒，已强制停止` })
    }, LIMIT_MS.javascript)
    w.onmessage = (m: MessageEvent<any>) => {
      const d = m.data
      if (d.type === 'out' || d.type === 'err') onEvent(d)
      else {
        clearTimeout(timer)
        w.terminate()
        resolve(d.type === 'done' ? { ok: true, ms: d.ms } : { ok: false, ms: d.ms, error: d.text })
      }
    }
    w.postMessage({ code })
  })
}

/** Python: the worker (and the ~12 MB runtime) is kept warm between runs, and replaced after a timeout. */
let py: Worker | null = null
let pyReady: Promise<void> | null = null

export function bootPython(): Promise<void> {
  if (pyReady) return pyReady
  py = new PyWorker()
  pyReady = new Promise((resolve, reject) => {
    py!.onmessage = (m: MessageEvent<any>) => {
      if (m.data.type === 'ready') resolve()
      else if (m.data.type === 'fatal') {
        pyReady = null
        reject(new Error(m.data.text))
      }
    }
    py!.postMessage({ type: 'boot' })
  })
  return pyReady
}

export async function runPython(code: string, onEvent: (e: RunEvent) => void): Promise<RunResult> {
  await bootPython()
  const w = py!
  return new Promise((resolve) => {
    let timer: ReturnType<typeof setTimeout> | undefined
    w.onmessage = (m: MessageEvent<any>) => {
      const d = m.data
      if (d.type === 'started') {
        timer = setTimeout(() => {
          w.terminate()
          py = null
          pyReady = null // the next run boots a fresh interpreter
          resolve({ ok: false, timedOut: true, error: `运行超过 ${LIMIT_MS.python / 1000} 秒，已强制停止（下次运行会重新加载 Python）` })
        }, LIMIT_MS.python)
      } else if (d.type === 'out' || d.type === 'err') onEvent(d)
      else {
        clearTimeout(timer)
        resolve(d.type === 'done' ? { ok: true, ms: d.ms } : { ok: false, ms: d.ms, error: d.text })
      }
    }
    w.postMessage({ type: 'run', code })
  })
}
