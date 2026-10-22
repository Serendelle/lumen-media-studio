import type { ConversionMode, ConversionRoute, DocumentFormat } from '@/api/types'

export const DOCUMENT_FORMATS: { value: DocumentFormat; label: string; short: string }[] = [
  { value: 'pdf', label: 'PDF 文档', short: 'PDF' },
  { value: 'docx', label: 'Word · DOCX', short: 'Word' },
  { value: 'xlsx', label: 'Excel · XLSX', short: 'Excel' },
  { value: 'html', label: 'HTML 网页', short: 'HTML' },
  { value: 'ofd', label: 'OFD 版式文档', short: 'OFD' },
  { value: 'png', label: 'PNG 图片', short: 'PNG' },
  { value: 'jpg', label: 'JPG 图片', short: 'JPG' },
]

export const formatLabel = (format: string) => DOCUMENT_FORMATS.find(f => f.value === format)?.label ?? format.toUpperCase()

/** Every selected source needs a server-declared route; never infer capability from a filename alone. */
export function conversionRoutes(routes: ConversionRoute[], sources: string[], target: DocumentFormat): ConversionRoute[] {
  return [...new Set(sources)].map(source => routes.find(route => route.from === source && route.to === target))
    .filter((route): route is ConversionRoute => !!route)
}

export function availableModes(routes: ConversionRoute[]): ConversionMode[] {
  if (!routes.length) return []
  const modes: ConversionMode[] = ['layout', 'editable']
  return modes.filter(mode => routes.every(route => (route.modes ?? [route.defaultMode ?? 'layout']).includes(mode)))
}

export function routeSupports(routes: ConversionRoute[], sources: string[], target: DocumentFormat): boolean {
  const unique = new Set(sources)
  const selected = conversionRoutes(routes, sources, target)
  return unique.size > 0 && selected.length === unique.size && selected.every(route => route.available)
}
