/** Only direct Tencent Docs links are rendered as external anchors. Server validation remains authoritative. */
export function safeTencentUrl(raw: string): string | null {
  if (raw !== raw.trim() || raw.length > 2048 || /[\s\u0000-\u001f\u007f]/.test(raw) || !/^https:\/\/docs\.qq\.com\//i.test(raw)) return null
  try {
    const url = new URL(raw)
    if (url.protocol !== 'https:' || url.username || url.password || url.port || url.hostname.toLowerCase() !== 'docs.qq.com') return null
    if (!/^\/(doc|sheet|slide|form|smartsheet|mind|flowchart|board|pdf)\/[A-Za-z0-9_-]{4,200}\/?$/.test(url.pathname)) return null
    const rawPath = raw.slice(raw.indexOf('/', raw.indexOf('://') + 3)).split(/[?#]/, 1)[0]
    if (rawPath !== url.pathname || (raw.split('#', 1)[0]!.includes('?') && !url.search)) return null
    const queryKeys = new Set(['tab', 'u', '_t', 'scode', 'nlc', 'preview', 'scene', 'from', 'is_mainbar'])
    if (url.search) {
      if (url.search.length > 1025) return null
      const seen = new Set<string>()
      for (const part of url.search.slice(1).split('&')) {
        const split = part.indexOf('=')
        if (split < 1) return null
        const key = part.slice(0, split), value = part.slice(split + 1)
        if (!queryKeys.has(key) || seen.has(key) || !/^[A-Za-z0-9_.~-]{0,256}$/.test(value)) return null
        seen.add(key)
      }
    }
    if (url.hash && !/^[A-Za-z0-9_=.-]{0,256}$/.test(url.hash.slice(1))) return null
    return url.href
  } catch { return null }
}
