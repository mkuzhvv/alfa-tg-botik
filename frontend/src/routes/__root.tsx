import { createRootRoute, Link, Outlet } from '@tanstack/react-router'
import { BookOpen, Bell, UserCircle } from 'lucide-react'

export const Route = createRootRoute({
  component: RootLayout,
})

function RootLayout() {
  return (
    <div className="min-h-screen bg-[#F3F4F6] text-[#121214] flex flex-col max-w-md mx-auto relative pb-20 shadow-sm">
      <header className="sticky top-0 z-20 bg-white/90 backdrop-blur-md border-b border-gray-200 px-4 py-3 flex items-center justify-between">
        <div className="flex items-center gap-2.5">
          <div className="w-8 h-8 rounded-xl bg-[#EF3124] flex items-center justify-center text-white font-bold text-lg shadow-xs">
            А
          </div>
          <div>
            <h1 className="text-sm font-bold leading-tight">Альфа-Курс</h1>
            <p className="text-[11px] text-gray-500 leading-tight">ИИ-навигатор предпринимателя</p>
          </div>
        </div>
      </header>

      <main className="flex-1 p-4">
        <Outlet />
      </main>

      <nav className="fixed bottom-0 left-0 right-0 z-20 max-w-md mx-auto bg-white border-t border-gray-200 px-6 py-2 flex justify-around items-center">
        <Link
          to="/"
          activeProps={{ className: 'text-[#EF3124] font-semibold' }}
          inactiveProps={{ className: 'text-gray-400' }}
          className="flex flex-col items-center gap-1 text-xs transition-colors"
        >
          <BookOpen className="w-5 h-5" />
          <span>План</span>
        </Link>

        <Link
          to="/reminders"
          activeProps={{ className: 'text-[#EF3124] font-semibold' }}
          inactiveProps={{ className: 'text-gray-400' }}
          className="flex flex-col items-center gap-1 text-xs transition-colors"
        >
          <Bell className="w-5 h-5" />
          <span>Календарь</span>
        </Link>

        <Link
          to="/profile"
          activeProps={{ className: 'text-[#EF3124] font-semibold' }}
          inactiveProps={{ className: 'text-gray-400' }}
          className="flex flex-col items-center gap-1 text-xs transition-colors"
        >
          <UserCircle className="w-5 h-5" />
          <span>Профиль</span>
        </Link>
      </nav>
    </div>
  )
}