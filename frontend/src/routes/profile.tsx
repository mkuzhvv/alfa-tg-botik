import { useState } from 'react'
import { createFileRoute } from '@tanstack/react-router'
import {
  UserCircle,
  Briefcase,
  Calculator,
  CheckCircle2,
  Save,
} from 'lucide-react'
import { useAppStore } from '@/shared/api/appStore'
import type {
  Profile,
  ProfileUpdate,
  TaxRegime,
  User,
} from '@/shared/api/client'
import { cn } from '@/shared/lib/utils'
import { haptic } from '@/shared/lib/telegram'
import { validZone } from '@/shared/lib/schedule'

export const Route = createFileRoute('/profile')({ component: ProfileScreen })

const TAX_REGIMES: { value: TaxRegime; label: string; desc: string }[] = [
  {
    value: 'NPD',
    label: 'НПД (Самозанятость)',
    desc: 'Налог на профессиональный доход',
  },
  { value: 'USN_6', label: 'УСН 6%', desc: 'С доходов' },
  { value: 'USN_15', label: 'УСН 15%', desc: 'Доходы минус расходы' },
  { value: 'PATENT', label: 'Патент (ПСН)', desc: 'Патентная система' },
  { value: 'OSNO', label: 'ОСНО', desc: 'Общий режим' },
  { value: 'AUSN', label: 'АУСН', desc: 'Автоматизированная УСН' },
]
const STAGES: { value: Profile['businessStage']; label: string }[] = [
  { value: null, label: 'Не указано' },
  { value: 'IDEA', label: 'Только идея' },
  { value: 'LAUNCH', label: 'Запуск' },
  { value: 'OPERATING', label: 'Действующий бизнес' },
  { value: 'GROWTH', label: 'Развитие и масштабирование' },
]
const inputClass =
  'w-full bg-alfa-gray/50 border border-gray-200 rounded-xl px-4 py-3 text-sm focus:outline-none focus:border-alfa-red focus:bg-white transition-colors'

function ProfileScreen() {
  const user = useAppStore((state) => state.user)
  return user ? <ProfileForm user={user} /> : null
}

