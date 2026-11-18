# Лабораторна робота №11 — Security Testing

SUT «Прокат обладнання», варіант 04. Виконано **варіант B**: обидва треки (Web/UI і Backend/API), fix + retest у кожному треку, порівняння.

## Структура

```
lab-11/
  sut-b-vulnerable/   оригінальний вразливий backend (порт 8082)
  sut-c-vulnerable/   оригінальний вразливий UI
  sut-b-fixed/        виправлений backend (порт 8083)
  sut-c-fixed/        виправлений UI
  security-check.sh   curl-перевірки: request → response + перевірка в БД
  evidence/           zap-report.html, vulnerable-8082.txt, fixed-8083.txt
```

## Запуск

Окрема навчальна БД (лише вигадані дані), створюється один раз:

```
docker run -d --name rental-security-db -e POSTGRES_USER=rental_student -e POSTGRES_PASSWORD=student -e POSTGRES_DB=rental_variant04_security -p 55433:5432 postgres:17-alpine
docker exec -i rental-security-db psql -U rental_student -d rental_variant04_security < sut-b-vulnerable/migrations/V1__create_tables.sql
```

Далі:

```
docker start rental-security-db
cd sut-b-vulnerable && SECURITY_DB_PASSWORD=student mvn spring-boot:run   # http://127.0.0.1:8082/index.html
cd sut-b-fixed      && SECURITY_DB_PASSWORD=student mvn spring-boot:run   # http://127.0.0.1:8083/index.html
bash security-check.sh 8082 | tee evidence/vulnerable-8082.txt            # до fix
bash security-check.sh 8083 | tee evidence/fixed-8083.txt                 # після fix
```

## Інструменти

- **OWASP ZAP 2.17.0**: Automated Scan (traditional spider + AJAX spider + active scan) по `http://127.0.0.1:8082/index.html`.
- **curl**: скрипт `security-check.sh` (усі backend-атаки і перевірка файлу клієнтів).
- **Браузер Chrome**: відтворення XSS.
- **psql** у контейнері БД: доказ, що підміна полів реально збереглася в БД.

## Підсумок

| # | Трек | Вразливість | Де в коді | Підтверджено | Fix | Retest |
|---|---|---|---|---|---|---|
| 1 | Web/UI | XSS | `sut-c-vulnerable/app.js`, рядок 109 | так | `innerHTML` → `textContent` | так |
| 2 | Web/UI | Розкриття конфіденційних даних | `assets/customer-export.json`, `robots.txt` | так | файл прибрано з публічної папки | так (404) |
| 3 | Backend/API | SQL Injection | `RentalsController.java`, рядок 40 | так | prepared statement (`?`) | так |
| 4 | Backend/API | Підміна захищених полів | `RentalsController.java`, рядки 42–51 | так | PATCH приймає лише `reference` | так |
| 5 | Backend/API | Витік внутрішньої інформації | `ApiExceptionHandler.java`, рядки 42–47 | так | загальне повідомлення, деталі тільки в лог | так |

## Результати ZAP (`evidence/zap-report.html`)

- Content Security Policy (CSP) Header Not Set — Medium
- Missing Anti-clickjacking Header — Medium
- X-Content-Type-Options Header Missing — Low
- User Controllable HTML Element Attribute (Potential XSS) — Informational
- Spider через `robots.txt` знайшов `/assets/customer-export.json`.

Alert сканера ≠ підтверджена вразливість. ZAP показав лише *потенційний* XSS, а API-запити (`/rentals/search`, `PATCH /rentals/{id}`) сторінка робить через JavaScript, тому всі вразливості нижче підтверджено вручну з фактичною поведінкою та impact.

---

## Web/UI — `sut-c-vulnerable`

### 1. XSS (DOM-based)

- **Відтворення:** `http://127.0.0.1:8082/index.html?welcome=<img src=x onerror=alert(document.domain)>`
  (закодовано: `?welcome=%3Cimg%20src%3Dx%20onerror%3Dalert%28document.domain%29%3E`)
- **Фактична поведінка:** виконується чужий JavaScript, з'являється вікно `127.0.0.1`. Payload `<img src=x onerror="document.body.innerHTML='<h1>Сайт зламано</h1>'">` повністю підміняє сторінку.
- **Impact:** атакувальник надсилає жертві посилання, і його скрипт виконується від імені сайту. Він може підмінити сторінку (фішинг, фальшива форма), читати дані сторінки й робити API-запити від імені користувача.
- **Причина:** `app.js`, рядок 109: дані з URL вставляються як HTML:
  ```js
  $('welcome').innerHTML = new URLSearchParams(location.search).get('welcome') || 'Вітаємо у прокаті!';
  ```
