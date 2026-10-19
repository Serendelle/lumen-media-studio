/// <reference lib="webworker" />
import { lockdown, MAX_OUTPUT } from './lockdown'

// user JavaScript runs here: no DOM, no network, killed by the page after the time limit
let size = 0
function out(kind: 'out' | 'err', parts: unknown[]) {
  const text = parts.map((p) => (typeof p === 'string' ? p : safeStringify(p))).join(' ')
  if (size > MAX_OUTPUT) return
  size += text.length
  postMessage({ type: kind, text: size > MAX_OUTPUT ? text.slice(0, 200) + '\n…（输出过长，已截断）' : text })
}
function safeStringify(v: unknown) {
  try {
    return typeof v === 'object' ? JSON.stringify(v) : String(v)
  } catch {
    return String(v)
  }
}

onmessage = async (e: MessageEvent<{ code: string }>) => {
  lockdown()
  const c = console as any
  c.log = c.info = (...a: unknown[]) => out('out', a)
  c.warn = c.error = (...a: unknown[]) => out('err', a)
  const started = performance.now()
  try {
    // async wrapper so top-level await works; Function, not eval, so the worker's own variables are not in scope
    await new Function(`"use strict"; return (async () => {\n${e.data.code}\n})()`)()
    postMessage({ type: 'done', ms: Math.round(performance.now() - started) })
  } catch (err) {
    postMessage({ type: 'error', text: err instanceof Error ? `${err.name}: ${err.message}` : String(err), ms: Math.round(performance.now() - started) })
  }
}
