/** A dropped or picked file together with its path inside the folder (the evidence used for matching). */
export interface PathedFile {
  file: File
  relativePath: string
}

const SKIP = /(^|\/)(\.|__MACOSX|Thumbs\.db$|desktop\.ini$)/i

/**
 * Collects every file of a drag-and-drop, descending into folders (Chrome / Edge / Firefox / Safari entries API).
 * Hidden files and OS clutter (.DS_Store, Thumbs.db, __MACOSX) are skipped.
 */
export async function filesFromDrop(dt: DataTransfer): Promise<PathedFile[]> {
  const entries: FileSystemEntry[] = []
  for (const item of Array.from(dt.items)) {
    const e = item.kind === 'file' ? item.webkitGetAsEntry?.() : null
    if (e) entries.push(e)
  }
  if (!entries.length) return filesFromInput(dt.files)
  const out: PathedFile[] = []
  for (const e of entries) await walk(e, '', out)
  return out.filter((f) => !SKIP.test(f.relativePath))
}

/** From an <input type=file webkitdirectory> or a plain multi-file input. */
export function filesFromInput(list: FileList | null): PathedFile[] {
  return Array.from(list ?? [])
    .map((file) => ({ file, relativePath: (file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name }))
    .filter((f) => !SKIP.test(f.relativePath))
}

async function walk(entry: FileSystemEntry, prefix: string, out: PathedFile[]) {
  const path = prefix ? `${prefix}/${entry.name}` : entry.name
  if (entry.isFile) {
    const file = await new Promise<File>((resolve, reject) => (entry as FileSystemFileEntry).file(resolve, reject))
    out.push({ file, relativePath: path })
    return
  }
  if (!entry.isDirectory) return
  const reader = (entry as FileSystemDirectoryEntry).createReader()
  // readEntries returns at most ~100 entries per call: keep reading until it returns none
  for (;;) {
    const batch = await new Promise<FileSystemEntry[]>((resolve, reject) => reader.readEntries(resolve, reject))
    if (!batch.length) break
    for (const child of batch) await walk(child, path, out)
  }
}
