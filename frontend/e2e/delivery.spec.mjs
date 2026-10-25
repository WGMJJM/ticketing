import { test, expect } from '@playwright/test'
import fs from 'node:fs'
import { execFileSync } from 'node:child_process'
import crypto from 'node:crypto'

const fixture = JSON.parse(fs.readFileSync('../.local/stage19/browser-fixture.json', 'utf8'))
if (fixture.environment !== 'ticketing-perf-only') throw new Error('拒绝非隔离浏览器环境')
const buyer = fixture.users[0]
let orderId, refundOrder, credential, ticketId, waitId
test.describe.configure({ mode: 'serial' })

async function login(page, account = buyer, admin = false) {
  // 账号切换先退出已有会话，遵守USER不能进入/admin/login的路由守卫。
  await page.goto('/login')
  await api(page, 'POST', '/auth/logout')
  await page.goto(admin ? '/admin/login' : '/login')
  await page.getByLabel('用户名', { exact: true }).fill(account.username)
  await page.getByLabel('密码', { exact: true }).fill(account.password)
  await page.getByRole('button', { name: admin ? '登录运营工作台' : '登录', exact: true }).click()
  await expect(page).toHaveURL(admin ? /\/admin\/activities$/ : /\/profile$/)
}
async function api(page, method, url, data, key) {
  return page.evaluate(async ({ method, url, data, key }) => {
    const headers = { 'Content-Type': 'application/json' }
    if (method !== 'GET') {
      const csrf = (await (await fetch('/api/v1/auth/csrf')).json()).data
      headers[csrf.headerName] = csrf.token
    }
    if (key) headers['Idempotency-Key'] = key
    const r = await fetch('/api/v1' + url, { method, headers, body: data === undefined ? undefined : JSON.stringify(data) })
    return { status: r.status, body: await r.json() }
  }, { method, url, data, key })
}
async function select(page, target = fixture.main) {
  await page.goto('/activities/' + target.activityId)
  await page.getByRole('button', { name: new RegExp(target.title) }).click()
  await expect(page.locator('.ticket-card')).toBeVisible()
}
async function purchase(page) {
  await select(page)
  const accepted = page.waitForResponse(r => r.url().endsWith('/api/v1/orders') && r.request().method() === 'POST')
  await page.getByRole('button', { name: '加入抢票排队', exact: true }).click()
  await page.getByRole('button', { name: '确认加入抢票排队', exact: true }).click()
  expect((await accepted).status()).toBe(202)
  await expect(page).toHaveURL(/\/orders\/\d+$/, { timeout: 30000 })
  return page.url().split('/').pop()
}
async function pay(page) {
  await page.getByRole('button', { name: '立即支付', exact: true }).click()
  await page.getByRole('button', { name: '确认模拟支付', exact: true }).click()
  await expect(page.locator('.order-card')).toHaveAttribute('data-status', 'PAID')
  await expect(page.getByRole('heading', { name: '模拟支付流水' })).toBeVisible()
}
async function cancel(page) {
  await page.getByRole('button', { name: '取消订单', exact: true }).click()
  await page.getByRole('button', { name: '确认取消订单', exact: true }).click()
  await expect(page.locator('.order-card')).toHaveAttribute('data-status', 'CANCELLED')
}
function control(action) {
  execFileSync('python', ['-B', 'scripts/performance/final_browser_fixture.py', '--action', action],
    { cwd: '..', stdio: 'pipe', windowsHide: true, timeout: 120000 })
}
test.beforeEach(async ({ browser, page }, info) => {
  info.annotations.push({ type: 'browser', description: browser.version() })
  page.on('pageerror', error => console.log('浏览器脚本异常', error.name,
    error.message.replace(/https?:\/\/\S+/g, '[resource-url]').replace(/TKT1\.\S+/g, '[redacted]').slice(0, 160)))
  page.on('requestfailed', request => {
    if (new URL(request.url()).pathname.startsWith('/assets/')) console.log('静态资源加载失败', new URL(request.url()).pathname)
  })
})
test.beforeAll(async ({ request }) => {
  const origin = 'http://127.0.0.1:5174'
  const csrf = (await (await request.get(origin + '/api/v1/auth/csrf')).json()).data
  const authenticated = await request.post(origin + '/api/v1/auth/login', {
    form: fixture.admin, headers: { [csrf.headerName]: csrf.token },
  })
  expect(authenticated.status()).toBe(200)
  const info = await request.get(origin + '/api/v1/admin/monitoring/info')
  expect((await info.json()).performance.environment).toBe('ticketing-perf-only')
  const fresh = (await (await request.get(origin + '/api/v1/auth/csrf')).json()).data
  await request.post(origin + '/api/v1/auth/logout', { headers: { [fresh.headerName]: fresh.token } })
})
test.afterEach(async ({ page }, info) => {
  if (info.status !== info.expectedStatus && !page.isClosed()) {
    await page.screenshot({ path: info.outputPath('failure-redacted.png'), fullPage: true, timeout: 5000, animations: 'disabled',
      mask: [page.locator('input,textarea,.ticket-qr,.ticket-number')] }).catch(() => {})
  }
})

