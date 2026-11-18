# SUT B — Прокат обладнання

Java 21, Maven, PostgreSQL 17, JDBC, JUnit 5 та Mockito. Варіант 04 із персональним правилом.

## Поведінка

Обладнання має унікальний непорожній код до 40 символів, добову ціну 100–1 000 000 копійок та невід’ємний залишок. Одна оренда містить один вид обладнання: 1–20 одиниць на 1–30 днів включно. Код оренди унікальний, непорожній, до 40 символів; ідентифікатор обладнання додатний.

Оформлення перевіряє заявку, існування обладнання та достатність залишку. Потім один раз викликає RentalPolicy.isAllowed(equipment, quantity, days). False або виняток припиняє оформлення без запису. Успіх атомарно створює оренду Active і позицію зі знімком добової ціни.

Початкова сума — добова ціна × кількість × дні у цілих копійках. Персональне правило нижче визначає знижку, заставу, доплату або дозвіл. Інших знижок чи доплат немає. Преміальність фіксується при оформленні. Подальші зміни обладнання не змінюють збереженої оренди. Залишок перевіряється, але оформлення й повернення його не змінюють.

Повернення змінює Active на Returned; повторне повернення заборонене. Початкова сума зберігається, lateFee залишається нульовою. Зовнішня перевірка при поверненні не викликається. Календар, платежі та паралельне оформлення не моделюються.

## Персональне правило

12% преміальному клієнту; інакше 8% від 10 днів. Знижки не додаються.

`calculateTotal(dailyRate, quantity, days, premium)`; коротка форма без premium використовує false. Оформлення зберігає суму після знижки.

Усі межі включні, якщо явно не зазначено «менше». Відсотки рахуються від суми до знижок. Загальна сума знижки, застави чи доплати округлюється донизу до цілої копійки один раз; потім застосовується мінімум або максимум. Загальні межі кількості 1–20 і тривалості 1–30 діють завжди.

## API

`RentalService(equipment, rentals, policy)`:

- `calculateTotal(dailyRate, quantity, days)` — початкова сума;
- `rent(new RentalRequest(reference, equipmentId, quantity, days, premium))` — оформлення;
- `returnRental(id)` — повернення.

Некоректні входи та відсутні записи — IllegalArgumentException; бізнес-відмови — RentalRejectedException. Помилки БД передаються як SQLException.

EquipmentRepository(connection): create(Equipment), findById, update(Equipment), delete. RentalRepository(connection): create(RentalOrder), findById, findRentals(equipmentId, status), markReturned(id, lateFee), delete.

create повертає модель з ідентифікаторами БД, ігноруючи вхідні id. findById повертає null за відсутності запису. findRentals застосовує обидва фільтри одночасно та впорядковує результати за id. update відсутнього обладнання — IllegalArgumentException; markReturned відсутньої чи повернутої оренди — RentalRejectedException. Видалення відсутнього запису нічого не змінює. Видалення оренди каскадно видаляє позицію; обладнання з позиціями видалити не можна.

Репозиторії отримують відкрите JDBC-з’єднання, яке власник закриває через try-with-resources. create оренди потребує autoCommit=true та використовує власну транзакцію. Не обгортайте його в зовнішню транзакцію і не використовуйте з’єднання паралельно. Репозиторії можна підміняти через Mockito або підкласи; RentalPolicy — через Stub/Mock чи лямбду. Реальних зовнішніх інтеграцій немає.

## Підготовка та запуск

Створіть навчального користувача (один раз) і окрему БД у локальній PostgreSQL:

```sql
CREATE ROLE rental_student LOGIN;
\password rental_student
CREATE DATABASE rental_variant04_test OWNER rental_student;
```

Із папки sut-b застосуйте міграцію один раз до порожньої БД:

```sh
psql -h localhost -U rental_student -d rental_variant04_test -v ON_ERROR_STOP=1 -f migrations/V1__create_tables.sql
```

Задайте змінні середовища за .env.example. Файл .env автоматично не читається; пароль задається через DB_PASSWORD.

```sh
mvn compile
mvn test
```

Є лише тест підключення. Дані для бізнес-сценаріїв готуйте у власних тестах. Автоматичного створення або видалення схем немає. Для незалежності тестів очищайте власні оренди перед обладнанням або використовуйте окрему тестову схему.


## REST API

