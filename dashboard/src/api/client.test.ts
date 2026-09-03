import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError } from './client'

describe('gateway API client', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
    document.cookie = 'ae2_csrf=; Max-Age=0; Path=/'
  })

  it('uses the same-origin versioned contract and includes credentials', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({
      revision: 1,
      nextCursor: null,
      items: [],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))

    await api.items('grid/a', { limit: 200, query: 'fluix', craftable: true })

    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/grids/grid%2Fa/items?limit=200&query=fluix&craftable=true',
      expect.objectContaining({ credentials: 'same-origin' }),
    )
  })

  it('copies the CSRF cookie and sends a valid idempotency key for mutations', async () => {
    document.cookie = 'ae2_csrf=csrf%20token; Path=/'
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({
      id: 'request-1',
      planId: 'plan-1',
      state: 'accepted',
      createdAt: new Date().toISOString(),
    }), { status: 202, headers: { 'Content-Type': 'application/json' } }))

    await api.submitPlan('plan-1', '1234567890abcdef')

    const [url, options] = vi.mocked(fetch).mock.calls[0]
    const headers = new Headers(options?.headers)
    expect(url).toBe('/api/v1/plans/plan-1/submit')
    expect(options?.method).toBe('POST')
    expect(headers.get('Idempotency-Key')).toBe('1234567890abcdef')
    expect(headers.get('X-CSRF-Token')).toBe('csrf token')
  })

  it('rejects invalid idempotency keys before making a request', () => {
    expect(() => api.submitPlan('plan-1', 'too-short')).toThrow(
      'Idempotency key must contain between 16 and 128 characters.',
    )
    expect(fetch).not.toHaveBeenCalled()
  })

  it('returns typed errors with server context', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(
      JSON.stringify({ code: 'grid_offline', message: 'Grid is unavailable' }),
      { status: 503, headers: { 'Content-Type': 'application/json' } },
    ))

    await expect(api.metrics('grid-1')).rejects.toMatchObject({
      name: 'ApiError',
      status: 503,
      code: 'grid_offline',
      message: 'Grid is unavailable',
    } satisfies Partial<ApiError>)
  })

  it('unwraps nested mod error payloads instead of stringifying objects', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(
      JSON.stringify({
        error: {
          code: 'player_offline',
          message: 'Linked Minecraft player must be online to preserve AE2 action-source security',
        },
      }),
      { status: 409, headers: { 'Content-Type': 'application/json' } },
    ))

    await expect(api.createPlan('grid-1', 'token', 1)).rejects.toMatchObject({
      name: 'ApiError',
      status: 409,
      code: 'player_offline',
      message: 'Linked Minecraft player must be online to preserve AE2 action-source security',
    } satisfies Partial<ApiError>)
  })
})
