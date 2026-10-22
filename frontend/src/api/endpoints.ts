import { getJson, http, publicHttp, idempotencyKey, postJson } from './http'
import type {
  AuthResult,
  CompressionPlan,
  ConversionMatrix,
  DocumentFormat,
  DocumentInspection,
  PdfPagePreview,
  MaterialSpec,
  LessonCapabilities,
  LessonSummary,
  LessonView,
  SpecBoard,
  SpecDraft,
  SpecItem,
  SpecProject,
  SpecProjectSummary,
  CollectionLink,
  PublicCollection,
  FileView,
  MediaCompare,
  MetadataCapability,
  MetadataView,
  Page,
  SystemCapabilities,
  TaskStatus,
  TaskHistoryDeletion,
  TaskView,
  Ticket,
  UserView,
  WorkspaceSummary,
} from './types'

const withKey = () => ({ headers: { 'Idempotency-Key': idempotencyKey() } })

export const authApi = {
  register: (username: string, password: string, displayName?: string) =>
    postJson<AuthResult>('/api/auth/register', { username, password, displayName }),
  login: (username: string, password: string) => postJson<AuthResult>('/api/auth/login', { username, password }),
  me: () => getJson<UserView>('/api/auth/me'),
}

export const documentsApi = {
  matrix: () => getJson<ConversionMatrix>('/api/v2/documents/matrix'),
  inspect: (fileId: string) => getJson<DocumentInspection>(`/api/v2/documents/${encodeURIComponent(fileId)}/inspect`),
  preview: (fileId: string, page = 1) =>
    getJson<PdfPagePreview>(`/api/v2/documents/${encodeURIComponent(fileId)}/preview`, { params: { page } }),
  convert: (sourceFileId: string, options: Record<string, unknown>) =>
    postJson<{ taskId: string; status: string; statusUrl: string }>(
      '/api/v2/documents/convert', { sourceFileId, targetFormat: 'pdf', options }, withKey()),
  convertTo: (sourceFileIds: string[], targetFormat: DocumentFormat, options: Record<string, unknown>) =>
    postJson<{ taskId: string; status: string; statusUrl: string }>(
      '/api/v2/documents/convert',
      { ...(sourceFileIds.length === 1 ? { sourceFileId: sourceFileIds[0] } : { sourceFileIds }), targetFormat, options }, withKey()),
}

const sp = (id: string) => `/api/v2/specs/${encodeURIComponent(id)}`

export const specsApi = {
  list: () => getJson<{ items: SpecProjectSummary[]; total: number }>('/api/v2/specs', { params: { size: 100 } }),
  create: (name: string) => postJson<SpecProject>('/api/v2/specs', { name }),
  get: (id: string) => getJson<SpecProject>(sp(id)),
  remove: (id: string) => http.delete(sp(id)),
  extract: (id: string, noticeText: string) => postJson<SpecDraft>(`${sp(id)}/extract`, { noticeText }, { timeout: 180_000 }),
  saveSpec: async (id: string, spec: MaterialSpec, version: number, noticeText?: string) =>
    (await http.put<SpecProject>(`${sp(id)}/spec`, { spec, version, noticeText })).data,
  importRoster: (id: string, fileId: string, headerRow?: number, nameField?: string) =>
    postJson<SpecProject>(`${sp(id)}/roster`, { fileId, headerRow, nameField }),
  setTemplates: async (id: string, docxFileId?: string | null, xlsxFileId?: string | null, xlsxHeaderRow?: number | null) =>
    (await http.put<SpecProject>(`${sp(id)}/templates`, { docxFileId, xlsxFileId, xlsxHeaderRow })).data,
  board: (id: string) => getJson<SpecBoard>(`${sp(id)}/items`),
  collections: (id: string) => getJson<CollectionLink[]>(`${sp(id)}/collections`),
  createCollection: (id: string, days: number) => postJson<CollectionLink>(`${sp(id)}/collections`, { days }),
  revokeCollection: (id: string, linkId: string) => http.delete(`${sp(id)}/collections/${encodeURIComponent(linkId)}`),
  organize: (id: string) => postJson<SpecBoard>(`${sp(id)}/organize`, {}, { timeout: 180_000 }),
  attach: (id: string, files: { fileId: string; relativePath: string }[]) => postJson<SpecBoard>(`${sp(id)}/items`, { files }),
  assign: async (id: string, itemId: string, personKey: string | null, slotKey: string | null) =>
    (await http.patch<SpecItem>(`${sp(id)}/items/${encodeURIComponent(itemId)}`, { personKey, slotKey })).data,
  removeItem: (id: string, itemId: string) => http.delete(`${sp(id)}/items/${encodeURIComponent(itemId)}`),
  fix: (id: string, itemIds?: string[]) => postJson<SpecBoard>(`${sp(id)}/fix`, { itemIds }),
  deliver: (id: string, onlyComplete: boolean, includeOriginals: boolean) =>
    postJson<{ taskId: string; status: string }>(`${sp(id)}/deliveries`, { onlyComplete, includeOriginals }, withKey()),
  deliveries: (id: string) => getJson<TaskView[]>(`${sp(id)}/deliveries`),
}

