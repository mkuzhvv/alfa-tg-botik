# Контракт бэкенда и фронтенда

Версия 0.1.0. Интерфейс состоит из трёх экранов ТЗ. Все пути ниже имеют префикс `/api/v1`.

Машиночитаемый контракт: [backend.openapi.yaml](backend.openapi.yaml). Этот документ объясняет поведение интерфейса и бизнес-правила.

## Авторизация

`POST /auth/telegram` принимает `initData: string` — исходную строку `Telegram.WebApp.initData`. Бэкенд проверяет подпись и `auth_date` по токену бота: возраст до 10 минут, допустимое опережение часов 30 секунд. Данные `initDataUnsafe` не доказывают личность.

Ответ: `accessToken: string`, `tokenType: "Bearer"`, `expiresAt: UTC`, `user: User`. Токен — непрозрачная случайная строка, срок сессии 8 часов. Сессии разных устройств независимы. После перезапуска Java нужен повторный вход.

Остальные запросы передают `Authorization: Bearer <accessToken>`. Токен хранится в памяти фронтенда; после перезагрузки выполняется вход. При `401` повторить вход; если `initData` устарело — предложить открыть Mini App заново. Публичная версия использует HTTPS. Токен и `initData` не передаются в URL и не записываются в логи.

## Методы

| Метод | Путь | Тело запроса | Ответ |
|---|---|---|---|
| POST | `/auth/telegram` | `initData` | 200, сессия и User |
| POST | `/auth/logout` | Нет | 204, текущий токен отозван |
| GET | `/me` | Нет | 200, User |
| PATCH | `/me/profile` | `expectedVersion` и изменяемые поля Profile | 200, обновлённый User |
| GET | `/plans/current` | Нет | 200, Plan либо `null` |
| POST | `/plans/generate` | Необязательные `goal` и `replacePlan` | 200, новый активный Plan. Требуется `Idempotency-Key` |
| GET | `/plans/{planId}` | Нет | 200, собственный Plan |
| PATCH | `/plans/{planId}/steps/{stepId}` | `expectedVersion`, `status: TODO или DONE` | 200, весь обновлённый Plan |
| POST | `/plans/{planId}/archive` | `expectedVersion` | 200, архивированный Plan |
| GET | `/materials/{materialId}` | Нет | 200, MaterialCard |
| GET | `/reminders` | Нет | 200, страница Reminder |
| GET | `/reminders/{reminderId}` | Нет | 200, Reminder |
| POST | `/reminders` | `title`, `schedule`, `materialId`, `planStepId` | 201, Reminder; нужен Idempotency-Key |
| PATCH | `/reminders/{reminderId}` | `expectedVersion` и изменяемые `title`, `schedule`, `status` | 200, Reminder |

Для PATCH напоминания передаётся хотя бы одно изменение, а для профиля — хотя бы одно поле помимо версии.

Список напоминаний принимает `status` необязательно, `offset` от 0 и `limit` от 1 до 100, по умолчанию 20. Ответ: `items`, `total`, `offset`, `limit`. Без `status` возвращаются все состояния. Порядок: `createdAt DESC, id DESC`.

## Генерация плана в Mini App

1. Пользователь нажимает «Создать план» либо подтверждает «Заменить текущий план». При замене фронтенд передаёт `replacePlan` с полями `id` и `expectedVersion` из показанного пользователю активного плана. Сохранение профиля само по себе генерацию не запускает.
2. Сервер берёт `goal` из запроса. Если поле отсутствует или равно `null`, используется `profile.currentTask`. Длина выбранной цели — до 250 символов. После удаления крайних пробелов текст должен быть непустым. Пустая или слишком длинная цель даёт `422 VALIDATION_FAILED` с ошибкой поля `goal`.
3. При активном плане без `replacePlan` сервер возвращает `409 PLAN_REPLACEMENT_REQUIRED`. Если указанный план или его версия отличаются от текущих, сервер возвращает `409 VERSION_CONFLICT`. Фронтенд перечитывает текущий план перед новым подтверждением.
4. Генерация выполняется вне транзакции. После проверки нового плана сервер снова проверяет текущий план и его версию. Архивирование прежнего, сохранение нового и запись успешного результата для повтора выполняются одной транзакцией. При ошибке генерации текущий план остаётся активным.

