import { fetchEventSource } from '@microsoft/fetch-event-source'
import { ApiError, http, tokenStore } from './http'
export { safeTencentUrl } from '@/components/collaboration/tencentLink'

export type ProjectRole = 'OWNER' | 'EDITOR' | 'REVIEWER' | 'VIEWER'
export type PublicationStatus = 'DRAFT' | 'REVIEW' | 'PUBLISHED'
export type ReviewStatus = 'PENDING' | 'APPROVED' | 'CHANGES_REQUESTED'
export interface Project {
  id: string; name: string; description: string; status: PublicationStatus; version: number
  role: ProjectRole; ownerId: number; createdAt?: number; updatedAt: number
}
export interface ProjectMember {
  userId: number; username: string; displayName: string; role: ProjectRole; joinedAt: number; online: boolean
}
export interface Page<T> { items: T[]; total: number; page: number }
export interface SharedDocument {
  id: string; title: string; documentType: 'DOCUMENT' | 'SPREADSHEET' | 'SLIDE' | 'OTHER'
  externalUrl: string; provider: string; visibility: 'ALL' | 'SELECTED'; visibleUserIds: number[]
  version: number; createdBy?: number; createdAt: number; updatedAt: number
}
export interface FileVersion {
  id: string; assetId: string; versionNumber: number; filename: string; sizeBytes: number; mediaType: string
  sha256: string; uploadedBy: number | string; uploaderName?: string; createdAt: number; reviewStatus: ReviewStatus
  documentId?: string; reviewNote?: string; archived?: boolean; mimeType?: string
}
export type SharedFile = FileVersion
export interface ProjectTodo {
  id: string; title: string; status: 'OPEN' | 'DOING' | 'DONE'; assigneeId: number | null
  version: number; createdAt: number; updatedAt: number
}
export interface Activity { id: string; action: string; actorName: string; createdAt: number; details?: unknown }
export interface ProjectTask {
  id: string; type: string; status: string; progress: number; message?: string; errorCode?: string
  outputVersionId?: string; outputFilename?: string; processingStatus?: string; createdAt: number
}
export interface FileCheck {
  versionId: string; filename: string; sha256: string; sizeBytes: number; mediaType: string
  checks: unknown[]; facts: Record<string, unknown>; fields?: string[]; reviewStatus: ReviewStatus
}
export interface CheckReport {
  generatedAt: number; files: FileCheck[]; missing: { key: string; label: string }[]
  summary: { files: number; approved: number; changesRequested: number; missing: number }; scope: string
  truncated?: boolean; notice?: string
}
export interface AssistantResult { content: string; model: string; generatedAt: number }
const base = (id: string) => `/api/v2/projects/${encodeURIComponent(id)}`
const data = async <T>(promise: Promise<{ data: T }>) => (await promise).data
export const collaborationApi = {
  list: (page = 0) => data(http.get<Page<Project>>('/api/v2/projects', { params: { page, size: 30 } })),
  create: (name: string, description: string) => data(http.post<Project>('/api/v2/projects', { name, description })),
  project: (id: string) => data(http.get<Project>(base(id))),
  update: (id: string, body: { name: string; description: string; version: number }) => data(http.patch<Project>(base(id), body)),
  remove: (id: string) => data(http.delete(base(id))),
  leave: (id: string) => data(http.post(`${base(id)}/leave`)),
  members: (id: string) => data(http.get<ProjectMember[]>(`${base(id)}/members`)),
  invite: (id: string, username: string, role: ProjectRole) => data(http.post(`${base(id)}/members`, { username, role })),
  setRole: (id: string, userId: number, role: ProjectRole) => data(http.patch(`${base(id)}/members/${userId}`, { role })),
  removeMember: (id: string, userId: number) => data(http.delete(`${base(id)}/members/${userId}`)),
  activities: (id: string, page = 0) => data(http.get<Page<Activity>>(`${base(id)}/activities`, { params: { page, size: 30 } })),
  documents: (id: string, page = 0) => data(http.get<Page<SharedDocument>>(`${base(id)}/documents`, { params: { page, size: 20 } })),
  saveDocument: (id: string, body: Omit<SharedDocument, 'id' | 'provider' | 'createdAt' | 'updatedAt' | 'createdBy' | 'version'> & { version?: number }, documentId?: string) =>
    documentId ? data(http.patch<SharedDocument>(`${base(id)}/documents/${documentId}`, body)) : data(http.post<SharedDocument>(`${base(id)}/documents`, body)),
  unlink: (id: string, documentId: string) => data(http.delete(`${base(id)}/documents/${documentId}`)),
  documentVersions: (id: string, documentId: string) => data(http.get<FileVersion[]>(`${base(id)}/documents/${documentId}/versions`)),
  importDocument: (id: string, documentId: string, file: File, progress: (percent: number) => void) => {
    const form = new FormData(); form.append('file', file)
    return data(http.post<FileVersion>(`${base(id)}/documents/${documentId}/imports`, form, {
      timeout: 300_000, onUploadProgress: e => { if (e.total) progress(Math.round(e.loaded * 100 / e.total)) },
    }))
  },
  files: (id: string, page = 0, kind = '', archived = false) => data(http.get<Page<SharedFile>>(`${base(id)}/files`, { params: { page, size: 20, kind: kind || undefined, archived } })),
  upload: (id: string, file: File, progress: (percent: number) => void, assetId?: string) => {
    const form = new FormData(); form.append('file', file); if (assetId) form.append('assetId', assetId)
    return data(http.post<FileVersion>(`${base(id)}/files`, form, { timeout: 300_000, onUploadProgress: e => { if (e.total) progress(Math.round(e.loaded * 100 / e.total)) } }))
  },
  fileVersions: (id: string, assetId: string) => data(http.get<FileVersion[]>(`${base(id)}/files/${assetId}/versions`)),
  content: (id: string, versionId: string, inline = false) => data(http.get<Blob>(`${base(id)}/files/${versionId}/content`, { params: { inline }, responseType: 'blob' })),
  archive: (id: string, assetId: string, archived: boolean) => data(http.patch(`${base(id)}/files/${assetId}`, { archived })),
  review: (id: string, versionId: string, status: Exclude<ReviewStatus, 'PENDING'>, note: string) => data(http.post(`${base(id)}/files/${versionId}/review`, { status, note })),
  todos: (id: string) => data(http.get<ProjectTodo[]>(`${base(id)}/todos`)),
  addTodo: (id: string, title: string, assigneeId: number | null) => data(http.post<ProjectTodo>(`${base(id)}/todos`, { title, assigneeId })),
  updateTodo: (id: string, todo: ProjectTodo, status: ProjectTodo['status']) => data(http.patch<ProjectTodo>(`${base(id)}/todos/${todo.id}`, { title: todo.title, assigneeId: todo.assigneeId, status, version: todo.version })),
  removeTodo: (id: string, todoId: string) => data(http.delete(`${base(id)}/todos/${todoId}`)),
  checks: (id: string) => data(http.get<CheckReport>(`${base(id)}/checks`)),
  assistant: (id: string) => data(http.post<AssistantResult>(`${base(id)}/assistant`, { consent: true })),
  tasks: (id: string) => data(http.get<ProjectTask[]>(`${base(id)}/tasks`)),
  process: (id: string, versionId: string, type: string, params: Record<string, unknown>, idempotencyKey: string) => data(http.post<ProjectTask>(`${base(id)}/files/${versionId}/tasks`, { type, params, idempotencyKey })),
  delivery: (id: string, idempotencyKey: string) => data(http.post<ProjectTask>(`${base(id)}/deliveries`, { idempotencyKey })),
  publish: (id: string, status: PublicationStatus, version: number) => data(http.post<Project>(`${base(id)}/publication`, { status, version })),
}

