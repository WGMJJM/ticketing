import { defineConfig } from '@playwright/test'

const label = process.env.TICKETING_E2E_LABEL
if (!/^[A-Za-z0-9_-]{1,40}$/.test(label ?? '')) throw new Error('必须使用唯一的 TICKETING_E2E_LABEL')

export default defineConfig({
  testDir: './e2e',
  testMatch: 'delivery.spec.mjs',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 120000,
  expect: { timeout: 15000 },
  reporter: [['./e2e/reporter.mjs']],
  outputDir: `../.local/verification/e2e-${label}`,
  use: {
    baseURL: 'http://127.0.0.1:5174',
    browserName: 'chromium',
    channel: 'msedge',
    viewport: { width: 1440, height: 1000 },
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    trace: 'off',
    video: 'off',
    screenshot: 'off',
    actionTimeout: 15000,
    navigationTimeout: 15000,
  },
  webServer: {
    command: 'node node_modules/vite/bin/vite.js preview --config e2e/vite.config.mjs',
    url: 'http://127.0.0.1:5174',
    reuseExistingServer: false,
    timeout: 30000,
  },
})
