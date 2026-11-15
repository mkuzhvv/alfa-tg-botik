interface TelegramUser {
  id: number
  first_name: string
  last_name?: string
  username?: string
}

interface TelegramWebApp {
  initData: string
  initDataUnsafe?: {
    user?: TelegramUser
  }
  ready: () => void
  expand: () => void
  setHeaderColor?: (color: string) => void
  setBackgroundColor?: (color: string) => void
  HapticFeedback?: {
    impactOccurred: (style: 'light' | 'medium' | 'heavy') => void
    notificationOccurred: (type: 'error' | 'success' | 'warning') => void
  }
}

declare global {
  interface Window {
    Telegram?: {
      WebApp?: TelegramWebApp
    }
  }
}

export function getTelegramWebApp(): TelegramWebApp | null {
  if (typeof window !== 'undefined' && window.Telegram?.WebApp) {
    return window.Telegram.WebApp
  }
  return null
}

export function isRunningInTelegram(): boolean {
  const webapp = getTelegramWebApp()
  return Boolean(webapp && webapp.initData && webapp.initData.length > 0)
}

export function initTelegramApp() {
  const webapp = getTelegramWebApp()
  if (!webapp) return

  webapp.ready()
  webapp.expand()

  webapp.setHeaderColor?.('#FFFFFF')
  webapp.setBackgroundColor?.('#F3F4F6')
}

export function getTelegramInitData(): string | null {
  const webapp = getTelegramWebApp()
  return webapp?.initData || null
}

export function getTelegramUser(): TelegramUser | null {
  const webapp = getTelegramWebApp()
  return webapp?.initDataUnsafe?.user || null
}

export const haptic = {
  light: () => getTelegramWebApp()?.HapticFeedback?.impactOccurred('light'),
  success: () => getTelegramWebApp()?.HapticFeedback?.notificationOccurred('success'),
}

export const botUrl = `https://t.me/${import.meta.env.VITE_BOT_USERNAME || 'alfa_business_helper_bot'}`
