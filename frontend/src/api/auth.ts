import { client } from './client'
import type { AxiosRequestConfig } from 'axios'
import type { ApiResponse } from './system'

export interface CurrentUser {
  id: string
  username: string
  role: 'USER' | 'ADMIN'
}
interface CsrfView {
  headerName: string
  token: string
}
export async function refreshCsrf() {
  return (await client.get<ApiResponse<CsrfView>>('/auth/csrf')).data.data
}
export function loginAccount(username: string, password: string) {
  return sessionRequest<CurrentUser>({
    method: 'POST',
    url: '/auth/login',
    data: new URLSearchParams({ username, password }),
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  })
}
export function registerAccount(username: string, password: string) {
  return sessionRequest<CurrentUser>({
    method: 'POST',
    url: '/auth/register',
    data: { username, password },
  })
}
export async function sessionRequest<T>(
  config: AxiosRequestConfig,
): Promise<T> {
  const method = (config.method ?? 'GET').toUpperCase()
  const csrf = ['GET', 'HEAD', 'OPTIONS'].includes(method)
    ? undefined
    : await refreshCsrf()
  return (
    await client.request<ApiResponse<T>>({
      ...config,
      headers: {
        ...config.headers,
        ...(csrf ? { [csrf.headerName]: csrf.token } : {}),
      },
    })
  ).data.data
}
