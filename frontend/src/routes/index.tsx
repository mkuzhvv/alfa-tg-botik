import { useState } from 'react'
import { createFileRoute, useNavigate } from '@tanstack/react-router'
import { Drawer } from 'vaul'
import {
  CheckCircle2,
  Circle,
  Sparkles,
  Clock,
  Lock,
  ExternalLink,
  BellPlus,
  Archive,
  RefreshCw,
  ChevronRight,
} from 'lucide-react'
import { useDevStore } from '@/shared/api/devStore'
import type { MaterialCard, MaterialKind } from '@/shared/api/client'
import { cn } from '@/shared/lib/utils'
import { haptic } from '@/shared/lib/telegram'
import { Loader2 } from 'lucide-react'
export const Route = createFileRoute('/')({
  component: PlanScreen,
})

const KIND_LABELS: Record<MaterialKind, string> = {
  ARTICLE: 'Статья',
  COURSE: 'Курс',
  LESSON: 'Урок',
  DOCUMENT: 'Документ',
  WEBINAR: 'Вебинар',
  PODCAST: 'Подкаст',
  GLOSSARY: 'Глоссарий',
  CALCULATOR: 'Калькулятор',
}

function PlanScreen() {
  const navigate = useNavigate()
  const { plan, user, toggleStepStatus, archiveCurrentPlan, generatePlan, createReminder } =
    useDevStore()

  const [selectedMaterial, setSelectedMaterial] = useState<{
    material: MaterialCard
    stepId: string
  } | null>(null)
  const [reminderAdded, setReminderAdded] = useState(false)

  const [isGenerating, setIsGenerating] = useState(false)

  const handleGeneratePlan = () => {
    haptic.light()
    window.scrollTo({ top: 0, behavior: 'smooth' })
    setIsGenerating(true)
    setTimeout(() => {
      generatePlan()
      setIsGenerating(false)
      haptic.success()
    }, 1500)
  }

  if (isGenerating) {
    const taxLabels: Record<string, string> = {
      NPD: 'НПД',
      USN_6: 'УСН 6%',
      USN_15: 'УСН 15%',
      PATENT: 'Патент',
      OSNO: 'ОСНО',
      AUSN: 'АУСН',
    }

    const readableTax = user.profile.taxRegime
      ? taxLabels[user.profile.taxRegime]
      : null

    return (
      <div className="bg-white rounded-3xl p-8 text-center border border-gray-100 shadow-xs space-y-4 my-4 animate-in fade-in duration-300">
        <div className="w-14 h-14 rounded-2xl bg-red-50 text-alfa-red flex items-center justify-center mx-auto">
          <Loader2 className="w-7 h-7 animate-spin" />
        </div>
        <div className="space-y-1.5">
          <h2 className="text-base font-bold text-alfa-dark">
            ИИ-навигатор формирует трек...
          </h2>
          <p className="text-xs text-gray-500 leading-relaxed">
            Анализируем нишу «{user.profile.industry || 'Новый бизнес'}» и подбираем материалы
            {readableTax ? ` под режим ${readableTax}` : ' под ваш профиль'}
          </p>
        </div>
      </div>
    )
  }

  if (!plan) {
    return (
      <div className="bg-white rounded-3xl p-6 text-center border border-gray-100 shadow-xs space-y-4 my-4 animate-in fade-in duration-300">
        <div className="w-12 h-12 rounded-2xl bg-red-50 text-alfa-red flex items-center justify-center mx-auto">
          <Sparkles className="w-6 h-6" />
        </div>
        <div className="space-y-1">
          <h2 className="text-base font-bold text-alfa-dark">
            У вас пока нет активного плана
          </h2>
          <p className="text-xs text-gray-500 leading-relaxed">
            Сформируйте персональный пошаговый план развития бизнеса на основе вашего профиля и налогового режима.
          </p>
        </div>
        <button
          onClick={handleGeneratePlan}
          className="w-full py-3 px-4 rounded-xl bg-alfa-red hover:bg-alfa-red-hover text-white font-semibold text-sm flex items-center justify-center gap-2 transition-colors cursor-pointer"
        >
          <Sparkles className="w-4 h-4" />
          <span>Сформировать план по профилю</span>
        </button>
      </div>
    )
  }

  const completedCount = plan.steps.filter((s) => s.status === 'DONE').length
  const totalCount = plan.steps.length
  const progressPercent = Math.round((completedCount / totalCount) * 100)

  const handleQuickReminder = (material: MaterialCard, stepId: string) => {
    createReminder({
      title: `Изучить: ${material.title.slice(0, 120)}`,
      materialId: material.id,
      planStepId: stepId,
      schedule: {
        type: 'ONCE',
        timeZone: user.profile.timeZone || 'Europe/Moscow',
        at: new Date(Date.now() + 86400000).toISOString(),
      },
    })
    setReminderAdded(true)
    setTimeout(() => {
      setReminderAdded(false)
      setSelectedMaterial(null)
      navigate({ to: '/reminders' })
    }, 900)
  }

  return (
    <div className="space-y-4">
      <div className="bg-alfa-dark text-white rounded-3xl p-5 shadow-sm space-y-4">
        <div className="flex items-start justify-between gap-2">
          <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[11px] font-semibold bg-alfa-red text-white">
            <Sparkles className="w-3 h-3" />
            Персональный маршрут
          </span>
          <span className="text-[11px] text-gray-400">v{plan.version}</span>
        </div>

        <div>
          <p className="text-xs text-gray-400 mb-1">Текущая цель</p>
          <h2 className="text-base font-bold leading-snug">{plan.goal}</h2>
        </div>

        <div className="space-y-1.5">
          <div className="flex justify-between text-xs">
            <span className="text-gray-300">
              Выполнено {completedCount} из {totalCount} шагов
            </span>
            <span className="font-bold text-white">{progressPercent}%</span>
          </div>
          <div className="w-full h-2 bg-white/15 rounded-full overflow-hidden">
            <div
              className="h-full bg-alfa-red transition-all duration-300 rounded-full"
              style={{ width: `${progressPercent}%` }}
            />
          </div>
        </div>
      </div>

      <div className="space-y-3">
        {plan.steps.map((step) => {
          const isDone = step.status === 'DONE'
          return (
            <div
              key={step.id}
              className={cn(
                'bg-white rounded-2xl p-4 border transition-all',
                isDone ? 'border-gray-200 bg-gray-50/70' : 'border-gray-100 shadow-xs',
              )}
            >
              <div className="flex items-start gap-3">
                <button
                  onClick={() => toggleStepStatus(step.id)}
                  className="mt-0.5 text-alfa-red hover:scale-105 transition-transform cursor-pointer shrink-0"
                  title={isDone ? 'Отметить как невыполненное' : 'Отметить выполненным'}
                >
                  {isDone ? (
                    <CheckCircle2 className="w-6 h-6 fill-alfa-red text-white" />
                  ) : (
                    <Circle className="w-6 h-6 text-gray-300 hover:text-alfa-red" />
                  )}
                </button>

                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-1">
                    <span className="text-[11px] font-semibold uppercase tracking-wider text-gray-400">
                      Шаг {step.position}
                    </span>
                    {isDone && (
                      <span className="text-[10px] font-medium px-2 py-0.5 rounded-full bg-green-100 text-green-700">
                        Готово
                      </span>
                    )}
                  </div>
                  <h3
                    className={cn(
                      'text-sm font-bold leading-snug',
                      isDone && 'line-through text-gray-400',
                    )}
                  >
                    {step.title}
                  </h3>
                  <p className="text-xs text-gray-500 mt-1 leading-relaxed">{step.description}</p>
                </div>
              </div>

              <div className="mt-3 pt-3 border-t border-gray-100 space-y-2.5">
                {step.materials.map((material) => (
                  <button
                    key={material.id}
                    onClick={() => setSelectedMaterial({ material, stepId: step.id })}
                    className="w-full text-left rounded-xl p-3 bg-alfa-gray/70 hover:bg-gray-200/60 transition-colors flex flex-col gap-2 cursor-pointer"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <div className="flex items-center gap-1.5 flex-wrap">
                        <span className="text-[10px] font-semibold px-2 py-0.5 rounded-md bg-white text-alfa-dark border border-gray-200">
                          {KIND_LABELS[material.kind]}
                        </span>
                        <span
                          className={cn(
                            'text-[10px] font-semibold px-2 py-0.5 rounded-md flex items-center gap-1',
                            material.isPaid
                              ? 'bg-amber-100 text-amber-800'
                              : 'bg-emerald-100 text-emerald-800',
                          )}
                        >
                          {material.isPaid && <Lock className="w-2.5 h-2.5" />}
                          {material.isPaid ? 'Платно' : 'Бесплатно'}
                        </span>
                        {material.duration && (
                          <span className="text-[11px] text-gray-500 flex items-center gap-1">
                            <Clock className="w-3 h-3" />
                            {material.duration}
                          </span>
                        )}
                      </div>
                      <ChevronRight className="w-4 h-4 text-gray-400 shrink-0" />
                    </div>

                    <div className="text-xs font-bold text-alfa-dark leading-snug">
                      {material.title}
                    </div>

                    {material.recommendationReason && (
                      <div className="text-[11px] text-gray-600 bg-white/90 rounded-lg p-2 border-l-2 border-alfa-red leading-relaxed">
                        <span className="font-semibold text-alfa-dark">Почему вам подходит: </span>
                        {material.recommendationReason}
                      </div>
                    )}
                  </button>
                ))}
              </div>
            </div>
          )
        })}
      </div>

      <div className="flex gap-2 pt-2">
        <button
          onClick={handleGeneratePlan}
          className="flex-1 py-3 px-4 rounded-2xl bg-white border border-gray-200 hover:bg-gray-50 text-xs font-semibold text-alfa-dark flex items-center justify-center gap-1.5 transition-colors cursor-pointer shadow-2xs"
        >
          <RefreshCw className="w-4 h-4 text-alfa-red" />
          <span>Обновить по профилю</span>
        </button>
        <button
          onClick={archiveCurrentPlan}
          className="py-2.5 px-3 rounded-xl bg-white border border-gray-200 text-xs font-medium text-gray-500 hover:text-alfa-red hover:bg-red-50 flex items-center justify-center gap-1.5 cursor-pointer"
        >
          <Archive className="w-3.5 h-3.5" />
          <span>В архив</span>
        </button>
      </div>

      <Drawer.Root
        open={Boolean(selectedMaterial)}
        onOpenChange={(open) => !open && setSelectedMaterial(null)}
      >
        <Drawer.Portal>
          <Drawer.Overlay className="fixed inset-0 bg-black/40 z-40" />
          <Drawer.Content className="bg-white flex flex-col rounded-t-3xl max-h-[85vh] fixed bottom-0 left-0 right-0 max-w-md mx-auto z-50 p-5 outline-none">
            <div className="w-12 h-1.5 bg-gray-300 rounded-full mx-auto mb-4 shrink-0" />
            {selectedMaterial && (
              <div className="space-y-4 overflow-y-auto pb-4">
                <div className="flex items-center gap-2 flex-wrap">
                  <span className="text-xs font-semibold px-2.5 py-1 rounded-lg bg-gray-100 text-alfa-dark">
                    {KIND_LABELS[selectedMaterial.material.kind]}
                  </span>
                  <span
                    className={cn(
                      'text-xs font-semibold px-2.5 py-1 rounded-lg flex items-center gap-1',
                      selectedMaterial.material.isPaid
                        ? 'bg-amber-100 text-amber-900'
                        : 'bg-emerald-100 text-emerald-800',
                    )}
                  >
                    {selectedMaterial.material.isPaid && <Lock className="w-3 h-3" />}
                    {selectedMaterial.material.isPaid ? 'Платный курс' : 'Бесплатный материал'}
                  </span>
                  {selectedMaterial.material.duration && (
                    <span className="text-xs text-gray-500 flex items-center gap-1">
                      <Clock className="w-3.5 h-3.5" />
                      {selectedMaterial.material.duration}
                    </span>
                  )}
                </div>

                <Drawer.Title className="text-lg font-bold leading-snug text-alfa-dark">
                  {selectedMaterial.material.title}
                </Drawer.Title>

                <Drawer.Description className="text-xs text-gray-600 leading-relaxed">
                  {selectedMaterial.material.description}
                </Drawer.Description>

                {selectedMaterial.material.recommendationReason && (
                  <div className="rounded-2xl bg-red-50/70 border border-red-100 p-3.5 space-y-1">
                    <div className="text-xs font-bold text-alfa-red flex items-center gap-1.5">
                      <Sparkles className="w-3.5 h-3.5" />
                      <span>Почему ИИ подобрал этот материал для вас</span>
                    </div>
                    <p className="text-xs text-alfa-dark leading-relaxed">
                      {selectedMaterial.material.recommendationReason}
                    </p>
                  </div>
                )}

                {selectedMaterial.material.isPaid && (
                  <div className="rounded-2xl bg-amber-50 border border-amber-200 p-3.5 text-xs text-amber-900 leading-relaxed">
                    <strong>Доступ по подписке или покупке:</strong> Полные видеолекции и
                    шаблоны таблиц находятся в закрытой части Альфа-Курса. Вы можете ознакомиться с
                    программой и приобрести доступ на официальной странице.
                  </div>
                )}

                <div className="pt-2 space-y-2.5">
                  <a
                    href={selectedMaterial.material.url}
                    target="_blank"
                    rel="noreferrer"
                    className="w-full py-3 px-4 rounded-xl bg-alfa-red hover:bg-alfa-red-hover text-white font-semibold text-sm flex items-center justify-center gap-2 transition-colors"
                  >
                    <span>
                      {selectedMaterial.material.isPaid
                        ? 'Перейти к странице покупки курса'
                        : 'Открыть материал на Альфа-Курсе'}
                    </span>
                    <ExternalLink className="w-4 h-4" />
                  </a>

                  <button
                    onClick={() =>
                      handleQuickReminder(selectedMaterial.material, selectedMaterial.stepId)
                    }
                    className="w-full py-3 px-4 rounded-xl bg-gray-100 hover:bg-gray-200 text-alfa-dark font-semibold text-sm flex items-center justify-center gap-2 transition-colors cursor-pointer"
                  >
                    <BellPlus className="w-4 h-4 text-alfa-red" />
                    <span>
                      {reminderAdded
                        ? 'Напоминание добавлено! Переходим...'
                        : 'Напомнить об изучении завтра'}
                    </span>
                  </button>
                </div>
              </div>
            )}
          </Drawer.Content>
        </Drawer.Portal>
      </Drawer.Root>
    </div>
  )
}