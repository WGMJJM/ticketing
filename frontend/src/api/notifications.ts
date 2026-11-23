import type { Page } from './activities'
import { useAuthStore } from '../stores/auth'

export interface TicketNotification {
  id: string
  kind: 'WAITLIST_ALLOCATED' | 'SESSION_CANCELLED'
  orderId: string
  activityTitle: string
  sessionTitle: string
  createdAt: string
  readAt: string | null
  paymentDeadline: string | null
  paymentAvailable: boolean
}

const url = '/me/notifications'

export const notificationApi = {
  list: (page: number, unread: boolean, signal: AbortSignal) =>
    useAuthStore().request<Page<TicketNotification>>({ url, params: { page, unread }, signal }),
  unreadCount: (signal: AbortSignal) =>
    useAuthStore().request<number>({ url: url + '/unread-count', signal }),
  read: (id: string) =>
    useAuthStore().request<void>({ url: url + '/' + id + '/read', method: 'PUT' }),
  readAll: () =>
    useAuthStore().request<void>({ url: url + '/read', method: 'PUT' }),
}
