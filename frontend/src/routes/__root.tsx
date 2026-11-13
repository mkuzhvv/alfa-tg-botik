import { useEffect } from 'react'
import { createRootRoute, Link, Outlet } from '@tanstack/react-router'
import { BookOpen, Bell, User } from 'lucide-react'
import {
  initTelegramApp,
  getTelegramUser,
  getTelegramInitData,
  isRunningInTelegram,
} from '@/shared/lib/telegram'
import { useDevStore, useAuthStore } from '@/shared/api/devStore'
import { apiClient } from '@/shared/api/client'

export const Route = createRootRoute({
  component: RootLayout,
})

function RootLayout() {
  const setDisplayName = useDevStore((s) => s.setDisplayName)
  const fetchInitialData = useDevStore((s) => s.fetchInitialData)
  const setAuth = useAuthStore((s) => s.setAuth)
  const inTelegram = isRunningInTelegram()

  useEffect(() => {
    initTelegramApp()

    const tgUser = getTelegramUser()
    if (tgUser?.first_name) {
      setDisplayName(tgUser.first_name)
    }

    const initData = getTelegramInitData()
    if (initData) {
      apiClient
        .POST('/auth/telegram', {
          body: { initData },
        })
        .then(({ data }) => {
          if (data) {
            setAuth(data.accessToken, data.user)
          }
        })
        .catch(() => {
          console.info('Бэкенд недоступен, работаем в локальном режиме')
        })
    }
    fetchInitialData()
  }, [setDisplayName, setAuth])

  return (
    <div className="min-h-screen bg-alfa-gray text-alfa-dark flex flex-col max-w-md mx-auto shadow-sm">
      <header className="bg-white px-4 py-3 border-b border-gray-200 sticky top-0 z-20 flex items-center justify-between">
        <div className="flex items-center gap-2.5">
          <div className="w-8 h-8 rounded-lg bg-alfa-red flex items-center justify-center text-white font-bold text-lg">
            А
          </div>
          <div>
            <h1 className="text-sm font-bold leading-tight">Альфа-Курс</h1>
            <p className="text-[11px] text-gray-500">ИИ-навигатор предпринимателя</p>
          </div>
        </div>

        <span className="text-[10px] font-semibold px-2 py-0.5 rounded-full bg-gray-100 text-gray-500">
          {inTelegram ? 'TG Mini App' : 'Dev Mode'}
        </span>
      </header>

      <main className="flex-1 p-4 pb-24">
        <Outlet />
      </main>

      <nav className="fixed bottom-0 left-0 right-0 max-w-md mx-auto bg-white border-t border-gray-200 px-6 py-2 flex justify-around items-center z-20">
        <Link
          to="/"
          className="flex flex-col items-center gap-1 text-xs text-gray-400 [&.active]:text-alfa-red [&.active]:font-semibold"
        >
          <BookOpen className="w-5 h-5" />
          <span>План</span>
        </Link>

        <Link
          to="/reminders"
          className="flex flex-col items-center gap-1 text-xs text-gray-400 [&.active]:text-alfa-red [&.active]:font-semibold"
        >
          <Bell className="w-5 h-5" />
          <span>Календарь</span>
        </Link>

        <Link
          to="/profile"
          className="flex flex-col items-center gap-1 text-xs text-gray-400 [&.active]:text-alfa-red [&.active]:font-semibold"
        >
          <User className="w-5 h-5" />
          <span>Профиль</span>
        </Link>
      </nav>
    </div>
  )
}