import request from '@/config/axios'
import type { CurrentPostContext, PlatformId, StaffPost } from '@/utils/platformIdentity'

export interface StaffToken {
  userId: PlatformId
  accessToken: string
  refreshToken: string
  expiresTime: string | number
}

export interface Team {
  deptId: PlatformId
  orgId: PlatformId
  name: string
  line: string
}

export interface PostTemplate {
  roleCode: string
  line: string
  lead: boolean
}

export interface StaffRow {
  postId: PlatformId
  userId: PlatformId
  name: string
  mobile: string
  active: boolean
  roleCode: string
  teamId?: PlatformId | null
  teamName: string
  createTime: string
}

export interface CreateStaffPayload {
  orgId: PlatformId
  name: string
  mobile: string
  roleCode: string
  teamId?: PlatformId | null
}

export interface StaffAccountResult extends StaffRow {
  accountId: string
  orgName: string
  loginName: string
  aliases: string[]
  initialPassword: string | null
  reusedUser: boolean
}

export interface HandoverItem {
  domain: string
  label: string
  inFlight: number
  transferred: number
  status: 'HANDED_OVER' | 'NONE' | 'ORGANIZATION_SHARED' | 'PENDING_UNASSIGNED' | 'NOT_INTEGRATED' | 'SCAN_FAILED'
}

export interface HandoverResult {
  mode: string
  orgId: PlatformId
  fromUserId: PlatformId
  toUserId?: PlatformId | null
  revokedPostIds: PlatformId[]
  items: HandoverItem[]
  receivers?: Record<string, PlatformId>
}

export interface PendingRow {
  id: PlatformId
  orgId: PlatformId
  fromUserId: PlatformId
  domain: string
  domainLabel: string
  inFlight: number
  createTime: string
}

export interface HandoverSource {
  userId: PlatformId
  name: string
  mobile: string
}

export const sendStaffSms = (mobile: string) =>
  request.post<boolean>({
    url: '/platform/auth/sms/send',
    data: { mobile },
    headers: { isToken: false }
  })
export const staffSmsLogin = (mobile: string, captcha: string) =>
  request.post<StaffToken>({
    url: '/platform/auth/sms/login',
    data: { mobile, captcha },
    headers: { isToken: false }
  })
export const getMyPosts = () => request.get<StaffPost[]>({ url: '/platform/posts/mine' })
export const getCurrentPostContext = () =>
  request.get<CurrentPostContext>({ url: '/platform/posts/current-context' })

const orgBase = '/platform/org'
export const getTeams = (orgId: PlatformId) =>
  request.get<Team[]>({ url: `${orgBase}/teams`, params: { orgId } })
export const getPostTemplates = (orgId: PlatformId) =>
  request.get<PostTemplate[]>({ url: `${orgBase}/post-templates`, params: { orgId } })
export const createTeam = (
  data: { orgId: PlatformId; name: string; line?: string },
  idempotencyKey: string
) =>
  request.post<Team>({
    url: `${orgBase}/teams`,
    data,
    headers: { 'Idempotency-Key': idempotencyKey }
  })
export const getStaff = (orgId: PlatformId) =>
  request.get<StaffRow[]>({ url: `${orgBase}/staff`, params: { orgId } })
export const createStaff = (data: CreateStaffPayload) =>
  request.post<StaffRow>({ url: `${orgBase}/staff`, data })
export const createStaffAccount = (data: CreateStaffPayload) =>
  request.post<StaffAccountResult>({ url: `${orgBase}/staff-accounts`, data })
export const assignPost = (data: {
  orgId: PlatformId
  userId: PlatformId
  roleCode: string
  teamId?: PlatformId | null
}) => request.post<StaffRow>({ url: `${orgBase}/posts`, data })
export const revokePost = (postId: PlatformId) =>
  request.delete<boolean>({ url: `${orgBase}/posts/${postId}` })
export const setStaffStatus = (userId: PlatformId, active: boolean) =>
  request.post<boolean>({ url: `${orgBase}/staff-status`, data: { userId, active } })
export const handoverStaff = (data: {
  orgId: PlatformId
  fromUserId: PlatformId
  toUserId?: PlatformId
  receivers?: Record<string, PlatformId>
}) => request.post<HandoverResult>({ url: `${orgBase}/staff-handover`, data })
export const getHandoverPreview = (orgId: PlatformId, fromUserId: PlatformId) =>
  request.get<HandoverItem[]>({ url: `${orgBase}/staff-handover-preview`, params: { orgId, fromUserId } })
export const emergencyRevokeStaff = (data: { orgId: PlatformId; userId: PlatformId }) =>
  request.post<HandoverResult>({ url: `${orgBase}/staff-emergency-revoke`, data })
export const getHandoverPending = (orgId: PlatformId) =>
  request.get<PendingRow[]>({ url: `${orgBase}/handover-pending`, params: { orgId } })
export const getHandoverSources = (orgId: PlatformId, keyword: string, pageNo = 1) =>
  request.get<{ items: HandoverSource[]; hasMore: boolean }>({
    url: `${orgBase}/handover-sources`,
    params: { orgId, keyword, pageNo }
  })

// ===== P1 批2：员工批量导入（B-only 端点；legacy 进程不注册） =====

export interface StaffImportRowPayload {
  line: number
  name: string
  mobile: string
  roleCode: string
  teamName: string
}

export type StaffImportAccountDelivery = Pick<
  StaffAccountResult,
  'accountId' | 'name' | 'orgName' | 'loginName' | 'aliases' | 'initialPassword' | 'reusedUser'
>

export interface StaffImportResultRow extends StaffImportRowPayload {
  status: 'OK' | 'SKIPPED' | 'FAILED' | 'CREATED'
  code?: number | null
  message?: string | null
  postId?: PlatformId | null
  userId?: PlatformId | null
  note?: string | null
  account?: StaffImportAccountDelivery | null
}

export interface StaffImportResult {
  orgId: PlatformId
  total: number
  created: number
  skipped: number
  failed: number
  rows: StaffImportResultRow[]
}

export const precheckStaffImport = (data: { orgId: PlatformId; rows: StaffImportRowPayload[] }) =>
  request.post<StaffImportResult>({ url: `${orgBase}/staff-import/precheck`, data })

export const submitStaffImport = (data: { orgId: PlatformId; rows: StaffImportRowPayload[] }) =>
  request.post<StaffImportResult>({ url: `${orgBase}/staff-import`, data })
