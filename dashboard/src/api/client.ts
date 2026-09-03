import type { components } from './schema'

export type Health = components['schemas']['Health']
export type Identity = components['schemas']['Identity']
export type LinkCode = components['schemas']['LinkCode']
export type Grid = components['schemas']['Grid']
export type Item = components['schemas']['Item']
export type ItemPage = components['schemas']['ItemPage']
export type CraftingCpu = components['schemas']['CraftingCpu']
export type CraftingCpuDetail = components['schemas']['CraftingCpuDetail']
export type CraftingCpuItem = components['schemas']['CraftingCpuItem']
export type GridMetrics = components['schemas']['GridMetrics']
export type CraftingPlan = components['schemas']['CraftingPlan']
export type CraftingRequest = components['schemas']['CraftingRequest']

const API_ROOT = '/api/v1'

export class ApiError extends Error {
  readonly status: number
  readonly code?: string
  readonly detail?: unknown

  constructor(message: string, status: number, code?: string, detail?: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.detail = detail
  }
}

type RequestOptions = Omit<RequestInit, 'body'> & { body?: unknown }

function cookieValue(name: string) {
  if (typeof document === 'undefined') return undefined
  const prefix = `${encodeURIComponent(name)}=`
  const pair = document.cookie
    .split(';')
    .map((value) => value.trim())
    .find((value) => value.startsWith(prefix))
  if (!pair) return undefined
  try {
    return decodeURIComponent(pair.slice(prefix.length))
  } catch {
    return pair.slice(prefix.length)
  }
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  if (import.meta.env.DEV && import.meta.env.MODE === 'mock') {
    const { mockRequest } = await import('./mock')
    return mockRequest<T>(path, options)
  }

  const method = (options.method ?? 'GET').toUpperCase()
  const headers = new Headers(options.headers)
  headers.set('Accept', 'application/json')
  if (options.body !== undefined) headers.set('Content-Type', 'application/json')
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    const csrfToken = cookieValue('ae2_csrf')
    if (csrfToken) headers.set('X-CSRF-Token', csrfToken)
  }

  const response = await fetch(`${API_ROOT}${path}`, {
    ...options,
    method,
    credentials: 'same-origin',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  })

  if (!response.ok) {
    let detail: unknown
    try {
      detail = await response.json()
    } catch {
      detail = await response.text().catch(() => undefined)
    }
    const { message, code } = extractApiFailure(detail, response.status)
    throw new ApiError(message, response.status, code, detail)
  }

  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

function queryString(params: Record<string, string | number | boolean | undefined>) {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') search.set(key, String(value))
  }
  const value = search.toString()
  return value ? `?${value}` : ''
}

export const api = {
  health: () => request<Health>('/health'),
  identity: () => request<Identity>('/identity'),
  createLinkCode: () => request<LinkCode>('/identity/link-code', { method: 'POST' }),
  grids: () => request<{ revision: number; grids: Grid[] }>('/grids'),
  items: (
    gridId: string,
    params: { cursor?: string; limit?: number; query?: string; craftable?: boolean } = {},
  ) =>
    request<ItemPage>(
      `/grids/${encodeURIComponent(gridId)}/items${queryString({
        limit: params.limit ?? 500,
        ...params,
      })}`,
    ),
  cpus: (gridId: string) =>
    request<{ revision: number; cpus: CraftingCpu[] }>(
      `/grids/${encodeURIComponent(gridId)}/cpus`,
    ),
  cpu: (gridId: string, cpuId: string) =>
    request<CraftingCpuDetail>(
      `/grids/${encodeURIComponent(gridId)}/cpus/${encodeURIComponent(cpuId)}`,
    ),
  metrics: (gridId: string) =>
    request<GridMetrics>(`/grids/${encodeURIComponent(gridId)}/metrics`),
  createPlan: (gridId: string, keyToken: string, amount: number) =>
    request<CraftingPlan>(`/grids/${encodeURIComponent(gridId)}/plans`, {
      method: 'POST',
      body: { keyToken, amount },
    }),
  plan: (planId: string) => request<CraftingPlan>(`/plans/${encodeURIComponent(planId)}`),
  submitPlan: (planId: string, idempotencyKey: string) => {
    if (idempotencyKey.length < 16 || idempotencyKey.length > 128) {
      throw new TypeError('Idempotency key must contain between 16 and 128 characters.')
    }
    return request<CraftingRequest>(`/plans/${encodeURIComponent(planId)}/submit`, {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
    })
  },
  craftingRequest: (requestId: string) =>
    request<CraftingRequest>(`/requests/${encodeURIComponent(requestId)}`),
}

export function readableError(error: unknown) {
  if (error instanceof ApiError) return error.message
  if (error instanceof TypeError) return 'The gateway could not be reached.'
  return error instanceof Error ? error.message : 'An unexpected error occurred.'
}

function extractApiFailure(detail: unknown, status: number): { message: string; code?: string } {
  if (typeof detail === 'string' && detail.trim()) {
    return { message: detail }
  }
  if (!detail || typeof detail !== 'object') {
    return { message: `Gateway request failed (${status})` }
  }

  const root = detail as Record<string, unknown>
  const nested = root.error && typeof root.error === 'object'
    ? root.error as Record<string, unknown>
    : undefined

  const message =
    pickString(root.message) ??
    pickString(nested?.message) ??
    pickString(root.error) ??
    pickString(nested?.error) ??
    `Gateway request failed (${status})`
  const code = pickString(root.code) ?? pickString(nested?.code)
  return { message, code }
}

function pickString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined
}
