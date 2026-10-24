import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// 输入只放在已忽略的 .local/performance 中；严禁 --http-debug 或输出响应正文。
const fixture = JSON.parse(open(__ENV.PERF_INPUT));
if (fixture.baseUrl !== 'http://127.0.0.1:18080' || fixture.environment !== 'ticketing-perf-only') {
  throw new Error('压测只允许专用本地环境');
}
const mode = __ENV.PERF_SCENARIO || 'query';
const vus = Number(__ENV.PERF_VUS || 10);
const warmup = Number(__ENV.PERF_WARMUP || 10);
const duration = Number(__ENV.PERF_DURATION || 20);
const admissionRejections = __ENV.PERF_ADMISSION_EXPECTED_REJECTION === 'true';
if (vus < 1 || vus > fixture.users.length || duration < 1 || warmup < 1) throw new Error('压测参数无效');
const latency = new Trend('steady_latency', true);
const requests = new Counter('steady_requests');
const unexpected = new Rate('unexpected_error');
const allUnexpected = new Rate('all_unexpected_error');
const outcomes = new Counter('all_http_outcomes');
const conflicts = new Counter('expected_conflicts');
const orders = new Counter('created_orders');
const acceptedRequests = new Counter('accepted_purchase_requests');
const acceptanceLatency = new Trend('steady_acceptance_latency', true);
const completionLatency = new Trend('steady_purchase_completion_latency', true);
const purchaseFailures = new Counter('purchase_business_failures');
const admissionRejected = new Counter('admission_rejected');
const rateLimited = new Counter('admission_rate_limited');
const paid = new Counter('paid_orders');
const checked = new Counter('checked_tickets');
const idem = new Rate('idempotency_correct');
const serverErrors = new Counter('server_errors');
const transportErrors = new Counter('transport_errors');
const routes = ['activities', 'activity', 'sessions', 'tickets', 'create', 'purchase_status', 'cancel', 'pay', 'waitlist', 'checkin'];
const thresholds = { unexpected_error: ['rate==0'], all_unexpected_error: ['rate==0'], http_req_failed: ['rate==0'], server_errors: ['count==0'], transport_errors: ['count==0'] };
for (const status of [0, 400, 401, 403, 404, 429, 500, 503]) {
  if (!(admissionRejections && status === 429)) thresholds[`all_http_outcomes{status:${status}}`] = ['count==0'];
}
for (const code of ['PURCHASE_GLOBAL_BUSY', 'PURCHASE_TICKET_BUSY']) thresholds[`admission_rate_limited{code:${code}}`] = ['count>=0'];
if (['mixed', 'hot-ample', 'hot-scarce'].includes(mode)) thresholds.idempotency_correct = ['rate==1'];
for (const name of routes) thresholds[`steady_latency{name:${name}}`] = ['p(95)<30000'];
export const options = {
  scenarios: { load: { executor: 'constant-vus', vus, duration: `${warmup + duration}s`, gracefulStop: '150s' } },
  noCookiesReset: true,
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  thresholds,
};
let token;
let allocatedPaid = false;
const expectedCodes = new Set(['TICKET_UNAVAILABLE', 'STOCK_UNAVAILABLE', 'PURCHASE_LIMIT',
  'ORDER_STATE_CONFLICT', 'ORDER_EXPIRED', 'TICKET_ALREADY_USED']);
function isSteady() {
  const elapsed = Date.now() - exec.scenario.startTime;
  return elapsed >= warmup * 1000 && elapsed < (warmup + duration) * 1000;
}