function ProfileForm({ user }: { user: User }) {
  const { updateProfile, busy, loading } = useAppStore()
  const [draft, setDraft] = useState({
    profile: { ...user.profile },
    base: user.profile,
    version: user.profileVersion,
  })
  const [isSaved, setIsSaved] = useState(false)
  const [message, setMessage] = useState('')
  const disabled = !!busy || loading
  function change<K extends keyof Profile>(field: K, value: Profile[K]) {
    setDraft((current) => ({
      ...current,
      profile: { ...current.profile, [field]: value },
    }))
    setIsSaved(false)
    setMessage('')
  }
  async function handleSave() {
    const profile = {
      ...draft.profile,
      industry: draft.profile.industry?.trim() || null,
      currentTask: draft.profile.currentTask?.trim() || null,
      timeZone: draft.profile.timeZone?.trim() || null,
    }
    if (profile.timeZone && !validZone(profile.timeZone)) {
      setMessage('Укажи часовой пояс IANA, например Europe/Moscow.')
      return
    }
    const patch: ProfileUpdate = { expectedVersion: draft.version }
    for (const field of Object.keys(profile) as (keyof Profile)[]) {
      if (profile[field] !== draft.base[field])
        Object.assign(patch, { [field]: profile[field] })
    }
    if (Object.keys(patch).length === 1) {
      setMessage('Изменений нет.')
      return
    }
    try {
      const saved = await updateProfile(patch)
      setDraft({
        profile: { ...saved.profile },
        base: saved.profile,
        version: saved.profileVersion,
      })
      setIsSaved(true)
      setMessage('')
      haptic.success()
    } catch {
      setIsSaved(false)
    }
  }

  return (
    <div className="space-y-5 animate-in fade-in slide-in-from-bottom-4 duration-500">
      <div className="bg-white rounded-3xl p-5 shadow-xs border border-gray-100 flex items-center gap-4">
        <div className="w-14 h-14 rounded-full bg-gray-100 flex items-center justify-center shrink-0">
          <UserCircle className="w-8 h-8 text-gray-400" />
        </div>
        <div>
          <h2 className="text-lg font-bold text-alfa-dark">
            {user.displayName || 'Мой бизнес'}
          </h2>
          <p className="text-xs text-gray-500 mt-0.5">Предприниматель</p>
        </div>
      </div>
      {draft.version !== user.profileVersion && (
        <div
          role="alert"
          className="rounded-2xl bg-red-50 p-3 text-xs space-y-2"
        >
          <p>
            Профиль изменился в другом окне или чате. Черновик сохранён на
            экране.
          </p>
          <button
            className="font-semibold text-alfa-red cursor-pointer"
            onClick={() => {
              setDraft({
                profile: { ...user.profile },
                base: user.profile,
                version: user.profileVersion,
              })
              setIsSaved(false)
            }}
          >
            Загрузить актуальный профиль
          </button>
        </div>
      )}
      <fieldset
        disabled={disabled}
        className="bg-white rounded-3xl p-5 shadow-xs border border-gray-100 space-y-6"
      >
        <div className="space-y-2.5">
          <label
            htmlFor="industry"
            className="flex items-center gap-2 text-sm font-bold text-alfa-dark"
          >
            <Briefcase className="w-4 h-4 text-alfa-red" />
            Сфера бизнеса
          </label>
          <input
            id="industry"
            value={draft.profile.industry || ''}
            maxLength={100}
            onChange={(event) => change('industry', event.target.value || null)}
            placeholder="Например: Кофейня, Маркетплейсы, IT..."
            className={inputClass}
          />
        </div>
        <div className="space-y-2.5">
          <label className="text-sm font-bold text-alfa-dark block">
            Стадия развития
          </label>
          <div className="flex flex-wrap gap-2">
            {STAGES.map((stage) => (
              <button
                key={stage.value || 'empty'}
                aria-pressed={draft.profile.businessStage === stage.value}
                onClick={() => change('businessStage', stage.value)}
                className={cn(
                  'px-3 py-2 rounded-xl text-xs font-semibold border transition-all cursor-pointer',
                  draft.profile.businessStage === stage.value
                    ? 'bg-alfa-red text-white border-alfa-red'
                    : 'bg-white border-gray-200 text-gray-600 hover:bg-gray-50',
                )}
              >
                {stage.label}
              </button>
            ))}
          </div>
        </div>
        <div className="space-y-2.5">
          <label
            htmlFor="experience"
            className="text-sm font-bold text-alfa-dark block"
          >
            Опыт
          </label>
          <select
            id="experience"
            value={draft.profile.experience || ''}
            onChange={(event) =>
              change(
                'experience',
                (event.target.value || null) as Profile['experience'],
              )
            }
            className={inputClass}
          >
            <option value="">Не указано</option>
            <option value="BEGINNER">Начинающий</option>
            <option value="EXPERIENCED">Есть опыт</option>
          </select>
        </div>
        <div className="space-y-2.5">
          <label
            htmlFor="currentTask"
            className="text-sm font-bold text-alfa-dark block"
          >
            Текущая задача бизнеса
          </label>
          <textarea
            id="currentTask"
            maxLength={500}
            value={draft.profile.currentTask || ''}
            onChange={(event) =>
              change('currentTask', event.target.value || null)
            }
            className={inputClass}
            placeholder="Какую задачу хочешь решить?"
          />
        </div>
        <div className="space-y-2.5">
          <label className="flex items-center gap-2 text-sm font-bold text-alfa-dark">
            <Calculator className="w-4 h-4 text-alfa-red" />
            Налоговый режим
          </label>
          <div className="grid grid-cols-2 gap-2">
            {TAX_REGIMES.map((tax) => (
              <button
                key={tax.value}
                aria-pressed={draft.profile.taxRegime === tax.value}
                onClick={() => change('taxRegime', tax.value)}
                className={cn(
                  'text-left p-3 rounded-xl border transition-all cursor-pointer flex flex-col gap-1',
                  draft.profile.taxRegime === tax.value
                    ? 'bg-red-50 border-alfa-red shadow-sm'
                    : 'bg-white border-gray-200 hover:bg-gray-50',
                )}
              >
                <div className="flex items-center justify-between">
                  <span
                    className={cn(
                      'text-xs font-bold',
                      draft.profile.taxRegime === tax.value
                        ? 'text-alfa-red'
                        : 'text-alfa-dark',
                    )}
                  >
                    {tax.label}
                  </span>
                  {draft.profile.taxRegime === tax.value && (
                    <CheckCircle2 className="w-4 h-4 text-alfa-red" />
                  )}
                </div>
                <span className="text-[10px] text-gray-500 leading-tight">
                  {tax.desc}
                </span>
              </button>
            ))}
          </div>
          <button
            className="text-xs text-gray-500 cursor-pointer"
            onClick={() => change('taxRegime', null)}
          >
            Не указывать налоговый режим
          </button>
        </div>
        <div className="space-y-2.5">
          <label
            htmlFor="timeZone"
            className="text-sm font-bold text-alfa-dark block"
          >
            Часовой пояс
          </label>
          <input
            id="timeZone"
            maxLength={64}
            value={draft.profile.timeZone || ''}
            onChange={(event) => change('timeZone', event.target.value || null)}
            placeholder="Europe/Moscow"
            className={inputClass}
          />
          <p className="text-xs text-gray-500">
            Изменение пояса профиля не переносит сохранённые напоминания.
          </p>
        </div>
        {message && (
          <p role="status" className="text-xs text-gray-500">
            {message}
          </p>
        )}
        <div className="pt-2">
          <button
            onClick={() => {
              void handleSave()
            }}
            className={cn(
              'w-full py-3.5 px-4 rounded-xl font-semibold text-sm flex items-center justify-center gap-2 transition-all cursor-pointer',
              isSaved
                ? 'bg-green-500 text-white'
                : 'bg-alfa-dark hover:bg-gray-800 text-white',
            )}
          >
            {isSaved ? (
              <>
                <CheckCircle2 className="w-5 h-5" />
                <span>Данные сохранены</span>
              </>
            ) : (
              <>
                <Save className="w-5 h-5" />
                <span>
                  {busy === 'profile.update'
                    ? 'Сохраняем…'
                    : 'Сохранить профиль'}
                </span>
              </>
            )}
          </button>
        </div>
      </fieldset>
    </div>
  )
}
