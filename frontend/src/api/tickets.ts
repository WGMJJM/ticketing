import { client } from './client'
import type { ApiResponse } from './system'
import { useAdminStore } from '../stores/admin'

export type TicketStatus = 'DRAFT' | 'ON_SALE' | 'OFF_SALE'
export type SaleStatus =
  'NOT_STARTED' | 'SELLING' | 'SOLD_OUT' | 'STOPPED' | 'UNAVAILABLE'
export interface TicketSku {
  id: string
  sessionId: string
  name: string
  priceCents: string
  totalStock: number
  lockedStock: number
  soldStock: number
  availableStock: number
  saleStartsAt: string
  saleEndsAt: string
  maxQuantityPerOrder: number
  status: TicketStatus
  saleStatus: SaleStatus
  serverTime: string
  version: number
}
export interface TicketInput {
  name: string
  priceCents: string
  totalStock: number
  saleStartsAt: string
  saleEndsAt: string
  maxQuantityPerOrder: number
  version?: number
}
export const ticketStatusText: Record<TicketStatus, string> = {
  DRAFT: '草稿',
  ON_SALE: '已启用销售',
  OFF_SALE: '已下架',
}
export const saleStatusText: Record<SaleStatus, string> = {
  NOT_STARTED: '暂未开售',
  SELLING: '正在售票',
  SOLD_OUT: '已售罄',
  STOPPED: '已停售',
  UNAVAILABLE: '暂不可售',
}
export async function listTickets(
  activityId: string,
  sessionId: string,
  signal?: AbortSignal,
) {
  return (
    await client.get<ApiResponse<TicketSku[]>>(
      `/activities/${activityId}/sessions/${sessionId}/tickets`,
      { signal },
    )
  ).data.data
}
export const adminTickets = {
  list: (sessionId: string) =>
    useAdminStore().request<TicketSku[]>({
      url: `/admin/sessions/${sessionId}/tickets`,
    }),
  create: (sessionId: string, data: TicketInput) =>
    useAdminStore().request<TicketSku>({
      method: 'POST',
      url: `/admin/sessions/${sessionId}/tickets`,
      data,
    }),
  update: (id: string, data: TicketInput) =>
    useAdminStore().request<TicketSku>({
      method: 'PUT',
      url: `/admin/tickets/${id}`,
      data,
    }),
  transition: (ticket: TicketSku, operation: 'publish' | 'offline') =>
    useAdminStore().request<TicketSku>({
      method: 'POST',
      url: `/admin/tickets/${ticket.id}/${operation}`,
      data: { version: ticket.version },
    }),
}
