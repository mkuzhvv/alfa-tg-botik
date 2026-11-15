import { create } from 'zustand'
import { apiClient, apiError, ApiError, result } from './client'
import type {
  User,
  Plan,
  Reminder,
  ProfileUpdate,
  ReminderCreate,
  ReminderUpdate,
  PlanReplacement,
} from './client'

interface AppState {
  user: User | null
  plan: Plan | null
  reminders: Reminder[]
  loading: boolean
  busy: string | null
  error: ApiError | null
  fetchInitialData: () => Promise<void>
  toggleStepStatus: (stepId: string) => Promise<void>
  archiveCurrentPlan: () => Promise<void>
  generatePlan: (goal: string, replacement?: PlanReplacement) => Promise<void>
  createReminder: (payload: ReminderCreate) => Promise<void>
  updateReminder: (id: string, payload: ReminderUpdate) => Promise<void>
  toggleReminderStatus: (id: string) => Promise<void>
  cancelReminder: (id: string) => Promise<void>
  updateProfile: (patch: ProfileUpdate) => Promise<User>
  reset: () => void
}

const attempts = new Map<string, { body: string; key: string }>()
function keyFor(operation: string, body: unknown) {
  const serialized = JSON.stringify(body)
  let attempt = attempts.get(operation)
  if (!attempt || attempt.body !== serialized) {
    attempt = { body: serialized, key: crypto.randomUUID() }
    attempts.set(operation, attempt)
  }
  return attempt.key
}

async function loadReminders(): Promise<Reminder[]> {
  const items: Reminder[] = []
  while (true) {
    const page = result(
      await apiClient.GET('/reminders', {
        params: { query: { offset: items.length, limit: 100 } },
      }),
    )
    items.push(...page.items)
    if (items.length >= page.total || page.items.length === 0) return items
  }
}

export const useAppStore = create<AppState>((set, get) => {
  async function mutate<T>(
    operation: string,
    action: () => Promise<T>,
    apply: (data: T) => void,
  ): Promise<T> {
    if (get().busy || get().loading)
      throw new ApiError('REQUEST_IN_PROGRESS', 409)
    set({ busy: operation, error: null })
    try {
      const data = await action()
      apply(data)
      return data
    } catch (failure) {
      const error = apiError(failure)
      set({ error })
      throw error
    } finally {
      set({ busy: null })
    }
  }

  return {
    user: null,
    plan: null,
    reminders: [],
    loading: false,
    busy: null,
    error: null,
    reset: () => {
      attempts.clear()
      set({
        user: null,
        plan: null,
        reminders: [],
        loading: false,
        busy: null,
        error: null,
      })
    },
    fetchInitialData: async () => {
      if (get().busy || get().loading) return
      set({ loading: true, error: null })
      try {
        const [user, plan, reminders] = await Promise.all([
          apiClient.GET('/me').then(result),
          apiClient.GET('/plans/current').then(result),
          loadReminders(),
        ])
        set({ user, plan, reminders })
      } catch (failure) {
        const error = apiError(failure)
        set({ error })
        throw error
      } finally {
        set({ loading: false })
      }
    },
    toggleStepStatus: async (stepId) => {
      const plan = get().plan
      const step = plan?.steps.find((item) => item.id === stepId)
      if (!plan || !step || plan.status !== 'ACTIVE')
        throw new ApiError('NOT_FOUND', 404)
      await mutate(
        'plan.step',
        async () =>
          result(
            await apiClient.PATCH('/plans/{planId}/steps/{stepId}', {
              params: { path: { planId: plan.id, stepId } },
              body: {
                expectedVersion: plan.version,
                status: step.status === 'DONE' ? 'TODO' : 'DONE',
              },
            }),
          ),
        (saved) => set({ plan: saved }),
      )
    },
    archiveCurrentPlan: async () => {
      const plan = get().plan
      if (!plan) return
      await mutate(
        'plan.archive',
        async () =>
          result(
            await apiClient.POST('/plans/{planId}/archive', {
              params: { path: { planId: plan.id } },
              body: { expectedVersion: plan.version },
            }),
          ),
        () => set({ plan: null }),
      )
    },
    generatePlan: async (goal, replacement) => {
      const body = {
        goal: goal.trim(),
        ...(replacement ? { replacePlan: replacement } : {}),
      }
      await mutate(
        'plan.generate',
        async () =>
          result(
            await apiClient.POST('/plans/generate', {
              body,
              params: {
                header: { 'Idempotency-Key': keyFor('plan.generate', body) },
              },
            }),
          ),
        (plan) => {
          attempts.delete('plan.generate')
          set({ plan })
        },
      )
    },
    createReminder: async (body) => {
      await mutate(
        'reminder.create',
        async () =>
          result(
            await apiClient.POST('/reminders', {
              body,
              params: {
                header: { 'Idempotency-Key': keyFor('reminder.create', body) },
              },
            }),
          ),
        (reminder) => {
          attempts.delete('reminder.create')
          set({
            reminders: [
              reminder,
              ...get().reminders.filter((item) => item.id !== reminder.id),
            ],
          })
        },
      )
    },
    updateReminder: async (id, body) => {
      await mutate(
        'reminder.update',
        async () =>
          result(
            await apiClient.PATCH('/reminders/{reminderId}', {
              params: { path: { reminderId: id } },
              body,
            }),
          ),
        (reminder) =>
          set({
            reminders: get().reminders.map((item) =>
              item.id === id ? reminder : item,
            ),
          }),
      )
    },
    toggleReminderStatus: async (id) => {
      const reminder = get().reminders.find((item) => item.id === id)
      if (!reminder || !['ACTIVE', 'PAUSED'].includes(reminder.status))
        throw new ApiError('REMINDER_TERMINAL', 409)
      await get().updateReminder(id, {
        expectedVersion: reminder.version,
        status: reminder.status === 'ACTIVE' ? 'PAUSED' : 'ACTIVE',
      })
    },
    cancelReminder: async (id) => {
      const reminder = get().reminders.find((item) => item.id === id)
      if (!reminder) return
      await get().updateReminder(id, {
        expectedVersion: reminder.version,
        status: 'CANCELLED',
      })
    },
    updateProfile: (body) =>
      mutate(
        'profile.update',
        async () => result(await apiClient.PATCH('/me/profile', { body })),
        (user) => set({ user }),
      ),
  }
})
