import { useAuthStore } from '../stores/auth'
import type { Page } from './activities'

export interface ElectronicTicket {
  id: string
  orderId: string
  orderItemId: string
  skuId: string
  ownerId: string
  ownerName: string
  ticketNo: string
  sequenceNo: number
  status: 'UNUSED' | 'USED' | 'VOID'
  activityId: string
  activityTitle: string
  sessionId: string
  sessionTitle: string
  sessionStartsAt: string
  sessionEndsAt: string
  city: string
  venue: string
  skuName: string
  usedAt: string | null
  checkedBy: string | null
  createdAt: string
}
export const electronicTicketStatus = {
  UNUSED: '未使用',
  USED: '已核销',
  VOID: '已作废',
}
export const electronicTicketApi = {
  list: (page: number, orderId?: string, status?: string) =>
    useAuthStore().request<Page<ElectronicTicket>>({
      url: '/tickets',
      params: { page, size: 10, orderId, status: status || undefined },
    }),
  detail: (id: string) =>
    useAuthStore().request<ElectronicTicket>({ url: '/tickets/' + id }),
  credential: (id: string) =>
    useAuthStore().request<{ ticketId: string; credential: string }>({
      url: '/tickets/' + id + '/credential',
    }),
  checkIn: (credential: string, staff = false) =>
    useAuthStore().request<ElectronicTicket>({
      method: 'POST',
      url: staff ? '/checkins' : '/admin/checkins',
      data: { credential },
    }),
}
