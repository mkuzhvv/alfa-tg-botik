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
  Pencil,
} from 'lucide-react'
import { useAppStore } from '@/shared/api/appStore'
import {
  ApiError,
  apiError,
  type Reminder,
  type ReminderCreate,
} from '@/shared/api/client'
import { ApiNotice } from '@/shared/ui/ApiNotice'
import {
  localDate,
  formatInstant,
  scheduleFrom,
  validZone,
} from '@/shared/lib/schedule'
import { botUrl } from '@/shared/lib/telegram'
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
    const day = schedule.dayOfWeek
      ? DAY_OF_WEEK_LABELS[schedule.dayOfWeek]
      : 'Чт'
    return `Еженедельно (${day}) в ${schedule.localTime ?? '19:00'}`
  }
  if (schedule.type === 'DAILY') {
    return `Ежедневно в ${schedule.localTime ?? '10:00'}`
  }
  if (schedule.at) {
    return `Разово: ${formatInstant(schedule.at, schedule.timeZone)}`
  }
  return 'Разовое напоминание'
}

function RemindersScreen() {
  const {
    user,
    reminders,
    createReminder,
    updateReminder,
    toggleReminderStatus,
    cancelReminder,
    busy,
    loading,
    error,
  } = useAppStore()

  const [isDrawerOpen, setIsDrawerOpen] = useState(false)
  const [title, setTitle] = useState('')
  const [scheduleType, setScheduleType] = useState<ScheduleType>('MONTHLY')
  const [localTime, setLocalTime] = useState('10:00')
  const [dayOfMonth, setDayOfMonth] = useState(25)
  const [dayOfWeek, setDayOfWeek] = useState<DayOfWeek>('THURSDAY')
  const [date, setDate] = useState('')
  const [timeZone, setTimeZone] = useState('')
  const [editing, setEditing] = useState<Reminder | null>(null)
  const [message, setMessage] = useState('')
  const [pendingAction, setPendingAction] = useState<{
    id: string
    label: string
  } | null>(null)
  const disabled = !!busy || loading

  const visibleReminders = reminders.filter((r) => r.status !== 'CANCELLED')

  const runReminderAction = async (
    id: string,
    label: string,
    action: () => Promise<void>,
  ) => {
    if (useAppStore.getState().busy || useAppStore.getState().loading) return
    setPendingAction({ id, label })
    try {
      await action()
    } catch (failure) {
      useAppStore.setState({ error: apiError(failure) })
    } finally {
      setPendingAction(null)
    }
  }

  const openForm = (reminder: Reminder | null = null, taxTemplate = false) => {
    const zone = reminder?.schedule.timeZone || user?.profile.timeZone || ''
    setEditing(reminder)
    setTitle(
      reminder?.title ||
        (taxTemplate ? 'Проверить налоговые задачи бизнеса' : ''),
    )
    setTimeZone(zone)
    setScheduleType(reminder?.schedule.type || 'MONTHLY')
    setLocalTime(
      reminder?.schedule.type === 'ONCE'
        ? new Intl.DateTimeFormat('en-GB', {
            timeZone: zone,
            hour: '2-digit',
            minute: '2-digit',
            hourCycle: 'h23',
          }).format(new Date(reminder.schedule.at))
        : reminder?.schedule.localTime || '10:00',
    )
    setDate(
      reminder?.schedule.type === 'ONCE'
        ? localDate(zone, new Date(reminder.schedule.at))
        : reminder?.schedule.startDate ||
            (validZone(zone) ? localDate(zone) : ''),
    )
    setDayOfWeek(
      reminder?.schedule.type === 'WEEKLY'
        ? reminder.schedule.dayOfWeek
        : 'THURSDAY',
    )
    setDayOfMonth(
      reminder?.schedule.type === 'MONTHLY' ? reminder.schedule.dayOfMonth : 25,
    )
    setMessage('')
    useAppStore.setState({ error: null })
    setIsDrawerOpen(true)
  }

  const handleCreateSubmit = async () => {
    setMessage('')
    try {
      if (!title.trim() || Array.from(title.trim()).length > 160)
        throw new Error('Заголовок должен содержать от 1 до 160 символов.')
      const schedule: ReminderCreate['schedule'] = scheduleFrom({
        type: scheduleType,
        date,
        time: localTime,
        timeZone: timeZone.trim(),
        dayOfWeek,
        dayOfMonth,
      })
      if (editing)
        await updateReminder(editing.id, {
          expectedVersion: editing.version,
          title: title.trim(),
          schedule,
        })
      else
        await createReminder({
          title: title.trim(),
          materialId: null,
          planStepId: null,
          schedule,
        })
      setIsDrawerOpen(false)
    } catch (failure) {
      if (!(failure instanceof ApiError))
        setMessage(
          failure instanceof Error
            ? failure.message
            : 'Проверь поля напоминания.',
        )
    }
  }

  return (
    <div className="space-y-4">
      <div className="bg-alfa-dark text-white rounded-3xl p-5 shadow-sm space-y-3">
        <div className="flex items-center justify-between">
          <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[11px] font-semibold bg-alfa-red text-white">
            <Sparkles className="w-3 h-3" />
            Налоговый контроль • {user?.profile.taxRegime || 'Режим не выбран'}
          </span>
          <Calendar className="w-4 h-4 text-gray-400" />
        </div>

        <div>
          <h2 className="text-base font-bold leading-snug">
            Не пропускайте отчётность и занятия
          </h2>
          <p className="text-xs text-gray-400 mt-1 leading-relaxed">
            Бот пришлёт уведомление в Telegram в выбранный день с прямой ссылкой
            на нужный материал или инструкцию.
          </p>
        </div>

        <div className="flex gap-2 pt-1">
          <button
            disabled={disabled}
            onClick={() => openForm()}
            className="flex-1 py-2.5 px-3 rounded-xl bg-alfa-red hover:bg-alfa-red-hover text-white text-xs font-semibold flex items-center justify-center gap-1.5 transition-colors cursor-pointer"
          >
            <Plus className="w-4 h-4" />
            <span>Новое напоминание</span>
          </button>

          <button
            disabled={disabled}
            onClick={() => openForm(null, true)}
            className="py-2.5 px-3 rounded-xl bg-white/10 hover:bg-white/20 text-white text-xs font-medium flex items-center justify-center gap-1.5 transition-colors cursor-pointer"
            title="Настроить напоминание о налоговых задачах"
          >
            <span>+ Шаблон</span>
          </button>
        </div>
      </div>

      {user && !user.canReceiveReminders && (
        <p className="text-xs text-gray-500">
          Для получения напоминаний{' '}
          <a
            href={botUrl}
            target="_blank"
            rel="noreferrer"
            className="text-alfa-red font-semibold"
          >
            открой бота
          </a>{' '}
          и отправь /start. Затем обнови данные.
        </p>
      )}

      {visibleReminders.length === 0 ? (
        <div className="bg-white rounded-3xl p-6 text-center border border-gray-100 space-y-3">
          <div className="w-12 h-12 rounded-2xl bg-red-50 text-alfa-red flex items-center justify-center mx-auto">
            <Bell className="w-6 h-6" />
          </div>
          <div className="space-y-1">
            <h3 className="text-sm font-bold text-alfa-dark">
              Нет активных напоминаний
            </h3>
            <p className="text-xs text-gray-500">
              Создайте напоминание о налогах или добавьте время для изучения
              курса.
            </p>
          </div>
        </div>
      ) : (
        <div className="space-y-3">
          {visibleReminders.map((reminder) => {
            const isPaused = reminder.status === 'PAUSED'
            const terminal =
              reminder.status === 'COMPLETED' || reminder.status === 'CANCELLED'
            return (
              <article
                key={reminder.id}
                aria-label={reminder.title}
                aria-busy={pendingAction?.id === reminder.id}
                className={cn(
                  'bg-white rounded-2xl p-4 border transition-all space-y-3',
                  isPaused
                    ? 'border-gray-200 bg-gray-50/70 opacity-75'
                    : 'border-gray-100 shadow-xs',
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
                        isPaused || terminal
                          ? 'bg-gray-200 text-gray-600'
                          : 'bg-emerald-100 text-emerald-800',
                      )}
                    >
                      {reminder.status === 'COMPLETED'
                        ? 'Завершено'
                        : reminder.status === 'CANCELLED'
                          ? 'Отменено'
                          : isPaused
                            ? 'На паузе'
                            : 'Активно'}
                    </span>
                  </div>

                  {!terminal && (
                    <div className="flex items-center gap-1 shrink-0">
                      <button
                        disabled={disabled}
                        onClick={() => openForm(reminder)}
                        title="Изменить напоминание"
                        aria-label="Изменить напоминание"
                        className="p-1.5 rounded-lg text-gray-500 hover:bg-gray-100 disabled:opacity-40 disabled:cursor-wait cursor-pointer"
                      >
                        <Pencil className="w-4 h-4" />
                      </button>
                      <button
                        disabled={disabled}
                        onClick={() => {
                          void runReminderAction(
                            reminder.id,
                            isPaused ? 'Возобновляем…' : 'Ставим на паузу…',
                            () => toggleReminderStatus(reminder.id),
                          )
                        }}
                        className="p-1.5 rounded-lg text-gray-500 hover:bg-gray-100 hover:text-alfa-dark transition-colors disabled:opacity-40 disabled:cursor-wait cursor-pointer"
                        title={isPaused ? 'Возобновить' : 'Поставить на паузу'}
                        aria-label={
                          isPaused ? 'Возобновить' : 'Поставить на паузу'
                        }
                      >
                        {isPaused ? (
                          <Play className="w-4 h-4 text-emerald-600" />
                        ) : (
                          <Pause className="w-4 h-4" />
                        )}
                      </button>
                      <button
                        disabled={disabled}
                        onClick={() => {
                          void runReminderAction(reminder.id, 'Отменяем…', () =>
                            cancelReminder(reminder.id),
                          )
                        }}
                        className="p-1.5 rounded-lg text-gray-400 hover:bg-red-50 hover:text-alfa-red transition-colors disabled:opacity-40 disabled:cursor-wait cursor-pointer"
                        title="Отменить напоминание"
                        aria-label="Отменить напоминание"
                      >
                        <Trash2 className="w-4 h-4" />
                      </button>
                    </div>
                  )}
                </div>

                {pendingAction?.id === reminder.id && (
                  <p role="status" className="text-xs text-gray-500">
                    {pendingAction.label}
                  </p>
                )}

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
                    <span>
                      {formatScheduleDescription(reminder.schedule)} (
                      {reminder.schedule.timeZone})
                    </span>
                  </div>
                </div>

                {reminder.nextFireAt && (
                  <p className="text-xs text-gray-500">
                    Ближайшая отправка:{' '}
                    {formatInstant(
                      reminder.nextFireAt,
                      reminder.schedule.timeZone,
                    )}
                  </p>
                )}
                {reminder.completionReason && (
                  <p className="text-xs text-gray-500">
                    {
                      {
                        DELIVERED: 'Доставлено',
                        EXPIRED: 'Срок отправки истёк',
                        DELIVERY_UNKNOWN: 'Результат доставки неизвестен',
                      }[reminder.completionReason]
                    }
                  </p>
                )}

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
              </article>
            )
          })}
        </div>
      )}

      <Drawer.Root
        open={isDrawerOpen}
        onOpenChange={(open) => {
          if (!busy) setIsDrawerOpen(open)
        }}
        dismissible={!busy}
      >
        <Drawer.Portal>
          <Drawer.Overlay className="fixed inset-0 bg-black/40 z-40" />
          <Drawer.Content className="bg-white flex flex-col rounded-t-3xl max-h-[85vh] fixed bottom-0 left-0 right-0 max-w-md mx-auto z-50 p-5 outline-none">
            <div className="w-12 h-1.5 bg-gray-300 rounded-full mx-auto mb-4 shrink-0" />

            <div className="space-y-4 overflow-y-auto pb-4">
              <Drawer.Title className="text-lg font-bold text-alfa-dark">
                {editing ? 'Изменить напоминание' : 'Новое напоминание'}
              </Drawer.Title>
              <Drawer.Description className="text-xs text-gray-500">
                Выбери время и подтверждённый часовой пояс. Изменение расписания
                сохраняется только для этого напоминания.
              </Drawer.Description>
              <ApiNotice error={error} />
              {message && (
                <p role="alert" className="text-xs text-alfa-red">
                  {message}
                </p>
              )}

              <div className="space-y-1.5">
                <span className="text-xs font-semibold text-gray-500">
                  Быстрый шаблон:
                </span>
                <div className="flex flex-wrap gap-1.5">
                  {[
                    'Проверить налоговые задачи',
                    'Проверить оплату налога',
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
                  id="reminderTitle"
                  aria-label="О чём напомнить?"
                  maxLength={160}
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
                  {(
                    ['MONTHLY', 'WEEKLY', 'DAILY', 'ONCE'] as ScheduleType[]
                  ).map((type) => (
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
                    Число месяца (1–31)
                  </label>
                  <div className="flex flex-wrap items-center gap-2">
                    <input
                      aria-label="Число месяца"
                      type="number"
                      min={1}
                      max={31}
                      value={dayOfMonth}
                      onChange={(event) =>
                        setDayOfMonth(Number(event.target.value))
                      }
                      className="w-16 bg-alfa-gray/60 border border-gray-200 rounded-xl px-2 py-1.5 text-xs"
                    />
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
                    {(Object.keys(DAY_OF_WEEK_LABELS) as DayOfWeek[]).map(
                      (dayKey) => (
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
                      ),
                    )}
                  </div>
                </div>
              )}

              <div className="space-y-1.5">
                <label
                  htmlFor="reminderDate"
                  className="text-xs font-bold text-alfa-dark block"
                >
                  {scheduleType === 'ONCE'
                    ? 'Дата отправки'
                    : 'Начинать с даты'}
                </label>
                <input
                  id="reminderDate"
                  type="date"
                  value={date}
                  onChange={(event) => setDate(event.target.value)}
                  className="w-full bg-alfa-gray/60 border border-gray-200 rounded-xl px-3.5 py-2 text-sm"
                />
              </div>
              <div className="space-y-1.5">
                <label
                  htmlFor="reminderZone"
                  className="text-xs font-bold text-alfa-dark block"
                >
                  Часовой пояс IANA
                </label>
                <input
                  id="reminderZone"
                  maxLength={64}
                  value={timeZone}
                  onChange={(event) => setTimeZone(event.target.value)}
                  placeholder="Europe/Moscow"
                  className="w-full bg-alfa-gray/60 border border-gray-200 rounded-xl px-3.5 py-2 text-sm"
                />
              </div>
              {
                <div className="space-y-1.5">
                  <label className="text-xs font-bold text-alfa-dark block">
                    Время отправки
                  </label>
                  <input
                    aria-label="Время отправки"
                    type="time"
                    value={localTime}
                    onChange={(e) => setLocalTime(e.target.value)}
                    className="w-full bg-alfa-gray/60 border border-gray-200 rounded-xl px-3.5 py-2 text-sm focus:outline-none focus:border-alfa-red"
                  />
                </div>
              }

              <button
                type="button"
                onClick={() => {
                  void handleCreateSubmit()
                }}
                disabled={disabled || !title.trim()}
                className="w-full py-3 px-4 rounded-xl bg-alfa-red hover:bg-alfa-red-hover disabled:bg-gray-200 disabled:text-gray-400 text-white font-semibold text-sm transition-colors cursor-pointer mt-2"
              >
                {busy ? 'Сохраняем…' : 'Сохранить напоминание'}
              </button>
            </div>
          </Drawer.Content>
        </Drawer.Portal>
      </Drawer.Root>
    </div>
  )
}
