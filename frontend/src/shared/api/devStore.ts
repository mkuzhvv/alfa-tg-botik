import { create } from 'zustand'
import {
  apiClient,
  type User,
  type Plan,
  type Reminder,
  type ProfileUpdate,
  type ReminderCreate,
} from './client'

const backend = apiClient as unknown as {
  GET: (url: string, init?: Record<string, unknown>) => Promise<{ data?: unknown; error?: unknown }>
  POST: (url: string, init?: Record<string, unknown>) => Promise<{ data?: unknown; error?: unknown }>
  PATCH: (url: string, init?: Record<string, unknown>) => Promise<{ data?: unknown; error?: unknown }>
  PUT: (url: string, init?: Record<string, unknown>) => Promise<{ data?: unknown; error?: unknown }>
  DELETE: (url: string, init?: Record<string, unknown>) => Promise<{ data?: unknown; error?: unknown }>
}

interface DevAppState {
  user: User
  plan: Plan | null
  reminders: Reminder[]
  fetchInitialData: () => Promise<void>
  toggleStepStatus: (stepId: string) => void
  archiveCurrentPlan: () => void
  generatePlan: (customGoal?: string) => void
  createReminder: (payload: ReminderCreate) => void
  toggleReminderStatus: (reminderId: string) => void
  cancelReminder: (reminderId: string) => void
  updateProfile: (patch: Omit<ProfileUpdate, 'expectedVersion'>) => void
  setDisplayName: (name: string) => void
}

export const TAX_LABELS: Record<string, string> = {
  NPD: 'НПД',
  USN_6: 'УСН 6%',
  USN_15: 'УСН 15%',
  PATENT: 'Патент',
  OSNO: 'ОСНО',
  AUSN: 'АУСН',
}

const STAGE_LABELS: Record<string, string> = {
  IDEA: 'стадии идеи',
  LAUNCH: 'стадии запуска',
  GROWTH: 'стадии активного роста',
  SCALE: 'стадии масштабирования',
}

const TAX_STEP_DESCRIPTIONS: Record<string, string> = {
  NPD: 'Узнайте, как формировать чеки в приложении «Мой налог» и вовремя оплачивать налог до 28-го числа каждого месяца.',
  USN_6:
    'Узнайте, как уменьшать налог УСН 6% на сумму страховых взносов ИП и в какие даты подавать уведомления ЕНП.',
  USN_15:
    'Узнайте, как правильно подтверждать расходы для уменьшения налоговой базы УСН 15% и в какие даты подавать уведомления ЕНП.',
  PATENT:
    'Узнайте, как вовремя оплачивать патент, уменьшать его стоимость на сумму страховых взносов и следить за лимитом доходов.',
  OSNO: 'Узнайте, как организовать учёт НДС, сдавать квартальные декларации и планировать налоговую нагрузку.',
  AUSN: 'Узнайте, как работает автоматический расчёт налога банком на АУСН без деклараций и страховых взносов.',
}

const initialUser: User = {
  id: '11111111-1111-4111-8111-111111111111',
  displayName: 'Дмитрий',
  canReceiveReminders: true,
  profileVersion: 1,
  profile: {
    businessStage: 'LAUNCH',
    industry: 'Кофейня / Общепит',
    experience: 'BEGINNER',
    currentTask: 'Открыть первую точку, выбрать кассу и не ошибиться с налогами на старте',
    taxRegime: 'USN_6',
    timeZone: 'Europe/Moscow',
  },
}

