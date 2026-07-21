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
- `MAX_API_BASE_URL` — по умолчанию `https://platform-api2.max.ru`
- `BOT_DB_PATH` — путь к SQLite (по умолчанию `/data/bot.db`)
- `MOYKLASS_ENABLED` — `true`/`false`
- `MOYKLASS_BASE_URL` — по умолчанию `https://api.moyklass.com`
- `MOYKLASS_TOKEN` — API ключ из настроек CRM (используется для получения accessToken)
- `MOYKLASS_LEAD_STATE_ID` — (необязательно) ID статуса, который считать лидом
- `MOYKLASS_JOIN_STATUS_ID` — ID статуса записи в группу (по умолчанию `2`)
- `MOYKLASS_MAX_ID_ATTR_ALIAS` — (необязательно) алиас признака, куда сохраняем `max_user_id`
- `MOYKLASS_PARENT_NAME_ATTR_ALIAS` — (необязательно) алиас признака для ФИО родителя
- `MOYKLASS_PAY_LINK_BASE` — базовый URL для pay‑ссылки (по умолчанию `https://pay.tvoyklass.com/key/`)
- `SITE_REGISTRATION_URL` — ссылка кнопки регистрации в боте (по умолчанию `https://roboacademiya.ru/`)
- `SITE_CONTACT_URL` — ссылка кнопки «Задать вопрос» в боте и на сайте

### Локальный запуск

```bash
mvn -q -DskipTests spring-boot:run
```

### Docker

```bash
docker build -t myclass .

docker run -d --name myclass --restart unless-stopped \
  -p 8081:8080 \
  -e MAX_BOT_TOKEN=YOUR_TOKEN \
  -e MAX_ADMIN_USER_ID=123456,789012 \
  -e ADMIN_PANEL_TOKEN=secret \
  -e MOYKLASS_ENABLED=true \
  -e MOYKLASS_TOKEN=YOUR_MOYKLASS_TOKEN \
  -e MAX_WEBHOOK_ENABLED=true \
  -e MOYKLASS_PARENT_NAME_ATTR_ALIAS=user.parent1 \
  -e MOYKLASS_LEAD_STATE_ID=323065 \
  -e MOYKLASS_JOIN_STATUS_ID=2 \
  -e MOYKLASS_MAX_ID_ATTR_ALIAS=max_user_id \
  -e MOYKLASS_PAY_LINK_BASE=https://pay.tvoyklass.com/key/ \
  -e MOYKLASS_ALLOWED_CLIENT_STATE_IDS=254541,323065,261119 \
  -e SITE_REGISTRATION_URL=https://roboacademiya.ru/ \
  -e SITE_CONTACT_URL=https://max.ru/id246516134480_2_bot \
  -v myclass-data:/data \
  myclass
```

## Админ‑панель

Откройте `http://localhost:8081/admin/index.html`, введите `ADMIN_PANEL_TOKEN`.

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
- Если **нет**, запрашивает ФИО ребенка, ФИО родителя, телефон и email, затем создаёт ученика (лида).

Рекомендуемый текст вопроса:
`Ранее уже были оплаты в нашей школе?`

## Файлы

- `src/main/java/com/myclass/maxbot/MaxBotService.java` — long polling + обработка команд
- `src/main/java/com/myclass/maxbot/DialogService.java` — логика живого диалога
- `src/main/resources/static/admin/*` — админ‑панель
- `src/main/resources/schema.sql` — структура SQLite
