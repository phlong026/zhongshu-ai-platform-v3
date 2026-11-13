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
  /** 24 号 B-8：同时开通设计能力（建公司积分池）；省略=不改变既有开通状态 */
  designEnabled?: boolean
  /** 24 号 B-8：开通赠点（仅首次生效，可 0） */
  initialCredits?: number
}

const base = '/platform/org'
export const onboardOrg = (data: OnboardPayload): Promise<OnboardResult> =>
  request.post({ url: `${base}/onboard`, data })