function buildPlanForProfile(
  profile: User['profile'],
  customGoal?: string,
  firstStepStatus: 'TODO' | 'DONE' = 'DONE',
): Plan {
  const industry = profile.industry || 'Кофейня / Общепит'
  const taxKey = profile.taxRegime || 'USN_6'
  const taxName = TAX_LABELS[taxKey] || 'УСН 6%'
  const stageName =
    (profile.businessStage && STAGE_LABELS[profile.businessStage]) ||
    'стадии запуска'
  const taxStepDesc =
    TAX_STEP_DESCRIPTIONS[taxKey] || TAX_STEP_DESCRIPTIONS.USN_6

  const goalText =
    customGoal?.trim() ||
    profile.currentTask ||
    `Запуск бизнеса (${industry}) и настройка учёта на ${taxName}`

  return {
    id: '22222222-2222-4222-8222-222222222222',
    goal: goalText,
    status: 'ACTIVE',
    version: 1,
    createdAt: '2026-11-12T09:00:00Z',
    updatedAt: new Date().toISOString(),
    steps: [
      {
        id: '33333333-3333-4333-8333-333333333331',
        position: 1,
        title: `Регистрация ИП и выбор кодов ОКВЭД (${industry})`,
        description: `Подберите основные и дополнительные коды ОКВЭД для направления «${industry}» и подайте уведомление о переходе на ${taxName} сразу при регистрации.`,
        status: firstStepStatus,
        materials: [
          {
            id: '44444444-4444-4444-8444-444444444441',
            title: 'Пошаговый гайд: регистрация ИП и ОКВЭД за 3 дня',
            kind: 'ARTICLE',
            description: `Разбор подходящих кодов ОКВЭД, частые ошибки при подаче заявления и шаблон уведомления о переходе на ${taxName}.`,
            recommendationReason: `Вы находитесь на ${stageName} в сфере «${industry}» — этот гайд убережёт от выбора неверного ОКВЭД и ошибок при переходе на ${taxName}.`,
            isPaid: false,
            duration: '12 мин',
            url: 'https://alfa.me/business-ip-guide',
          },
        ],
      },
      {
        id: '33333333-3333-4333-8333-333333333332',
        position: 2,
        title: 'Подключение онлайн-кассы (54-ФЗ) и эквайринга',
        description:
          'Настройте приём оплаты картами, по СБП и фискализацию чеков до первой продажи.',
        status: 'TODO',
        materials: [
          {
            id: '44444444-4444-4444-8444-444444444442',
            title: 'Касса и торговый эквайринг для бизнеса',
            kind: 'LESSON',
            description: `Как выбрать фискальный накопитель на 15 или 36 месяцев при режиме ${taxName} и сэкономить на комиссии за приём платежей.`,
            recommendationReason: `Для работы на режиме ${taxName} важно правильно выбрать срок фискального накопителя и выгодную ставку эквайринга.`,
            isPaid: false,
            duration: '18 мин',
            url: 'https://alfa.me/kassa-lesson',
          },
          {
            id: '44444444-4444-4444-8444-444444444443',
            title: 'Калькулятор выгодного тарифа РКО и эквайринга',
            kind: 'CALCULATOR',
            description:
              'Интерактивный расчёт расходов на обслуживание счёта в зависимости от оборота точки.',
            recommendationReason:
              'Поможет заранее заложить банковскую комиссию в финансовую модель.',
            isPaid: false,
            duration: '5 мин',
            url: 'https://alfa.me/rko-calc',
          },
        ],
      },
      {
        id: '33333333-3333-4333-8333-333333333333',
        position: 3,
        title: 'Финансовая модель и расчёт точки безубыточности',
        description:
          'Рассчитайте себестоимость, аренду, ФОТ и минимальное число продаж в день для выхода в плюс.',
        status: 'TODO',
        materials: [
          {
            id: '44444444-4444-4444-8444-444444444444',
            title: 'Финансовое планирование и юнит-экономика',
            kind: 'COURSE',
            description:
              'Практический курс Альфа-Курса с готовыми таблицами P&L, Cash Flow и расчётом маржинальности.',
            recommendationReason: `Закрывает вашу главную задачу на ${stageName}: даст готовую финмодель с учётом налога ${taxName}, чтобы не уйти в кассовый разрыв.`,
            isPaid: true,
            duration: '4 модуля • 3 часа',
            url: 'https://alfa.me/unit-food-course',
          },
        ],
      },
      {
        id: '33333333-3333-4333-8333-333333333334',
        position: 4,
        title: 'Налоговый календарь и контроль отчётности',
        description: taxStepDesc,
        status: 'TODO',
        materials: [
          {
            id: '44444444-4444-4444-8444-444444444445',
            title: `Памятка по срокам уплаты ${taxName} и взносов в 2026 году`,
            kind: 'DOCUMENT',
            description:
              'График платежей до 25-го и 28-го числа и правила расчёта авансовых платежей.',
            recommendationReason: `Подобран специально под ваш налоговый режим ${taxName}, чтобы вы не пропустили обязательные платежи и работали без штрафов.`,
            isPaid: false,
            duration: '10 мин',
            url: 'https://alfa.me/usn6-calendar',
          },
        ],
      },
    ],
  }
}

