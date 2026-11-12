import createClient, { type Middleware } from 'openapi-fetch'
import { create } from 'zustand'
import type { paths, components } from './schema'

export type User = components['schemas']['User']
export type Profile = components['schemas']['Profile']
export type TaxRegime = components['schemas']['TaxRegime']
export type Plan = components['schemas']['Plan']
export type PlanStep = components['schemas']['PlanStep']
export type MaterialCard = components['schemas']['MaterialCard']
export type Reminder = components['schemas']['Reminder']
export type ApiProblem = components['schemas']['ApiProblem']
export type ProfileUpdate = components['schemas']['ProfileUpdate']
export type ReminderCreate = components['schemas']['ReminderCreate']
export type MaterialKind = components['schemas']['MaterialKind']

interface AuthState {
  accessToken: string | null
  user: User | null
  setSession: (accessToken: string, user: User) => void
  updateUser: (user: User) => void
  clearSession: () => void
}

export const useAuthStore = create<AuthState>((set) => ({
  accessToken: null,
  user: null,
  setSession: (accessToken, user) => set({ accessToken, user }),
  updateUser: (user) => set({ user }),
  clearSession: () => set({ accessToken: null, user: null }),
}))

const authMiddleware: Middleware = {
  async onRequest({ request }) {
    const token = useAuthStore.getState().accessToken
    if (token) {
      request.headers.set('Authorization', `Bearer ${token}`)
    }

    if (request.method === 'POST' && !request.headers.has('Idempotency-Key')) {
      request.headers.set('Idempotency-Key', crypto.randomUUID())
    }

    return request
  },
}

export const apiClient = createClient<paths>({
  baseUrl: import.meta.env.VITE_API_BASE_URL || '/api/v1',
})

apiClient.use(authMiddleware)