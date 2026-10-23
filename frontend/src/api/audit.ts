import { useAuthStore } from '../stores/auth'
import type { Page } from './activities'
export const auditActionText: Record<string, string> = {
  ACTIVITY_CREATE: '创建活动', ACTIVITY_UPDATE: '修改活动', ACTIVITY_PUBLISH: '发布活动', ACTIVITY_OFFLINE: '下架活动',
  SESSION_CREATE: '新增场次', SESSION_UPDATE: '修改场次', SESSION_CANCEL: '取消场次',
  TICKET_CREATE: '新增票档', TICKET_UPDATE: '修改票档', STOCK_ADJUST: '调整总库存', TICKET_PUBLISH: '上架票档', TICKET_OFFLINE: '下架票档',
  STAFF_PROMOTE: '授权工作人员角色', STAFF_DEMOTE: '撤销工作人员角色', STAFF_DISABLE: '停用工作人员',
  SESSION_GRANT: '授权核销场次', SESSION_REVOKE: '撤销核销场次', ORDER_REFUND: '处理整单模拟退款',
}
export const auditResourceText: Record<string, string> = { ACTIVITY: '活动', SESSION: '场次', TICKET_SKU: '票档', STAFF: '工作人员', ORDER: '订单' }
export interface AuditRecord { id: string; operatorId: string; operatorName: string; action: string; resourceType: string; resourceId: string; occurredAt: string; summary: string; beforeValues: string; afterValues: string; requestId: string }
export function listAudit(params: Record<string, string | number>) {
  return useAuthStore().request<Page<AuditRecord>>({ url: '/admin/audit-logs', params })
}