Запуск: `mvn spring-boot:run` або клас `RentalApplication`. Потрібні Java 21 і підготовлена PostgreSQL із застосованими міграціями цього проєкту.
Адреса: `http://127.0.0.1:8080`; порт змінюється через `API_PORT`.
Параметри БД: `DB_HOST`, `DB_PORT`, `DB_NAME` (типово `rental_variant04_test`), `DB_USER` (типово `rental_student`), `DB_PASSWORD`.

| Метод | Шлях | Призначення |
|---|---|---|
| POST | `/equipment` | Створити обладнання, 201 та заголовок Location |
| GET | `/equipment/{id}` | Отримати обладнання, 200 |
| POST | `/rentals` | Оформити оренду через сервіс, 201 та Location |
| GET | `/rentals/{id}` | Прочитати збережену оренду, 200 |
| POST | `/rentals/{id}/return` | Повернути обладнання через сервіс, 200 |

Для JSON передавайте `Content-Type: application/json`.
Обладнання: `{"code":"CAM-01","dailyRate":10000,"stock":5}`.
Оренда: `{"reference":"RENT-001","equipmentId":1,"quantity":1,"days":7,"premium":false}`.
Замість 1 використовуйте `id` створеного обладнання. Суми передаються в копійках.
Повернення виконується без тіла запиту.

Особисте правило цього варіанта діє через наявний сервіс. Його результат можна перевірити повторним GET: `total`, `lateFee`, `status` та `item`.
Невідомий ресурс повертає 404, бізнес-відмова або повторне повернення — 409, некоректний запит — 400.
Помилки мають JSON `{"code":"...","message":"..."}`. Статуси оренди: `Active`, `Returned`.

`RENTAL_POLICY_URL` вмикає наявну зовнішню HTTP-перевірку; без цієї змінної локальна політика дозволяє операцію. Недоступна зовнішня перевірка повертає 502.
Spring керує залежностями та пулом з’єднань. Репозиторії використовують JdbcTemplate, створення оренди й позиції є однією транзакцією.
Конструктори з Connection збережені для попередніх тестів; SQL-помилки JdbcTemplate обгортає в DataAccessException.
API не очищає БД й не застосовує міграції автоматично. Для навчальних сценаріїв виконуйте операції послідовно.


---

# Лабораторна робота №12 — Property-Based and Fuzz Testing

Інструмент: **jqwik 1.9.2** (JUnit Platform, Java). SUT B варіанта 04 «Прокат обладнання».

Запуск (Docker Desktop має бути запущений, Testcontainers піднімає PostgreSQL 17):

```
mvn test
```

## Тести

| Клас | Рівень | Що перевіряє |
|---|---|---|
| `RentalPropertiesTest` | Service + test doubles (Mockito, лямбда-політика) | properties 1–4 + 3 boundary-тести |
| `RentalStatePropertiesTest` | Service + реальна test DB (Testcontainers) | properties 5–6 (persisted state) |
| `RentalApiFuzzTest` | REST API: реальний Spring Boot + реальна test DB | 2 fuzz targets + 5 regression/boundary-тестів |

## Properties / invariants

1. **Персональне правило:** 12% преміальному клієнту, інакше 8% від 10 днів, знижки не додаються (округлення донизу). Статистика показує покриття всіх трьох випадків: ~50% / ~25% / ~25%.
2. Сума ≥ 0, і знижка ніколи не збільшує суму (`total ≤ ціна × кількість × дні`).
3. Преміальний клієнт ніколи не платить більше за звичайного за ті самі дані.
4. Кількість, тривалість або ціна поза межами завжди відхиляються (`IllegalArgumentException`).
5. Заборонена операція не змінює стан: кількість > залишку → `RentalRejectedException`, у БД немає оренди й позиції, залишок той самий.
6. Збережена оренда: `Active`, сума за правилом, знімок ціни, залишок не змінився; повторне повернення відхиляється, статус `Returned` і сума не змінюються.

## Генерація