Если модели нужно уточнение, сервер возвращает `422 VALIDATION_FAILED` с вопросом в `errors[].message` для поля `goal`. Если материалов для плана недостаточно, возвращается `422 PLAN_MATERIALS_NOT_FOUND`. Тайм-аут, недоступность модели или некорректный результат после попытки исправить формат дают `503 SERVICE_UNAVAILABLE`.

При сетевом повторе с прежним `Idempotency-Key` и тем же телом возвращается сохранённый результат успешной генерации. Для получения актуального состояния используется `/plans/current`. Правила ключа описаны в [общем контракте](README.md).

## Объекты ответа

Все перечисленные поля ответа присутствуют. Nullable-поля могут содержать `null`.

| Объект | Поля |
|---|---|
| User | `id: UUID`, `displayName: string или null`, `canReceiveReminders: boolean`, `profileVersion: integer`, `profile: Profile` |
| Profile | `businessStage`, `industry`, `experience`, `currentTask`, `taxRegime`, `timeZone`; все nullable |
| MaterialCard | `id: UUID`, `title: string`, `kind: MaterialKind`, `description: string или null`, `recommendationReason: string или null`, `isPaid: boolean`, `duration: string или null`, `url: HTTPS` |
| Plan | `id: UUID`, `goal: string`, `status: ACTIVE или ARCHIVED`, `version: integer`, `steps: PlanStep[]`, `createdAt: UTC`, `updatedAt: UTC` |
| PlanStep | `id: UUID`, `position: integer от 1`, `title: string`, `description: string`, `status: TODO или DONE`, `materials: MaterialCard[]` |
| Reminder | `id: UUID`, `title: string`, `status`, `schedule`, `nextFireAt: UTC или null`, `lastSentAt: UTC или null`, `completionReason: string или null`, `material: MaterialCard или null`, `planStepId: UUID или null`, `version: integer`, `createdAt: UTC`, `updatedAt: UTC` |

`businessStage`: `IDEA` — идея, `LAUNCH` — запуск, `OPERATING` — действующий бизнес, `GROWTH` — развитие. `experience`: `BEGINNER` или `EXPERIENCED`. `taxRegime`: `NPD` — НПД (самозанятость), `USN_6` — УСН 6%, `USN_15` — УСН 15%, `PATENT` — патент, `OSNO` — ОСНО, `AUSN` — АУСН. `industry` — строка до 100 символов, `currentTask` — до 500. `timeZone` — подтверждённая IANA-зона.

`MaterialKind`: `ARTICLE`, `COURSE`, `LESSON`, `DOCUMENT`, `WEBINAR`, `PODCAST`, `GLOSSARY`, `CALCULATOR`. `duration` — подпись из каталога, а не гарантированное число минут. `isPaid` задаёт надпись «Платно»/«Бесплатно». Цену фронтенд не вычисляет.

Для карточки в плане `recommendationReason` берётся из `recommendations[].reason` по `materialId` и хранится вместе с планом. Если объяснения для материала нет, возвращается `null`. В карточке каталога и карточке в напоминании значение равно `null`.

Заголовок напоминания — 1–160 символов. Цель плана — до 250, заголовок шага — до 160, описание шага — до 600. План содержит три–пять шагов, шаг — один–два материала. Архивный план доступен только для чтения.

## Расписания

| `schedule.type` | Остальные обязательные поля |
|---|---|
| `ONCE` | `at: UTC`, `timeZone: IANA` |
| `DAILY` | `localTime: HH:mm`, `startDate: YYYY-MM-DD`, `timeZone: IANA` |
| `WEEKLY` | Поля DAILY и `dayOfWeek` |
| `MONTHLY` | Поля DAILY и `dayOfMonth` |

