# Подключение RAG к Java-бэкенду

Актуально для текущей Python-реализации, 15 ноября 2026.

15 ноября добавлена Java-консультация через Spring AI: см. раздел «Консультации: этап 8»
в корневом [README](../README.md). Docker-бэкенд вызывает локальную Ollama напрямую;
JSONL worker в этом пути не запускается. Ниже описан отдельный Python-прототип.
Его гибридный поиск, режим extracts и измеренные метрики не являются результатами Java-интеграции.

## Что уже работает и где граница

Работают импорт предоставленного PGDMP/ZIP, подготовка разрешённого корпуса,
семантический + BM25-поиск, ответы с проверяемыми выдержками, рекомендации,
уточнения и отказ при отсутствии информации. Есть ограниченный детерминированный
расчёт точки безубыточности одной товарной позиции по явно заданным данным.

Профили, история, Telegram, авторизация, сохранение планов и напоминания находятся
в Java. Python не хранит пользователей и не выполняет эти действия.
Поддержаны `profilePatch`, `planProposal`, `reminderProposal` и статус
`ACTION_PROPOSAL`. `currentPlan` принимает текущий план с карточками шагов;
модель учитывает цель и статусы, но не изменяет хранилище.
План и напоминание возвращаются как предложения. Java проверяет права/версии
и сохраняет их только после подтверждения пользователя. Отмена и изменение
уже сохранённых записей по-прежнему маршрутизируются в Java.

[Полные примеры планов, профиля и напоминаний](RAG_ACTIONS.md).

Целевая архитектура со Spring AI описана в [ARCHITECTURE.md](ARCHITECTURE.md).
Для первого сквозного сценария добавлен уже работающий адаптер процесса
`ml/rag_bridge.py`: Java запускает его через ProcessBuilder. Это не отдельный
HTTP-сервис и не API для фронтенда. Перенос алгоритмов в Java — отдельный вариант,
описанный ниже; вызова одной модели Ollama недостаточно для воспроизведения RAG.

## 1. Подготовить окружение на машине worker

Требуется Python 3.11+, Ollama, около 16 ГБ RAM для текущей Qwen на одном диалоге.
Все команды из корня репозитория. Реальную выгрузку получить отдельно у команды
и положить в `data/`: она намеренно не хранится в Git.

```sh
uv venv .venv
uv pip install --python .venv/bin/python -r ml/requirements.lock

# Запустить Ollama приложением или, если сервер ещё не запущен:
ollama serve
```

В другом терминале:

```sh
ollama pull embeddinggemma:300m
ollama pull qwen3.5:9b-q4_K_M
.venv/bin/python ml/setup_local.py data/drive-download-20261113T183742Z-1-001.zip
.venv/bin/python -m unittest discover -s ml -p 'test_*.py'
```

Без uv: `python3 -m venv .venv` и
`.venv/bin/python -m pip install -r ml/requirements.lock`.
В Windows путь к интерпретатору — `.venv\Scripts\python.exe`.
Вместо конкретного ZIP можно передать другой ZIP с одним `.backup` или сам PGDMP;
для новых данных используйте `--version NAME` и соответствующий путь к корпусу.

Setup скачивает недостающие модели, читает дамп без PostgreSQL и исполнения SQL,
строит корпус/индекс, загружает tokenizer Qwen. Источник и производные остаются
локальными. Повторный setup проверяет происхождение данных и digest модели;
незавершённая индексация продолжается. Не запускайте setup во время консультаций.

Нужные артефакты:

- `data/processed/2026-11-13-local/materials.jsonl`;
- `chunks.jsonl`, `manifest.json`, `report.json` в той же папке;
- `semantic.sqlite` — 4877 векторов размерности 768 для этой выгрузки;
- `data/source/models/qwen3.5-tokenizer.json` и `.meta.json`;
- локальные веса Ollama. Индекс нельзя смешивать с другой embedding-моделью.

В репозиторий входят код, lock-файл и синтетические тесты. ZIP, снимки, тексты,
векторы, реальные eval-примеры и подробные отчёты передаются приватно либо
восстанавливаются на машине получателя из предоставленного архива.

