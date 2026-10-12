import axios, { AxiosError, type AxiosRequestConfig } from 'axios'

/** Normalised error every caller sees: the backend's {code,message,details} plus HTTP status. */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly details?: Record<string, any>,
  ) {
    super(message)
  }

  /** Field-level validation messages, if any. */
  get fields(): Record<string, string> {
    return (this.details?.fields as Record<string, string>) ?? {}
  }
}

const TOKEN_KEY = 'lumen.token'

export const tokenStore = {
  get(): string | null {
    try {
      return localStorage.getItem(TOKEN_KEY)
    } catch {
      return null
    }
  },
  set(token: string | null) {
    try {
      if (token) localStorage.setItem(TOKEN_KEY, token)
      else localStorage.removeItem(TOKEN_KEY)
    } catch {
      /* storage unavailable (private mode): session-only login */
    }
  },
}

/** Called when the backend says the session is no longer valid. */
let onUnauthorized: (() => void) | null = null
export function setUnauthorizedHandler(fn: () => void) {
  onUnauthorized = fn
}

export const http = axios.create({ baseURL: '/', timeout: 120_000 })

http.interceptors.request.use((config) => {
  const token = tokenStore.get()
  if (token) config.headers.set('Authorization', `Bearer ${token}`)
  return config
})

http.interceptors.response.use(
  (r) => r,
  (err: AxiosError<any>) => {
    if (!err.response) {
      const offline = err.code === 'ECONNABORTED' ? '请求超时，请稍后重试' : '无法连接到服务器，请确认后端已启动'
      return Promise.reject(new ApiError(0, 'NETWORK', offline))
    }
    const { status, data } = err.response
    const code = (data && typeof data === 'object' && data.code) || `HTTP_${status}`
    const message =
      (data && typeof data === 'object' && data.message) ||
      (status === 502 || status === 504 ? '后端服务不可用' : `请求失败（${status}）`)
    if (status === 401 && code !== 'BAD_CREDENTIALS') onUnauthorized?.()
    return Promise.reject(new ApiError(status, code, message, data?.details))
  },
)

export async function getJson<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return (await http.get<T>(url, config)).data
}

export async function postJson<T>(url: string, body?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return (await http.post<T>(url, body ?? {}, config)).data
}

/** Unique per user action, so a double click or a retried request never creates two tasks. */
export function idempotencyKey(): string {
  return crypto.randomUUID()
}