- `@ForAll` з `@LongRange` / `@IntRange`: jqwik сам додає межові значення (edge cases: 100 і 1 000 000, 1 і 20, 1 і 30) і комбінує їх з випадковими (`edge-cases#mode = MIXIN`).
- **Custom generators:**
    - `validRentals`: `Combinators.combine(...)` + `frequencyOf` (частіше межа правила 9/10 днів);
    - `invalidRentals`: рівно одне поле поза межами;
    - `quantityAboveStock`: `flatMap` (спочатку залишок, потім кількість строго більша);
    - `fuzzBodies`: мутаційний fuzz (валідний JSON, у якому одне поле замінене «брудним» значенням або видалене, а також зламаний JSON). «Брудні» значення: довжина 39–42, довільний Unicode, зокрема `\u0000` і `\t`, SQL/HTML-рядки, межі чисел ±1, `Long.MIN/MAX`, дробові, рядок/boolean/масив замість числа, `null`.

## Кількість cases, budget, seed

| Тест | tries |
|---|---|
| Properties 1–4 | 1000 кожна |
| Properties 5–6 (з БД) | 200 кожна |
| Fuzz 1, Fuzz 2 | 500 кожен, тобто budget 1000 HTTP-запитів |

`seed` друкується у звіті jqwik для кожної property; відтворити генерацію можна через `@Property(seed = "...")`. Невдалий sample jqwik зберігає в `.jqwik-database` і при наступному запуску перевіряє його першим (`after-failure = SAMPLE_FIRST`).

## Незалежність cases

`@BeforeTry` виконується перед **кожною** спробою, включно зі спробами shrinking: `TRUNCATE ... RESTART IDENTITY`, тобто БД щоразу порожня (для fuzz ще й створюється одне відоме обладнання `id = 1`). Для Service + doubles стану немає. Тому результат спроби не залежить від попередніх, а shrinking дає коректний мінімальний приклад.

## Знайдені дефекти (fuzz) → regression tests → fix

| # | Fuzz target | seed | Original sample | Shrunk counterexample | Було | Має бути |
|---|---|---|---|---|---|---|
| 1 | `POST /equipment` | `4127983430790827332` | код із 50 випадкових літер | `{"code":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","dailyRate":100,"stock":0}` (41 символ) | 500 `DATABASE_ERROR` | 400 |
| 2 | `POST /rentals` | `3128834812091369811` | `reference = "\u0000"`, 2 шт, 4 дні | `{"reference":"\u0000","equipmentId":1,"quantity":1,"days":1,"premium":true}` | 500 `DATABASE_ERROR` | 400 |
| 3 | `POST /equipment` (інваріант) | `4127983430790827332` | `{"code":"\t","dailyRate":648,"stock":1}` | `{"code":"\t","dailyRate":100,"stock":0}` | 201, збережено порожній код | 400 |

**Причини:**
- 1–2: PostgreSQL відхиляє дані помилкою класу `22` (`22001` — задовгий рядок, `22021` — нульовий символ), а `ApiExceptionHandler` обробляв лише `23xxx`, тому решта ставала 500.
- 3: для коду обладнання немає валідації, а `CHECK(length(trim(code)) > 0)` не допомагає, бо `trim()` у PostgreSQL прибирає лише пробіли, а не табуляцію.

**Deterministic regression / boundary tests** (`@Example` у `RentalApiFuzzTest`):
`regressionTooLongEquipmentCode`, `regressionNullByteInEquipmentCode`, `regressionNullByteInRentalReference`, `regressionBlankEquipmentCode`, `boundaryEquipmentCodeOf40Chars` (40 символів → 201).
Boundary-тести правила (`@Example` у `RentalPropertiesTest`): 9/10 днів, преміальний 10 днів (−12%, а не −20%), мінімальна і максимальна сума, округлення 3330 → 3064.

**Fix:**

`ApiExceptionHandler.database(...)`:
```java
if (state != null && state.startsWith("22")) return error(400, "INVALID_REQUEST", "Некоректні дані ресурсу");
```

`EquipmentController.create(...)`:
```java
if (input.code() == null || input.code().isBlank() || input.code().length() > 40)
    throw new IllegalArgumentException("Некоректний код обладнання");
```

## Результат

`Tests run: 16, Failures: 0`, жодного HTTP 500:
- Fuzz 1: 201 — 59%, 400 — 41%;
- Fuzz 2: 400 — 41%, 409 — 29%, 201 — 28%, 404 — 2%.

Бізнес-відмова (400 / 404 / 409) — не дефект; дефект — це 500, crash, неочікуваний виняток або порушення інваріанту.

**PBT vs fuzz:** PBT перевіряє, що правило / інваріант виконується на багатьох згенерованих *валідних* даних. Fuzz перевіряє *стійкість* системи до некоректних, «брудних» input.
