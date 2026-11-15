import { describe, expect, it } from 'vitest'
import {
  onceAt,
  localDate,
  scheduleFrom,
  validZone,
} from '../src/shared/lib/schedule'

describe('user time zones', () => {
  it.each([
    ['2027-01-01', '09:00', 'Europe/Moscow', '2027-01-01T06:00:00.000Z'],
    ['2027-01-01', '13:00', 'Asia/Kolkata', '2027-01-01T07:30:00.000Z'],
    ['2027-03-14', '02:30', 'America/New_York', '2027-03-14T07:30:00.000Z'],
    ['2027-11-07', '01:30', 'America/New_York', '2027-11-07T05:30:00.000Z'],
  ])(
    'converts %s %s in %s to UTC with the server DST policy',
    (date, time, zone, expected) => {
      expect(onceAt(date, time, zone)).toBe(expected)
    },
  )
  it('uses the selected zone for the calendar date', () => {
    expect(
      localDate('Pacific/Auckland', new Date('2027-01-01T23:00:00Z')),
    ).toBe('2027-01-02')
    expect(validZone('UTC+3')).toBe(false)
  })
  it('retains monthly 31 and rejects invalid calendar input', () => {
    expect(
      scheduleFrom({
        type: 'MONTHLY',
        date: '2027-01-31',
        time: '09:00',
        timeZone: 'UTC',
        dayOfMonth: 31,
        dayOfWeek: 'MONDAY',
      }),
    ).toMatchObject({ dayOfMonth: 31, startDate: '2027-01-31' })
    expect(() => onceAt('2027-02-31', '09:00', 'UTC')).toThrow()
    expect(() => onceAt('2027-01-01', '25:00', 'UTC')).toThrow()
  })
})