export const roleLabel: Record<ProjectRole, string> = { OWNER: '负责人', EDITOR: '编辑', REVIEWER: '审核', VIEWER: '查看' }
export const publicationLabel: Record<PublicationStatus, string> = { DRAFT: '准备中', REVIEW: '审核中', PUBLISHED: '已发布' }
export const reviewLabel: Record<ReviewStatus, string> = { PENDING: '待审核', APPROVED: '已通过', CHANGES_REQUESTED: '需修改' }
export const dateTime = (value?: number) => value ? new Date(value).toLocaleString('zh-CN') : '—'
export function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a'); a.href = url; a.download = filename; a.click()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}
class StopStream extends Error {}
/** Streams use bearer headers; reconnect always reloads authorized state from the server. */
export function projectEvents(id: string, callbacks: {
  onChange: () => void; onPresence: (ids: number[]) => void; onStatus: (status: 'connecting' | 'live' | 'retrying' | 'closed') => void
  onDenied: (status: number) => void
}): { close: () => void } {
  const controller = new AbortController()
  let attempt = 0
  callbacks.onStatus('connecting')
  void fetchEventSource(`${base(id)}/events`, {
    signal: controller.signal, openWhenHidden: true,
    headers: { Authorization: `Bearer ${tokenStore.get() ?? ''}`, Accept: 'text/event-stream' },
    async onopen(response) {
      if (response.ok && response.headers.get('content-type')?.includes('text/event-stream')) {
        attempt = 0; callbacks.onStatus('live'); callbacks.onChange(); return
      }
      if ([401, 403, 404].includes(response.status)) { callbacks.onDenied(response.status); throw new StopStream() }
      throw new Error(`连接失败（${response.status}）`)
    },
    onmessage(message) {
      if (message.event === 'presence') {
        try { callbacks.onPresence((JSON.parse(message.data) as { userIds: number[] }).userIds) } catch { /* next presence snapshot restores state */ }
      } else if (message.event === 'change' || message.event === 'snapshot') callbacks.onChange()
      else if (message.event === 'revoked') { callbacks.onDenied(403); throw new StopStream() }
    },
    onclose() { throw new Error('连接已断开') },
    onerror(error) {
      if (error instanceof StopStream) throw error
      callbacks.onStatus('retrying'); attempt++
      return Math.min(15_000, 800 * 2 ** Math.min(attempt, 5))
    },
  }).catch(() => { if (!controller.signal.aborted) callbacks.onStatus('closed') })
  return { close() { controller.abort(); callbacks.onStatus('closed') } }
}

export function isAccessError(error: unknown) {
  return error instanceof ApiError && [401, 403, 404].includes(error.status)
}
