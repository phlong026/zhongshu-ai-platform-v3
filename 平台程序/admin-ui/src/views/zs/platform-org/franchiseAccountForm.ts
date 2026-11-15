import type { FranchiseOnboardResult, FranchisePreviewPayload } from '@/api/zs/platform-org'

export interface AreaNode {
  id: number
  name: string
  children?: AreaNode[]
}

export function toProvinceCityTree(tree: AreaNode[]): AreaNode[] {
  return tree.map((province) => ({
    id: province.id,
    name: province.name,
    children: (province.children || []).map((city) => ({ id: city.id, name: city.name }))
  }))
}

export interface FranchiseFormFields extends FranchisePreviewPayload {
  adminMobile?: string
  remark?: string
}

export function findAreaNames(
  tree: AreaNode[],
  path?: number[]
): { province: string; city: string } {
  const province = tree.find((node) => node.id === path?.[0])
  const city = province?.children?.find((node) => node.id === path?.[1])
  return { province: province?.name || '', city: city?.name || '' }
}

export function buildPreviewPayload(fields: FranchiseFormFields): FranchisePreviewPayload {
  return {
    brand: fields.brand.trim(),
    province: fields.province.trim(),
    city: fields.city.trim(),
    area: fields.area.trim(),
    adminName: fields.adminName.trim(),
    areaPinyin: fields.areaPinyin?.trim().toLowerCase() || undefined,
    ownerPinyin: fields.ownerPinyin?.trim().toLowerCase() || undefined
  }
}

export function resetOverrideWhenSourceChanges(
  previousSource: string,
  nextSource: string,
  override: string
): string {
  return previousSource === nextSource ? override : ''
}

export function createPreviewGenerationGuard() {
  let generation = 0
  return {
    next: () => ++generation,
    isCurrent: (requestGeneration: number) => requestGeneration === generation,
    invalidate: () => ++generation
  }
}

export const canLeaveFranchiseOnboard = (submitting: boolean) => !submitting

export function buildCredentialCopyText(
  result: Pick<
    FranchiseOnboardResult,
    'orgName' | 'loginName' | 'aliases' | 'initialPassword' | 'reusedUser'
  >
): string {
  const lines = [
    `门店老板账号：${result.orgName}`,
    `登录账号：${result.loginName}`,
    `快捷查找：${result.aliases.join('、') || '使用完整账号'}`
  ]
  if (result.reusedUser) {
    lines.push('密码：沿用原账号密码')
  } else {
    lines.push(`初始密码：${result.initialPassword || ''}`, '提示：首次登录后请修改初始密码')
  }
  lines.push('使用方式：进入小程序，选择账号密码登录')
  return lines.join('\n')
}
