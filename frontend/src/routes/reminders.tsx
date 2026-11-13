import { useState } from 'react'
import { createFileRoute } from '@tanstack/react-router'
import { Drawer } from 'vaul'
import {
  Bell,
  Plus,
  Calendar,
  Clock,
  Pause,
  Play,
  Trash2,
  Sparkles,
  BookOpen,
} from 'lucide-react'
import { useDevStore } from '@/shared/api/devStore'
import type { Reminder, ReminderCreate } from '@/shared/api/client'
import { cn } from '@/shared/lib/utils'

export const Route = createFileRoute('/reminders')({
  component: RemindersScreen,
})

type ScheduleType = Reminder['schedule']['type']
type WeeklySchedule = Extract<Reminder['schedule'], { type: 'WEEKLY' }>
type DayOfWeek = WeeklySchedule['dayOfWeek']

const SCHEDULE_TYPE_LABELS: Record<ScheduleType, string> = {
  ONCE: 'Один раз',
  DAILY: 'Каждый день',
  WEEKLY: 'Каждую неделю',
  MONTHLY: 'Каждый месяц',
}

const DAY_OF_WEEK_LABELS: Record<DayOfWeek, string> = {
  MONDAY: 'Пн',
  TUESDAY: 'Вт',
  WEDNESDAY: 'Ср',
  THURSDAY: 'Чт',
  FRIDAY: 'Пт',
  SATURDAY: 'Сб',
  SUNDAY: 'Вс',
}

function formatScheduleDescription(schedule: Reminder['schedule']): string {
  if (schedule.type === 'MONTHLY') {
    return `Ежемесячно, ${schedule.dayOfMonth ?? 25}-го числа в ${schedule.localTime ?? '10:00'}`
  }
  if (schedule.type === 'WEEKLY') {
    const day = schedule.dayOfWeek ? DAY_OF_WEEK_LABELS[schedule.dayOfWeek] : 'Чт'
    return `Еженедельно (${day}) в ${schedule.localTime ?? '19:00'}`
  }
  if (schedule.type === 'DAILY') {
    return `Ежедневно в ${schedule.localTime ?? '10:00'}`
  }
  if (schedule.at) {
    const date = new Date(schedule.at)
    return `Разово: ${date.toLocaleDateString('ru-RU', {
      day: 'numeric',
      month: 'short',
    })} в ${date.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })}`
  }
  return 'Разовое напоминание'
}

