import type { ApiEnvelope } from '../types/api'

const configuredBase = import.meta.env.VITE_API_BASE?.trim()
export const API_BASE = (configuredBase || '/api/v1').replace(/\/+$/, '')

// 第 28 节：定时任务管理走 /api/v2。若 API_BASE 被显式配置成完整 URL，v2 沿用同一主机前缀。
export const API_BASE_V2 = API_BASE.endsWith('/v1')
  ? `${API_BASE.slice(0, -3)}v2`
  : '/api/v2'

type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE'

interface RequestOptions {
  method?: HttpMethod
  body?: unknown
  timeoutMs?: number
  base?: string
}

interface ApiErrorOptions {
  httpStatus?: number | null
  code?: number | null
  traceId?: string | null
}

export class ApiError extends Error {
  readonly httpStatus: number | null
  readonly code: number | null
  readonly traceId: string | null

  constructor(message: string, options: ApiErrorOptions = {}) {
    super(message)
    this.name = 'ApiError'
    this.httpStatus = options.httpStatus ?? null
    this.code = options.code ?? null
    this.traceId = options.traceId ?? null
  }
}

function isEnvelope(value: unknown): value is ApiEnvelope<unknown> {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const candidate = value as Record<string, unknown>
  return (
    typeof candidate.code === 'number' &&
    typeof candidate.message === 'string' &&
    'data' in candidate &&
    typeof candidate.timestamp === 'string' &&
    'traceId' in candidate
  )
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = options.method ?? 'GET'
  const base = options.base ?? API_BASE
  const timeoutMs = options.timeoutMs ?? 15_000
  const controller = new AbortController()
  let timedOut = false
  const timer = window.setTimeout(() => {
    timedOut = true
    controller.abort()
  }, timeoutMs)

  try {
    const headers: Record<string, string> = {}
    if (options.body !== undefined) {
      headers['Content-Type'] = 'application/json'
    }

    const response = await fetch(`${base}${path}`, {
      method,
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: controller.signal,
      credentials: 'same-origin',
    })

    const raw = await response.text()
    let payload: unknown = null
    if (raw.length > 0) {
      try {
        payload = JSON.parse(raw) as unknown
      } catch {
        throw new ApiError('响应不是合法 JSON', {
          httpStatus: response.status,
        })
      }
    }

    if (!isEnvelope(payload)) {
      throw new ApiError(
        response.ok ? '后端响应缺少统一信封' : `请求失败（HTTP ${response.status}）`,
        { httpStatus: response.status },
      )
    }

    if (response.ok && payload.code === 0) {
      return payload.data as T
    }

    throw new ApiError(payload.message || `请求失败（HTTP ${response.status}）`, {
      httpStatus: response.status,
      code: payload.code,
      traceId: payload.traceId,
    })
  } catch (error) {
    if (error instanceof ApiError) {
      throw error
    }
    if (timedOut || (error instanceof DOMException && error.name === 'AbortError')) {
      throw new ApiError(`请求超时（${Math.round(timeoutMs / 1000)} 秒）`)
    }
    throw new ApiError(error instanceof Error ? error.message : '网络请求失败')
  } finally {
    window.clearTimeout(timer)
  }
}

export function normalizeError(error: unknown): ApiError {
  if (error instanceof ApiError) {
    return error
  }
  if (error instanceof Error) {
    return new ApiError(error.message || '未知错误')
  }
  return new ApiError('未知错误')
}