test('01 公开查询 注册登录 刷新登出 越权跳转和移动宽度', async ({ page }) => {
  await select(page)
  await expect(page.getByRole('button', { name: '登录后购票' })).toBeVisible()
  await select(page, fixture.waitlist)
  await expect(page.locator('.ticket-sale-label')).toContainText(['已售罄'])
  await page.goto('/orders')
  await expect(page).toHaveURL(/\/login\?redirect=/)
  await page.goto('/register')
  await page.getByLabel('用户名', { exact: true }).fill(buyer.username)
  await page.getByLabel('密码', { exact: true }).fill(buyer.password)
  await page.getByLabel('确认密码', { exact: true }).fill(buyer.password)
  await page.getByRole('button', { name: '注册账号' }).click()
  await expect(page).toHaveURL(/\/login\?registered=1/)
  await login(page)
  await page.reload()
  await expect(page.getByText(buyer.username, { exact: true }).first()).toBeVisible()
  await page.goto('/admin/orders')
  await expect(page).toHaveURL(/\/profile\?denied=1/)
  await page.setViewportSize({ width: 390, height: 844 })
  await select(page)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 2)).toBe(true)
  await page.goto('/profile')
  await page.getByRole('button', { name: '退出登录', exact: true }).click()
  await page.goto('/orders')
  await expect(page).toHaveURL(/\/login/)
})

test('02 普通202购票 丢失响应后刷新恢复 模拟支付与二维码不持久化', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await login(page)
  await select(page)
  // 真实后端已提交后丢弃网络响应；不伪造任何业务响应。
  await page.route('**/api/v1/orders', async route => {
    if (route.request().method() !== 'POST') return route.continue()
    const response = await route.fetch()
    expect(response.status()).toBe(202)
    await route.abort('connectionreset')
  })
  await page.getByRole('button', { name: '加入抢票排队', exact: true }).click()
  await page.getByRole('button', { name: '确认加入抢票排队' }).click()
  await expect(page).toHaveURL(/\/orders\/\d+$/, { timeout: 30000 })
  orderId = page.url().split('/').pop()
  await page.unroute('**/api/v1/orders')
  await page.reload()
  await pay(page)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 2)).toBe(true)
  await page.getByRole('link', { name: '查看本单电子票' }).click()
  await page.getByRole('link', { name: '查看电子票与二维码' }).click()
  await expect(page).toHaveURL(/\/tickets\/\d+$/)
  ticketId = page.url().split('/').pop()
  const response = page.waitForResponse(r => r.url().endsWith('/credential'))
  await page.getByRole('button', { name: '展示入场二维码' }).click()
  const secretResponse = await response
  expect(secretResponse.headers()['cache-control']).toContain('no-store')
  credential = (await secretResponse.json()).data.credential
  await expect(page.getByAltText('该张电子票的独立入场二维码')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 2)).toBe(true)
  expect(page.url().includes('TKT1.')).toBe(false)
  expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }).includes('TKT1.'))).toBe(false)
  await page.goto('/profile')
  expect(await page.locator('.ticket-qr img').count()).toBe(0)
})