- **Fix** (`sut-c-fixed/app.js`, рядок 109): вставляємо як текст:
  ```js
  $('welcome').textContent = new URLSearchParams(location.search).get('welcome') || 'Вітаємо у прокаті!';
  ```
- **Retest:** те саме посилання на порту 8083: вікна немає, payload показано як звичайний текст.

### 2. Розкриття конфіденційних даних

- **Відтворення:**
  ```
  GET /robots.txt                   → HTTP 200: Disallow: /assets/customer-export.json
  GET /assets/customer-export.json  → HTTP 200
  ```
- **Фактична поведінка** (`evidence/vulnerable-8082.txt`, розділ 4):
  ```
  {"synthetic":true,"classification":"Внутрішні дані, не для публікації","customers":[{"name":"Тестовий клієнт А","email":"buyer-a@example.invalid","privateNote":"Навчальна приватна примітка А"},{"name":"Тестовий клієнт Б", ...}]}
  ```
- **Impact:** будь-хто без входу отримує персональні дані клієнтів (імена, email, приватні нотатки): витік PII, спам і фішинг, порушення конфіденційності.
- **Причина:** внутрішній файл лежить у публічній папці UI, яку сервер віддає як статику. `robots.txt` не захищає, це лише прохання до пошукових ботів, і воно ще й розкриває шлях до файлу.
- **Fix:** з `sut-c-fixed` видалено `assets/customer-export.json`, з `robots.txt` прибрано рядок `Disallow`.
- **Retest** (`evidence/fixed-8083.txt`, розділ 4):
  ```
  GET /assets/customer-export.json → HTTP 404 {"code":"NOT_FOUND","message":"Маршрут не знайдено"}
  ```

---

## Backend/API — `sut-b-vulnerable`

### 3. SQL Injection — `GET /rentals/search`

Вимога: пошук лише за точним кодом.

```
REQUEST:  GET /rentals/search?reference=RENT-A-1795029808
RESPONSE: HTTP 200  [{"id":1,"reference":"RENT-A-1795029808","total":92000,"status":"Active"}]

REQUEST:  GET /rentals/search?reference=' OR '1'='1
RESPONSE: HTTP 200  [{"id":1,...,"total":92000,...},{"id":2,"reference":"RENT-B-1795029808","total":79200,...}]   ← усі оренди

REQUEST:  GET /rentals/search?reference=' UNION SELECT 0, current_user, 0, version() --
RESPONSE: HTTP 200  [{"id":0,"reference":"rental_student","total":0,"status":"PostgreSQL 17.11 on aarch64-unknown-linux-musl, ..."}]
```

- **Impact:** атакувальник читає будь-які дані з БД (усі оренди, інші таблиці через UNION, користувача і версію БД) і обходить умову пошуку.
- **Причина** (`RentalsController.java`, рядок 40): SQL склеюється з введеного рядка, тому лапка `'` закриває рядок і далі йде SQL атакувальника:
  ```java
  return jdbc.queryForList("SELECT id,reference,total,status FROM rentals WHERE reference = '" + reference + "'");
  ```
- **Fix** (`sut-b-fixed`): parameterized query / prepared statement. Значення передається окремо від SQL і ніколи не стає його частиною:
  ```java
  return jdbc.queryForList("SELECT id,reference,total,status FROM rentals WHERE reference = ?", reference);
  ```
- **Retest:** обидва payload дають `HTTP 200 []`, а звичайний пошук далі знаходить одну оренду.

### 4. Підміна захищених полів (mass assignment) — `PATCH /rentals/{id}`

Вимога: PATCH лише для зміни `reference`; суму визначає Service, статус змінюється тільки бізнес-діями.

```
DB до:    id=1 | RENT-A-1795029808      | total 92000 | Active

REQUEST:  PATCH /rentals/1
BODY:     {"reference":"RENT-A-1795029808-EDIT","total":1,"status":"Returned"}
RESPONSE: HTTP 200  {"id":1,"reference":"RENT-A-1795029808-EDIT",...,"total":1,...,"status":"Returned",...}

DB після: id=1 | RENT-A-1795029808-EDIT | total 1     | Returned     ← зміна реально в БД
```

