import { http, publicHttp, tokenStore } from './http'

export type FieldType = 'TEXT' | 'LONG_TEXT' | 'NUMBER' | 'SELECT' | 'DATE' | 'CHECKBOX'
export interface FormField { key: string; label: string; type: FieldType; required: boolean; options?: string[] }
export interface FormConfig {
  version: number
  fields: FormField[]
  viewMode: 'OWN' | 'ALL'
  editMode: 'OWN' | 'ALL' | 'DESIGNATED' | 'LOCKED'
  accepting: boolean
  allowMultiple: boolean
  allowComments: boolean
}
export type FormValues = Record<string, string | boolean | null>
export interface FormRow {
  id: string; submittedBy: string; version: number; values: FormValues
  mine: boolean; canEdit: boolean; canDelete: boolean; canAttach: boolean; canComment: boolean
  deleted: boolean; createdAt: number; updatedAt: number
  files: { id: string; filename: string; sizeBytes: number; slotKey: string; status: string }[]
}
export interface FormBoard {
  rows: FormRow[]; total: number; page: number; size: number; config: FormConfig
  actor: { name: string; role: string }; permissions: { seeAll: boolean; create: boolean; owner: boolean }
}
export interface FormStatistics {
  rows: number; participants: number; attachments: number; scope: 'OWN' | 'ALL'
  fields: { key: string; label: string; type: FieldType; filled: number; missing: number; distinct: number; duplicates: number
    groups: { value: string; count: number }[]; groupsTruncated: boolean; sum?: string; average?: string; min?: string; max?: string }[]
}
export interface FormDiscussion {
  comments: { id: string; name: string; content: string; createdAt: number }[]
  events: { name: string; action: string; createdAt: number; before?: FormValues; after?: FormValues }[]
}
export type FormTarget = { projectId: string; token?: never; secret?: never } | { projectId?: never; token: string; secret: string }
export function randomFormKey(bytes = 16) {
  return Array.from(crypto.getRandomValues(new Uint8Array(bytes)), n => n.toString(16).padStart(2, '0')).join('')
}

export function formApi(target: FormTarget) {
  const base = target.projectId ? `/api/v2/specs/${encodeURIComponent(target.projectId)}/form`
    : `/api/public/collections/${encodeURIComponent(target.token!)}/form`
  const client = target.projectId ? http : publicHttp
  // Anonymous forms and signed-in designated members share a page; stale tokens are cleared by auth.restore first.
  const config = () => ({ headers: target.projectId ? {} : {
    'X-Participant-Token': target.secret!, ...(tokenStore.get() ? { Authorization: `Bearer ${tokenStore.get()}` } : {}),
  } })
  const get = async <T>(path: string) => (await client.get<T>(base + path, config())).data
  const post = async <T>(path: string, body?: unknown) => (await client.post<T>(base + path, body ?? {}, config())).data
  const rowPath = (id: string) => `/rows/${encodeURIComponent(id)}`
  return {
    settings: () => get<FormConfig>(''),
    save: async (c: FormConfig) => (await client.put<FormConfig>(base, { config: c, version: c.version }, config())).data,
    members: () => get<{ username: string; displayName: string; role: 'EDITOR' | 'VIEWER' }[]>('/members'),
    member: (username: string, role: 'EDITOR' | 'VIEWER' | null) => client.put(base + '/members', { username, role }, config()),
    join: (name: string, secret: string) => post<{ name: string; role: string }>('/participants', { name, secret }),
    board: (page = 0, deleted = false) => get<FormBoard>(`/rows?page=${page}&size=50&deleted=${deleted}`),
    create: (values: FormValues, requestKey: string, configVersion: number) => post<FormRow>('/rows', { values, requestKey, configVersion }),
    update: async (id: string, values: FormValues, version: number, configVersion: number) =>
      (await client.patch<FormRow>(base + rowPath(id), { values, version, configVersion }, config())).data,
    remove: (r: FormRow) => client.delete(base + rowPath(r.id) + `?version=${r.version}`, config()),
    restore: (r: FormRow) => post(rowPath(r.id) + `/restore?version=${r.version}`),
    statistics: () => get<FormStatistics>('/statistics'),
    discussion: (id: string) => get<FormDiscussion>(rowPath(id) + '/discussion'),
    comment: (id: string, content: string) => post(rowPath(id) + '/comments', { content }),
    export: async () => (await client.get<Blob>(base + '/export', { ...config(), responseType: 'blob' })).data,
    download: async (rowId: string, itemId: string) =>
      (await client.get<Blob>(base + rowPath(rowId) + `/files/${encodeURIComponent(itemId)}`, { ...config(), responseType: 'blob' })).data,
    upload: (id: string, file: File, slotKey: string, key: string, progress: (percent: number) => void) => {
      const body = new FormData(); body.append('file', file); body.append('slotKey', slotKey); body.append('requestKey', key)
      return client.post(base + rowPath(id) + '/files', body, { ...config(), timeout: 300_000,
        onUploadProgress: e => progress(e.total ? Math.min(99, Math.round(e.loaded * 100 / e.total)) : 0) })
    },
  }
}

export function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob), a = document.createElement('a')
  a.href = url; a.download = filename; document.body.appendChild(a); a.click(); a.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 10_000)
}
