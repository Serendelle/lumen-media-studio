import { fetchEventSource, type EventSourceMessage } from '@microsoft/fetch-event-source'
import { tokenStore } from './http'

class FatalSseError extends Error {}

export interface SseHandle {
  close(): void
}

/**
 * Authenticated SSE (EventSource cannot send an Authorization header, so this uses fetch streaming).
 * Reconnects with capped backoff on network drops; 4xx is fatal (no access / not found) and is not retried.
 * The server always sends a full snapshot first, so a reconnect can never miss a state transition.
 */
export function openSse(
  url: string,
  onEvent: (msg: EventSourceMessage) => void,
  opts: { onFatal?: (status: number) => void; onClose?: () => void } = {},
): SseHandle {
  const ctrl = new AbortController()
  let attempt = 0
  void fetchEventSource(url, {
    signal: ctrl.signal,
    openWhenHidden: true, // keep progress flowing in background tabs
    headers: { Authorization: `Bearer ${tokenStore.get() ?? ''}`, Accept: 'text/event-stream' },
    async onopen(res) {
      if (res.ok && res.headers.get('content-type')?.includes('text/event-stream')) {
        attempt = 0
        return
      }
      if (res.status >= 400 && res.status < 500) {
        opts.onFatal?.(res.status)
        throw new FatalSseError(String(res.status))
      }
      throw new Error(`SSE open failed: ${res.status}`)
    },
    onmessage(msg) {
      if (msg.event || msg.data) onEvent(msg)
    },
    onclose() {
      // server completed the stream (task reached a terminal state)
      opts.onClose?.()
      throw new FatalSseError('closed')
    },
    onerror(err) {
      if (err instanceof FatalSseError) throw err
      attempt++
      return Math.min(15_000, 500 * 2 ** attempt) // retry delay
    },
  }).catch(() => {
    /* fatal or aborted: already reported */
  })
  return { close: () => ctrl.abort() }
}
