# SOFTWARE REQUIREMENTS SPECIFICATION (SRS)
Project: EduMind — AI Adaptive Learning Platform for Schoolchildren
Standard: ISO/IEC/IEEE 29148:2018 | Format: Living Documentation (Markdown)
Version: 1.0 (Baseline) | Authors: Tetiana Laptiuk (BA), Nastia Demianchuk (PM)
Client: SmartSchool | Budget: $180,000 | Deadline: 2027-06-01

---

## 1. INTRODUCTION

### 1.1 Purpose
Цей документ визначає повний набір функціональних і нефункціональних вимог до MVP платформи EduMind. Він призначений для команди розробки, QA, спонсора (SmartSchool) і слугує єдиним джерелом істини, що трасується з беклогом Jira (проєкт EDU) та документом BRD (Блоки 1–5).

### 1.2 Project Scope
EduMind — веб-платформа, що збирає поведінкові метрики учня (час відповіді, паузи, спроби) і адаптує формат та складність матеріалу через LLM з кешуванням. У межах MVP: реєстрація з батьківською згодою (COPPA), проходження уроків, адаптивна генерація контенту, мікропідказки, тижневі PDF-звіти батькам, моніторинг збоїв.
Поза межами MVP: веб-камера та біометрія, генерація графіки в реальному часі, навчання власної ML-моделі.

## 2. OVERALL DESCRIPTION

### 2.1 Product Perspective & Constraints
Система — нова веб-платформа, що інтегрується із зовнішніми сервісами: LLM API, сервіс кешу контенту, Email-сервіс (SMTP), сервіс генерації PDF.
Обмеження: фіксований бюджет $180,000; реліз до 2027-06-01; до 300 одночасних сесій і 1000 учнів; повна відповідність COPPA (без камери та біометрії); адаптований контент за ≤3 с.

### 2.2 User Classes and Characteristics
| Роль | Опис | Доступ |
|------|------|--------|
| Учень (до 13 р.) | Проходить уроки, отримує підказки | Лише власні уроки |
| Батько | Створює та підтверджує профілі, отримує звіти | Лише свої діти (до 5) |
| Вчитель | Бачить метрики класу, отримує ескалації | Лише власний клас |
| Адміністратор школи | Імпорт і керування профілями школи | Лише власна школа |
| Адміністратор платформи | Моніторинг збоїв генерації та доставки | Службові журнали |
| Планувальник (автономна система) | Формує й надсилає тижневі звіти | Запуск за розкладом |

### 2.3 Assumptions and Dependencies
- Батьки мають дійсний email для підтвердження згоди.
- Зовнішній LLM-провайдер доступний із таймаутом ≤8 с.
- Часовий пояс розкладу звітів береться зі schools.timezone.
- Параметри відмовостійкості (3 спроби × 10 с, далі кожні 15 хв) узгоджені в ЛР5.

### 2.4 Glossary
| Термін | Визначення |
|--------|-----------|
| COPPA | Children's Online Privacy Protection Act — закон США про захист даних дітей до 13 років |
| LLM | Large Language Model — зовнішня нейромережа для генерації матеріалу |
| FR / NFR | Functional / Non-Functional Requirement |
| EARS | Easy Approach to Requirements Syntax — шаблони формулювання вимог |
| MST | Measurable, Specific, Testable — критерії якості NFR |
| SLA | Service Level Agreement — гарантований рівень доступності |
| RTO / RPO | Recovery Time / Recovery Point Objective — цілі відновлення БД |
| RPS | Requests Per Second — запитів за секунду |
| Pending / Active / Suspended / Archived | Статуси профілю учня |
| Cache hit / miss | Наявність / відсутність матеріалу в кеші |
| idempotency key | Унікальний ключ запиту для захисту від дублів |
| Circuit breaker | Запобіжник: припинення звернень до збійного сервісу |

## 3. FUNCTIONAL REQUIREMENTS (EARS)
Повний реєстр — 35 FR у BRD, розділ 5.1. Нижче 10 ключових вимог за синтаксисом EARS (розділ 5.2).

