import { client } from './client'
import type { ApiResponse } from './system'

export const categories = [
  { value: 'CONCERT', label: '演唱会', symbol: '♫', theme: 'purple' },
  { value: 'FESTIVAL', label: '音乐节', symbol: '≋', theme: 'green' },
  { value: 'STAGE', label: '舞台演出', symbol: '◒', theme: 'orange' },
  { value: 'EXHIBITION', label: '展览', symbol: '✧', theme: 'blue' },
  { value: 'ESPORTS', label: '电竞赛事', symbol: '✦', theme: 'blue' },
] as const
export type Category = (typeof categories)[number]['value']
export type ActivityStatus = 'DRAFT' | 'PUBLISHED' | 'OFFLINE'
export type SessionStatus = 'SCHEDULED' | 'CANCELLED' | 'ENDED'
export interface Activity {
  id: string
  title: string
  category: Category
  city: string
  venue: string
  summary: string
  description: string
  coverUrl: string | null
  status: ActivityStatus
  publishedAt: string | null
  createdAt: string
  updatedAt: string
  version: number
}
export interface ActivitySession {
  id: string
  activityId: string
  title: string
  startsAt: string
  endsAt: string
  saleStartsAt: string
  status: SessionStatus
  version: number
}
export interface ActivityQuery {
  keyword?: string
  city?: string
  category?: string
  status?: string
  page: number
  size: number
}
export interface Page<T> {
  items: T[]
  total: number
  page: number
  size: number
}
export type ActivityInput = Pick<
  Activity,
  'title' | 'category' | 'city' | 'venue' | 'summary' | 'description'
> & { coverUrl: string; version?: number }
export type SessionInput = Pick<
  ActivitySession,
  'title' | 'startsAt' | 'endsAt' | 'saleStartsAt'
> & { version?: number }
export const activityStatusText: Record<ActivityStatus, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  OFFLINE: '已下架',
}
export const sessionStatusText: Record<SessionStatus, string> = {
  SCHEDULED: '正常',
  CANCELLED: '已取消',
  ENDED: '已结束',
}
export function categoryInfo(value: string) {
  return categories.find((item) => item.value === value) ?? categories[0]
}
export async function listActivities(
  params: ActivityQuery,
  signal?: AbortSignal,
) {
  return (
    await client.get<ApiResponse<Page<Activity>>>('/activities', {
      params,
      signal,
    })
  ).data.data
}
export async function getActivity(id: string, signal?: AbortSignal) {
  return (
    await client.get<ApiResponse<Activity>>(`/activities/${id}`, { signal })
  ).data.data
}
export async function listSessions(id: string, signal?: AbortSignal) {
  return (
    await client.get<ApiResponse<ActivitySession[]>>(
      `/activities/${id}/sessions`,
      { signal },
    )
  ).data.data
}
