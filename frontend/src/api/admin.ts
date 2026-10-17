import { useAuthStore } from '../stores/auth'
import type {
  Activity,
  ActivityInput,
  ActivityQuery,
  ActivitySession,
  Page,
  SessionInput,
} from './activities'

export const adminApi = {
  list: (params: ActivityQuery) =>
    useAuthStore().request<Page<Activity>>({
      url: '/admin/activities',
      params,
    }),
  detail: (id: string) =>
    useAuthStore().request<Activity>({ url: `/admin/activities/${id}` }),
  create: (data: ActivityInput) =>
    useAuthStore().request<Activity>({
      method: 'POST',
      url: '/admin/activities',
      data,
    }),
  update: (id: string, data: ActivityInput) =>
    useAuthStore().request<Activity>({
      method: 'PUT',
      url: `/admin/activities/${id}`,
      data,
    }),
  transition: (id: string, operation: 'publish' | 'offline', version: number) =>
    useAuthStore().request<Activity>({
      method: 'POST',
      url: `/admin/activities/${id}/${operation}`,
      data: { version },
    }),
  sessions: (id: string) =>
    useAuthStore().request<ActivitySession[]>({
      url: `/admin/activities/${id}/sessions`,
    }),
  createSession: (id: string, data: SessionInput) =>
    useAuthStore().request<ActivitySession>({
      method: 'POST',
      url: `/admin/activities/${id}/sessions`,
      data,
    }),
  updateSession: (id: string, data: SessionInput) =>
    useAuthStore().request<ActivitySession>({
      method: 'PUT',
      url: `/admin/sessions/${id}`,
      data,
    }),
  cancelSession: (id: string, version: number) =>
    useAuthStore().request<ActivitySession>({
      method: 'POST',
      url: `/admin/sessions/${id}/cancel`,
      data: { version },
    }),
}
