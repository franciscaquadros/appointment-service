import http from 'k6/http';
import encoding from 'k6/encoding';
import exec from 'k6/execution';
import crypto from 'k6/crypto';
import { check, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const base = __ENV.BASE_URL || 'http://app:8080';
const rate = Number(__ENV.RATE_PER_MINUTE || 100);
const duration = __ENV.DURATION || '3m';
const total = new Counter('baseline_requests');
const errors = new Rate('baseline_unexpected_errors');
const latency = new Trend('baseline_latency', true);
const created = new Counter('baseline_created');
const conflicts = new Counter('baseline_expected_conflicts');
const auth = { Authorization: `Basic ${encoding.b64encode('demo-admin:demo-admin-only')}` };

export const options = {
  scenarios: {
    warmup: { executor: 'constant-arrival-rate', exec: 'traffic', rate: 60,
      timeUnit: '1m', duration: '30s', preAllocatedVUs: 5, maxVUs: 20, gracefulStop: '10s' },
    measured: { executor: 'constant-arrival-rate', exec: 'traffic', startTime: '40s',
      rate, timeUnit: '1m', duration, preAllocatedVUs: 10, maxVUs: 40, gracefulStop: '10s' },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max', 'count'],
  thresholds: {
    baseline_requests: ['count>0'],
    baseline_created: ['count>0'],
    baseline_expected_conflicts: ['count>0'],
    baseline_unexpected_errors: ['rate<0.01'],
    baseline_latency: ['p(95)<500', 'p(99)<1000'],
    'baseline_latency{endpoint:create}': ['p(95)<500', 'p(99)<1000'],
    'baseline_latency{endpoint:list}': ['p(95)<500', 'p(99)<1000'],
    'baseline_latency{endpoint:detail}': ['p(95)<500', 'p(99)<1000'],
    'baseline_latency{endpoint:conflict}': ['p(95)<500', 'p(99)<1000'],
    dropped_iterations: ['count==0'],
    checks: ['rate==1'],
  },
};

function uuid() {
  const h = crypto.randomBytes(16);
  const hex = Array.from(new Uint8Array(h), b => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function booking(email, startsAt, name) {
  return http.post(`${base}/api/v1/appointments`, JSON.stringify({
    patientName: 'Synthetic Load Patient', patientEmail: email,
    specialtyCode: 'CARDIOLOGY', startsAt,
  }), { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': uuid() },
    tags: { name }, responseCallback: http.expectedStatuses(name === 'conflict' ? 409 : 201), timeout: '5s' });
}

export function setup() {
  const start = new Date();
  start.setUTCDate(start.getUTCDate() + 2);
  start.setUTCHours(10, 0, 0, 0);
  const response = booking('load-conflict@example.invalid', start.toISOString(), 'setup');
  if (response.status !== 201) fail(`Fixture booking failed: ${response.status} ${response.body}`);
  return { conflictStart: start.toISOString(), reference: response.json('id'),
    nextYear: new Date().getUTCFullYear() + 1 };
}

export function traffic(data) {
  const measured = exec.scenario.name === 'measured';
  const i = exec.scenario.iterationInTest;
  const kind = i % 10;
  let response, endpoint, expected;
  if (kind < 5) {
    // Unique half-hour slots, 10:00–15:00 UTC, also valid during Lisbon DST.
    // Distinct warmup/measured dates prevent fixture collisions.
    const slot = i + (measured ? 10000 : 0);
    const start = new Date(Date.UTC(data.nextYear, 0, 1 + Math.floor(slot / 10),
      10, (slot % 10) * 30));
    endpoint = 'create'; expected = 201;
    response = booking(`load-${measured ? 'main' : 'warmup'}-${i}@example.invalid`,
      start.toISOString(), endpoint);
  } else if (kind < 7) {
    endpoint = 'list'; expected = 200;
    response = http.get(`${base}/api/v1/appointments?limit=20`,
      { headers: auth, tags: { name: endpoint }, timeout: '5s' });
  } else if (kind < 9) {
    endpoint = 'detail'; expected = 200;
    response = http.get(`${base}/api/v1/appointments/${data.reference}`,
      { headers: auth, tags: { name: endpoint }, timeout: '5s' });
  } else {
    endpoint = 'conflict'; expected = 409;
    response = booking('load-conflict@example.invalid', data.conflictStart, endpoint);
  }
  let valid = response.status === expected;
  if (valid) {
    try {
      const body = response.json();
      if (endpoint === 'create') valid = !!body.id && body.status === 'CONFIRMED';
      if (endpoint === 'list') valid = Array.isArray(body.items) && body.items.length === 20 && typeof body.nextCursor === 'string';
      if (endpoint === 'detail') valid = body.id === data.reference;
      if (endpoint === 'conflict') valid = body.code === 'PATIENT_ALREADY_BOOKED';
    } catch (_) { valid = false; }
  }
  check(response, { 'expected status and response body': () => valid });
  if (measured) {
    total.add(1);
    errors.add(!valid);
    latency.add(response.timings.duration, { endpoint });
    created.add(endpoint === 'create' && valid ? 1 : 0);
    conflicts.add(endpoint === 'conflict' && valid ? 1 : 0);
  }
}

export function handleSummary(data) {
  return { stdout: JSON.stringify(data, null, 2) + '\n' };
}
