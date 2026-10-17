import { useAdminStore } from '../stores/admin'
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
    useAdminStore().request<Page<Activity>>({
      url: '/admin/activities',
      params,
    }),
  detail: (id: string) =>
    useAdminStore().request<Activity>({ url: `/admin/activities/${id}` }),
  create: (data: ActivityInput) =>
    useAdminStore().request<Activity>({
      method: 'POST',
      url: '/admin/activities',
      data,
    }),
  update: (id: string, data: ActivityInput) =>
    useAdminStore().request<Activity>({
      method: 'PUT',
      url: `/admin/activities/${id}`,
      data,
    }),
  transition: (id: string, operation: 'publish' | 'offline', version: number) =>
    useAdminStore().request<Activity>({
      method: 'POST',
      url: `/admin/activities/${id}/${operation}`,
      data: { version },
    }),
  sessions: (id: string) =>
    useAdminStore().request<ActivitySession[]>({
      url: `/admin/activities/${id}/sessions`,
    }),
  createSession: (id: string, data: SessionInput) =>
    useAdminStore().request<ActivitySession>({
      method: 'POST',
      url: `/admin/activities/${id}/sessions`,
      data,
    }),
  updateSession: (id: string, data: SessionInput) =>
    useAdminStore().request<ActivitySession>({
      method: 'PUT',
      url: `/admin/sessions/${id}`,
      data,
    }),
  cancelSession: (id: string, version: number) =>
    useAdminStore().request<ActivitySession>({
      method: 'POST',
      url: `/admin/sessions/${id}/cancel`,
      data: { version },
    }),
}
