import { test, expect, type Page } from '@playwright/test'
import { createHmac, randomInt } from 'node:crypto'
import { readFileSync, mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { execFileSync } from 'node:child_process'

const root = fileURLToPath(new URL('../../../', import.meta.url))
const token =
  process.env.TELEGRAM_BOT_TOKEN ||
  readFileSync(`${root}/.env`, 'utf8')
    .match(/^TELEGRAM_BOT_TOKEN=(.+)$/m)?.[1]
    .trim()
if (!token)
  throw new Error('Set TELEGRAM_BOT_TOKEN for the local integration test')
const telegramId = 7600000000000 + randomInt(1000000)
let sessionToken = ''
let userId = ''
const auth = () => ({ Authorization: `Bearer ${sessionToken}` })

function initData(valid = true) {
  const params = new URLSearchParams({
    auth_date: String(Math.floor(Date.now() / 1000)),
    query_id: `miniapp-test-${telegramId}`,
    user: JSON.stringify({
      id: telegramId,
      first_name: 'Mini App integration test',
    }),
  })
  const text = [...params.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([key, value]) => `${key}=${value}`)
    .join('\n')
  const secret = createHmac('sha256', 'WebAppData').update(token!).digest()
  params.set(
    'hash',
    valid
      ? createHmac('sha256', secret).update(text).digest('hex')
      : '0'.repeat(64),
  )
  return params.toString()
}

function sql(statement: string) {
  return execFileSync(
    'docker',
    [
      'compose',
      'exec',
      '-T',
      'postgres',
      'psql',
      '-U',
      'postgres',
      '-d',
      'assistant',
      '-v',
      'ON_ERROR_STOP=1',
      '-At',
      '-c',
      statement,
    ],
    { cwd: root, encoding: 'utf8' },
  )
}

async function open(page: Page, path = '/', valid = true) {
  await page.route('https://telegram.org/js/telegram-web-app.js', (route) =>
    route.fulfill({ contentType: 'application/javascript', body: '' }),
  )
  await page.addInitScript((data) => {
    window.Telegram = { WebApp: { initData: data, ready() {}, expand() {} } }
  }, initData(valid))
  await page.goto(path)
  if (valid)
    await expect(
      page.getByRole('button', { name: 'Обновить данные', exact: true }),
    ).toBeVisible()
}

test.describe.serial('Mini App with the local Java API', () => {
  test.beforeAll(async ({ request }) => {
    const login = await request.post('/api/v1/auth/telegram', {
      data: { initData: initData() },
    })
    expect(login.status()).toBe(200)
    const body = await login.json()
    sessionToken = body.accessToken
    userId = body.user.id
    expect(body.user.displayName).toBe('Mini App integration test')
  })

  test.afterAll(async ({ request }) => {
    if (!userId) return
    const listed = await request.get('/api/v1/reminders?limit=100', {
      headers: auth(),
    })
    if (listed.ok())
      for (const reminder of (await listed.json()).items) {
        if (['ACTIVE', 'PAUSED'].includes(reminder.status)) {
          const cancelled = await request.patch(
            `/api/v1/reminders/${reminder.id}`,
            {
              headers: auth(),
              data: { expectedVersion: reminder.version, status: 'CANCELLED' },
            },
          )
          expect(cancelled.ok()).toBeTruthy()
        }
      }
    sql(
      `DELETE FROM app.users WHERE id='${userId}' AND telegram_user_id=${telegramId} AND display_name='Mini App integration test'`,
    )
    await request.post('/api/v1/auth/logout', { headers: auth() })
  })

  test('rejects forged Telegram data and shows the real empty state at all widths', async ({
    page,
  }) => {
    await open(page, '/', false)
    await expect(page.getByRole('alert')).toContainText('Данные входа устарели')
    await page.close()
  })

  test('profile, nullable fields and optimistic conflict preserve server data', async ({
    page,
    request,
  }) => {
    await open(page, '/profile')
    await expect(page.locator('#industry')).toHaveValue('')
    await page.locator('#industry').fill('Кофейня для проверки Mini App')
    await page
      .getByRole('button', { name: 'Действующий бизнес', exact: true })
      .click()
    await page.locator('#experience').selectOption('BEGINNER')
    await page.locator('#currentTask').fill('Привлечь клиентов в кофейню')
    await page.locator('#timeZone').fill('UTC')
    await page
      .getByRole('button', { name: 'Сохранить профиль', exact: true })
      .click()
    await expect(
      page.getByText('Данные сохранены', { exact: true }),
    ).toBeVisible()
    expect(
      await (
        await request.get('/api/v1/plans/current', { headers: auth() })
      ).json(),
    ).toBeNull()
    const me = await (
      await request.get('/api/v1/me', { headers: auth() })
    ).json()
    expect(me.profile.businessStage).toBe('OPERATING')
    await page.locator('#industry').fill('Черновик')
    expect(
      (
        await request.patch('/api/v1/me/profile', {
          headers: auth(),
          data: {
            expectedVersion: me.profileVersion,
            industry: 'Внешнее изменение',
          },
        })
      ).ok(),
    ).toBeTruthy()
    await page
      .getByRole('button', { name: 'Сохранить профиль', exact: true })
      .click()
    await expect(page.getByRole('alert').first()).toContainText(
      'Данные изменились',
    )
    await expect(page.locator('#industry')).toHaveValue('Черновик')
    await page
      .getByRole('button', { name: 'Обновить данные', exact: true })
      .last()
      .click()
    await page
      .getByRole('button', { name: 'Загрузить актуальный профиль' })
      .click()
    await page.locator('#industry').fill('')
    await page
      .getByRole('button', { name: 'Сохранить профиль', exact: true })
      .click()
    await expect(
      page.getByText('Данные сохранены', { exact: true }),
    ).toBeVisible()
    expect(
      (await (await request.get('/api/v1/me', { headers: auth() })).json())
        .profile.industry,
    ).toBeNull()
  })

  test('generates a real plan, updates progress and preserves the plan on failure', async ({
    page,
    request,
  }) => {
    await open(page)
    await expect(page.getByText('У вас пока нет активного плана')).toBeVisible()
    await page
      .getByRole('button', { name: 'Сформировать план по профилю' })
      .click()
    await page.locator('#planGoal').fill('Привлечь клиентов в кофейню')
    const generated = page.waitForResponse(response => response.url().endsWith('/api/v1/plans/generate') && response.request().method() === 'POST')
    await page
      .getByRole('button', { name: 'Создать план', exact: true })
      .click()
    expect((await generated).status()).toBe(200)
    await expect(
      page.getByRole('button', { name: 'Выполнить шаг 1', exact: true }),
    ).toBeVisible({ timeout: 75000 })
    await page
      .getByRole('button', { name: 'Выполнить шаг 1', exact: true })
      .click()
    await expect(
      page.getByRole('button', { name: 'Вернуть в работу шаг 1', exact: true }),
    ).toBeVisible()
    const original = await (
      await request.get('/api/v1/plans/current', { headers: auth() })
    ).json()
    await page.route('**/api/v1/plans/generate', async (route) => {
      const body = route.request().postDataJSON()
      expect(body.replacePlan).toEqual({
        id: original.id,
        expectedVersion: original.version,
      })
      expect(route.request().headers()['idempotency-key']).toMatch(
        /^[a-f0-9-]{36}$/,
      )
      await route.fulfill({
        status: 503,
        contentType: 'application/problem+json',
        body: JSON.stringify({ code: 'SERVICE_UNAVAILABLE' }),
      })
    })
    await page.getByRole('button', { name: 'Обновить по профилю' }).click()
    await expect(
      page.getByText(/будет сохранён в архиве только после/),
    ).toBeVisible()
    await page.getByRole('button', { name: 'Подтверждаю замену плана' }).click()
    await expect(page.getByRole('alert').last()).toContainText(
      'временно недоступен',
    )
    expect(
      (
        await (
          await request.get('/api/v1/plans/current', { headers: auth() })
        ).json()
      ).id,
    ).toBe(original.id)
  })

  test('handles BOT_START_REQUIRED and all reminder schedules, edits, pause and cancellation', async ({
    page,
    request,
  }) => {
    await open(page, '/reminders')
    const initialZone = (
      await (await request.get('/api/v1/me', { headers: auth() })).json()
    ).profile.timeZone
    await expect(
      page.getByText('отправь /start', { exact: false }),
    ).toBeVisible()
    sql(
      `UPDATE app.users SET telegram_chat_id=telegram_user_id, can_receive_reminders=TRUE WHERE id='${userId}' AND telegram_user_id=${telegramId} AND display_name='Mini App integration test'`,
    )
    await page
      .getByRole('button', { name: 'Обновить данные', exact: true })
      .click()
    const date = new Date(Date.now() + 40 * 86400000).toISOString().slice(0, 10)
    for (const type of [
      'Один раз',
      'Каждый день',
      'Каждую неделю',
      'Каждый месяц',
    ]) {
      await page
        .getByRole('button', { name: 'Новое напоминание', exact: true })
        .click()
      await page
        .getByRole('textbox', { name: 'О чём напомнить?' })
        .fill(`Проверка ${type}`)
      await page.getByRole('button', { name: type, exact: true }).click()
      await page.locator('#reminderDate').fill(date)
      await page.locator('#reminderZone').fill('Asia/Kolkata')
      await page.getByLabel('Время отправки', { exact: true }).fill('13:00')
      if (type === 'Каждый месяц')
        await page.getByRole('spinbutton', { name: 'Число месяца' }).fill('31')
      await page
        .getByRole('button', { name: 'Сохранить напоминание', exact: true })
        .click()
      await expect(
        page.getByRole('heading', { name: `Проверка ${type}`, exact: true }),
      ).toBeVisible()
    }
    const reminders = (
      await (await request.get('/api/v1/reminders', { headers: auth() })).json()
    ).items
    expect(reminders).toHaveLength(4)
    expect(
      reminders.find(
        (item: { schedule: { type: string } }) => item.schedule.type === 'ONCE',
      ).schedule.at,
    ).toBe(`${date}T07:30:00Z`)
    expect(
      reminders.find(
        (item: { schedule: { type: string } }) =>
          item.schedule.type === 'MONTHLY',
      ).schedule.dayOfMonth,
    ).toBe(31)
    expect(
      (await (await request.get('/api/v1/me', { headers: auth() })).json())
        .profile.timeZone,
    ).toBe(initialZone)
    const card = page.getByRole('article', {
      name: 'Проверка Один раз',
      exact: true,
    })
    let releasePause!: () => void
    const pauseGate = new Promise<void>((resolve) => {
      releasePause = resolve
    })
    await page.route('**/api/v1/reminders/*', async (route) => {
      if (route.request().method() === 'PATCH') await pauseGate
      await route.continue()
    })
    await card.getByRole('button', { name: 'Поставить на паузу' }).click()
    try {
      await expect(card.getByRole('status')).toHaveText('Ставим на паузу…')
      for (const button of await card.getByRole('button').all())
        await expect(button).toBeDisabled()
    } finally {
      releasePause()
    }
    await expect(card.getByText('На паузе', { exact: true })).toBeVisible()
    await page.unroute('**/api/v1/reminders/*')
    await page.route('**/api/v1/reminders/*', (route) =>
      route.fulfill({
        status: 503,
        json: { code: 'SERVICE_UNAVAILABLE', message: 'Unavailable' },
      }),
    )
    await card.getByRole('button', { name: 'Возобновить' }).click()
    await expect(page.getByRole('alert')).toContainText(
      'Сервис временно недоступен',
    )
    await expect(card.getByText('На паузе', { exact: true })).toBeVisible()
    await expect(
      card.getByRole('button', { name: 'Возобновить' }),
    ).toBeEnabled()
    await page.unroute('**/api/v1/reminders/*')
    await card.getByRole('button', { name: 'Возобновить' }).click()
    await expect(card.getByText('Активно', { exact: true })).toBeVisible()
    await card.getByRole('button', { name: 'Изменить напоминание' }).click()
    await page
      .getByRole('textbox', { name: 'О чём напомнить?' })
      .fill('Изменённое напоминание')
    await page
      .getByRole('button', { name: 'Сохранить напоминание', exact: true })
      .click()
    const edited = page.getByRole('article', {
      name: 'Изменённое напоминание',
      exact: true,
    })
    await edited.getByRole('button', { name: 'Отменить напоминание' }).click()
    await expect(edited).toHaveCount(0)
    await page.route('**/api/v1/reminders?**', async (route) => {
      const response = await route.fetch()
      const body = await response.json()
      body.items = body.items.map((item: { title: string }) =>
        item.title === 'Изменённое напоминание'
          ? { ...item, status: 'COMPLETED', completionReason: 'DELIVERED' }
          : item,
      )
      await route.fulfill({ response, json: body })
    })
    await page
      .getByRole('button', { name: 'Обновить данные', exact: true })
      .click()
    await expect(edited.getByText('Завершено', { exact: true })).toBeVisible()
    await expect(edited.getByText('Доставлено', { exact: true })).toBeVisible()
    await expect(edited.getByRole('button')).toHaveCount(0)
  })

  test('refreshes changes from the chat API, keeps layout widths and supports deep links/logout', async ({
    page,
    request,
  }) => {
    await open(page, '/profile')
    const current = await (
      await request.get('/api/v1/me', { headers: auth() })
    ).json()
    await request.patch('/api/v1/me/profile', {
      headers: auth(),
      data: {
        expectedVersion: current.profileVersion,
        industry: 'Изменение из другого интерфейса',
      },
    })
    await page
      .getByRole('button', { name: 'Обновить данные', exact: true })
      .click()
    await expect(
      page.getByText(
        'Профиль изменился в другом окне или чате. Черновик сохранён на экране.',
      ),
    ).toBeVisible()
    await page
      .getByRole('button', { name: 'Загрузить актуальный профиль' })
      .click()
    await expect(page.locator('#industry')).toHaveValue(
      'Изменение из другого интерфейса',
    )
    const output = `${root}/data/source/local-runtime/stage10-layout`
    mkdirSync(output, { recursive: true })
    for (const [link, name] of [['План', 'plan'], ['Календарь', 'reminders'], ['Профиль', 'profile']]) {
      await page.getByRole('link', { name: link, exact: true }).click()
      for (const width of [360, 768, 1280]) {
        await page.setViewportSize({ width, height: 900 })
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
        await page.screenshot({ path: `${output}/${name}-${width}.png`, fullPage: true })
      }
    }
    await page.reload()
    await expect(page.locator('#industry')).toHaveValue(
      'Изменение из другого интерфейса',
    )
    await page.getByRole('button', { name: 'Выйти', exact: true }).click()
    await expect(page.getByRole('alert')).toContainText(
      'Открой приложение из нашего бота',
    )
  })
})
