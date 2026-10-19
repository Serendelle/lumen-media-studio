import { getJson, postJson } from './http'

export type ConversationState =
  | 'IDLE'
  | 'ANALYZING'
  | 'AWAITING_INPUT'
  | 'PLANNING'
  | 'AWAITING_CONFIRMATION'
  | 'EXECUTING'
  | 'REVIEWING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'

export type AgentId = 'requirement-analyst' | 'task-planner' | 'media-executor' | 'quality-reviewer'

export interface PlanStep {
  id: number
  tool: string
  title: string
  inputs: string[]
  params: Record<string, unknown>
  rationale: string
  qualityImpact: 'none' | 'low' | 'medium' | 'high'
  estimate?: { feasible: boolean; videoKbps: number; height: number }
}

export interface Plan {
  version: number
  summary: string
  steps: PlanStep[]
  warnings: string[]
  /** the user's hard requirements on the final files, as the reviewer will check them */
  constraintsText?: string
}

export interface ReviewCheck {
  stepId: number
  name: string
  ok: boolean
  expected: string
  actual: string
  afterRetry?: boolean
}

export interface ReviewOutput {
  stepId: number
  tool: string
  status: string
  taskId?: string
  fileId?: string
  filename?: string
  sizeBytes?: number
  mediaType?: string
}

export interface Review {
  checks: ReviewCheck[]
  retries: number
  allPassed: boolean
  /** VERIFIED only when every check, including the user's constraints, held */
  verdict?: 'VERIFIED' | 'FAILED'
  outputs: ReviewOutput[]
}

export interface ConversationView {
  id: string
  title: string
  state: ConversationState
  planVersion: number
  plan: Plan | null
  context: Record<string, any>
  promptVersion: string
  createdAt: number
  updatedAt: number
}

export interface AiEvent {
  seq: number
  type: string
  payload: any
}

export interface Snapshot {
  conversation: ConversationView
  events: AiEvent[]
}

export const aiApi = {
  create: () => postJson<ConversationView>('/api/ai/conversations', {}),
  list: () => getJson<{ content: ConversationView[] }>('/api/ai/conversations', { params: { size: 30 } }),
  get: (id: string) => getJson<Snapshot>(`/api/ai/conversations/${encodeURIComponent(id)}`),
  send: (id: string, text: string, fileIds: string[]) =>
    postJson<ConversationView>(`/api/ai/conversations/${encodeURIComponent(id)}/messages`, { text, fileIds }),
  confirm: (id: string, version: number, approve: boolean) =>
    postJson<ConversationView>(`/api/ai/conversations/${encodeURIComponent(id)}/confirm`, { version, approve }),
  cancel: (id: string) => postJson<ConversationView>(`/api/ai/conversations/${encodeURIComponent(id)}/cancel`),
  agents: () => getJson<{ promptVersion: string; agents: { id: AgentId; title: string; agent: string; version: string; tools: string[]; llm: boolean }[] }>('/api/ai/agents'),
}
