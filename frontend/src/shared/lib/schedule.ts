import { TZDate } from '@date-fns/tz'
import type { ReminderCreate, Reminder } from '../api/client'

export type ScheduleType = Reminder['schedule']['type']
export type DayOfWeek = Extract<
  Reminder['schedule'],
  { type: 'WEEKLY' }
>['dayOfWeek']
export interface ScheduleForm {
  type: ScheduleType
  date: string
  time: string
  timeZone: string
  dayOfWeek: DayOfWeek
  dayOfMonth: number
}

export function validZone(zone: string) {
  try {
    new Intl.DateTimeFormat('en', { timeZone: zone }).format()
    return !!zone && (zone === 'UTC' || zone.includes('/'))
  } catch {
    return false
  }
}

export function localDate(zone: string, now = new Date()) {
  const date = new TZDate(now.getTime(), zone)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

export function onceAt(date: string, time: string, zone: string) {
  if (
    !validZone(zone) ||
    !/^\d{4}-\d{2}-\d{2}$/.test(date) ||
    !/^\d{2}:\d{2}$/.test(time)
  )
    throw new Error('Укажи дату, время и часовой пояс IANA.')
  const [year, month, day] = date.split('-').map(Number)
  const [hour, minute] = time.split(':').map(Number)
  const calendar = new Date(Date.UTC(year, month - 1, day))
  if (
    calendar.getUTCFullYear() !== year ||
    calendar.getUTCMonth() !== month - 1 ||
    calendar.getUTCDate() !== day ||
    hour > 23 ||
    minute > 59
  )
    throw new Error('Проверь дату и время.')
  const value = new TZDate(year, month - 1, day, hour, minute, 0, 0, zone)
  return new Date(value.getTime()).toISOString()
}

export function scheduleFrom(form: ScheduleForm): ReminderCreate['schedule'] {
  if (!validZone(form.timeZone))
    throw new Error('Подтверди часовой пояс IANA, например Europe/Moscow.')
  if (!form.date || !form.time) throw new Error('Укажи дату и время.')
  const at = onceAt(form.date, form.time, form.timeZone)
  if (form.type === 'ONCE') {
    if (new Date(at).getTime() <= Date.now())
      throw new Error('Разовое напоминание должно быть в будущем.')
    return { type: 'ONCE', at, timeZone: form.timeZone }
  }
  const common = {
    timeZone: form.timeZone,
    localTime: form.time,
    startDate: form.date,
  }
  if (form.type === 'WEEKLY')
    return { ...common, type: form.type, dayOfWeek: form.dayOfWeek }
  if (form.type === 'MONTHLY') {
    if (
      !Number.isInteger(form.dayOfMonth) ||
      form.dayOfMonth < 1 ||
      form.dayOfMonth > 31
    )
      throw new Error('Число месяца должно быть от 1 до 31.')
    return { ...common, type: form.type, dayOfMonth: form.dayOfMonth }
  }
  return { ...common, type: 'DAILY' }
}

export function formatInstant(at: string, zone: string) {
  return new Date(at).toLocaleString('ru-RU', {
    timeZone: zone,
    dateStyle: 'short',
    timeStyle: 'short',
  })
}