test('03 同键同参重放 异参409 限额失败 主动取消与本人边界', async ({ page }) => {
  await login(page)
  const key = crypto.randomUUID()
  const data = { skuId: fixture.main.skuId, quantity: 1 }
  const first = await api(page, 'POST', '/orders', data, key)
  const replay = await api(page, 'POST', '/orders', data, key)
  expect(first.status).toBe(202)
  expect(replay.body.data.requestId).toBe(first.body.data.requestId)
  const conflict = await api(page, 'POST', '/orders', { ...data, quantity: 2 }, key)
  expect(conflict.status).toBe(409)
  expect(conflict.body.code).toBe('IDEMPOTENCY_CONFLICT')
  await page.goto('/purchase-requests/' + first.body.data.requestId)
  await expect(page).toHaveURL(/\/orders\/\d+$/, { timeout: 30000 })
  await cancel(page)
  const limited = await api(page, 'POST', '/orders', { ...data, quantity: 4 }, crypto.randomUUID())
  expect(limited.status).toBe(202)
  await expect.poll(async () => (await api(page, 'GET', '/purchase-requests/' + limited.body.data.requestId)).body.data.failureCode).toBe('PURCHASE_LIMIT')
  await login(page, fixture.users[1])
  expect((await api(page, 'GET', '/orders/' + orderId)).status).toBe(404)
  expect((await api(page, 'GET', '/tickets/' + ticketId)).status).toBe(404)
})

test('04 管理员授权工作人员 核销一次 重复拒绝 撤权与停用旧会话', async ({ page, browser }) => {
  await login(page, fixture.admin, true)
  const info = await api(page, 'GET', '/admin/monitoring/info')
  expect(info.body.performance.environment).toBe('ticketing-perf-only')
  await page.goto('/admin/staff')
  await page.getByLabel('用户名', { exact: true }).fill(fixture.users[4].username)
  await page.getByRole('button', { name: '查询账号' }).click()
  await page.getByRole('button', { name: '授权工作人员', exact: true }).click()
  await page.getByRole('button', { name: '确认变更' }).click()
  await expect(page.getByRole('button', { name: '撤销工作人员角色' })).toBeVisible()
  await page.getByRole('button', { name: '场次授权', exact: true }).click()
  await page.getByLabel('场次编号', { exact: true }).fill(fixture.main.sessionId)
  await page.getByRole('button', { name: '分配核销场次' }).click()
  await page.getByRole('button', { name: '确认操作', exact: true }).click()
  await expect(page.getByRole('button', { name: '撤销场次授权' })).toBeVisible()
  const context = await browser.newContext()
  const staff = await context.newPage()
  try {
    await login(staff, fixture.users[4])
    await staff.goto('/staff/checkin')
    await staff.getByLabel('完整电子票凭证').fill(credential)
    await staff.getByRole('button', { name: '确认核销入场' }).click()
    await expect(staff.getByText('核销成功', { exact: false }).first()).toBeVisible()
    await staff.getByLabel('完整电子票凭证').fill(credential)
    await staff.getByRole('button', { name: '确认核销入场' }).click()
    await expect(staff.getByRole('alert')).toContainText('已核销')
    await page.getByRole('button', { name: '撤销场次授权' }).click()
    await page.getByRole('button', { name: '确认操作', exact: true }).click()
    expect((await api(staff, 'POST', '/checkins', { credential })).status).toBe(403)
    await page.getByRole('button', { name: '停用工作人员' }).click()
    await page.getByRole('button', { name: '确认变更' }).click()
    await expect(page.getByRole('cell', { name: '已停用', exact: true })).toBeVisible()
    expect((await api(staff, 'GET', '/auth/me')).status).toBe(401)
  } finally { await context.close() }
})

test('05 管理订单退款及已核销人工待办 买家回看支付事实', async ({ page }) => {
  await login(page)
  refundOrder = await purchase(page)
  await pay(page)
  await login(page, fixture.admin, true)
  await page.goto('/admin/orders/' + refundOrder)
  await page.getByRole('button', { name: '整单模拟退款', exact: true }).click()
  await page.getByRole('button', { name: '确认模拟退款', exact: true }).click()
  await expect(page.locator('.order-card')).toHaveAttribute('data-status', 'REFUNDED')
  await page.goto('/admin/orders/' + orderId)
  await page.getByRole('button', { name: '整单模拟退款', exact: true }).click()
  await page.getByRole('button', { name: '确认模拟退款', exact: true }).click()
  await expect(page.locator('.aftercare-state')).toHaveAttribute('data-status', 'MANUAL_REQUIRED')
  await expect(page.locator('.order-card')).toHaveAttribute('data-status', 'PAID')
  await login(page)
  await page.goto('/orders/' + refundOrder)
  await expect(page.getByRole('heading', { name: '整单模拟退款已完成' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '模拟支付流水' })).toBeVisible()
})

