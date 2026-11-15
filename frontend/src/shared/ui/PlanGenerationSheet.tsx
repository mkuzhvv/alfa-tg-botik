import { useState } from 'react'
import { Drawer } from 'vaul'
import { Loader2 } from 'lucide-react'
import type { Plan } from '../api/client'
import { useAppStore } from '../api/appStore'
import { ApiNotice } from './ApiNotice'
import { haptic } from '../lib/telegram'

export function PlanGenerationSheet({
  current,
  onClose,
}: {
  current: Plan | null
  onClose: () => void
}) {
  const { user, generatePlan, busy, error, fetchInitialData } = useAppStore()
  const [goal, setGoal] = useState(user?.profile.currentTask || '')
  const generating = busy === 'plan.generate'
  async function submit() {
    try {
      await generatePlan(
        goal,
        current
          ? { id: current.id, expectedVersion: current.version }
          : undefined,
      )
      haptic.success()
      onClose()
    } catch {
      haptic.light()
    }
  }
  return (
    <Drawer.Root
      open
      onOpenChange={(open) => {
        if (!open && !generating) onClose()
      }}
      dismissible={!generating}
    >
      <Drawer.Portal>
        <Drawer.Overlay className="fixed inset-0 bg-black/40 z-40" />
        <Drawer.Content className="bg-white flex flex-col rounded-t-3xl max-h-[85vh] fixed bottom-0 left-0 right-0 max-w-md mx-auto z-50 p-5 outline-none">
          <div className="w-12 h-1.5 bg-gray-300 rounded-full mx-auto mb-4 shrink-0" />
          <div className="space-y-4 overflow-y-auto pb-4">
            <Drawer.Title className="text-lg font-bold text-alfa-dark">
              {current ? 'Заменить текущий план' : 'Создать план'}
            </Drawer.Title>
            <Drawer.Description className="text-xs text-gray-500">
              {current
                ? `Действующий план «${current.goal}» будет сохранён в архиве только после успешного создания нового.`
                : 'Укажи цель бизнеса. План будет создан по материалам Альфа-Курса.'}
            </Drawer.Description>
            <ApiNotice
              error={error}
              onRetry={() => {
                void fetchInitialData()
                  .then(onClose)
                  .catch(() => undefined)
              }}
            />
            <label
              htmlFor="planGoal"
              className="text-xs font-bold text-alfa-dark block"
            >
              Цель плана
            </label>
            <textarea
              id="planGoal"
              value={goal}
              maxLength={250}
              disabled={generating}
              onChange={(event) => setGoal(event.target.value)}
              className="w-full bg-alfa-gray/60 border border-gray-200 rounded-xl px-3.5 py-2.5 text-sm focus:outline-none focus:border-alfa-red"
            />
            <p className="text-xs text-gray-500">
              {Array.from(goal).length}/250
            </p>
            {generating && (
              <div
                role="status"
                className="bg-white rounded-3xl p-8 text-center border border-gray-100 shadow-xs space-y-4"
              >
                <Loader2 className="w-7 h-7 animate-spin text-alfa-red mx-auto" />
                <h2 className="text-base font-bold text-alfa-dark">
                  ИИ-навигатор формирует трек…
                </h2>
                <p className="text-xs text-gray-500">
                  Подготовка может занять до минуты.
                </p>
              </div>
            )}
            <button
              disabled={
                !!busy || !goal.trim() || Array.from(goal.trim()).length > 250
              }
              onClick={() => {
                void submit()
              }}
              className="w-full py-3 px-4 rounded-xl bg-alfa-red hover:bg-alfa-red-hover disabled:bg-gray-200 disabled:text-gray-400 text-white font-semibold text-sm cursor-pointer"
            >
              {generating
                ? 'Создаём…'
                : current
                  ? 'Подтверждаю замену плана'
                  : 'Создать план'}
            </button>
          </div>
        </Drawer.Content>
      </Drawer.Portal>
    </Drawer.Root>
  )
}
