# MAX бот для МойКласс

Бот для мессенджера MAX (long polling) с:
- стартовым сообщением и кнопками (callback)
- сценариями: Записаться, Абонементы, Счет на оплату
- живым диалогом между пользователем и администратором
- админ‑панелью
- хранением состояния в SQLite

## Быстрый старт

### Переменные окружения

Обязательные:
- `MAX_BOT_TOKEN` — токен бота MAX
- `MAX_ADMIN_USER_ID` — user_id администратора в MAX
- `ADMIN_PANEL_TOKEN` — токен для доступа к админ‑панели
- `ADMIN_PANEL_URL` — (необязательно) ссылка на админ‑панель для команды `/admin`

Опциональные:
- `MAX_API_BASE_URL` — по умолчанию `https://platform-api.max.ru`
- `BOT_DB_PATH` — путь к SQLite (по умолчанию `/data/bot.db`)
- `MOYKLASS_ENABLED` — `true`/`false`
- `MOYKLASS_BASE_URL` — по умолчанию `https://api.moyklass.com`
- `MOYKLASS_TOKEN` — API ключ из настроек CRM (используется для получения accessToken)
- `MOYKLASS_LEAD_STATE_ID` — (необязательно) ID статуса, который считать лидом
- `MOYKLASS_MAX_ID_ATTR_ALIAS` — (необязательно) алиас признака, куда сохраняем `max_user_id`
- `MOYKLASS_PAY_LINK_BASE` — базовый URL для pay‑ссылки (по умолчанию `https://pay.tvoyklass.com/key/`)

### Локальный запуск

```bash
mvn -q -DskipTests spring-boot:run
```

### Docker

```bash
docker build -t max-bot .

docker run -d --name max-bot \
  -p 8080:8080 \
  -e MAX_BOT_TOKEN=YOUR_TOKEN \
  -e MAX_ADMIN_USER_ID=123456 \
  -e ADMIN_PANEL_TOKEN=secret \
  -v max-bot-data:/data \
  max-bot
```

## Админ‑панель

Откройте `http://localhost:8080/admin/index.html`, введите `ADMIN_PANEL_TOKEN`.

Возможности:
- просмотреть активные диалоги
- запустить диалог с пользователем (аналог `/ask`)
- завершить диалог

## Команды администратора в MAX

- `/ask <user_id> [сообщение]` — начинает диалог с пользователем и делает его текущим.
- После запуска все сообщения администратора отправляются этому пользователю до завершения.
- К каждому сообщению админа бот присылает кнопку **Завершить диалог**.

## Интеграция с МойКласс

Интеграция реализована через официальный OpenAPI (`/openapi.json`).
Используемые методы:
- Получение accessToken: `POST /v1/company/auth/getToken`
- Создание лида: `POST /v1/company/users`
- Поиск ученика по признаку: `GET /v1/company/users?attributes[alias]=value`
- Абонементы: `GET /v1/company/userSubscriptions`
- Счет на оплату: `GET /v1/company/users/{id}?includePayLink=true`

Для корректного сопоставления пользователя MAX и CRM рекомендуется задать
`MOYKLASS_MAX_ID_ATTR_ALIAS` (добавить в CRM пользовательский признак для хранения `max_user_id`).

Если у вас уже есть старая SQLite база без поля `moyklass_user_id`, проще всего удалить файл БД
или выполнить `ALTER TABLE users ADD COLUMN moyklass_user_id INTEGER;`.

### Счет на оплату
При выборе **Счет на оплату** бот возвращает pay‑ссылку `https://pay.tvoyklass.com/key/<payLinkKey>`.
`payLinkKey` берётся из `GET /v1/company/users/{id}?includePayLink=true`.

### Записаться (поиск клиента)
После нажатия **Записаться** бот уточняет, были ли ранее оплаты:
- Если **да**, просит телефон и ищет клиента по `GET /v1/company/users?phone=...`.
- Если **нет**, создаёт нового ученика (лида).

Рекомендуемый текст вопроса:
`Ранее уже были оплаты в нашей школе?`

## Файлы

- `src/main/java/com/myclass/maxbot/MaxBotService.java` — long polling + обработка команд
- `src/main/java/com/myclass/maxbot/DialogService.java` — логика живого диалога
- `src/main/resources/static/admin/*` — админ‑панель
- `src/main/resources/schema.sql` — структура SQLite
