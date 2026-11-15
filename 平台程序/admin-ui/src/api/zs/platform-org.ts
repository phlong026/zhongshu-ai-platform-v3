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
export const onboardOrg = (data: OnboardPayload): Promise<OnboardResult> =>
  request.post({ url: `${base}/onboard`, data })

export const previewFranchise = (data: FranchisePreviewPayload): Promise<FranchisePreviewResult> =>
  request.post({ url: `${base}/franchise-preview`, data })

export const onboardFranchise = (data: FranchiseOnboardPayload): Promise<FranchiseOnboardResult> =>
  request.post({ url: `${base}/franchise-onboard`, data })
