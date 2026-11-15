import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { useAppStore } from '../src/shared/api/appStore'
import { useAuthStore } from '../src/shared/api/authStore'
import { setAccessToken, ApiError } from '../src/shared/api/client'
import type { User, Plan, Reminder } from '../src/shared/api/client'

const user: User = {
  id: '11111111-1111-4111-8111-111111111111',
  displayName: 'Test',
  canReceiveReminders: true,
  profileVersion: 0,
  profile: {
    businessStage: null,
    industry: null,
    experience: null,
    currentTask: null,
    taxRegime: null,
    timeZone: null,
  },
}
const plan: Plan = {
  id: '22222222-2222-4222-8222-222222222222',
  goal: 'Test goal',
  status: 'ACTIVE',
  version: 0,
  createdAt: '2026-11-15T10:00:00Z',
  updatedAt: '2026-11-15T10:00:00Z',
  steps: [],
}
const reminder: Reminder = {
  id: '33333333-3333-4333-8333-333333333333',
  title: 'Test reminder',
  status: 'ACTIVE',
  schedule: { type: 'ONCE', at: '2099-01-01T10:00:00Z', timeZone: 'UTC' },
  nextFireAt: '2099-01-01T10:00:00Z',
  lastSentAt: null,
  completionReason: null,
  material: null,
  planStepId: null,
  version: 0,
  createdAt: '2026-11-15T10:00:00Z',
  updatedAt: '2026-11-15T10:00:00Z',
}
const response = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })

