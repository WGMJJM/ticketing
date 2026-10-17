import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import axios, { type AxiosRequestConfig } from 'axios'
import { sessionRequest, refreshCsrf, type CurrentUser } from '../api/auth'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<CurrentUser | null>(null)
  const initialized = ref(false)
  const sessionError = ref('')
  const authenticated = computed(() => user.value !== null)
  const isAdmin = computed(() => user.value?.role === 'ADMIN')
  let restoring: Promise<void> | undefined

  async function restore(force = false) {
    if (restoring) return restoring
    if (initialized.value && !force) return
    restoring = (async () => {
      sessionError.value = ''
      try {
        user.value = await sessionRequest<CurrentUser>({ url: '/auth/me' })
        initialized.value = true
      } catch (failure) {
        user.value = null
        if (axios.isAxiosError(failure) && failure.response?.status === 401)
          initialized.value = true
        else {
          initialized.value = false
          sessionError.value =
            failure instanceof Error
              ? failure.message
              : '登录状态恢复失败，请重试'
        }
      }
    })()
    try {
      await restoring
    } finally {
      restoring = undefined
    }
  }

  function acceptLogin(current: CurrentUser) {
    user.value = current
    initialized.value = true
    sessionError.value = ''
  }
  async function logout() {
    await request<void>({ method: 'POST', url: '/auth/logout' })
    user.value = null
    initialized.value = true
    sessionError.value = ''
    await refreshCsrf()
  }
  async function request<T>(config: AxiosRequestConfig): Promise<T> {
    try {
      return await sessionRequest<T>(config)
    } catch (failure) {
      if (axios.isAxiosError(failure) && failure.response?.status === 401)
        user.value = null
      if (axios.isAxiosError(failure) && failure.response?.status === 403)
        await restore(true)
      throw failure
    }
  }
  return {
    user,
    initialized,
    sessionError,
    authenticated,
    isAdmin,
    restore,
    acceptLogin,
    logout,
    request,
  }
})
