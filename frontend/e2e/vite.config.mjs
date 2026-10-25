import { defineConfig } from 'vite'

// 仅验收生产dist；不转发到8080开发后端。
export default defineConfig({
  preview: { host: '127.0.0.1', port: 5174, strictPort: true, proxy: { '/api': 'http://127.0.0.1:18080' } },
})
