import type { ApiError } from '../api/client'
import { botUrl } from '../lib/telegram'

const messages: Record<string, string> = {
  OPEN_IN_TELEGRAM: 'Открой приложение из нашего бота в Telegram.',
  AUTH_REQUIRED: 'Нужно войти заново. Закрой приложение и открой его из бота.',
  TELEGRAM_AUTH_INVALID:
    'Данные входа устарели. Закрой приложение и открой его из бота заново.',
  TELEGRAM_AUTH_UNAVAILABLE: 'Вход временно недоступен. Попробуй позднее.',
  VERSION_CONFLICT:
    'Данные изменились в другом окне или чате. Обнови их и повтори изменение.',
  PLAN_REPLACEMENT_REQUIRED:
    'Уже есть активный план. Обнови данные и подтверди его замену.',
  PLAN_ARCHIVED: 'Этот план уже архивирован. Обнови данные.',
  REMINDER_TERMINAL: 'Напоминание уже завершено или отменено. Обнови данные.',
  NOT_FOUND: 'Запись больше недоступна. Обнови данные.',
  BOT_START_REQUIRED: 'Открой бота и отправь /start, затем обнови данные.',
  VALIDATION_FAILED: 'Проверь указанные данные.',
  MALFORMED_REQUEST: 'Запрос не принят. Проверь поля и повтори действие.',
  PLAN_MATERIALS_NOT_FOUND:
    'Материалов для этого плана недостаточно. Уточни цель.',
  IDEMPOTENCY_CONFLICT: 'Параметры запроса изменились. Повтори действие.',
  REQUEST_IN_PROGRESS: 'Запрос ещё выполняется. Подожди и повтори его позднее.',
  RATE_LIMITED: 'Слишком много запросов. Попробуй позднее.',
  SERVICE_UNAVAILABLE: 'Сервис временно недоступен. Повтори действие позднее.',
  NETWORK_ERROR:
    'Связь с сервером прервалась. Повтори то же действие, когда связь восстановится.',
}

export function ApiNotice({
  error,
  onRetry,
}: {
  error: ApiError | null
  onRetry?: () => void
}) {
  if (!error) return null
  return (
    <div
      role="alert"
      className="rounded-2xl bg-red-50 border border-red-100 p-3.5 text-xs text-alfa-dark space-y-2"
    >
      <p>
        {messages[error.code] ||
          'Не удалось выполнить действие. Попробуй позднее.'}
      </p>
      {error.fields.map((item) => (
        <p key={item.field}>{item.message}</p>
      ))}
      {error.retryAfter && <p>Повторить через {error.retryAfter} сек.</p>}
      {[
        'OPEN_IN_TELEGRAM',
        'TELEGRAM_AUTH_INVALID',
        'AUTH_REQUIRED',
        'BOT_START_REQUIRED',
      ].includes(error.code) && (
        <a
          href={botUrl}
          target="_blank"
          rel="noreferrer"
          className="font-semibold text-alfa-red"
        >
          Открыть бота
        </a>
      )}
      {onRetry && (
        <button
          onClick={onRetry}
          className="font-semibold text-alfa-red block cursor-pointer"
        >
          Обновить данные
        </button>
      )}
    </div>
  )
}
