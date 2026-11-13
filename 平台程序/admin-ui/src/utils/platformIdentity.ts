export type PlatformId = string | number

export interface StaffPost {
  postId: PlatformId
  userId: PlatformId
  orgId: PlatformId
  orgName: string
  orgType: string
  roleCode: string
  teamId?: PlatformId | null
  lead: boolean
}

export interface CurrentPostContext {
  post: StaffPost
  permissions: string[]
  // 负责人管理的团队（含所在团队及下级）；供 P1 客资接手人候选等按授权团队渲染。
  // 可选字段保持向后兼容：旧会话缓存可能不含此字段。
  managedTeamIds?: PlatformId[]
}

const POSTS_KEY = 'platform-staff-posts'
const POST_ID_KEY = 'platform-current-post-id'
const CONTEXT_KEY = 'platform-current-post-context'
const GENERATION_KEY = 'platform-post-generation'
const PORTAL_MODE_KEY = 'platform-portal-mode'

const parse = <T>(key: string, fallback: T): T => {
  try {
    const value = sessionStorage.getItem(key)
    return value ? (JSON.parse(value) as T) : fallback
  } catch {
    return fallback
  }
}

export const posts = (): StaffPost[] => parse<StaffPost[]>(POSTS_KEY, [])
export const savePosts = (value: StaffPost[]) =>
  sessionStorage.setItem(POSTS_KEY, JSON.stringify(value.filter(Boolean)))
export const currentPostId = () => sessionStorage.getItem(POST_ID_KEY) || ''
export const portalMode = (accessToken = '') => {
  const binding = parsePortalBinding()
  if (!accessToken || binding?.accessToken !== accessToken) {
    localStorage.removeItem(PORTAL_MODE_KEY)
    return 'admin'
  }
  return binding.mode
}
export const setPortalMode = (mode: 'admin' | 'staff', accessToken: string) =>
  localStorage.setItem(PORTAL_MODE_KEY, JSON.stringify({ mode, accessToken }))
export const postGeneration = () => Number(sessionStorage.getItem(GENERATION_KEY)) || 0
export const isCurrentGeneration = (generation: number) => generation === postGeneration()
export const currentContext = (): CurrentPostContext | null =>
  parse<CurrentPostContext | null>(CONTEXT_KEY, null)

export const beginPostSwitch = (postId: number | string) => {
  const generation = postGeneration() + 1
  sessionStorage.setItem(GENERATION_KEY, String(generation))
  sessionStorage.setItem(POST_ID_KEY, String(postId))
  sessionStorage.removeItem(CONTEXT_KEY)
  return generation
}

export const saveCurrentContext = (generation: number, context: CurrentPostContext) => {
  if (!isCurrentGeneration(generation) || String(context.post.postId) !== currentPostId())
    return false
  sessionStorage.setItem(CONTEXT_KEY, JSON.stringify(context))
  return true
}

export const hasCurrentPostPermission = (permission: string) =>
  currentContext()?.permissions?.includes(permission) === true

export const clearPlatformIdentity = () => {
  sessionStorage.removeItem(POSTS_KEY)
  sessionStorage.removeItem(POST_ID_KEY)
  sessionStorage.removeItem(CONTEXT_KEY)
  sessionStorage.setItem(GENERATION_KEY, String(postGeneration() + 1))
  localStorage.removeItem(PORTAL_MODE_KEY)
}

const parsePortalBinding = (): { mode: 'admin' | 'staff'; accessToken: string } | null => {
  try {
    const value = localStorage.getItem(PORTAL_MODE_KEY)
    return value ? JSON.parse(value) : null
  } catch {
    return null
  }
}

export const isPostScopedUrl = (url = '') =>
  url.includes('/franchise/') ||
  url.includes('/platform/org/') ||
  // 钱包 account/ledger 等 GET 需当前岗位上下文（服务端 @post.has('platform:wallet:read')）。
  url.includes('/platform/wallet/') ||
  url.includes('/platform/posts/current-context')

/**
 * 员工 Web 选岗后的落地页（identity 切换与短信登录共用，P2-1）。
 *
 * 角色优先（权限无法区分 boss 与 finance，两者都有 operate+fin）：
 * - boss / owner → /fr/dashboard（老板首屏看经营）
 * - finance / finance-lead → /fr/finance（财务首屏看核对）
 * 其余角色按权限兜底，保证 roleCode 不在映射表时仍有落地页：
 * - franchise:performance:operate → /fr/dashboard
 * - franchise:fin:query → /fr/finance
 * - platform:org:query → /fr/org/staff
 * - 否则留在 /fr/identity
 */
export const resolveStaffLandingPath = (
  roleCode: string | null | undefined,
  can: (permission: string) => boolean
): string => {
  if (roleCode === 'boss' || roleCode === 'owner') return '/fr/dashboard'
  if (roleCode === 'finance' || roleCode === 'finance-lead') return '/fr/finance'
  if (can('franchise:performance:operate')) return '/fr/dashboard'
  if (can('franchise:fin:query')) return '/fr/finance'
  if (can('platform:org:query')) return '/fr/org/staff'
  return '/fr/identity'
}
