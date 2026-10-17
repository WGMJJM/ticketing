import axios from 'axios'

export const client = axios.create({
  baseURL: '/api/v1',
  timeout: 5000,
  headers: { Accept: 'application/json' },
})
