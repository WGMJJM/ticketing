import axios from 'axios'

export const client = axios.create({
  baseURL: '/api/v1',
  timeout: 5000,
  withCredentials: true,
  headers: { Accept: 'application/json' },
})

client.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    if (axios.isAxiosError(error)) {
      const message: unknown = error.response?.data?.message
      error.message =
        typeof message === 'string' && message.length < 200
          ? message
          : error.code === 'ECONNABORTED'
            ? '请求超时，请稍后重试'
            : '暂时无法连接服务，请稍后重试'
    }
    return Promise.reject(error)
  },
)