function RemindersScreen() {
  const { user, reminders, createReminder, toggleReminderStatus, cancelReminder } =
    useDevStore()

  const [isDrawerOpen, setIsDrawerOpen] = useState(false)
  const [title, setTitle] = useState('')
  const [scheduleType, setScheduleType] = useState<ScheduleType>('MONTHLY')
  const [localTime, setLocalTime] = useState('10:00')
  const [dayOfMonth, setDayOfMonth] = useState(25)
  const [dayOfWeek, setDayOfWeek] = useState<DayOfWeek>('THURSDAY')

  const visibleReminders = reminders.filter((r) => r.status !== 'CANCELLED')

  const handleQuickTaxReminder = () => {
    const taxName = user.profile.taxRegime || 'УСН'
    createReminder({
      title: `Подать уведомление ЕНП и проверить аванс (${taxName})`,
      materialId: null,
      planStepId: null,
      schedule: {
        type: 'MONTHLY',
        timeZone: user.profile.timeZone || 'Europe/Moscow',
        localTime: '10:00',
        startDate: new Date().toISOString().slice(0, 10),
        dayOfMonth: 25,
      },
    })
  }

  const handleCreateSubmit = () => {
    if (!title.trim()) return

    const todayDate = new Date().toISOString().slice(0, 10)
    const tz = user.profile.timeZone || 'Europe/Moscow'

    let schedule: ReminderCreate['schedule']

    if (scheduleType === 'MONTHLY') {
      schedule = {
        type: 'MONTHLY',
        timeZone: tz,
        localTime,
        startDate: todayDate,
        dayOfMonth,
      }
    } else if (scheduleType === 'WEEKLY') {
      schedule = {
        type: 'WEEKLY',
        timeZone: tz,
        localTime,
        startDate: todayDate,
        dayOfWeek,
      }
    } else if (scheduleType === 'DAILY') {
      schedule = {
        type: 'DAILY',
        timeZone: tz,
        localTime,
        startDate: todayDate,
      }
    } else {
      schedule = {
        type: 'ONCE',
        timeZone: tz,
        at: new Date(Date.now() + 86400000).toISOString(),
      }
    }

    createReminder({
      title: title.trim(),
      materialId: null,
      planStepId: null,
      schedule,
    })

    setTitle('')
    setIsDrawerOpen(false)
  }

  return (
    <div className="space-y-4">
      <div className="bg-alfa-dark text-white rounded-3xl p-5 shadow-sm space-y-3">
        <div className="flex items-center justify-between">
          <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[11px] font-semibold bg-alfa-red text-white">
            <Sparkles className="w-3 h-3" />
            Налоговый контроль • {user.profile.taxRegime || 'Режим не выбран'}
          </span>
          <Calendar className="w-4 h-4 text-gray-400" />
        </div>

        <div>
          <h2 className="text-base font-bold leading-snug">
            Не пропускайте отчётность и занятия
          </h2>
          <p className="text-xs text-gray-400 mt-1 leading-relaxed">
            Бот пришлёт уведомление в Telegram в выбранный день с прямой ссылкой на нужный материал или инструкцию.
          </p>
        </div>

        <div className="flex gap-2 pt-1">
          <button
            onClick={() => setIsDrawerOpen(true)}
            className="flex-1 py-2.5 px-3 rounded-xl bg-alfa-red hover:bg-alfa-red-hover text-white text-xs font-semibold flex items-center justify-center gap-1.5 transition-colors cursor-pointer"
          >
            <Plus className="w-4 h-4" />
            <span>Новое напоминание</span>
          </button>

          <button
            onClick={handleQuickTaxReminder}
            className="py-2.5 px-3 rounded-xl bg-white/10 hover:bg-white/20 text-white text-xs font-medium flex items-center justify-center gap-1.5 transition-colors cursor-pointer"
            title="Добавить напоминание на 25-е число каждого месяца"
          >
            <span>+ Налог 25-го</span>
          </button>
        </div>
      </div>

      {visibleReminders.length === 0 ? (
        <div className="bg-white rounded-3xl p-6 text-center border border-gray-100 space-y-3">
          <div className="w-12 h-12 rounded-2xl bg-red-50 text-alfa-red flex items-center justify-center mx-auto">
            <Bell className="w-6 h-6" />
          </div>
          <div className="space-y-1">
            <h3 className="text-sm font-bold text-alfa-dark">Нет активных напоминаний</h3>
            <p className="text-xs text-gray-500">
              Создайте напоминание о налогах или добавьте время для изучения курса.
            </p>
          </div>
        </div>
      ) : (
        <div className="space-y-3">
          {visibleReminders.map((reminder) => {
            const isPaused = reminder.status === 'PAUSED'
            return (
              <div
                key={reminder.id}
                className={cn(
                  'bg-white rounded-2xl p-4 border transition-all space-y-3',
                  isPaused ? 'border-gray-200 bg-gray-50/70 opacity-75' : 'border-gray-100 shadow-xs',
                )}
              >
                <div className="flex items-start justify-between gap-2">
                  <div className="flex items-center gap-1.5 flex-wrap">
                    <span className="text-[10px] font-semibold px-2 py-0.5 rounded-md bg-red-50 text-alfa-red">
                      {SCHEDULE_TYPE_LABELS[reminder.schedule.type]}
                    </span>
                    <span
                      className={cn(
                        'text-[10px] font-semibold px-2 py-0.5 rounded-md',
                        isPaused
                          ? 'bg-gray-200 text-gray-600'
                          : 'bg-emerald-100 text-emerald-800',
                      )}
                    >
                      {isPaused ? 'На паузе' : 'Активно'}
                    </span>
                  </div>

                  <div className="flex items-center gap-1 shrink-0">
                    <button
                      onClick={() => toggleReminderStatus(reminder.id)}
                      className="p-1.5 rounded-lg text-gray-500 hover:bg-gray-100 hover:text-alfa-dark transition-colors cursor-pointer"
                      title={isPaused ? 'Возобновить' : 'Поставить на паузу'}
                    >
                      {isPaused ? (
                        <Play className="w-4 h-4 text-emerald-600" />
                      ) : (
                        <Pause className="w-4 h-4" />
                      )}
                    </button>
                    <button
                      onClick={() => cancelReminder(reminder.id)}
                      className="p-1.5 rounded-lg text-gray-400 hover:bg-red-50 hover:text-alfa-red transition-colors cursor-pointer"
                      title="Удалить напоминание"
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                  </div>
                </div>

                <div>
                  <h3
                    className={cn(
                      'text-sm font-bold leading-snug text-alfa-dark',
                      isPaused && 'line-through text-gray-400',
                    )}
                  >
                    {reminder.title}
                  </h3>
                  <div className="flex items-center gap-1.5 text-xs text-gray-500 mt-1.5">
                    <Clock className="w-3.5 h-3.5 text-alfa-red shrink-0" />
                    <span>{formatScheduleDescription(reminder.schedule)}</span>
                  </div>
                </div>

                {reminder.material && (
                  <a
                    href={reminder.material.url}
                    target="_blank"
                    rel="noreferrer"
                    className="flex items-center gap-2 p-2.5 rounded-xl bg-alfa-gray/70 hover:bg-gray-200/70 transition-colors text-xs text-alfa-dark font-medium"
                  >
                    <BookOpen className="w-4 h-4 text-alfa-red shrink-0" />
                    <span className="truncate">{reminder.material.title}</span>
                  </a>
                )}
              </div>
            )
          })}
        </div>
      )}

      <Drawer.Root open={isDrawerOpen} onOpenChange={setIsDrawerOpen}>
        <Drawer.Portal>
          <Drawer.Overlay className="fixed inset-0 bg-black/40 z-40" />
          <Drawer.Content className="bg-white flex flex-col rounded-t-3xl max-h-[85vh] fixed bottom-0 left-0 right-0 max-w-md mx-auto z-50 p-5 outline-none">
            <div className="w-12 h-1.5 bg-gray-300 rounded-full mx-auto mb-4 shrink-0" />

            <div className="space-y-4 overflow-y-auto pb-4">
              <Drawer.Title className="text-lg font-bold text-alfa-dark">
                Новое напоминание
              </Drawer.Title>

              <div className="space-y-1.5">
                <span className="text-xs font-semibold text-gray-500">Быстрый шаблон:</span>
                <div className="flex flex-wrap gap-1.5">
                  {[
                    'Уведомление ЕНП (до 25 числа)',
                    'Оплата налога (до 28 числа)',
                    'Вечерний урок по бизнесу',
                  ].map((preset) => (
                    <button
                      key={preset}
                      type="button"
                      onClick={() => setTitle(preset)}
                      className="text-[11px] px-2.5 py-1 rounded-lg bg-alfa-gray hover:bg-gray-200 text-alfa-dark font-medium transition-colors cursor-pointer"
                    >
                      {preset}
                    </button>
                  ))}
                </div>
              </div>

              <div className="space-y-1.5">
                <label className="text-xs font-bold text-alfa-dark block">
                  О чём напомнить?
                </label>
                <input
                  type="text"
                  value={title}
                  onChange={(e) => setTitle(e.target.value)}
                  placeholder="Например: Проверить отчётность по УСН..."
                  className="w-full bg-alfa-gray/60 border border-gray-200 rounded-xl px-3.5 py-2.5 text-sm focus:outline-none focus:border-alfa-red focus:bg-white"
                />
              </div>

              <div className="space-y-1.5">
                <label className="text-xs font-bold text-alfa-dark block">
                  Периодичность
                </label>
                <div className="grid grid-cols-2 gap-2">
                  {(['MONTHLY', 'WEEKLY', 'DAILY', 'ONCE'] as ScheduleType[]).map((type) => (
                    <button
                      key={type}
                      type="button"
                      onClick={() => setScheduleType(type)}
                      className={cn(
                        'py-2 px-3 rounded-xl text-xs font-semibold border transition-all cursor-pointer',
                        scheduleType === type
                          ? 'bg-alfa-red text-white border-alfa-red'
                          : 'bg-white text-gray-600 border-gray-200 hover:bg-gray-50',
                      )}
                    >
                      {SCHEDULE_TYPE_LABELS[type]}
                    </button>
                  ))}
                </div>
              </div>

              {scheduleType === 'MONTHLY' && (
                <div className="space-y-1.5">
                  <label className="text-xs font-bold text-alfa-dark block">
                    Число месяца (1–28)
                  </label>
                  <div className="flex items-center gap-2">
                    {[15, 20, 25, 28].map((day) => (
                      <button
                        key={day}
                        type="button"
                        onClick={() => setDayOfMonth(day)}
                        className={cn(
                          'px-3 py-1.5 rounded-lg text-xs font-semibold border cursor-pointer',
                          dayOfMonth === day
                            ? 'bg-red-50 border-alfa-red text-alfa-red'
                            : 'bg-white border-gray-200 text-gray-600',
                        )}
                      >
                        {day}-е число
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {scheduleType === 'WEEKLY' && (
                <div className="space-y-1.5">
                  <label className="text-xs font-bold text-alfa-dark block">
                    День недели
                  </label>
                  <div className="flex justify-between gap-1">
                    {(Object.keys(DAY_OF_WEEK_LABELS) as DayOfWeek[]).map((dayKey) => (
                      <button
                        key={dayKey}
                        type="button"
                        onClick={() => setDayOfWeek(dayKey)}
                        className={cn(
                          'flex-1 py-1.5 rounded-lg text-xs font-semibold border cursor-pointer',
                          dayOfWeek === dayKey
                            ? 'bg-red-50 border-alfa-red text-alfa-red'
                            : 'bg-white border-gray-200 text-gray-600',
                        )}
                      >
                        {DAY_OF_WEEK_LABELS[dayKey]}
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {scheduleType !== 'ONCE' && (
                <div className="space-y-1.5">
                  <label className="text-xs font-bold text-alfa-dark block">
                    Время отправки
                  </label>
                  <input
                    type="time"
                    value={localTime}
                    onChange={(e) => setLocalTime(e.target.value)}
                    className="w-full bg-alfa-gray/60 border border-gray-200 rounded-xl px-3.5 py-2 text-sm focus:outline-none focus:border-alfa-red"
                  />
                </div>
              )}

              <button
                type="button"
                onClick={handleCreateSubmit}
                disabled={!title.trim()}
                className="w-full py-3 px-4 rounded-xl bg-alfa-red hover:bg-alfa-red-hover disabled:bg-gray-200 disabled:text-gray-400 text-white font-semibold text-sm transition-colors cursor-pointer mt-2"
              >
                Сохранить напоминание
              </button>
            </div>
          </Drawer.Content>
        </Drawer.Portal>
      </Drawer.Root>
    </div>
  )
}