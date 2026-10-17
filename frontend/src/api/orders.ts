import { useAuthStore } from '../stores/auth'
import type { Page } from './activities'
export interface PurchaseLimit {
  maxPerSession: number
  usedQuantity: number
  remainingQuantity: number
}
export interface Order {
  id: string
  orderNo: string
  activityId: string
  activityTitle: string
  sessionId: string
  sessionTitle: string
  sessionStartsAt: string
  skuId: string
  skuName: string
  quantity: number
  unitPriceCents: string
  totalAmountCents: string
  status: 'PENDING' | 'PAID' | 'CANCELLED' | 'EXPIRED'
  createdAt: string
  expiresAt: string
  paymentDeadlinePassed: boolean
  canCancel: boolean
  canPay: boolean
  payment: Payment | null
  serverTime: string
}
export interface Payment {
  orderId: string
  paymentNo: string
  amountCents: string
  status: 'SUCCEEDED'
  paidAt: string
  simulated: boolean
}
export const orderStatusText = {
  PENDING: '待支付',
  PAID: '已支付',
  CANCELLED: '已取消',
  EXPIRED: '已过期',
}
export const orderApi = {
  limits: (sessionId: string) =>
    useAuthStore().request<PurchaseLimit>({
      url: '/orders/limits',
      params: { sessionId },
    }),
  create: (skuId: string, quantity: number, key: string) =>
    useAuthStore().request<Order>({
      method: 'POST',
      url: '/orders',
      data: { skuId, quantity },
      headers: { 'Idempotency-Key': key },
    }),
  list: (page: number) =>
    useAuthStore().request<Page<Order>>({
      url: '/orders',
      params: { page, size: 10 },
    }),
  detail: (id: string) =>
    useAuthStore().request<Order>({ url: '/orders/' + id }),
  cancel: (id: string) =>
    useAuthStore().request<Order>({
      method: 'POST',
      url: '/orders/' + id + '/cancel',
    }),
  pay: (id: string) =>
    useAuthStore().request<Payment>({
      method: 'POST',
      url: '/orders/' + id + '/pay',
    }),
  payment: (id: string) =>
    useAuthStore().request<Payment>({ url: '/orders/' + id + '/payment' }),
}
