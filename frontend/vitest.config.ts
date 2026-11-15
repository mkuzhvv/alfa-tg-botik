import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    include: ['tests/**/*.test.ts'],
    env: { VITE_API_BASE_URL: 'http://localhost/api/v1' },
  },
})
