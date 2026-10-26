import { useAuthStore } from '../stores/auth'
import type { Page } from './activities'
export interface PurchaseLimit {
  maxPerSession: number
  usedQuantity: number
  remainingQuantity: number
}
export interface PurchaseRequest {
  requestId: string
  skuId: string
  quantity: number
  status: 'QUEUED' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED' | 'EXPIRED'
  orderId: string | null
  failureCode: string | null
  failureMessage: string | null
  acceptedAt: string
  deadlineAt: string
  serverTime: string
}
export const purchaseStatusText = {
  QUEUED: '等待处理', PROCESSING: '正在确认购票结果', SUCCEEDED: '订单已创建',
  FAILED: '购票未成功', EXPIRED: '排队已超时',
}
export const purchaseApi = {
  lookup: (key: string) => useAuthStore().request<PurchaseRequest>({
    url: '/purchase-requests/lookup', headers: { 'Idempotency-Key': key }, timeout: 10000,
  }),
  detail: (id: string, signal?: AbortSignal) => useAuthStore().request<PurchaseRequest>({
    url: '/purchase-requests/' + id, timeout: 10000, signal,
  }),
  active: (signal?: AbortSignal) => useAuthStore().request<PurchaseRequest[]>({
    url: '/purchase-requests', timeout: 10000, signal,
  }),
}
export interface Order {
  id: string
  orderNo: string
  activityId: string
  activityTitle: string
  sessionId: string
  sessionTitle: string
  sessionStartsAt: string
  skuId: string | null
  skuName: string | null
  quantity: number | null
  unitPriceCents: string | null
  totalAmountCents: string
  status: 'PENDING' | 'PAID' | 'CANCELLED' | 'EXPIRED' | 'REFUNDED'
  createdAt: string
  expiresAt: string
  paymentDeadlinePassed: boolean
  canCancel: boolean
  canPay: boolean
  payment: Payment | null
  refund: Refund | null
  serverTime: string
}
export interface Refund {
  orderId: string
  refundNo: string
  paymentNo: string
  amountCents: string
  operatorId: string | null
  reason: RefundReason
  status: 'SUCCEEDED'
  refundedAt: string
  simulated: boolean
}
export type RefundReason = 'CUSTOMER_REQUEST' | 'SERVICE_ADJUSTMENT' | 'SESSION_CANCELLED'
export const refundReasonText = {
  CUSTOMER_REQUEST: '用户诉求售后',
  SERVICE_ADJUSTMENT: '运营服务调整',
  SESSION_CANCELLED: '场次取消善后',
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
  REFUNDED: '已模拟退款',
}
export const orderApi = {
  limits: (sessionId: string) =>
    useAuthStore().request<PurchaseLimit>({
      url: '/orders/limits',
      params: { sessionId },
    }),
  create: (skuId: string, quantity: number, key: string) =>
    useAuthStore().request<PurchaseRequest>({
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