test('06 真实候补入队 队首不足不越过 释放后FIFO分配并支付', async ({ page, browser }) => {
  await login(page, fixture.users[1])
  await select(page, fixture.waitlist)
  await page.getByLabel('FIFO候补场次票档候补数量').fill('2')
  await page.getByRole('button', { name: '申请候补', exact: true }).click()
  await page.getByRole('button', { name: '确认申请候补' }).click()
  await expect(page).toHaveURL(/\/waitlist\/\d+$/)
  waitId = page.url().split('/').pop()
  const context = await browser.newContext()
  const second = await context.newPage()
  try {
    await login(second, fixture.users[2])
    await select(second, fixture.waitlist)
    await second.getByRole('button', { name: '申请候补', exact: true }).click()
    await second.getByRole('button', { name: '确认申请候补' }).click()
    await expect(second).toHaveURL(/\/waitlist\/\d+$/)
    const secondId = second.url().split('/').pop()
    await login(second, fixture.users[3])
    await second.goto('/orders/' + fixture.holderOrders[0])
    await cancel(second)
    // 真实补偿扫描/消息处理期间队首仍需2张，后面的1张申请不得分配。
    await expect.poll(async () => (await api(page, 'GET', '/waitlist/' + waitId)).body.data.status).toBe('WAITING')
    await second.goto('/orders/' + fixture.holderOrders[1])
    await cancel(second)
    await expect.poll(async () => (await api(page, 'GET', '/waitlist/' + waitId)).body.data.status, { timeout: 30000 }).toBe('ALLOCATED')
    await page.reload()
    await page.getByRole('link', { name: /查看候补订单/ }).click()
    await pay(page)
    await page.goto('/waitlist/' + waitId)
    await expect(page.getByText('候补订单已完成模拟支付', { exact: false })).toBeVisible()
    await login(second, fixture.users[2])
    await second.goto('/waitlist/' + secondId)
    await expect(second.getByRole('button', { name: '取消候补', exact: true })).toBeVisible()
    await second.getByRole('button', { name: '取消候补', exact: true }).click()
    await second.getByRole('button', { name: '确认取消候补' }).click()
    await expect(second.getByText('候补已取消', { exact: true })).toBeVisible()
  } finally { await context.close() }
})

test('07 管理员从页面创建发布活动 场次票档及库存调整', async ({ page }) => {
  await login(page, fixture.admin, true)
  const title = '界面管理验收 ' + fixture.run
  const beijing = minutes => new Date(Date.now() + minutes * 60000 + 8 * 3600000).toISOString().slice(0, 16)
  await page.getByRole('button', { name: '创建活动', exact: true }).click()
  let dialog = page.getByRole('dialog').last()
  await dialog.getByLabel('活动名称', { exact: true }).fill(title)
  await dialog.getByLabel('举办城市').fill('上海')
  await dialog.getByLabel('场馆与详细地点').fill('独立浏览器验收场馆')
  await dialog.getByLabel('活动简介').fill('真实页面创建')
  await dialog.getByLabel('活动详情').fill('仅隔离演示使用')
  await dialog.getByRole('button', { name: '保存活动' }).click()
  await expect(dialog).not.toBeVisible()
  await page.getByPlaceholder('搜索活动名称').fill(title)
  await page.getByRole('button', { name: '筛选', exact: true }).click()
  await page.getByRole('button', { name: '发布', exact: true }).click()
  await page.getByRole('button', { name: '确认发布', exact: true }).click()
  await page.getByRole('button', { name: '场次', exact: true }).click()
  dialog = page.getByRole('dialog').last()
  await dialog.getByRole('button', { name: '新增场次', exact: true }).click()
  await dialog.getByLabel('场次名称').fill('页面新增场次')
  await dialog.getByLabel('开始时间（北京时间）', { exact: true }).fill(beijing(60))
  await dialog.getByLabel('结束时间（北京时间）', { exact: true }).fill(beijing(180))
  await dialog.getByLabel('开售时间（北京时间）', { exact: true }).fill(beijing(-30))
  await dialog.getByRole('button', { name: '保存场次' }).click()
  await dialog.getByRole('button', { name: '票档管理' }).click()
  dialog = page.getByRole('dialog').last()
  await dialog.getByRole('button', { name: '新增票档' }).click()
  await dialog.getByLabel('票档名称').fill('页面新增票档')
  await dialog.getByLabel('票价（人民币元）').fill('100.00')
  await dialog.getByLabel('总库存（张）').fill('10')
  await dialog.getByLabel('单笔限购数量（张）').fill('4')
  await dialog.getByLabel('票档开售时间（北京时间）', { exact: true }).fill(beijing(-20))
  await dialog.getByLabel('票档停售时间（北京时间）', { exact: true }).fill(beijing(50))
  await dialog.getByRole('button', { name: '保存票档' }).click()
  await dialog.getByRole('button', { name: '启用销售', exact: true }).click()
  await page.getByRole('button', { name: '确认启用销售' }).click()
  await dialog.getByRole('button', { name: '调整库存' }).click()
  await dialog.getByLabel('总库存（张）').fill('13')
  await dialog.getByRole('button', { name: '保存票档' }).click()
  await expect(dialog.getByText('可售 13', { exact: true })).toBeVisible()
})