function call(method, path, body, name, extra = {}, measured = true) {
  const response = http.request(method, fixture.baseUrl + '/api/v1' + path,
    body === null ? null : JSON.stringify(body), {
      headers: { 'Content-Type': 'application/json', ...(token ? { [token.headerName]: token.token } : {}), ...extra },
      tags: { name }, timeout: '20s', responseCallback: admissionRejections ? http.expectedStatuses(200, 202, 409, 429) : http.expectedStatuses(200, 202, 409),
    });
  const stable = measured && isSteady();
  let expected = false;
  if (response.status === 409) {
    try { expected = expectedCodes.has(response.json('code')); } catch (_) { /* 解析失败计为非预期错误 */ }
  }
  let backpressure = false;
  try { backpressure = response.status === 503 && response.json('code') === 'PURCHASE_QUEUE_FULL'; } catch (_) { /* 未知503仍失败 */ }
  let admissionCode = '';
  if (admissionRejections && response.status === 429) {
    try {
      const code = response.json('code');
      if (['PURCHASE_GLOBAL_BUSY', 'PURCHASE_TICKET_BUSY'].includes(code)) admissionCode = code;
    } catch (_) { /* 未知限流仍失败 */ }
  }
  const invalid = ![200, 202].includes(response.status) && !expected && !backpressure && !admissionCode;
  outcomes.add(1, { status: String(response.status) });
  allUnexpected.add(invalid);
  serverErrors.add(response.status >= 500 ? 1 : 0);
  transportErrors.add(response.status === 0 ? 1 : 0);
  if (stable && measured) {
    requests.add(1); latency.add(response.timings.duration, { name });
    unexpected.add(invalid);
    if (backpressure) admissionRejected.add(1);
    if (admissionCode) rateLimited.add(1, { code: admissionCode });
    if (expected) conflicts.add(1);
  }
  return response;
}
function login(account) {
  let csrf = http.get(fixture.baseUrl + '/api/v1/auth/csrf', { tags: { name: 'csrf' } });
  if (csrf.status !== 200) throw new Error('获取安全令牌失败');
  token = csrf.json('data');
  const response = http.post(fixture.baseUrl + '/api/v1/auth/login', {
    username: account.username, password: account.password,
  }, { headers: { [token.headerName]: token.token }, tags: { name: 'login' } });
  if (response.status !== 200) throw new Error('测试账号登录失败');
  token = http.get(fixture.baseUrl + '/api/v1/auth/csrf', { tags: { name: 'csrf' } }).json('data');
}
export function setup() {
  login(fixture.admin);
  const info = http.get(fixture.baseUrl + '/api/v1/admin/monitoring/info', { tags: { name: 'environment_guard' } });
  if (info.status !== 200 || info.json('performance.environment') !== 'ticketing-perf-only') throw new Error('专用环境标记不匹配');
  if (mode === 'checkin') {
    login(fixture.users[0]);
    const response = call('GET', `/tickets/${fixture.ticketId}/credential`, null, 'credential', {}, false);
    if (response.status !== 200) throw new Error('真实电子票凭证读取失败');
    return { credential: response.json('data.credential') };
  }
  return {};
}
export default function (data) {
  const index = exec.vu.idInTest - 1;
  if (!token) login(mode === 'checkin' ? fixture.admins[index] : fixture.users[index]);
  const target = mode === 'mixed' ? fixture.mixed[exec.vu.iterationInScenario % fixture.mixed.length] : fixture.target;
  if (mode === 'query') {
    call('GET', '/activities?page=1&size=20', null, 'activities');
    call('GET', `/activities/${target.activityId}`, null, 'activity');
    call('GET', `/activities/${target.activityId}/sessions`, null, 'sessions');
    call('GET', `/activities/${target.activityId}/sessions/${target.sessionId}/tickets`, null, 'tickets');
  } else if (mode === 'checkin') {
    if (Date.now() - exec.scenario.startTime < warmup * 1000) { sleep(0.1); return; }
    const response = call('POST', fixture.staffCheckin === true ? '/checkins' : '/admin/checkins', { credential: data.credential }, 'checkin');
    if (response.status === 200) checked.add(1);
  } else if (mode === 'waitlist') {
    const response = call('GET', `/waitlist/${fixture.waitlistIds[index]}`, null, 'waitlist');
    if (response.status === 200 && response.json('data.status') === 'ALLOCATED' && !allocatedPaid) {
      const result = call('POST', `/orders/${response.json('data.reservedOrderId')}/pay`, null, 'pay');
      if (result.status === 200) { paid.add(1); allocatedPaid = true; }
    }
    sleep(0.1);
  } else {
    const key = `perf-${fixture.runId}-${exec.vu.idInTest}-${exec.vu.iterationInScenario}`;
    const body = { skuId: target.skuId, quantity: 1 };
    const submittedAt = Date.now();
    const response = call('POST', '/orders', body, 'create', { 'Idempotency-Key': key });
    if (![200, 202].includes(response.status)) { sleep(0.1); return; }
    const requestId = response.json('data.requestId');
    if (response.status === 202 && isSteady()) { acceptedRequests.add(1); acceptanceLatency.add(response.timings.duration); }
    if (exec.vu.iterationInScenario % 5 === 0) {
      const replay = call('POST', '/orders', body, 'create', { 'Idempotency-Key': key });
      idem.add([200, 202].includes(replay.status) && replay.json('data.requestId') === requestId);
    }
    let result = response.json('data');
    // 每次购票有限轮询；端到端时间按终态查询完成截取，不以202延迟代替。
    for (let poll = 0; ['QUEUED', 'PROCESSING'].includes(result.status) && poll < 80; poll++) {
      sleep(0.25);
      const status = call('GET', `/purchase-requests/${requestId}`, null, 'purchase_status');
      if (status.status !== 200) { allUnexpected.add(true); return; }
      result = status.json('data');
    }
    const stable = isSteady();
    if (['QUEUED', 'PROCESSING'].includes(result.status)) { allUnexpected.add(true); return; }
    if (stable) completionLatency.add(Date.now() - submittedAt);
    if (result.status !== 'SUCCEEDED') {
      const known = expectedCodes.has(result.failureCode) || result.failureCode === 'QUEUE_EXPIRED';
      unexpected.add(!known);
      allUnexpected.add(!known);
      if (stable) purchaseFailures.add(1, { code: known ? result.failureCode : 'UNKNOWN' });
      return;
    }
    const id = result.orderId;
    if (!id) { allUnexpected.add(true); return; }
    if (stable) orders.add(1);
    if (mode === 'mixed' && exec.vu.iterationInScenario % 3 === 0) {
      const result = call('POST', `/orders/${id}/pay`, null, 'pay');
      if (result.status === 200 && stable) paid.add(1);
    } else call('POST', `/orders/${id}/cancel`, null, 'cancel');
  }
}
export function handleSummary(data) {
  // 只有聚合统计，没有响应、票券凭证、Cookie、账号或 URL 标签。
  delete data.setup_data;
  return { [__ENV.PERF_SUMMARY]: JSON.stringify(data, null, 2) };
}