const initialPlan: Plan = buildPlanForProfile(initialUser.profile, undefined, 'DONE')

const initialReminders: Reminder[] = [
  {
    id: '55555555-5555-4555-8555-555555555551',
    title: 'Проверка уведомления ЕНП и аванса по налогу (до 28 числа)',
    status: 'ACTIVE',
    schedule: {
      type: 'MONTHLY',
      timeZone: 'Europe/Moscow',
      localTime: '10:00',
      startDate: '2026-11-12',
      dayOfMonth: 25,
    },
    nextFireAt: '2026-11-25T07:00:00Z',
    lastSentAt: null,
    completionReason: null,
    material: initialPlan.steps[3].materials[0],
    planStepId: initialPlan.steps[3].id,
    version: 1,
    createdAt: '2026-11-12T09:00:00Z',
    updatedAt: '2026-11-12T09:00:00Z',
  },
  {
    id: '55555555-5555-4555-8555-555555555552',
    title: 'Изучить урок про выбор онлайн-кассы и эквайринга',
    status: 'ACTIVE',
    schedule: {
      type: 'WEEKLY',
      timeZone: 'Europe/Moscow',
      localTime: '19:00',
      startDate: '2026-11-12',
      dayOfWeek: 'THURSDAY',
    },
    nextFireAt: '2026-11-12T16:00:00Z',
    lastSentAt: null,
    completionReason: null,
    material: initialPlan.steps[1].materials[0],
    planStepId: initialPlan.steps[1].id,
    version: 1,
    createdAt: '2026-11-12T09:00:00Z',
    updatedAt: '2026-11-12T09:00:00Z',
  },
]