test('08 场次取消逐单善后及管理审计页面', async ({ page }) => {
  await login(page, fixture.admin, true)
  await page.getByPlaceholder('搜索活动名称').fill(fixture.activity.title)
  await page.getByRole('button', { name: '筛选', exact: true }).click()
  await page.getByRole('button', { name: '场次', exact: true }).click()
  const row = page.getByRole('row').filter({ hasText: fixture.aftercare.title })
  await row.getByRole('button', { name: '票档管理' }).click()
  await expect(page.getByText('总量 10', { exact: false })).toBeVisible()
  const ticketDialog = page.getByRole('dialog').last()
  await ticketDialog.getByRole('button', { name: '关闭此对话框', exact: true }).click()
  await row.getByRole('button', { name: '取消场次' }).click()
  await page.getByRole('button', { name: '确认取消场次' }).click()
  await expect.poll(async () => (await api(page, 'GET', '/admin/orders/' + fixture.aftercareOrder)).body.data.summary.order.status, { timeout: 30000 }).toBe('REFUNDED')
  await page.goto('/admin/orders/' + fixture.aftercareOrder)
  await expect(page.locator('.aftercare-state')).toHaveAttribute('data-status', 'COMPLETED')
  await page.goto('/admin/audit-logs')
  await expect(page.getByRole('heading', { name: '管理操作审计' })).toBeVisible()
  await expect(page.locator('tbody tr').first()).toBeVisible()
})

test('09 数据库截止后订单真实过期且页面禁用支付', async ({ page }) => {
  control('order-timeout')
  try {
    await login(page)
    const id = await purchase(page)
    await expect.poll(async () => (await api(page, 'GET', '/orders/' + id)).body.data.status,
      { timeout: 75000, intervals: [1000] }).toBe('EXPIRED')
    await page.reload()
    await expect(page.locator('.order-card')).toHaveAttribute('data-status', 'EXPIRED')
    await expect(page.getByRole('button', { name: '立即支付', exact: true })).toHaveCount(0)
  } finally { control('resume') }
})

test('10 消费者停止时202排队 刷新恢复及真实排队截止', async ({ page }) => {
  control('queue-timeout')
  try {
    await login(page)
    await select(page)
    await page.getByRole('button', { name: '加入抢票排队', exact: true }).click()
    await page.getByRole('button', { name: '确认加入抢票排队' }).click()
    await expect(page).toHaveURL(/\/purchase-requests\/\d+$/)
    await page.reload()
    await expect(page.getByRole('heading', { name: '排队已超时', exact: true })).toBeVisible({ timeout: 20000 })
    const result = await api(page, 'GET', '/purchase-requests/' + page.url().split('/').pop())
    expect(result.body.data.status).toBe('EXPIRED')
    expect(result.body.data.orderId).toBeNull()
  } finally { control('resume') }
})