beforeEach(() => {
  useAppStore.getState().reset()
  useAppStore.setState({ user })
  setAccessToken('old-token')
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('Mini App API state', () => {
  it('waits for login before protected reads and applies the authenticated user', async () => {
    const calls: string[] = []
    vi.stubGlobal('window', {
      Telegram: {
        WebApp: { initData: 'signed-test', ready() {}, expand() {} },
      },
    })
    vi.stubGlobal('fetch', async (request: Request) => {
      const path = new URL(request.url).pathname
      calls.push(path)
      if (path.endsWith('/auth/telegram')) {
        expect(request.headers.has('Authorization')).toBe(false)
        return response({ accessToken: 'new-token', user, tokenType: 'Bearer' })
      }
      expect(request.headers.get('Authorization')).toBe('Bearer new-token')
      if (path.endsWith('/me')) return response(user)
      if (path.endsWith('/plans/current')) return response(null)
      return response({ items: [], total: 0 })
    })
    await useAuthStore.getState().initialize()
    expect(calls[0]).toBe('/api/v1/auth/telegram')
    expect(useAuthStore.getState().status).toBe('ready')
    expect(useAppStore.getState().user).toEqual(user)
    expect(useAppStore.getState().plan).toBeNull()
  })

  it('does not fall back to demo data outside Telegram', async () => {
    vi.stubGlobal('window', {})
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    useAppStore.getState().reset()
    await useAuthStore.getState().initialize()
    expect(fetch).not.toHaveBeenCalled()
    expect(useAuthStore.getState().error?.code).toBe('OPEN_IN_TELEGRAM')
    expect(useAppStore.getState().user).toBeNull()
  })

  it('clears an old plan on null and loads all reminder pages', async () => {
    useAppStore.setState({ plan })
    let pages = 0
    vi.stubGlobal('fetch', async (request: Request) => {
      if (request.url.endsWith('/me')) return response(user)
      if (request.url.endsWith('/plans/current')) return response(null)
      pages++
      return response({ items: [{ ...reminder, id: String(pages) }], total: 2 })
    })
    await useAppStore.getState().fetchInitialData()
    expect(useAppStore.getState().plan).toBeNull()
    expect(useAppStore.getState().reminders).toHaveLength(2)
    expect(pages).toBe(2)
  })

  it('PATCHes only supplied profile fields and preserves the plan', async () => {
    useAppStore.setState({ plan })
    vi.stubGlobal('fetch', async (request: Request) => {
      expect(request.method).toBe('PATCH')
      expect(await request.json()).toEqual({
        expectedVersion: 0,
        industry: null,
      })
      return response({ ...user, profileVersion: 1 })
    })
    await useAppStore
      .getState()
      .updateProfile({ expectedVersion: 0, industry: null })
    expect(useAppStore.getState().plan).toEqual(plan)
    expect(useAppStore.getState().user?.profileVersion).toBe(1)
  })

  it('keeps server state after version conflict', async () => {
    useAppStore.setState({ plan })
    vi.stubGlobal('fetch', async () =>
      response({ code: 'VERSION_CONFLICT' }, 409),
    )
    await expect(
      useAppStore.getState().archiveCurrentPlan(),
    ).rejects.toMatchObject({ code: 'VERSION_CONFLICT' })
    expect(useAppStore.getState().plan).toEqual(plan)
  })

  it('reuses the key and body after a failed generation and sends explicit replacement', async () => {
    useAppStore.setState({ plan })
    const keys: (string | null)[] = []
    let calls = 0
    vi.stubGlobal('fetch', async (request: Request) => {
      keys.push(request.headers.get('Idempotency-Key'))
      expect(await request.json()).toEqual({
        goal: 'New goal',
        replacePlan: { id: plan.id, expectedVersion: 0 },
      })
      if (++calls === 1) throw new TypeError('connection lost')
      return response({ ...plan, goal: 'New goal' })
    })
    await expect(
      useAppStore
        .getState()
        .generatePlan('New goal', { id: plan.id, expectedVersion: 0 }),
    ).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
    expect(useAppStore.getState().plan).toEqual(plan)
    await useAppStore
      .getState()
      .generatePlan('New goal', { id: plan.id, expectedVersion: 0 })
    expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/)
    expect(keys[1]).toBe(keys[0])
    expect(useAppStore.getState().plan?.goal).toBe('New goal')
  })

  it('does not invent a reminder on failure and retries with the same key', async () => {
    const keys: (string | null)[] = []
    vi.stubGlobal('fetch', async (request: Request) => {
      keys.push(request.headers.get('Idempotency-Key'))
      return response({ code: 'REQUEST_IN_PROGRESS' }, 409)
    })
    const body = {
      title: reminder.title,
      schedule: reminder.schedule,
      materialId: null,
      planStepId: null,
    }
    for (let attempt = 0; attempt < 2; attempt++)
      await expect(
        useAppStore.getState().createReminder(body),
      ).rejects.toBeInstanceOf(ApiError)
    expect(keys[0]).toBeTruthy()
    expect(keys[1]).toBe(keys[0])
    expect(useAppStore.getState().reminders).toEqual([])
  })

  it('cancels with PATCH and uses the server reminder version and nextFireAt', async () => {
    useAppStore.setState({ reminders: [reminder] })
    vi.stubGlobal('fetch', async (request: Request) => {
      expect(request.method).toBe('PATCH')
      expect(await request.json()).toEqual({
        expectedVersion: 0,
        status: 'CANCELLED',
      })
      return response({
        ...reminder,
        status: 'CANCELLED',
        version: 3,
        nextFireAt: null,
      })
    })
    await useAppStore.getState().cancelReminder(reminder.id)
    expect(useAppStore.getState().reminders[0]).toMatchObject({
      status: 'CANCELLED',
      version: 3,
      nextFireAt: null,
    })
  })

  it('renews a revoked session once and replays the original body and idempotency key', async () => {
    vi.stubGlobal('window', {
      Telegram: { WebApp: { initData: 'signed-test' } },
    })
    const requests: { body: unknown; key: string | null }[] = []
    let logins = 0
    vi.stubGlobal('fetch', async (request: Request) => {
      if (request.url.endsWith('/auth/telegram')) {
        logins++
        return response({ accessToken: 'renewed', user })
      }
      requests.push({
        body: await request.json(),
        key: request.headers.get('Idempotency-Key'),
      })
      return request.headers.get('Authorization') === 'Bearer old-token'
        ? response({ code: 'AUTH_REQUIRED' }, 401)
        : response(plan)
    })
    await useAppStore.getState().generatePlan('Goal')
    expect(logins).toBe(1)
    expect(requests).toHaveLength(2)
    expect(requests[0]).toEqual(requests[1])
  })
})
