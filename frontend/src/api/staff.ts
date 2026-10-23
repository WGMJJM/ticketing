import { useAuthStore } from '../stores/auth'
import type { Page } from './activities'
export interface StaffAccount { id: string; username: string; role: 'USER' | 'CHECKIN_STAFF'; status: 'ACTIVE' | 'DISABLED' }
export interface StaffPermission {
  staffId: string; sessionId: string; activityTitle: string; sessionTitle: string
  startsAt: string; endsAt: string; sessionStatus: 'SCHEDULED' | 'CANCELLED' | 'ENDED'
  grantedBy: string | null; grantedAt: string | null; revokedAt: string | null
}
export const staffRoleText = { USER: '普通用户', CHECKIN_STAFF: '核销工作人员' }
export const staffApi = {
  list: (page: number, username: string) => useAuthStore().request<Page<StaffAccount>>({ url: '/admin/staff', params: { page, size: 10, username: username || undefined } }),
  change: (id: string, action: 'promote' | 'demote' | 'disable') => useAuthStore().request<StaffAccount>({ method: 'POST', url: `/admin/staff/${id}/${action}` }),
  permissions: (id: string, page: number) => useAuthStore().request<Page<StaffPermission>>({ url: `/admin/staff/${id}/sessions`, params: { page, size: 10 } }),
  grant: (id: string, sessionId: string, action: 'grant' | 'revoke') => useAuthStore().request<StaffPermission>({ method: 'POST', url: `/admin/staff/${id}/sessions/${sessionId}/${action}` }),
  available: (page: number) => useAuthStore().request<Page<StaffPermission>>({ url: '/checkins/sessions', params: { page, size: 10 } }),
}
