import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('./scenarios.js', import.meta.url), 'utf8');
const login = source.slice(source.indexOf('function login(account)'), source.indexOf('export function setup()'));
for (const statuses of [[503, 200, 200], [200, 503, 200], [200, 200, 503], [200, 200, 200]]) {
  let gets = 0;
  const result = vm.runInNewContext(`let token = { token: 'old' }; let failures = 0; let server = 0;
    const loginErrors = { add: n => { failures += n; } };
    const serverErrors = { add: n => { server += n; } };
    const transportErrors = { add() {} }; const allUnexpected = { add() {} };
    ${login}
    let threw = false; try { login({}); } catch { threw = true; }
    ({ token, failures, server, threw });`, {
      fixture: { baseUrl: 'http://127.0.0.1:18080' },
      http: {
        get() { const index = gets++; return { status: statuses[index === 0 ? 0 : 2], json: () => ({ headerName: 'X-CSRF-TOKEN', token: index === 0 ? 'before' : 'after' }) }; },
        post() { return { status: statuses[1] }; },
      },
    });
  const success = statuses.every(status => status === 200);
  assert.equal(result.threw, !success);
  assert.equal(result.failures, success ? 0 : 1);
  assert.equal(result.server, success ? 0 : 1);
  assert.equal(result.token?.token, success ? 'after' : undefined);
}
console.log('4项真实压测登录函数状态单元检查通过');