const collectionPath = (token: string) => `/api/public/collections/${encodeURIComponent(token)}`
export const collectionApi = {
  get: async (token: string) => (await publicHttp.get<PublicCollection>(collectionPath(token))).data,
  submit: async (token: string, file: File, name: string, identifier: string, slotKey: string, requestKey: string,
    progress: (percent: number) => void) => {
    const form = new FormData()
    form.append('file', file); form.append('name', name); form.append('identifier', identifier)
    form.append('slotKey', slotKey); form.append('requestKey', requestKey)
    return (await publicHttp.post<{ receipt: string; filename: string; submittedAt: number }>(`${collectionPath(token)}/submissions`, form,
      { onUploadProgress: (e) => progress(e.total ? Math.min(99, Math.round(e.loaded * 100 / e.total)) : 0) })).data
  },
}

const ls = (id: string) => `/api/v2/lessons/${encodeURIComponent(id)}`

export const lessonsApi = {
  capabilities: () => getJson<LessonCapabilities>('/api/v2/lessons/capabilities'),
  list: () => getJson<{ items: LessonSummary[] }>('/api/v2/lessons'),
  get: (id: string) => getJson<LessonView>(ls(id)),
  analyze: (videoFileId: string) => postJson<{ taskId: string; status: string }>('/api/v2/lessons', { videoFileId }, withKey()),
  remove: (id: string) => http.delete(ls(id)),
  ticket: (id: string) => postJson<{ ticket: string; frameUrl: string; expiresAt: number }>(`${ls(id)}/ticket`),
}

export const compareApi = {
  media: (sourceFileId: string, outputFileId: string) =>
    getJson<MediaCompare>('/api/v2/media/compare', { params: { sourceFileId, outputFileId } }),
}

export const workspaceApi = {
  summary: () => getJson<WorkspaceSummary>('/api/v2/workspace/summary'),
}

export const systemApi = {
  capabilities: () => getJson<SystemCapabilities>('/api/system/capabilities'),
}

export const filesApi = {
  /** Generic upload with real upload progress (0-1). */
  async upload(file: File, onProgress?: (fraction: number) => void, signal?: AbortSignal): Promise<FileView> {
    const form = new FormData()
    form.append('file', file)
    const r = await http.post<FileView>('/api/files/upload', form, {
      timeout: 0,
      signal,
      onUploadProgress: (e) => {
        if (onProgress && e.total) onProgress(e.loaded / e.total)
      },
    })
    return r.data
  },
  list: (role: 'INPUT' | 'OUTPUT', page = 0, size = 50) =>
    getJson<Page<FileView>>('/api/files', { params: { role, page, size } }),
  get: (id: string) => getJson<FileView>(`/api/files/${encodeURIComponent(id)}`),
  remove: (id: string) => http.delete(`/api/files/${encodeURIComponent(id)}`),
  ticket: (id: string) => postJson<Ticket>(`/api/files/${encodeURIComponent(id)}/ticket`),
}

export const tasksApi = {
  list: (page = 0, size = 30, status?: TaskStatus[]) =>
    getJson<Page<TaskView>>('/api/tasks', { params: { page, size, status: status?.length ? status.join(',') : undefined } }),
  get: (id: string) => getJson<TaskView>(`/api/tasks/${encodeURIComponent(id)}`),
  cancel: (id: string) => postJson<TaskView>(`/api/tasks/${encodeURIComponent(id)}/cancel`),
  retry: (id: string) => postJson<TaskView>(`/api/tasks/${encodeURIComponent(id)}/retry`),
  remove: async (id: string) => (await http.delete<TaskHistoryDeletion>(`/api/tasks/${encodeURIComponent(id)}`)).data,
  removeMany: (taskIds: string[]) => postJson<TaskHistoryDeletion>('/api/tasks/history/delete', { taskIds }),
  clearHistory: async () => (await http.delete<TaskHistoryDeletion>('/api/tasks/history')).data,
}

type Created = { taskId: string; status: string }

/** Professional-mode operations on the original endpoints (shared engine with AI mode). */
export const mediaApi = {
  compressVideo: (body: Record<string, unknown>) => postJson<Created>('/api/video/compress', body, withKey()),
  estimateCompression: (body: Record<string, unknown>) => postJson<CompressionPlan>('/api/video/compress/estimate', body),
  convertVideo: (body: Record<string, unknown>) => postJson<Created>('/api/video/convert', body, withKey()),
  watermark: (body: Record<string, unknown>) => postJson<Created>('/api/video/watermark', body, withKey()),
  extractAudio: (body: Record<string, unknown>) => postJson<Created>('/api/audio/extract', body, withKey()),
  processImages: (filenames: string[], tool: string, settings: Record<string, unknown>) =>
    postJson<Created>('/api/image/process', { filenames, tool, settings }, withKey()),
  removeBackground: (uniqueFilename: string) =>
    postJson<Created>('/api/image/remove-background', { uniqueFilename }, withKey()),
  pdf: (filenames: string[], tool: 'pdf-to-image' | 'images-to-pdf', settings: Record<string, unknown>) =>
    postJson<Created>('/api/pdf/process', { filenames, tool, settings }, withKey()),
  viewMetadata: (uniqueFilename: string) => postJson<MetadataView>('/api/metadata/view', { uniqueFilename }),
  scrubMetadata: (uniqueFilename: string, mode: 'all' | 'location') =>
    postJson<{ taskId: string; downloadFilename?: string; status?: string; result?: any }>(
      '/api/metadata/remove', { uniqueFilename, mode }, { ...withKey(), timeout: 150_000 }),
  modifyMetadata: (body: Record<string, unknown>) =>
    postJson<{ taskId: string; downloadFilename?: string; status?: string; result?: any }>(
      '/api/metadata/modify', body, { ...withKey(), timeout: 150_000 }),
  metadataCapabilities: () => getJson<Record<string, MetadataCapability>>('/api/metadata/capabilities'),
}