export const useDevStore = create<DevAppState>((set, get) => ({
  user: initialUser,
  plan: initialPlan,
  reminders: initialReminders,

  fetchInitialData: async () => {
    try {
      const [meRes, planRes, remRes] = await Promise.allSettled([
        backend.GET('/me'),
        backend.GET('/plans/current'),
        backend.GET('/reminders'),
      ])

      if (meRes.status === 'fulfilled' && meRes.value.data) {
        set({ user: meRes.value.data as User })
      }
      if (planRes.status === 'fulfilled' && planRes.value.data) {
        set({ plan: planRes.value.data as Plan })
      }
      if (remRes.status === 'fulfilled' && remRes.value.data) {
        const raw = remRes.value.data as { items?: Reminder[] } | Reminder[]
        const list = Array.isArray(raw) ? raw : raw.items
        if (list) set({ reminders: list })
      }
    } catch {
    }
  },

  setDisplayName: (name: string) =>
    set((state) => ({
      user: {
        ...state.user,
        displayName: name,
      },
    })),

  toggleStepStatus: (stepId) => {
    const currentPlan = get().plan
    if (!currentPlan || currentPlan.status === 'ARCHIVED') return

    const targetStep = currentPlan.steps.find((s) => s.id === stepId)
    if (!targetStep) return
    const nextStatus: 'TODO' | 'DONE' = targetStep.status === 'DONE' ? 'TODO' : 'DONE'

    set((state) => {
      if (!state.plan) return state
      return {
        plan: {
          ...state.plan,
          version: state.plan.version + 1,
          updatedAt: new Date().toISOString(),
          steps: state.plan.steps.map((step) =>
            step.id === stepId ? { ...step, status: nextStatus } : step,
          ),
        },
      }
    })

    backend
      .PATCH('/plans/{planId}/steps/{stepId}', {
        params: { path: { planId: currentPlan.id, stepId } },
        body: { status: nextStatus, expectedVersion: currentPlan.version },
      })
      .then(({ data }) => {
        if (data) set({ plan: data as Plan })
      })
      .catch(() => {})
  },

  archiveCurrentPlan: () => {
    const currentPlan = get().plan
    if (!currentPlan) return

    set({ plan: null })

    backend
      .POST('/plans/{planId}/archive', {
        params: { path: { planId: currentPlan.id } },
        body: { expectedVersion: currentPlan.version },
      })
      .catch(() => {})
  },

  generatePlan: (customGoal) => {
    set((state) => {
      const rebuilt = buildPlanForProfile(state.user.profile, customGoal, 'TODO')
      return {
        plan: {
          ...rebuilt,
          id: crypto.randomUUID(),
          version: (state.plan?.version ?? 0) + 1,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
      }
    })

    backend
      .POST('/plans/generate', {
        body: { goal: customGoal?.trim() || null },
      })
      .then(({ data }) => {
        if (data) set({ plan: data as Plan })
      })
      .catch(() => {})
  },

  createReminder: (payload) => {
    set((state) => {
      const newReminder: Reminder = {
        id: crypto.randomUUID(),
        title: payload.title,
        status: 'ACTIVE',
        schedule: payload.schedule,
        nextFireAt: new Date(Date.now() + 86400000).toISOString(),
        lastSentAt: null,
        completionReason: null,
        material: null,
        planStepId: payload.planStepId,
        version: 1,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      }
      return { reminders: [newReminder, ...state.reminders] }
    })

    backend
      .POST('/reminders', { body: payload })
      .then(({ data }) => {
        if (data) {
          const created = data as Reminder
          set((state) => ({
            reminders: [created, ...state.reminders.slice(1)],
          }))
        }
      })
      .catch(() => {})
  },

  toggleReminderStatus: (reminderId) => {
    const target = get().reminders.find((r) => r.id === reminderId)
    if (!target) return
    const nextStatus = target.status === 'ACTIVE' ? 'PAUSED' : 'ACTIVE'

    set((state) => ({
      reminders: state.reminders.map((r) =>
        r.id === reminderId
          ? {
              ...r,
              status: nextStatus,
              nextFireAt:
                nextStatus === 'PAUSED'
                  ? null
                  : new Date(Date.now() + 86400000).toISOString(),
              version: r.version + 1,
            }
          : r,
      ),
    }))

    backend
      .PATCH('/reminders/{reminderId}', {
        params: { path: { reminderId } },
        body: { status: nextStatus, expectedVersion: target.version },
      })
      .catch(() => {})
  },

  cancelReminder: (reminderId) => {
    const target = get().reminders.find((r) => r.id === reminderId)
    if (!target) return

    set((state) => ({
      reminders: state.reminders.map((r) =>
        r.id === reminderId
          ? { ...r, status: 'CANCELLED', nextFireAt: null, version: r.version + 1 }
          : r,
      ),
    }))

    backend
      .DELETE('/reminders/{reminderId}', {
        params: { path: { reminderId } },
        body: { expectedVersion: target.version },
      })
      .catch(() => {})
  },

  updateProfile: (patch) => {
    const currentVersion = get().user.profileVersion

    set((state) => {
      const updatedProfile = {
        ...state.user.profile,
        ...patch,
      }

      let updatedPlan = state.plan
      if (state.plan) {
        const rebuilt = buildPlanForProfile(updatedProfile, state.plan.goal, 'TODO')
        updatedPlan = {
          ...rebuilt,
          id: state.plan.id,
          version: state.plan.version + 1,
          steps: rebuilt.steps.map((step, idx) => ({
            ...step,
            status: state.plan?.steps[idx]?.status ?? step.status,
          })),
        }
      }

      return {
        user: {
          ...state.user,
          profileVersion: state.user.profileVersion + 1,
          profile: updatedProfile,
        },
        plan: updatedPlan,
      }
    })

    backend
      .PUT('/me/profile', {
        body: {
          ...patch,
          expectedVersion: currentVersion,
        },
      })
      .then(({ data }) => {
        if (data) set({ user: data as User })
      })
      .catch(() => {})
  },
}))

interface AuthState {
  accessToken: string | null
  user: User | null
  setAuth: (token: string, user: User) => void
  clearAuth: () => void
}

export const useAuthStore = create<AuthState>((set) => ({
  accessToken: null,
  user: null,
  setAuth: (accessToken, user) => set({ accessToken, user }),
  clearAuth: () => set({ accessToken: null, user: null }),
}))