## 2. Проверить bridge без Java

```sh
.venv/bin/python -u ml/rag_bridge.py data/processed/2026-11-13-local
```

Первая строка stdout — сигнал готовности:

```json
{"event":"ready","protocolVersion":"1","schemaVersion":"0.1.0","datasetVersion":"2026-11-13-local","chatModel":"qwen3.5:9b-q4_K_M","embeddingModel":"embeddinggemma:300m"}
```

Он подтверждает, что корпус/индекс читаются и локальные модели зарегистрированы.
Это не прогрев генерации; первый ответ может занимать больше времени.
При ошибке подготовки приходит `event=fatal`, `error.code=STARTUP_ERROR`,
после чего процесс заканчивается с кодом 1. Исправьте окружение, а не повторяйте
пользовательские запросы в цикле.

Далее отправляйте **один UTF-8 JSON-объект на строку**, завершая каждую строку LF
и вызывая flush. Переводы строк внутри текста должны быть экранированы JSON-кодеком.
Ниже пример самостоятельной проверки; первая строка результата будет ready,
вторая — ответ на запрос:

```sh
printf '%s\n' '{"requestId":"demo-1","message":"Помоги мне с бизнесом.","profile":{},"history":[],"currentPlan":null}' | .venv/bin/python -u ml/rag_bridge.py data/processed/2026-11-13-local
```

EOF stdin останавливает worker. Дополнительных stdout-логов в протоколе нет.
Логи процесса идут в stderr; не объединяйте stderr со stdout.

## 3. Вход Java → worker

Пример JSON ниже для чтения отформатирован. В stdin он отправляется одной строкой.

```json
{
  "schemaVersion": "0.1.0",
  "requestId": "915c2495-1990-4735-a26f-40594220968e",
  "message": "Как увеличить повторные покупки?",
  "profile": {
    "industry": "кофейня",
    "businessStage": "OPERATING",
    "experience": "BEGINNER",
    "currentTask": "Увеличить повторные продажи",
    "timeZone": "Europe/Moscow"
  },
  "history": [
    {"role": "USER", "text": "У меня небольшая кофейня"},
    {"role": "ASSISTANT", "text": "Какую задачу вы хотите решить?"}
  ],
  "currentPlan": null
}
```

Дополнительно передавайте `now` — свежее серверное время UTC. Для `/plans/generate`
передавайте `operation="generate_plan"`, а выбранную цель — в `message`.
`operation="consultation"` используется по умолчанию.

Обязательны `requestId` и `message`. Остальные поля необязательны и имеют значения
`schemaVersion=0.1.0`, `profile={}`, `history=[]`, `currentPlan=null`.
Неизвестные поля отвергаются: не отправляйте `userId`, Telegram ID, токены,
`sources`, `catalog`, URL сервера модели или произвольные параметры генерации.
Если `now` не указан, worker использует свой UTC-clock. Не берите `now` из
пользовательского текста или фронтенда.

| Поле | Ограничение |
|---|---|
| requestId | Непустая строка до 128 символов, без управляющих символов; удобен UUID |
| message | 1–4000 Unicode-символов после проверки непустоты |
| history | До 12 сообщений, role только USER/ASSISTANT, text до 4000 символов |
| Вся строка | До 512 КиБ включая LF |
| currentPlan | null либо Plan с id, goal, status, version и 3–5 шагами; формат ниже в RAG_ACTIONS.md |

Допустимые поля профиля:

| Поле | Значения |
|---|---|
| industry | Строка до 100 символов |
| currentTask | Строка до 500 символов |
| timeZone | Строка до 100 символов; бизнес-операции проверяют IANA-зону в Java |
| businessStage | IDEA, LAUNCH, OPERATING, GROWTH |
| experience | BEGINNER, EXPERIENCED |
| taxRegime | NPD, USN_6, USN_15, PATENT, OSNO, AUSN |

