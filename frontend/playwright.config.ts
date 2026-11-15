import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests/e2e',
  timeout: 90000,
  expect: { timeout: 10000 },
  workers: 1,
  retries: 0,
  reporter: 'list',
  use: {
    baseURL: process.env.MINI_APP_TEST_URL || 'http://127.0.0.1:8081',
    channel: process.env.PLAYWRIGHT_CHANNEL,
    trace: 'off',
    screenshot: 'off',
  },
})
