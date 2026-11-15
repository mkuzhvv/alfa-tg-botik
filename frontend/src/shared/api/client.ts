import createClient from 'openapi-fetch'
import type { paths, components } from './schema'

let accessToken: string | null = null
let renewSession: (() => Promise<void>) | undefined
let renewal: Promise<void> | undefined
const copies = new WeakMap<Request, Request>()
let blockedUntil = 0

export function setAccessToken(token: string | null) {
  accessToken = token
}
export function setSessionRenewal(callback: () => Promise<void>) {
  renewSession = callback
}

export const apiClient = createClient<paths>({
  baseUrl: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  fetch: (request) =>
    fetch(request, {
      signal: AbortSignal.any([request.signal, AbortSignal.timeout(75000)]),
    }),
})

apiClient.use({
  onRequest({ request }) {
    if (Date.now() < blockedUntil)
      throw new ApiError(
        'RATE_LIMITED',
        429,
        [],
        undefined,
        Math.ceil((blockedUntil - Date.now()) / 1000),
      )
    if (!request.url.endsWith('/auth/telegram') && accessToken)
      request.headers.set('Authorization', `Bearer ${accessToken}`)
    copies.set(request, request.clone())
    return request
  },
  async onResponse({ request, response }) {
    if (response.status === 429)
      blockedUntil =
        Date.now() + (Number(response.headers.get('Retry-After')) || 1) * 1000
    if (
      response.status !== 401 ||
      request.url.endsWith('/auth/telegram') ||
      !renewSession
    )
      return response
    if (
      !accessToken ||
      request.headers.get('Authorization') === `Bearer ${accessToken}`
    ) {
      renewal ??= renewSession().finally(() => {
        renewal = undefined
      })
      await renewal
    }
    const retry = copies.get(request)!.clone()
    retry.headers.set('Authorization', `Bearer ${accessToken}`)
    return fetch(retry, {
      signal: AbortSignal.any([retry.signal, AbortSignal.timeout(75000)]),
    })
  },
})

export class ApiError extends Error {
  readonly code: string
  readonly status: number
  readonly fields: { field: string; message: string }[]
  readonly requestId?: string
  readonly retryAfter?: number
  constructor(
    code: string,
    status = 0,
    fields: { field: string; message: string }[] = [],
    requestId?: string,
    retryAfter?: number,
  ) {
    super(code)
    this.code = code
    this.status = status
    this.fields = fields
    this.requestId = requestId
    this.retryAfter = retryAfter
  }
}

export function result<T>(response: {
  data?: T
  error?: unknown
  response: Response
}): T {
  if (!response.response.ok) {
    const body = response.error as
      Partial<components['schemas']['ApiProblem']> | undefined
    throw new ApiError(
      body?.code || 'SERVICE_UNAVAILABLE',
      response.response.status,
      body?.errors,
      body?.requestId,
      Number(response.response.headers.get('Retry-After')) || undefined,
    )
  }
  return response.data as T
}

export function apiError(error: unknown): ApiError {
  return error instanceof ApiError ? error : new ApiError('NETWORK_ERROR')
}

export type User = components['schemas']['User']
export type Profile = components['schemas']['Profile']
export type ProfileUpdate = components['schemas']['ProfileUpdate']
export type TaxRegime = components['schemas']['TaxRegime']
export type Plan = components['schemas']['Plan']
export type PlanStep = components['schemas']['PlanStep']
export type StepStatus = PlanStep['status']
export type MaterialCard = components['schemas']['MaterialCard']
export type MaterialKind = components['schemas']['MaterialKind']
export type Reminder = components['schemas']['Reminder']
export type ReminderCreate = components['schemas']['ReminderCreate']
export type ReminderUpdate = components['schemas']['ReminderUpdate']
export type PlanReplacement = components['schemas']['PlanReplacement']