Java должна передавать историю и профиль только текущего авторизованного
пользователя. Worker не определяет принадлежность данных. Новое сообщение уже
находится в `message`; не дублируйте его в history. После успешного ответа Java
сама сохраняет USER/ASSISTANT в БД. Python не переносит контекст между запросами. Java применяет проверенный
profilePatch к собственному профилю и передаёт обновлённое состояние на следующем ходе.
Ответ profilePatch сам по себе не означает, что профиль уже сохранён.
При неизвестной стадии бизнеса поле лучше опустить, чем угадывать значение.

## 4. Выход worker → Java

Для успешно выполненной консультации:

```json
{
  "requestId": "915c2495-1990-4735-a26f-40594220968e",
  "ok": true,
  "result": {
    "response": {
      "schemaVersion": "0.1.0",
      "status": "CLARIFY",
      "answer": "Какую конкретную задачу бизнеса вы хотите решить?",
      "sourceChunkIds": [],
      "recommendations": [],
      "profilePatch": {},
      "planProposal": null,
      "reminderProposal": null
    },
    "references": [],
    "recommendationCards": [],
    "diagnostics": {}
  }
}
```

Это иллюстрация формы, не ожидаемый ответ на конкретный пример выше.

- `result.response` — публичные поля [LLM-контракта](contracts/llm/README.md), включая предложения действий.
  `ACTION_PROPOSAL` требует reminderProposal; ANSWER может содержать planProposal.
  Сохранять или исполнять эти объекты автоматически до пользовательского подтверждения нельзя.
- `references` — `chunkId`, `materialId`, `title`, `url` использованных источников.
- `recommendationCards` — полная карточка из materials.jsonl с `recommendationReason`.
  Для фронтенда маппить `materialId` в его поле `id`, `sourceType` в `kind`.
- `diagnostics` — внутренние метрики; набор полей различается для консультации,
  уточнения, неподдержанного действия и калькулятора. Не считайте его стабильным
  пользовательским DTO и не отображайте целиком в Telegram/Mini App.

При ANSWER есть хотя бы один sourceChunkId. Статусы CLARIFY и NOT_FOUND — обычные
успешные результаты с `ok=true`, их нельзя автоматически повторять как ошибки.
Ссылки брать из references/cards, а не извлекать из текста ответа.

Техническая/входная ошибка имеет другую форму:

```json
{"requestId":"demo-1","ok":false,"error":{"code":"RAG_ERROR","message":"Не удалось выполнить консультацию. Проверьте локальную Ollama, модели и индекс."}}
```

| Случай | Поведение Java |
|---|---|
| INVALID_REQUEST | Ошибка входа; исправить сериализацию/валидацию, не ретраить вслепую |
| RAG_ERROR | Модель/индекс/тайм-аут; техническое сообщение пользователю, обычно 503 |
| INTERNAL_ERROR | Технический сбой worker; лог с requestId, без профиля и корпуса |
| Timeout транспорта | Убить worker, сообщить технический сбой, обычно 504 |
| EOF/невалидный JSON/не тот requestId | Сбой протокола: пересоздать worker |
| event=fatal при старте | RAG недоступен до исправления setup |

У битого JSON, неверного UTF-8 или слишком большой строки requestId может быть
null, поскольку корреляции доверять нельзя. Такие случаи не превращаются в
NOT_FOUND. Для обычного ответа Java проверяет совпадение requestId.

## 5. Пример Java 21: транспорт процесса

Ниже самостоятельный класс без зависимости от конкретной версии Jackson.
`isReady` и `matchesId` реализуйте через уже настроенный JSON-кодек бэкенда:
первый проверяет `event=ready` и `protocolVersion=1`, второй сравнивает поле
requestId с ожидаемым. Не реализуйте их поиском подстрок в JSON.
Сериализация request и разбор результата остаются на стороне вашего JSON-кодека.

