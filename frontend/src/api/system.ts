import { client } from './client'

export interface ApiResponse<T> {
  code: string
  message: string
  data: T
}

export interface SystemStatusResponse {
  application: string
  status: 'RUNNING'
}

export async function getSystemStatus(
  signal?: AbortSignal,
): Promise<SystemStatusResponse> {
  const { data } = await client.get<ApiResponse<SystemStatusResponse>>(
    '/system/status',
    { signal },
  )
  if (data.code !== 'OK' || data.data?.status !== 'RUNNING') {
    throw new Error('服务状态响应异常')
  }
  return data.data
}
