import { useState } from 'react'
import { createFileRoute } from '@tanstack/react-router'
import { UserCircle, Briefcase, Calculator, CheckCircle2, Save } from 'lucide-react'
import { useDevStore } from '@/shared/api/devStore'
import type { TaxRegime } from '@/shared/api/client'
import { cn } from '@/shared/lib/utils'

export const Route = createFileRoute('/profile')({
  component: ProfileScreen,
})

// Тот самый строгий список из твоего обновлённого API контракта
const TAX_REGIMES: { value: TaxRegime; label: string; desc: string }[] = [
  { value: 'NPD', label: 'НПД (Самозанятость)', desc: 'Налог 4-6% без отчётов' },
  { value: 'USN_6', label: 'УСН 6%', desc: 'С доходов' },
  { value: 'USN_15', label: 'УСН 15%', desc: 'Доходы минус расходы' },
  { value: 'PATENT', label: 'Патент (ПСН)', desc: 'Фиксированный платёж' },
  { value: 'OSNO', label: 'ОСНО', desc: 'Общий режим (с НДС)' },
  { value: 'AUSN', label: 'АУСН', desc: 'Автоматизированная УСН' },
]

const BUSINESS_STAGES = [
  { value: 'IDEA', label: 'Только идея' },
  { value: 'LAUNCH', label: 'Запуск' },
  { value: 'GROWTH', label: 'Активный рост' },
  { value: 'SCALING', label: 'Масштабирование' },
]

function ProfileScreen() {
  const { user, updateProfile } = useDevStore()
  
  // Локальное состояние для формы, берем начальные данные из стора
  const [industry, setIndustry] = useState(user.profile.industry || '')
  const [taxRegime, setTaxRegime] = useState<TaxRegime | undefined>(user.profile.taxRegime ?? undefined)
  const [stage, setStage] = useState(user.profile.businessStage)
  
  const [isSaved, setIsSaved] = useState(false)

  const handleSave = () => {
    updateProfile({
      industry,
      taxRegime,
      businessStage: stage,
    })
    
    // Показываем красивую анимацию сохранения
    setIsSaved(true)
    setTimeout(() => setIsSaved(false), 2000)
  }

  return (
    <div className="space-y-5 animate-in fade-in slide-in-from-bottom-4 duration-500">
      
      {/* Шапка профиля */}
      <div className="bg-white rounded-3xl p-5 shadow-xs border border-gray-100 flex items-center gap-4">
        <div className="w-14 h-14 rounded-full bg-gray-100 flex items-center justify-center shrink-0">
          <UserCircle className="w-8 h-8 text-gray-400" />
        </div>
        <div>
          <h2 className="text-lg font-bold text-alfa-dark">{user.displayName}</h2>
          <p className="text-xs text-gray-500 mt-0.5">Предприниматель</p>
        </div>
      </div>

      {/* Форма настроек бизнеса */}
      <div className="bg-white rounded-3xl p-5 shadow-xs border border-gray-100 space-y-6">
        
        {/* Ниша */}
        <div className="space-y-2.5">
          <label className="flex items-center gap-2 text-sm font-bold text-alfa-dark">
            <Briefcase className="w-4 h-4 text-alfa-red" />
            Сфера бизнеса
          </label>
          <input
            type="text"
            value={industry}
            onChange={(e) => setIndustry(e.target.value)}
            placeholder="Например: Кофейня, Маркетплейсы, IT..."
            className="w-full bg-alfa-gray/50 border border-gray-200 rounded-xl px-4 py-3 text-sm focus:outline-none focus:border-alfa-red focus:bg-white transition-colors"
          />
        </div>

        {/* Стадия бизнеса */}
        <div className="space-y-2.5">
          <label className="text-sm font-bold text-alfa-dark block">Стадия развития</label>
          <div className="flex flex-wrap gap-2">
            {BUSINESS_STAGES.map((s) => (
              <button
                key={s.value}
                onClick={() => setStage(s.value as any)}
                className={cn(
                  "px-3 py-2 rounded-xl text-xs font-semibold border transition-all cursor-pointer",
                  stage === s.value
                    ? "bg-alfa-red text-white border-alfa-red"
                    : "bg-white text-gray-600 border-gray-200 hover:bg-gray-50"
                )}
              >
                {s.label}
              </button>
            ))}
          </div>
        </div>

        {/* Налоговый режим (твоя правка!) */}
        <div className="space-y-2.5">
          <label className="flex items-center gap-2 text-sm font-bold text-alfa-dark">
            <Calculator className="w-4 h-4 text-alfa-red" />
            Налоговый режим
          </label>
          <div className="grid grid-cols-2 gap-2">
            {TAX_REGIMES.map((tax) => (
              <button
                key={tax.value}
                onClick={() => setTaxRegime(tax.value)}
                className={cn(
                  "text-left p-3 rounded-xl border transition-all cursor-pointer flex flex-col gap-1",
                  taxRegime === tax.value
                    ? "bg-red-50 border-alfa-red shadow-sm"
                    : "bg-white border-gray-200 hover:bg-gray-50"
                )}
              >
                <div className="flex items-center justify-between">
                  <span className={cn(
                    "text-xs font-bold",
                    taxRegime === tax.value ? "text-alfa-red" : "text-alfa-dark"
                  )}>
                    {tax.label}
                  </span>
                  {taxRegime === tax.value && <CheckCircle2 className="w-4 h-4 text-alfa-red" />}
                </div>
                <span className="text-[10px] text-gray-500 leading-tight">{tax.desc}</span>
              </button>
            ))}
          </div>
        </div>

        {/* Кнопка сохранения */}
        <div className="pt-2">
          <button
            onClick={handleSave}
            className={cn(
              "w-full py-3.5 px-4 rounded-xl font-semibold text-sm flex items-center justify-center gap-2 transition-all cursor-pointer",
              isSaved 
                ? "bg-green-500 text-white"
                : "bg-alfa-dark hover:bg-gray-800 text-white"
            )}
          >
            {isSaved ? (
              <>
                <CheckCircle2 className="w-5 h-5" />
                <span>Данные сохранены</span>
              </>
            ) : (
              <>
                <Save className="w-5 h-5" />
                <span>Сохранить профиль</span>
              </>
            )}
          </button>
        </div>

      </div>
    </div>
  )
}