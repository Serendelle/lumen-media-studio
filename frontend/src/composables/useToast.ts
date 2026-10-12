import { reactive } from 'vue'

export interface Toast {
  id: number
  tone: 'info' | 'ok' | 'error'
  text: string
}

const state = reactive<{ items: Toast[] }>({ items: [] })
let seq = 0

function push(tone: Toast['tone'], text: string, ms = 4200) {
  const id = ++seq
  state.items.push({ id, tone, text })
  if (state.items.length > 4) state.items.shift()
  window.setTimeout(() => dismiss(id), ms)
}

function dismiss(id: number) {
  const i = state.items.findIndex((t) => t.id === id)
  if (i >= 0) state.items.splice(i, 1)
}

export function useToast() {
  return {
    items: state.items,
    info: (t: string) => push('info', t),
    ok: (t: string) => push('ok', t),
    error: (t: string) => push('error', t, 6500),
    dismiss,
  }
}
