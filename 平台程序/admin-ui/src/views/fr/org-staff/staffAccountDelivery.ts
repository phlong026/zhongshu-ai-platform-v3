import type { StaffAccountResult } from '@/api/platform/staff'

export const isRestrictedOwnerRole = (roleCode: string) =>
  ['boss', 'owner'].includes(roleCode.trim().toLowerCase())

export function buildStaffCredentialCopyText(
  result: Pick<
    StaffAccountResult,
    'name' | 'orgName' | 'loginName' | 'aliases' | 'initialPassword' | 'reusedUser'
  >
): string {
  const lines = [
    `员工独立账号：${result.name}`,
    `所属门店：${result.orgName}`,
    `登录账号：${result.loginName}`,
    `快捷查找：${result.aliases.join('、') || '使用完整账号'}`
  ]
  if (result.reusedUser) {
    lines.push('密码：沿用原账号密码')
  } else {
    lines.push(`初始密码：${result.initialPassword || ''}`, '提示：首次登录后请修改初始密码')
  }
  lines.push('此账号仅供该员工本人使用，请勿与其他员工共用')
  return lines.join('\n')
}