- **Impact:** клієнт сам ставить суму (1 коп. замість 920 грн) і статус «Повернено» без реального повернення обладнання. Це фінансові втрати й обхід бізнес-процесу.
- **Причина** (`RentalsController.java`, рядки 42–51): DTO приймає `total` і `status`, і вони пишуться в БД:
  ```java
  public record EditInput(String reference, Long total, RentalStatus status) {}
  jdbc.update("UPDATE rentals SET reference=?,total=?,status=? WHERE id=?", ...);
  ```
- **Fix:** DTO містить лише дозволене поле, і SQL оновлює тільки його:
  ```java
  public record EditInput(String reference) {}
  jdbc.update("UPDATE rentals SET reference=? WHERE id=?", input.reference(), id);
  ```
- **Retest:**
  ```
  PATCH /rentals/3  {"reference":"RENT-A-1795030224-EDIT","total":1,"status":"Returned"}
  RESPONSE: HTTP 200  {...,"total":92000,...,"status":"Active",...}
  DB після: id=3 | RENT-A-1795030224-EDIT | total 92000 | Active     ← змінився лише reference
  ```

### 5. Витік внутрішньої інформації

```
REQUEST:  GET /rentals/search?reference='
RESPONSE: HTTP 500
{"code":"DATABASE_ERROR","trace":"org.springframework.jdbc.BadSqlGrammarException: StatementCallback; bad SQL grammar [SELECT id,reference,total,status FROM rentals WHERE reference = ''']\n\tat org.springframework.jdbc.support.SQLStateSQLExceptionTranslator.doTranslate(...)..."}

REQUEST:  PATCH /rentals/1  {"reference":"THIS-REFERENCE-IS-DEFINITELY-LONGER-THAN-40-CHARS"}
RESPONSE: HTTP 500
{"code":"DATABASE_ERROR","trace":"org.springframework.dao.DataIntegrityViolationException: PreparedStatementCallback; SQL [UPDATE rentals SET reference=?,total=?,status=? WHERE id=?]; ERROR: value too long for type character varying(40)\n\tat ..."}
```

- **Impact:** відповідь розкриває SQL-запити, назви таблиць і колонок, обмеження БД, фреймворк і внутрішні класи. Це готова карта для атаки: саме з такої помилки легко побудувати SQL Injection.
- **Причина** (`ApiExceptionHandler.java`, рядки 42–47): у відповідь іде `ex.toString()` і повний stack trace.
- **Fix:** клієнт отримує коротке повідомлення, а деталі пишуться тільки в лог сервера:
  ```java
  org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class).error("Database error", ex);
  if (ex instanceof org.springframework.dao.DuplicateKeyException) return error(409, "DUPLICATE_RESOURCE", "Ресурс із таким кодом уже існує");
  if (ex instanceof org.springframework.dao.DataIntegrityViolationException) return error(400, "INVALID_REQUEST", "Некоректні дані ресурсу");
  return error(500, "DATABASE_ERROR", "Помилка роботи з БД");
  ```
- **Retest:**
  ```
  GET /rentals/search?reference='   → HTTP 200 []
  PATCH /rentals/3 (задовгий код)   → HTTP 400 {"code":"INVALID_REQUEST","message":"Некоректні дані ресурсу"}   ← без SQL і trace
  ```

---

## Порівняння Web/UI vs Backend/API

| | Web/UI | Backend/API |
|---|---|---|
| Де вразливість | у браузері: JavaScript, статичні файли | на сервері: SQL, валідація полів, обробка помилок |
| Кого зачіпає | користувача, який відкрив шкідливе посилання | дані всіх користувачів у БД |
| Як шукати | браузер, сканер (ZAP), перегляд публічних файлів | прямі HTTP-запити (curl/Postman), підміна параметрів |
| Типові проблеми | XSS, публічні внутрішні файли, відсутні security-заголовки | SQL Injection, mass assignment, витік stack trace, обхід бізнес-правил |
| Як виправляти | безпечний вивід (`textContent`, екранування), не класти внутрішні дані в публічну папку, CSP | parameterized queries, DTO лише з дозволеними полями, загальні повідомлення про помилки |

Головний висновок: перевірки в UI не захищають backend. Форма редагування в UI надсилає тільки `reference`, але прямий `PATCH` через curl приймав `total` і `status`. Захист має бути на сервері, а UI відповідає за безпечне відображення даних.