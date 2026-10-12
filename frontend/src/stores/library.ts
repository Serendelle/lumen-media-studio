import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { filesApi } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import type { FileView, MediaKind } from '@/api/types'

export interface PendingUpload {
  id: string
  name: string
  size: number
  progress: number
  error?: string
  abort: AbortController
}

/** The user's uploaded inputs, shared by every tool so a file is uploaded once and reused. */
export const useLibraryStore = defineStore('library', () => {
  const files = ref<FileView[]>([])
  const uploads = ref<PendingUpload[]>([])
  const loading = ref(false)
  const loadError = ref<string | null>(null)

  async function refresh() {
    loading.value = true
    try {
      files.value = (await filesApi.list('INPUT', 0, 100)).content
      loadError.value = null
    } catch (e) {
      loadError.value = (e as Error).message
    } finally {
      loading.value = false
    }
  }

  function byKind(...kinds: MediaKind[]) {
    return computed(() => files.value.filter((f) => kinds.includes(f.mediaType)))
  }

  async function upload(file: File): Promise<FileView | null> {
    const pending: PendingUpload = {
      id: crypto.randomUUID(),
      name: file.name,
      size: file.size,
      progress: 0,
      abort: new AbortController(),
    }
    uploads.value.push(pending)
    const entry = () => uploads.value.find((u) => u.id === pending.id)
    try {
      const fv = await filesApi.upload(file, (p) => {
        const e = entry()
        if (e) e.progress = p
      }, pending.abort.signal)
      files.value = [fv, ...files.value.filter((f) => f.fileId !== fv.fileId)]
      uploads.value = uploads.value.filter((u) => u.id !== pending.id)
      return fv
    } catch (e) {
      const ent = entry()
      if (ent) ent.error = e instanceof ApiError ? e.message : '上传失败'
      return null
    }
  }

  function dismissUpload(id: string) {
    const u = uploads.value.find((x) => x.id === id)
    u?.abort.abort()
    uploads.value = uploads.value.filter((x) => x.id !== id)
  }

  async function remove(id: string) {
    await filesApi.remove(id)
    files.value = files.value.filter((f) => f.fileId !== id)
  }

  return { files, uploads, loading, loadError, refresh, byKind, upload, dismissUpload, remove }
})
