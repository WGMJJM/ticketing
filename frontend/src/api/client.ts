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
      const retryAfter = Number(error.response?.headers['retry-after'])
      const protectedRejection =
        error.response?.status === 429 ||
        (error.response?.status === 503 && error.response?.data?.code === 'RATE_LIMIT_UNAVAILABLE')
      error.message =
        protectedRejection
          ? `${error.response?.status === 429 ? '操作过于频繁' : '安全校验服务暂不可用'}，${Number.isInteger(retryAfter) && retryAfter > 0 && retryAfter <= 3600 ? `请在${retryAfter}秒后重试` : '请稍后重试'}`
          : typeof message === 'string' && message.length < 200
          ? message
          : error.code === 'ECONNABORTED'
            ? '请求超时，请稍后重试'
            : '暂时无法连接服务，请稍后重试'
    }
    return Promise.reject(error)
  },
)