`dayOfWeek`: `MONDAY`, `TUESDAY`, `WEDNESDAY`, `THURSDAY`, `FRIDAY`, `SATURDAY`, `SUNDAY`. `ONCE.at` находится в будущем. Для повторения `startDate` — нижняя граница; сервер рассчитывает ближайшую будущую отправку. Фронтенд использует серверный `nextFireAt`. Изменение зоны профиля не переносит существующие напоминания.

`dayOfMonth` — целое число от 1 до 31. В коротком месяце отправка приходится на последний день. Сервер хранит исходное число и использует его при расчёте каждого следующего месяца.

Состояния: `ACTIVE`, `PAUSED`, `COMPLETED`, `CANCELLED`. Создание даёт `ACTIVE`; PATCH допускает `ACTIVE`, `PAUSED`, `CANCELLED`. Завершённые и отменённые записи не возобновляются. Расписание изменяется целиком. При паузе или завершении `nextFireAt=null`.

`materialId` и `planStepId` при создании обязательны как поля, но допускают `null`. Связанный шаг принадлежит пользователю; при обоих ID материал входит в этот шаг. Новые связи после создания не назначаются. Архивирование плана не отменяет напоминания автоматически.

`canReceiveReminders=false` означает, что пользователь не нажал `/start` либо заблокировал бота. Создание/возобновление даёт `422 BOT_START_REQUIRED`; интерфейс предлагает открыть бота. При блокировке бота его напоминания ставятся на паузу.

После простоя разовая задача с опозданием до 24 часов отправляется один раз с пометкой; более старая завершается с причиной `EXPIRED`. Для повторений допускается одна отправка за пропущенный срок в пределах 24 часов, затем рассчитывается будущее время. Пачка накопленных уведомлений не отправляется.

Причины завершения разового напоминания: `DELIVERED`, `EXPIRED`, `DELIVERY_UNKNOWN`; у остальных записей поле равно `null`. При тайм-ауте после возможного принятия сообщения Telegram автоматический повтор этой отправки не выполняется; разовая завершается с `DELIVERY_UNKNOWN`, повторяющаяся продолжает расписание. Явные временные отказы допускают ограниченный повтор. Перед отправкой проверяются статус и версия.

Для переходов летнего времени несуществующее время сдвигается вперёд на величину перехода; для двукратного времени выбирается первое вхождение. Уже принятую Telegram отправку после отмены отозвать нельзя.

## Ошибки

| HTTP / code | Действие интерфейса |
|---|---|
| 400 `MALFORMED_REQUEST` | Исправить формат запроса |
| 401 `AUTH_REQUIRED`, `TELEGRAM_AUTH_INVALID` | Повторить вход или открыть Mini App заново |
| 404 `NOT_FOUND` | Перечитать список, сообщить о недоступности объекта |
| 409 `VERSION_CONFLICT` | Перечитать объект, предложить повторить изменение |
| 409 `PLAN_REPLACEMENT_REQUIRED` | Получить текущий план и запросить подтверждение замены |
| 409 `IDEMPOTENCY_CONFLICT`, `REQUEST_IN_PROGRESS` | Проверить ключ / повторить ту же команду позднее |
| 409 `PLAN_ARCHIVED`, `REMINDER_TERMINAL` | Перечитать объект, убрать недоступное действие |
| 422 `VALIDATION_FAILED` | Показать ошибки полей |
| 422 `PLAN_MATERIALS_NOT_FOUND` | Предложить изменить цель и попробовать снова |
| 422 `BOT_START_REQUIRED` | Предложить открыть бота и нажать `/start` |
| 429 `RATE_LIMITED` | Учесть `Retry-After` в секундах |
| 503 `SERVICE_UNAVAILABLE` | Показать временную недоступность |
