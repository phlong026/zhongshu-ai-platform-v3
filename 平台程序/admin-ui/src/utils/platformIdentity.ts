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
  url.includes('/platform/posts/current-context')
