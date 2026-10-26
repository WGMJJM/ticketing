import { useAuthStore } from '../stores/auth'
import type { Page } from './activities'
import type { Order } from './orders'

export interface WaitlistEntry {
  id: string
  skuId: string
  sessionId: string
  activityId: string
  activityTitle: string
  sessionTitle: string
  skuName: string
  priceCents: string
  quantity: number
  status: 'WAITING' | 'PROCESSING' | 'ALLOCATED' | 'COMPLETED' | 'CANCELLED' | 'EXPIRED'
  createdAt: string
  expiresAt: string
  reservedOrderId: string | null
  orderStatus: Order['status'] | null
  orderExpiresAt: string | null
  canCancel: boolean
}
export const waitlistStatusText = {
  WAITING: '等待候补',
  PROCESSING: '分配中',
  ALLOCATED: '已分配待支付订单',
  COMPLETED: '候补已完成',
  CANCELLED: '已取消候补',
  EXPIRED: '已过期或失效',
}
export const waitlistApi = {
  join: (skuId: string, quantity: number) =>
    useAuthStore().request<WaitlistEntry>({
      method: 'POST',
      url: '/waitlist',
      data: { skuId, quantity },
    }),
  list: (page: number) =>
    useAuthStore().request<Page<WaitlistEntry>>({
      url: '/waitlist',
      params: { page, size: 10 },
    }),
  detail: (id: string) =>
    useAuthStore().request<WaitlistEntry>({ url: '/waitlist/' + id }),
  cancel: (id: string) =>
    useAuthStore().request<WaitlistEntry>({
      method: 'POST',
      url: '/waitlist/' + id + '/cancel',
    }),
}