```java
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

public final class RagWorker implements AutoCloseable {
    private final Process process;
    private final BufferedReader input;
    private final BufferedWriter output;
    private final ExecutorService io = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().factory());
    private final ReentrantLock busy = new ReentrantLock();
    private final BiPredicate<String, String> matchesId;

    public RagWorker(Path root, Path python, Path dataset,
                     Predicate<String> isReady,
                     BiPredicate<String, String> matchesId) throws IOException {
        this.matchesId = matchesId;
        process = new ProcessBuilder(
                python.toAbsolutePath().toString(), "-u",
                root.resolve("ml/rag_bridge.py").toAbsolutePath().toString(),
                dataset.toAbsolutePath().toString(), "--timeout", "60")
                .directory(root.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        input = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8));
        output = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8));
        String ready = exchange(null, Duration.ofSeconds(30));
        try {
            if (!isReady.test(ready)) throw new IOException("RAG startup failed");
        } catch (RuntimeException | IOException e) {
            close();
            throw new IOException("Invalid RAG readiness message", e);
        }
    }

    public String call(String requestId, String requestJson) throws IOException {
        if (requestJson.indexOf('\n') >= 0 || requestJson.indexOf('\r') >= 0
                || requestJson.getBytes(StandardCharsets.UTF_8).length >= 512 * 1024) {
            throw new IOException("Request must be one bounded JSON line");
        }
        if (!busy.tryLock()) throw new IOException("RAG worker is busy");
        try {
            String reply = exchange(requestJson, Duration.ofSeconds(75));
            if (!matchesId.test(reply, requestId)) {
                close();
                throw new IOException("Unexpected RAG requestId");
            }
            return reply;
        } catch (RuntimeException e) {
            close();
            throw new IOException("Invalid RAG response", e);
        } finally {
            busy.unlock();
        }
    }

    private String exchange(String request, Duration timeout) throws IOException {
        Future<String> future = io.submit(() -> {
            if (request != null) {
                output.write(request);
                output.newLine();
                output.flush();
            }
            StringBuilder line = new StringBuilder();
            for (int c; (c = input.read()) != -1; ) {
                if (c == '\n') return line.toString();
                if (line.length() >= 1024 * 1024) {
                    throw new IOException("RAG response too large");
                }
                line.append((char) c);
            }
            throw new EOFException("RAG worker stopped");
        });
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            close();
            throw new IOException("RAG call interrupted", e);
        } catch (TimeoutException | ExecutionException e) {
            future.cancel(true);
            close();
            throw new IOException("RAG worker failed or timed out", e);
        }
    }

    @Override public void close() {
        process.destroyForcibly();
        io.shutdownNow();
    }
}
```

В Spring создавайте один такой worker как bean с `destroyMethod="close"`.
Пути берите из конфигурации приложения, не из пользовательского HTTP-запроса.
Для хоста примеры значений: корень клона, `<root>/.venv/bin/python`,
`<root>/data/processed/2026-11-13-local`.

Порядок в сервисе консультаций:

1. Авторизовать пользователя и загрузить его профиль/историю.
2. Сформировать DTO выше, выдать UUID requestId и сериализовать JSON-кодеком.
3. Вызвать worker вне транзакции БД; разобрать envelope и проверить `ok`.
4. Проверить result.response по DTO/контракту, достроить публичный ответ из
   response/references/recommendationCards.
5. Сохранить ход диалога с идемпотентностью по requestId, отправить ответ в Telegram.

На Mac M2/16 ГБ используйте один последовательный worker. Для загруженного worker
возвращайте занятость или ставьте запрос в ограниченную очередь с общим deadline;
не создавайте неограниченные процессы на каждый HTTP-запрос. Для одного диалога
сохраняйте порядок сообщений. Внутренний бюджет модели 60 секунд включает
возможное исправление формата. 75 секунд в примере — аварийный тайм-аут транспорта,
а не обещание SLA. При строгом общем deadline учитывайте ожидание очереди и
сократите `--timeout` worker, например до 55 секунд.

После аварии пересоздайте worker. Не считывайте запоздалую строку как ответ на
следующий запрос. Профиль и история не потеряются, поскольку их хранит Java.
Не логируйте полный stdin/stdout: там могут быть профиль и учебные тексты.

## 6. Docker и размещение

Рабочий простой вариант для текущей реализации: Java, Python worker и Ollama
запускаются на одной машине, PostgreSQL может оставаться в Docker. Java из IDE
подключается к БД по инструкции корневого README.

