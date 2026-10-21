// Лабораторна робота №7 — сценарій навантаження для k6.
// Один VU повторює бізнес-сценарій: оформити оренду -> прочитати її -> повернути.
// id оренди береться з відповіді першого запиту і передається в наступні.
//
// Запуск: k6 run -e PROFILE=baseline k6/rental-scenario.js   (baseline | load | stress)

import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const PROFILE = __ENV.PROFILE || 'baseline';

// Профілі навантаження — однакові для k6 і JMeter (див. scripts/run.sh)
const PROFILES = {
  baseline: { vus: 2, rampUp: '5s', hold: '55s' },
  load: { vus: 20, rampUp: '15s', hold: '75s' },
  stress: { vus: 100, rampUp: '30s', hold: '60s' },
};
const profile = PROFILES[PROFILE];
if (!profile) {
  throw new Error(`Невідомий профіль "${PROFILE}". Доступні: ${Object.keys(PROFILES).join(', ')}`);
}

const THINK_TIME = 0.5; // пауза між ітераціями, секунд (у JMeter — Constant Timer 500 ms)

export const options = {
  scenarios: {
    rental_flow: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: profile.rampUp, target: profile.vus }, // поступово додаємо користувачів
        { duration: profile.hold, target: profile.vus }, // тримаємо навантаження
      ],
      gracefulRampDown: '5s',
      gracefulStop: '5s',
    },
  },
  // Критерії прийнятності — однакові для всіх трьох профілів
  thresholds: {
    http_req_duration: ['p(95)<500'], // 95% запитів швидші за 500 мс
    http_req_failed: ['rate<0.01'], // менше 1% невдалих HTTP-запитів
    checks: ['rate>0.99'], // більше 99% успішних перевірок
    // ті самі межі окремо для кожної операції — щоб у звіті було видно, яка з них повільніша
    'http_req_duration{name:POST /rentals}': ['p(95)<500'],
    'http_req_duration{name:GET /rentals/{id}}': ['p(95)<500'],
    'http_req_duration{name:POST /rentals/{id}/return}': ['p(95)<500'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'max'],
};

const RUN_ID = Date.now().toString(36); // щоб reference не повторювались між запусками

// Виконується один раз перед тестом: створюємо обладнання, на яке оформлюються оренди
export function setup() {
  const response = http.post(
    `${BASE_URL}/equipment`,
    JSON.stringify({ code: `K6-${PROFILE}-${RUN_ID}`, dailyRate: 10000, stock: 20 }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'POST /equipment (setup)' } },
  );
  if (response.status !== 201) {
    throw new Error(`Не вдалося створити обладнання: ${response.status} ${response.body}`);
  }
  return { equipmentId: response.json('id') };
}

export default function (data) {
  // 1. Оформлення оренди
  const created = http.post(
    `${BASE_URL}/rentals`,
    JSON.stringify({
      reference: `K6-${RUN_ID}-${__VU}-${__ITER}`,
      equipmentId: data.equipmentId,
      quantity: 2,
      days: 12,
      premium: false,
    }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'POST /rentals' } },
  );
  const createdOk = check(created, {
    'POST /rentals -> 201': (r) => r.status === 201,
    'POST /rentals -> є id': (r) => r.status === 201 && r.json('id') > 0,
  });
  if (!createdOk) {
    sleep(THINK_TIME);
    return; // без id наступні запити не надсилаємо
  }
  const rentalId = created.json('id');

  // 2. Читання створеної оренди за id з попередньої відповіді
  const fetched = http.get(`${BASE_URL}/rentals/${rentalId}`, { tags: { name: 'GET /rentals/{id}' } });
  check(fetched, {
    'GET /rentals/{id} -> 200': (r) => r.status === 200,
    'GET /rentals/{id} -> Active': (r) => r.status === 200 && r.json('status') === 'Active',
  });

  // 3. Повернення
  const returned = http.post(`${BASE_URL}/rentals/${rentalId}/return`, null, {
    tags: { name: 'POST /rentals/{id}/return' },
  });
  check(returned, {
    'return -> 200': (r) => r.status === 200,
    'return -> Returned': (r) => r.status === 200 && r.json('status') === 'Returned',
  });

  sleep(THINK_TIME);
}
