import request from '@/config/axios'
import type { Organization } from '@/api/zs/platform-org'

const base = '/platform/operations'
export interface OrganizationProfile {
  organization: Organization
  version: number
  ownerUserId: string | null
  wechatBound: boolean
  mobileBlockedReason: string
  contactName: string
  contactMobile: string
  address: string
}
export interface ProfileUpdate {
  expectedVersion: number
  orgName: string
  brand: string
  province: string
  city: string
  area: string
  ownerName: string
  ownerMobile: string
  contactName: string
  contactMobile: string
  address: string
  remark: string
  reason: string
  requestKey: string
}
export interface CapabilityPolicy {
  version: number
  items: { subject: string; label: string; unit: string; enabled: boolean; priced: boolean }[]
}
export interface CapabilityUpdate {
  expectedVersion: number
  enabledSubjects: string[]
  reason: string
  requestKey: string
}
export interface OwnerTransfer {
  expectedVersion: number
  newOwnerUserId: string
  confirmedName: string
  reason: string
  requestKey: string
}
export interface TaskReview {
  expectedVersion: number
  decision: string
  reason: string
  evidence: string
  requestKey: string
}
export type TaskRow = Record<string, any>
export interface TaskDetail {
  task: TaskRow
  ledger: TaskRow[]
  history: TaskRow[]
  review: TaskRow
}
export const getOrganizationProfile = (id: number): Promise<OrganizationProfile> =>
  request.get({ url: `${base}/organizations/${id}/profile` })
export const updateOrganizationProfile = (
  id: number,
  data: ProfileUpdate
): Promise<OrganizationProfile> =>
  request.post({ url: `${base}/organizations/${id}/profile`, data })
export const transferOrganizationOwner = (
  id: number,
  data: OwnerTransfer
): Promise<OrganizationProfile> => request.post({ url: `${base}/organizations/${id}/owner`, data })
export const getOwnerCandidates = (
  id: number,
  keyword = ''
): Promise<{ id: string; name: string; mobile: string }[]> =>
  request.get({ url: `${base}/organizations/${id}/owner-candidates`, params: { keyword } })
export const getOrganizationCapabilities = (id: number): Promise<CapabilityPolicy> =>
  request.get({ url: `${base}/organizations/${id}/capabilities` })
export const updateOrganizationCapabilities = (
  id: number,
  data: CapabilityUpdate
): Promise<CapabilityPolicy> =>
  request.post({ url: `${base}/organizations/${id}/capabilities`, data })
export const getOrganizationOperationsAudit = (
  id: number,
  pageNo = 1
): Promise<{ list: TaskRow[]; total: number }> =>
  request.get({ url: `${base}/organizations/${id}/audits`, params: { pageNo, pageSize: 20 } })
export const getOperationsTasks = (
  params: Record<string, unknown>
): Promise<{ list: TaskRow[]; total: number }> => request.get({ url: `${base}/tasks`, params })
export const getOperationsTask = (key: string): Promise<TaskDetail> =>
  request.get({ url: `${base}/tasks/${encodeURIComponent(key)}` })
export const reviewOperationsTask = (key: string, data: TaskReview): Promise<TaskDetail> =>
  request.post({ url: `${base}/tasks/${encodeURIComponent(key)}/review`, data })
