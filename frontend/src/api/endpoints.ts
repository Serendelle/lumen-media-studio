import { getJson, http, idempotencyKey, postJson } from './http'
import type {
  AuthResult,
  CompressionPlan,
  ConversionMatrix,
  DocumentInspection,
  PdfPagePreview,
  FileView,
  MediaCompare,
  MetadataCapability,
  MetadataView,
  Page,
  SystemCapabilities,
  TaskStatus,
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
