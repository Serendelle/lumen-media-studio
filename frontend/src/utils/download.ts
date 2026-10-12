import { filesApi } from '@/api/endpoints'

/** Downloads through a short-lived signed ticket so the browser can stream large files natively. */
export async function downloadFile(fileId: string, filename?: string) {
  const t = await filesApi.ticket(fileId)
  const a = document.createElement('a')
  a.href = t.downloadUrl
  if (filename) a.download = filename
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
}

/** Inline URL for <video>/<img>/<audio> previews (valid ~10 minutes). */
export async function previewUrl(fileId: string): Promise<string> {
  return (await filesApi.ticket(fileId)).url
}