В текущем `backend`-образе нет Python, ML-кода, корпуса и моделей; один `docker
compose up` пока не включает RAG. Host `.venv` также нельзя просто перенести
в Linux-контейнер — зависимости устанавливают внутри целевой среды.

Worker разрешает только `http://127.0.0.1:11434`/localhost и локальные GGUF-модели.
Из обычного backend-контейнера localhost указывает на контейнер, не на ноутбук.
`host.docker.internal` текущим клиентом отвергается. Не отключайте эту проверку
молча. Для отдельного private model server нужна явная конфигурация и пересмотр
сетевой границы; для текущего адаптера нужен общий сетевой namespace с локальной
Ollama и доступный в нём Python runtime. Такой Docker-деплой в этой версии
не подготовлен и не проверен.

## 7. Если переносить всё в Java / Spring AI

Ниже требования к полному воспроизведению Python-алгоритмов в Java. Текущий Java-путь
реализует консультацию по общему контракту, с pgvector-поиском и проверкой ID;
он пока не воспроизводит весь гибридный поиск и extract-selection-1.

- Импортировать materials/chunks с теми же правами, стабильными ID и URL.
- EmbeddingGemma 300M: 768 измерений, нормализованные векторы. Документный формат
  `title: {title} | text: {text}`, вопрос `task: search result | query: {query}`.
  Усечение embedding-входа отключено. Модель/digest и версию корпуса хранить в индексе.
- Повторить BM25 со Snowball-стеммингом, title boost 3, RRF: окно 100, k=60,
  вес dense 0.75, cosine gate 0.35, до двух фрагментов материала в топ-5.
  Простой pgvector top-k не воспроизводит текущий гибридный поиск.
- Сформировать контекст по `make_input`, сохранить бюджет токенов, историю и
  выбранные источники. Из истории передавать не более четырёх последних сообщений.
- Qwen3.5 9B Q4_K_M: `think=false`, temperature=0, seed=42, num_ctx=16384,
  num_predict=1200, num_batch=512. До одной повторной попытки в общем бюджете.
- Для режима extracts портировать `evidence_passages`, `model_request`,
  `expand_selection` и валидацию. Модель возвращает внутренние E-ID, не готовый
  публичный ответ. UUID и URL восстанавливает адаптер. Один промпт со старым
  compact-1 парсером несовместим с этим режимом.
- Портировать планы из plan_proposals.py, расписания из reminder_proposals.py,
  проверку доказательств профиля из profile_updates.py и feature_contract.py.
  Проверить routes из answer_policy.py и ограниченный calculator из calculation.py.
  В пользовательских действиях окончательное решение и сохранение остаются в Java.
- Сравнить перенос на тех же приватных вопросах и отдельно собрать новый holdout.
  Не обещать сохранения измеренного качества при изменении chunking, модели,
  embedding-prefix, алгоритма BM25 или сборки контекста.

## 8. Проверки и результаты

[Методика оценки](../ml/EVALUATION.md), [запуск ML](../ml/README.md).
Основные измерения: 100 синтетических примеров, 69 dev / 31 holdout,
32 конфигурации поиска. На holdout точный фрагмент в топ-5: 54.5% → 90.9%;
ожидаемый статус: 67.7% → 100%; технические ошибки: 2 → 0.
Это не оценка полной правильности советов и не проверка живого Telegram-сценария.
Извлечённые выдержки могут быть избыточны; законодательные сведения требуют
проверки актуальности. Калькулятор проверен отдельно после основного бенчмарка.

Подробные отчёты и реальные eval-примеры не коммитятся: они содержат фрагменты
корпуса и находятся в data/. Для приёмки подключения проверьте готовность,
обычный вопрос, продолжение диалога, пустой профиль, уточнение, отказ, отсутствие
Ollama, неверный ID/JSON, тайм-аут процесса и отсутствие смешивания двух пользователей.

## Новые действия и измерения

С 15 ноября добавлена ветка actions-1 / consultation-7. Исторические показатели
сотни консультационных вопросов относятся к consultation-6; они не являются
оценкой генерации планов и новых ACTION_PROPOSAL. Новые проверки и форматы
описаны в [RAG_ACTIONS.md](RAG_ACTIONS.md).
