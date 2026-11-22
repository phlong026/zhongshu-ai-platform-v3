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
    lines.push('密码：沿用原账号密码；也可使用小程序官方微信手机号授权登录')
  } else {
    lines.push(
      `初始密码：${result.initialPassword || ''}`,
      '提示：初始密码仅本次交付，48小时内登录并改密；微信登录后初始密码失效'
    )
  }
  lines.push('此账号仅供该员工本人使用，请勿与其他员工共用')
  return lines.join('\n')
}
