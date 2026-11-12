import { create } from 'zustand'
import type { User, Plan, Reminder, ProfileUpdate, ReminderCreate } from './client'

interface DevAppState {
  user: User
  plan: Plan | null
  reminders: Reminder[]
  toggleStepStatus: (stepId: string) => void
  archiveCurrentPlan: () => void
  generatePlan: (customGoal?: string) => void
  createReminder: (payload: ReminderCreate) => void
  toggleReminderStatus: (reminderId: string) => void
  cancelReminder: (reminderId: string) => void
  updateProfile: (patch: Omit<ProfileUpdate, 'expectedVersion'>) => void
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

const initialPlan: Plan = {
  id: '22222222-2222-4222-8222-222222222222',
  goal: 'Запуск первой кофейни и настройка учёта на УСН 6%',
  status: 'ACTIVE',
  version: 1,
  createdAt: '2026-11-12T09:00:00Z',
  updatedAt: '2026-11-12T09:00:00Z',
  steps: [
    {
      id: '33333333-3333-4333-8333-333333333331',
      position: 1,
      title: 'Регистрация ИП и выбор кодов ОКВЭД для общепита',
      description: 'Подберите основные и дополнительные коды ОКВЭД для кофейни навынос и подайте уведомление о переходе на УСН 6% сразу при регистрации.',
      status: 'DONE',
      materials: [
        {
          id: '44444444-4444-4444-8444-444444444441',
          title: 'Пошаговый гайд: регистрация ИП для кофейни за 3 дня',
          kind: 'ARTICLE',
          description: 'Разбор кодов ОКВЭД 56.10 и 56.30, частые ошибки при подаче заявления и шаблон уведомления о переходе на УСН.',
          recommendationReason: 'Вы находитесь на стадии запуска (LAUNCH) в сфере общепита без предыдущего опыта — этот гайд убережёт от выбора неверного ОКВЭД.',
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
      description: 'Настройте приём оплаты картами, по СБП и фискализацию чеков до первой продажи.',
      status: 'TODO',
      materials: [
        {
          id: '44444444-4444-4444-8444-444444444442',
          title: 'Касса и торговый эквайринг для розницы и кафе',
          kind: 'LESSON',
          description: 'Как выбрать фискальный накопитель на 15 или 36 месяцев при УСН и сэкономить на комиссии за приём платежей.',
          recommendationReason: 'Для розничной точки с режимом УСН 6% важно правильно выбрать срок фискального накопителя и ставку эквайринга.',
          isPaid: false,
          duration: '18 мин',
          url: 'https://alfa.me/kassa-lesson',
        },
        {
          id: '44444444-4444-4444-8444-444444444443',
          title: 'Калькулятор выгодного тарифа РКО и эквайринга',
          kind: 'CALCULATOR',
          description: 'Интерактивный расчёт расходов на обслуживание счёта в зависимости от оборота точки.',
          recommendationReason: 'Поможет заранее заложить банковскую комиссию в себестоимость чашки кофе.',
          isPaid: false,
          duration: '5 мин',
          url: 'https://alfa.me/rko-calc',
        },
      ],
    },
    {
      id: '33333333-3333-4333-8333-333333333333',
      position: 3,
      title: 'Финансовая модель точки и расчёт точки безубыточности',
      description: 'Рассчитайте фудкост, аренду, ФОТ бариста и минимальное число чеков в день для выхода в плюс.',
      status: 'TODO',
      materials: [
        {
          id: '44444444-4444-4444-8444-444444444444',
          title: 'Финансовое планирование и юнит-экономика в общепите',
          kind: 'COURSE',
          description: 'Практический курс Альфа-Курса с готовыми таблицами P&L, Cash Flow и расчётом себестоимости техкарт.',
          recommendationReason: 'Закрывает вашу главную цель по запуску первой точки: даст готовую финмодель кофейни, чтобы не уйти в кассовый разрыв в первые 3 месяца.',
          isPaid: true,
          duration: '4 модуля • 3 часа',
          url: 'https://alfa.me/unit-food-course',
        },
      ],
    },
    {
      id: '33333333-3333-4333-8333-333333333334',
      position: 4,
      title: 'Налоговый календарь УСН 6% и уменьшение налога на взносы',
      description: 'Узнайте, как уменьшать налог УСН 6% на сумму страховых взносов ИП и в какие даты подавать уведомления ЕНП.',
      status: 'TODO',
      materials: [
        {
          id: '44444444-4444-4444-8444-444444444445',
          title: 'Памятка по срокам уплаты УСН 6% и страховых взносов в 2026 году',
          kind: 'DOCUMENT',
          description: 'График платежей до 25-го и 28-го числа и правила уменьшения авансовых платежей.',
          recommendationReason: 'Подобран специально под ваш налоговый режим УСН 6% (USN_6), чтобы вы не пропустили обязательные платежи и сэкономили на взносах.',
          isPaid: false,
          duration: '10 мин',
          url: 'https://alfa.me/usn6-calendar',
        },
      ],
    },
  ],
}

const initialReminders: Reminder[] = [
  {
    id: '55555555-5555-4555-8555-555555555551',
    title: 'Проверка уведомления ЕНП и аванса по УСН 6% (до 28 числа)',
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

export const useDevStore = create<DevAppState>((set) => ({
  user: initialUser,
  plan: initialPlan,
  reminders: initialReminders,

  toggleStepStatus: (stepId) =>
    set((state) => {
      if (!state.plan || state.plan.status === 'ARCHIVED') return state
      const updatedSteps = state.plan.steps.map((step) =>
        step.id === stepId
          ? { ...step, status: (step.status === 'DONE' ? 'TODO' : 'DONE') as 'TODO' | 'DONE' }
          : step,
      )
      return {
        plan: {
          ...state.plan,
          version: state.plan.version + 1,
          updatedAt: new Date().toISOString(),
          steps: updatedSteps,
        },
      }
    }),

  archiveCurrentPlan: () =>
    set((state) => {
      if (!state.plan) return state
      return {
        plan: null,
      }
    }),

  generatePlan: (customGoal) =>
    set((state) => {
      const taxLabel = state.user.profile.taxRegime || 'УСН 6%'
      const goalText =
        customGoal?.trim() ||
        state.user.profile.currentTask ||
        `План развития бизнеса (${state.user.profile.industry || 'Общий профиль'})`

      return {
        plan: {
          ...initialPlan,
          id: crypto.randomUUID(),
          goal: goalText,
          status: 'ACTIVE',
          version: 1,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
          steps: initialPlan.steps.map((s, idx) => ({
            ...s,
            status: idx === 0 ? 'TODO' : s.status,
            title:
              idx === 3
                ? `Налоговый календарь (${taxLabel}) и контроль отчётности`
                : s.title,
          })),
        },
      }
    }),

  createReminder: (payload) =>
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
    }),

  toggleReminderStatus: (reminderId) =>
    set((state) => ({
      reminders: state.reminders.map((r) =>
        r.id === reminderId
          ? {
              ...r,
              status: r.status === 'ACTIVE' ? 'PAUSED' : 'ACTIVE',
              nextFireAt: r.status === 'ACTIVE' ? null : new Date(Date.now() + 86400000).toISOString(),
              version: r.version + 1,
            }
          : r,
      ),
    })),

  cancelReminder: (reminderId) =>
    set((state) => ({
      reminders: state.reminders.map((r) =>
        r.id === reminderId
          ? { ...r, status: 'CANCELLED', nextFireAt: null, version: r.version + 1 }
          : r,
      ),
    })),

  updateProfile: (patch) =>
    set((state) => ({
      user: {
        ...state.user,
        profileVersion: state.user.profileVersion + 1,
        profile: {
          ...state.user.profile,
          ...patch,
        },
      },
    })),
}))