import { defineStore } from 'pinia'
import { ref } from 'vue'
import axios, { type AxiosRequestConfig } from 'axios'
import { client } from '../api/client'
import type { ApiResponse } from '../api/system'

interface AdminAccess {
  username: string
  csrfHeader: string
  csrfToken: string
}

export const useAdminStore = defineStore('admin', () => {
  const username = ref('')
  const authenticated = ref(false)
  let authorization = ''
  let csrfHeader = ''
  let csrfToken = ''

  function clear() {
    username.value = ''
    authenticated.value = false
    authorization = ''
    csrfHeader = ''
    csrfToken = ''
  }

  async function login(name: string, password: string) {
    clear()
    const bytes = new TextEncoder().encode(`${name}:${password}`)
    const credentials = `Basic ${btoa(String.fromCharCode(...bytes))}`
    const response = await client.get<ApiResponse<AdminAccess>>(
      '/admin/session',
      { headers: { Authorization: credentials } },
    )
    authorization = credentials
    csrfHeader = response.data.data.csrfHeader
    csrfToken = response.data.data.csrfToken
    username.value = response.data.data.username
    authenticated.value = true
  }

  async function request<T>(config: AxiosRequestConfig): Promise<T> {
    if (!authenticated.value) throw new Error('请先完成管理员认证')
    try {
      const response = await client.request<ApiResponse<T>>({
        ...config,
        headers: {
          ...config.headers,
          Authorization: authorization,
          [csrfHeader]: csrfToken,
        },
      })
      return response.data.data
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.status === 401) clear()
      throw error
    }
  }
  return { username, authenticated, login, clear, request }
})
