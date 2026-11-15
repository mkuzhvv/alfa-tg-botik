import { useEffect } from 'react'
import { createRootRoute, Link, Outlet } from '@tanstack/react-router'
import { BookOpen, Bell, User } from 'lucide-react'
import { useAppStore } from '@/shared/api/appStore'
import { useAuthStore } from '@/shared/api/authStore'
import { ApiNotice } from '@/shared/ui/ApiNotice'

export const Route = createRootRoute({
  component: RootLayout,
})

function RootLayout() {
  const { status, error: authError, initialize, logout } = useAuthStore()
  const { error, loading, busy, fetchInitialData } = useAppStore()

  useEffect(() => {
    void initialize()
  }, [initialize])

  useEffect(() => {
    const refresh = () => {
      if (
        document.visibilityState === 'visible' &&
        useAuthStore.getState().status === 'ready'
      ) {
        void fetchInitialData().catch(() => undefined)
      }
    }
    document.addEventListener('visibilitychange', refresh)
    window.addEventListener('focus', refresh)
    return () => {
      document.removeEventListener('visibilitychange', refresh)
      window.removeEventListener('focus', refresh)
    }
  }, [fetchInitialData])

  return (
    <div className="min-h-screen bg-alfa-gray text-alfa-dark flex flex-col max-w-md mx-auto shadow-sm">
      <header className="bg-white px-4 py-3 border-b border-gray-200 sticky top-0 z-20 flex items-center justify-between">
        <div className="flex items-center gap-2.5">
          <div className="w-8 h-8 rounded-lg bg-alfa-red flex items-center justify-center text-white font-bold text-lg">
            А
          </div>
          <div>
            <h1 className="text-sm font-bold leading-tight">Альфа-Курс</h1>
            <p className="text-[11px] text-gray-500">
              ИИ-навигатор предпринимателя
            </p>
          </div>
        </div>

        <span className="text-[10px] font-semibold px-2 py-0.5 rounded-full bg-gray-100 text-gray-500">
          TG Mini App
        </span>
      </header>

      <main className="flex-1 p-4 pb-24">
        {status === 'loading' && (
          <div
            role="status"
            className="bg-white rounded-3xl p-6 text-sm text-gray-500"
          >
            Загружаем данные…
          </div>
        )}
        {status === 'error' && (
          <ApiNotice
            error={authError}
            onRetry={() => {
              void initialize()
            }}
          />
        )}
        {status === 'ready' && (
          <>
            <ApiNotice
              error={error}
              onRetry={() => {
                void fetchInitialData().catch(() => undefined)
              }}
            />
            <div className="flex justify-end gap-3 text-[11px] text-gray-500 mb-2">
              <button
                disabled={loading || !!busy}
                onClick={() => {
                  void fetchInitialData().catch(() => undefined)
                }}
                className="cursor-pointer"
              >
                {loading ? 'Обновляем…' : 'Обновить данные'}
              </button>
              <button
                disabled={loading || !!busy}
                onClick={() => {
                  void logout().catch(() => undefined)
                }}
                className="cursor-pointer"
              >
                Выйти
              </button>
            </div>
            <Outlet />
          </>
        )}
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
