import type { FormValues } from '@/api/forms'

/** Send only changed fields so a revision merge cannot restore stale values in unrelated columns. */
export function changedFormValues(base: FormValues, draft: FormValues): FormValues {
  return Object.fromEntries(Object.entries(draft).filter(([key, value]) => value !== base[key]))
}

/** Rebase independent edits automatically; overlapping edits need an explicit per-field choice. */
export function mergeFormDraft(base: FormValues, draft: FormValues, server: FormValues) {
  const patch = changedFormValues(base, draft)
  const conflicts = Object.keys(patch).filter(key => server[key] !== base[key] && server[key] !== draft[key])
  return { values: { ...server, ...patch }, conflicts }
}
