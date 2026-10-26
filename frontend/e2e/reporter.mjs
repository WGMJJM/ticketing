import fs from 'node:fs'
import path from 'node:path'

// 不导出步骤、请求体、认证响应、错误原文、trace或票券凭证。
export default class SafeReporter {
  rows = []
  started = new Date().toISOString()
  onTestEnd(test, result) {
    const row = { name: test.title, status: result.status, durationMs: result.duration,
      annotations: test.annotations.filter(a => ['browser', 'scope'].includes(a.type)) }
    this.rows.push(row)
    console.log(`${result.status} ${test.title} ${result.duration}ms`)
    // 诊断仅输出源码位置；不输出可能带完整凭证的Playwright调用参数。
    if (result.status !== 'passed') console.log(result.error?.stack?.split('\n').filter(s => /delivery.spec.mjs:\d+/.test(s)).join('\n') ?? '')
  }
  onEnd(result) {
    const label = process.env.TICKETING_E2E_LABEL
    const target = path.resolve('../.local/verification/results', `${label}-browser.json`)
    if (fs.existsSync(target)) throw new Error('浏览器证据已存在，禁止覆盖')
    const app = JSON.parse(fs.readFileSync('../.local/verification/app.json', 'utf8'))
    fs.mkdirSync(path.dirname(target), { recursive: true })
    fs.writeFileSync(target, JSON.stringify({ startedAt: this.started, endedAt: new Date().toISOString(),
      runner: 'Playwright + real Microsoft Edge', environment: 'ticketing-verification-only',
      mockedBusinessResponses: false, jarSha256: app.sha256, result: result.status,
      passed: this.rows.filter(r => r.status === 'passed').length,
      failed: this.rows.filter(r => ['failed', 'timedOut', 'interrupted'].includes(r.status)).length,
      skipped: this.rows.filter(r => r.status === 'skipped').length, tests: this.rows }, null, 2) + '\n')
  }
}
