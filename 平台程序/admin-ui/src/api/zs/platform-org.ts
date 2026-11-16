import request from '@/config/axios'

/** M01-T06 / R02：正式组织开通（平台管理员，platform:org:onboard 权限） */
export interface OnboardResult {
  orgId: number
  adminUserId: number
  postId: number
  /** true=命中既有组织幂等返回（不重复建组织） */
  reusedOrg: boolean
}

export interface OnboardPayload {
  /** 组织类型：pf_org_type 已注册编码（如 headquarters / store） */
  orgType: string
  orgName: string
  adminName: string
  adminMobile: string
}

export interface FranchisePreviewPayload {
  brand: string
  province: string
  city: string
  area: string
  adminName: string
  areaPinyin?: string
  ownerPinyin?: string
}

export interface FranchisePreviewResult {
  orgName: string
  loginName: string
  aliases: string[]
  areaPinyin: string
  ownerPinyin: string
}

export interface FranchiseOnboardPayload extends FranchisePreviewPayload {
  adminMobile: string
  remark?: string
}

export interface FranchiseOnboardResult extends OnboardResult, FranchisePreviewResult {
  reusedUser: boolean
  /** 只在新建用户时返回一次；复用用户为 null。 */
  initialPassword: string | null
  requiresPasswordChange: boolean
  accountId: string
}

const base = '/platform/org'
export interface Organization {
  orgId: number
  orgName: string
  orgType: string
  status: string
  ownerName: string | null
  mobile: string | null
  loginName: string | null
  accountStatus: number | null
  brand: string | null
  province: string | null
  city: string | null
  area: string | null
  remark: string | null
  createdAt: string
  accountId: string | null
}

export interface OrganizationDetail {
  organization: Organization
  aliases: string[]
}

export const getOrganizationPage = (params: {
  keyword: string
  orgType: string
  pageNo: number
  pageSize: number
  includeDeleted?: boolean
}): Promise<{ list: Organization[]; total: number }> =>
  request.get({ url: `${base}/directory`, params })

export const getOrganizationDetail = (id: number): Promise<OrganizationDetail> =>
  request.get({ url: `${base}/directory/${id}` })

export const getOrganizationPoints = (id: number): Promise<Record<string, string | null>> =>
  request.get({ url: `${base}/directory/${id}/points` })
export const getOrganizationSection = (
  id: number,
  section: 'members' | 'ledger' | 'generations' | 'audits',
  pageNo = 1
): Promise<{ list: Record<string, any>[]; total: number }> =>
  request.get({ url: `${base}/directory/${id}/${section}`, params: { pageNo, pageSize: 20 } })
export const changeOrganizationState = (
  id: number,
  data: { action: string; confirmedName: string; reason: string; requestKey: string }
): Promise<OrganizationDetail> => request.post({ url: `${base}/directory/${id}/lifecycle`, data })

export interface OrganizationAccount {
  loginName: string
  ownerName: string
  relatedOrganizations: string[]
  resetAllowed: boolean
  blockedReason: string
}
export const getOrganizationAccount = (id: number): Promise<OrganizationAccount> =>
  request.get({ url: `${base}/directory/${id}/account` })
export const resetOrganizationPassword = (
  id: number,
  data: { password: string; confirmedName: string; reason: string; requestKey: string }
): Promise<boolean> => request.post({ url: `${base}/directory/${id}/account/reset-password`, data })

export const onboardOrg = (data: OnboardPayload): Promise<OnboardResult> =>
  request.post({ url: `${base}/onboard`, data })

export const previewFranchise = (data: FranchisePreviewPayload): Promise<FranchisePreviewResult> =>
  request.post({ url: `${base}/franchise-preview`, data })

export const onboardFranchise = (data: FranchiseOnboardPayload): Promise<FranchiseOnboardResult> =>
  request.post({ url: `${base}/franchise-onboard`, data })
