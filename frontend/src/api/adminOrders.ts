import { useAuthStore } from '../stores/auth'
import type { Page } from './activities'
import type { Order, Refund, RefundReason } from './orders'

export interface AdminOrder {
  order: Order
  userId: string
  username: string
  paidAt: string | null
}
export interface Aftercare {
  status: 'RETRY' | 'MANUAL_REQUIRED' | 'COMPLETED'
  reasonCode: string
  message: string
  attemptCount: number
  lastAttemptAt: string
  nextRetryAt: string | null
}
export interface AdminOrderDetail {
  summary: AdminOrder
  waitlist: { id: string; status: string; reservedOrderId: string; skuId: string; quantity: number } | null
  tickets: { total: number; unused: number; used: number; voided: number }
  refund: Refund | null
  aftercare: Aftercare | null
}
export interface AdminOrderQuery {
  orderNo?: string
  userId?: string
  username?: string
  activityId?: string
  sessionId?: string
  status?: Order['status'] | ''
  createdFrom?: string
  createdTo?: string
  page: number
  size: number
}
export const aftercareStatusText = { RETRY: '等待重试', MANUAL_REQUIRED: '待人工处理', COMPLETED: '善后已完成' }
export const adminOrdersApi = {
  list: (query: AdminOrderQuery) => useAuthStore().request<Page<AdminOrder>>({ url: '/admin/orders', params: query }),
  detail: (id: string) => useAuthStore().request<AdminOrderDetail>({ url: '/admin/orders/' + id }),
  refund: (id: string, reason: RefundReason) => useAuthStore().request<{ status: 'SUCCEEDED' | 'MANUAL_REQUIRED'; refund: Refund | null; aftercare: Aftercare | null }>({
    method: 'POST', url: '/admin/orders/' + id + '/refund', data: { reason },
  }),
}
