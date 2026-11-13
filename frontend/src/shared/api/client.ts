import createClient from 'openapi-fetch'
import type { paths, components } from './schema'
import { useAuthStore } from './devStore'

export const apiClient = createClient<paths>({
  baseUrl: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1',
})

apiClient.use({
  async onRequest({ request }) {
    const token = useAuthStore.getState().accessToken

    if (token) {
      request.headers.set('Authorization', `Bearer ${token}`)
    } else if (import.meta.env.DEV) {
      request.headers.set('X-Dev-User-Id', '11111111-1111-1111-1111-111111111111')
    }

    return request
  },
})

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