| ID FR | Тип EARS | Вимога | Jira |
|-------|----------|--------|------|
| FR-DATA-04 | Ubiquitous | The system shall записувати для кожної спроби рядок task_attempts (duration_ms, attempt_no, is_correct). | EDU-33 |
| FR-UI-02 | Ubiquitous | The system shall відображати екран уроку без елемента числової оцінки на кожному кроці. | EDU-21 |
| FR-DATA-01 | WHEN | WHEN батько надсилає форму створення профілю, the system shall перевірити name, grade (1–6) і ліміт 5 профілів перед створенням. | EDU-27 |
| FR-DATA-03 | WHEN | WHEN учень завершує останнє завдання, the system shall встановити status=Completed і повернути лічильник завдань. | EDU-37 |
| FR-AUTH-04 | WHERE | WHERE користувач має роль Teacher, the system shall надавати доступ до таблиці метрик учнів його класу. | EDU-34 |
| FR-UI-05 | WHERE | WHERE учень обрав формат «Інфографіка», the system shall повертати матеріал із material_type=infographic. | EDU-39 |
| FR-ERR-04 | WHILE | WHILE запобіжник кешу відкритий, the system shall видавати шаблони з БД і не звертатися до LLM. | EDU-41 |
| FR-AUTH-02 | WHILE | WHILE профіль у статусі Suspended, the system shall відхиляти запити учня відповіддю 403. | EDU-35 |
| FR-ERR-02 | IF...THEN | IF LLM не відповів за 8 с, THEN the system shall видати шаблон і записати generation_logs=Failed. | EDU-42 |
| FR-ERR-01 | IF...THEN | IF збій між UPDATE профілю і токена, THEN the system shall виконати ROLLBACK (Pending, used=false). | EDU-28 |

## 4. NON-FUNCTIONAL REQUIREMENTS (MST)

| ID NFR | Категорія | Метрика | QA Pass/Fail |
|--------|-----------|---------|--------------|
| NFR-US-01 | Usability | Створення профілю ≤5 кроків, час системних реакцій ≤10 с | ≤5 кроків і ≤10 с → Pass |
| NFR-US-02 | Usability | Запуск уроку ≤3 кліки до першого завдання | ≤3 кліки → Pass |
| NFR-SEC-01 | Security | Паролі — bcrypt cost ≥12, без відкритого тексту | hash $2b$12$ → Pass |
| NFR-SEC-02 | Security | TLS 1.2+; токени — SHA-256 | TLS≥1.2 і 64 hex → Pass |
| NFR-PERF-01 | Performance | 95% API-запитів (крім LLM) ≤500 мс при 300 сесіях | p95 ≤500 мс → Pass |
| NFR-PERF-02 | Scalability | ≥150 RPS (300 сесій × 0,5 зап/с), помилок <1% | error <1% → Pass |
| NFR-AVL-01 | Availability | SLA ≥99.5%/міс (≤216 хв) і ≥99.9% у 08:00–20:00 | обидва ≥ → Pass |
| NFR-AVL-02 | Reliability | RTO ≤60 хв, RPO ≤15 хв | відновлення ≤60/≤15 → Pass |
| NFR-AI-01 | AI-Compliance | LLM-генерація ≤8 с, інакше шаблон | шаблон після 8 с → Pass |
| NFR-AI-02 | AI-Governance | 0% запитів до LLM з PII; лише topic_id | 0 PII → Pass |

## 5. SYSTEM INTEGRATION INTERFACES
Формати обміну з вторинними акторами (JSON).

### 5.1 LLM API — запит на генерацію (деперсоналізований, FR-INT-01 / NFR-AI-02)
Request:
    {
      "topic_id": "math_g5_fractions_02",
      "grade": 5,
      "format": "text",
      "timeout_ms": 8000
    }
Response (200):
    {
      "content": "<lesson_body>",
      "tokens": 742,
      "model": "llm-provider-v1"
    }
Віддаємо назовні: topic_id, grade, format. Ніколи: name, email, student_id.

### 5.2 Email Service — надсилання листа (FR-INT-03 / FR-INT-05)
Request:
    {
      "template": "consent_email",
      "to": "parent@example.com",
      "vars": { "confirm_url": "https://.../confirm?token=<hash>", "ttl_hours": 24 }
    }
Response (202):
    { "message_id": "msg_01H...", "status": "queued" }
Webhook (delivery): { "message_id": "msg_01H...", "status": "delivered|bounced" }

### 5.3 PDF Service — формування звіту (FR-INT-04)
Request:
    {
      "template": "weekly_report",
      "metrics": { "study_time_min": 180, "lessons_done": 7, "correct_rate": 0.82 }
    }
Response (200):
    { "file_url": "https://.../reports/r_01H.pdf", "pages": 2 }

### 5.4 Cache Service — читання матеріалу (FR-INT-02)
Request key: (topic_id, grade, format)
Response hit:  { "content": "<lesson_body>", "cached_at": "2027-05-01T10:00:00Z" }
Response miss: { "status": "miss" }  → тригерить LLM-генерацію (FR-ERR-09 валідація перед записом)

---
END OF SRS — EduMind v1.0 Baseline
