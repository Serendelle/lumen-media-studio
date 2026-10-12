import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { aiApi, type AgentId, type AiEvent, type ConversationState, type ConversationView, type Plan, type Review } from '@/api/ai'
import { openSse, type SseHandle } from '@/api/sse'

export type AgentStatus = 'idle' | 'working' | 'waiting' | 'done' | 'partial' | 'failed' | 'cancelled'

export interface StepState {
  stepId: number
  status: string
  progress: number
  stage?: string
  taskId?: string
  outputFileId?: string
  message?: string
}

export const AGENTS: { id: AgentId; no: string; title: string; en: string }[] = [
  { id: 'requirement-analyst', no: '01', title: '需求理解官', en: 'Analyst' },
  { id: 'task-planner', no: '02', title: '任务规划官', en: 'Planner' },
  { id: 'media-executor', no: '03', title: '处理执行官', en: 'Executor' },
  { id: 'quality-reviewer', no: '04', title: '质量审核官', en: 'Reviewer' },
]

const BUSY: ConversationState[] = ['ANALYZING', 'PLANNING', 'EXECUTING', 'REVIEWING']

/**
 * AI conversation state is a pure projection of the server's ordered event log: a snapshot on load, then live
 * events over SSE resumed from the last seq. Events at or below the last applied seq are ignored, so reconnects
 * and replays can never duplicate anything. Streamed text deltas are transient and replaced by the final message.
 */
export const useAiStore = defineStore('ai', () => {
  const conversations = ref<ConversationView[]>([])
  const current = ref<ConversationView | null>(null)
  const events = ref<AiEvent[]>([])
  const lastSeq = ref(0)
  const streaming = ref<{ agent: string; text: string } | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  let stream: SseHandle | null = null

  const state = computed<ConversationState>(() => current.value?.state ?? 'IDLE')
  const busy = computed(() => BUSY.includes(state.value))

  const agents = computed(() => {
    const m: Record<string, { status: AgentStatus; note: string }> = {}
    for (const a of AGENTS) m[a.id] = { status: 'idle', note: '' }
    for (const e of events.value) {
      if (e.type === 'state' && e.payload.state === 'ANALYZING') {
        // a new run starts: reset the constellation
        for (const a of AGENTS) m[a.id] = { status: 'idle', note: '' }
      }
      if (e.type === 'agent') {
        if (e.payload.agent === 'all') {
          for (const a of AGENTS) if (m[a.id].status === 'working') m[a.id] = { status: 'cancelled', note: e.payload.note }
        } else if (m[e.payload.agent]) {
          m[e.payload.agent] = { status: e.payload.status, note: e.payload.note }
        }
      }
      if (e.type === 'error') {
        for (const a of AGENTS) if (m[a.id].status === 'working') m[a.id] = { status: 'failed', note: e.payload.message }
      }
    }
    return m
  })

  const plan = computed<Plan | null>(() => {
    for (let i = events.value.length - 1; i >= 0; i--) if (events.value[i].type === 'plan') return events.value[i].payload
    return null
  })

  const steps = computed(() => {
    const m: Record<number, StepState> = {}
    let latestPlanSeq = 0
    for (const e of events.value) if (e.type === 'confirmed') latestPlanSeq = e.seq
    for (const e of events.value) {
      if (e.type !== 'step' || e.seq < latestPlanSeq) continue
      const p = e.payload
      const prev = m[p.stepId]
      m[p.stepId] = { ...prev, ...p, progress: p.progress ?? prev?.progress ?? 0 }
    }
    return m
  })

  const review = computed<Review | null>(() => {
    for (let i = events.value.length - 1; i >= 0; i--) {
      if (events.value[i].type === 'review') return events.value[i].payload
      if (events.value[i].type === 'state' && events.value[i].payload.state === 'ANALYZING') return null
    }
    return null
  })

  /** Clarification options of the latest analyst turn, offered as quick replies. */
  const quickReplies = computed<string[]>(() => {
    if (state.value !== 'AWAITING_INPUT') return []
    for (let i = events.value.length - 1; i >= 0; i--) {
      const e = events.value[i]
      if (e.type === 'requirements') {
        const out: string[] = []
        for (const q of e.payload.clarifications ?? []) for (const o of q.options ?? []) if (out.length < 6) out.push(String(o))
        return out
      }
    }
    return []
  })

  function apply(e: AiEvent) {
    if (e.seq <= lastSeq.value) return // duplicate from a replay
    lastSeq.value = e.seq
    events.value.push(e)
    if (e.type === 'state' && current.value) current.value = { ...current.value, state: e.payload.state }
    if (e.type === 'plan' && current.value) current.value = { ...current.value, plan: e.payload, planVersion: e.payload.version }
    if (e.type === 'message' && streaming.value && e.payload.agent === streaming.value.agent) streaming.value = null
    if (e.type === 'state' && !BUSY.includes(e.payload.state)) streaming.value = null
  }

  function connect(id: string) {
    stream?.close()
    stream = openSse(
      `/api/ai/conversations/${encodeURIComponent(id)}/events?after=${lastSeq.value}`,
      (msg) => {
        if (!msg.event) return
        let payload: any
        try {
          payload = JSON.parse(msg.data)
        } catch {
          return
        }
        if (msg.event === 'text.delta') {
          if (!streaming.value || streaming.value.agent !== payload.agent) streaming.value = { agent: payload.agent, text: '' }
          streaming.value.text += payload.delta
          return
        }
        const seq = Number(msg.id)
        if (Number.isFinite(seq)) apply({ seq, type: msg.event, payload })
      },
      { onFatal: () => (error.value = '与智能服务的连接已断开') },
    )
  }

  async function open(id: string) {
    loading.value = true
    error.value = null
    try {
      const snap = await aiApi.get(id)
      current.value = snap.conversation
      events.value = []
      lastSeq.value = 0
      streaming.value = null
      for (const e of snap.events) apply(e)
      connect(id) // resumes from lastSeq: nothing missed between snapshot and stream
    } catch (e) {
      error.value = (e as Error).message
    } finally {
      loading.value = false
    }
  }

  async function refreshList() {
    try {
      conversations.value = (await aiApi.list()).content
    } catch {
      /* non-fatal */
    }
  }

  async function startNew() {
    const c = await aiApi.create()
    conversations.value = [c, ...conversations.value]
    await open(c.id)
    return c
  }

  async function send(text: string, fileIds: string[]) {
    if (!current.value) await startNew()
    const c = await aiApi.send(current.value!.id, text, fileIds)
    current.value = { ...current.value!, title: c.title }
    const i = conversations.value.findIndex((x) => x.id === c.id)
    if (i >= 0) conversations.value[i] = { ...conversations.value[i], title: c.title, state: c.state }
    else conversations.value = [c, ...conversations.value]
  }

  async function confirm(approve: boolean) {
    if (!current.value) return
    await aiApi.confirm(current.value.id, current.value.planVersion, approve)
  }

  async function cancel() {
    if (current.value) await aiApi.cancel(current.value.id)
  }

  function close() {
    stream?.close()
    stream = null
  }

  return {
    conversations, current, events, lastSeq, streaming, loading, error,
    state, busy, agents, plan, steps, review, quickReplies,
    open, refreshList, startNew, send, confirm, cancel, close,
  }
})
