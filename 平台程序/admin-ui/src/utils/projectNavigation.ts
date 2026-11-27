// 只裁剪本项目的展示入口，不删除路由、服务或修改后端授权。
const businessPermissions: Record<string, string> = {
  case: 'design:case:query',
  review: 'design:submission:review',
  account: 'identity:account:query',
  'access-code': 'identity:access-code:manage',
  recharge: 'commerce:recharge-order:query',
  'point-ledger': 'commerce:points:query',
  budget: 'design:budget:query',
  'budget-estimates': 'design:budget:query',
  'generation-pricing': 'commerce:generation-price:query',
  'usage-pricing': 'commerce:usage-price:query',
  export: 'design:export:manage',
  audit: 'design:audit:query',
  // 此前遗漏该键，导致「隐私申请」页因 permission 恒为 falsy 而永不出现在菜单（只能手输 URL）。
  // 权限码与后端 PrivacyAdminController 类级 @PreAuthorize 保持一致。
  privacy: 'identity:privacy:manage',
  announcement: 'design:announcement:send'
}

// 业务菜单分组：与路由 meta.group 对应，顺序即侧边栏顺序（户型最上、隐私在最后组末尾）。
const businessGroups = [
  { key: 'content', title: '案例库', icon: 'ep:office-building' },
  { key: 'auth', title: '客户与激活', icon: 'ep:key' },
  { key: 'fund', title: '资金与点数', icon: 'ep:coin' },
  { key: 'budget', title: '预算与报价', icon: 'ep:money' },
  { key: 'pricing', title: '定价管理', icon: 'ep:price-tag' },
  { key: 'ops', title: '运营与合规', icon: 'ep:document-checked' }
] as const

// 2026-10-02 运营确认：菜单管理不属本业务后台，从入口裁剪；
// 上游 CRM/商城等模块菜单行的唯一可见入口即「菜单管理」页，随之不可见（底层数据保留不删）。
// 2026-10-11 恢复「用户管理」「部门管理」入口：设计师角色（designer）开通需在用户管理
// 新建后台账号并分配角色；公司/部门结构与数据权限的维护依赖部门管理。
const supportPaths = new Set([
  '/system/user',
  '/system/dept',
  '/system/role',
  '/system/log/operate-log',
  '/system/log/login-log',
  '/platform-org/onboard',
  '/training/courses',
  '/training/materials',
  '/infra/config',
  '/infra/file/file',
  '/infra/file/file-config',
  '/infra/log/api-access-log',
  '/infra/log/api-error-log'
])

const fullPath = (parent: string, path: string) =>
  (path.startsWith('/') ? path : `${parent}/${path}`).replace(/\/{2,}/g, '/').replace(/\/$/, '') ||
  '/'

const capitalize = (value: string) => value.charAt(0).toUpperCase() + value.slice(1)

const supportMenus = (routes: AppRouteRecordRaw[], parent = '/'): AppRouteRecordRaw[] =>
  routes.flatMap((route) => {
    if (route.meta?.hidden || /^[a-z]+:/i.test(route.path)) return []
    const path = fullPath(parent, route.path)
    const children = supportMenus(route.children || [], path)
    if (!children.length && (route.children?.length || !supportPaths.has(path))) return []
    const title = { '/system': '系统设置', '/infra': '运维支持' }[path]
    return [
      {
        ...route,
        meta: { ...route.meta, ...(title ? { title } : {}) },
        ...(route.children ? { children } : {}),
        // 原目录默认页可能已被隐藏，重定向到保留的第一项。
        ...(children.length ? { redirect: fullPath(path, children[0].path) } : {})
      }
    ]
  })

export function buildProjectMenus(
  base: AppRouteRecordRaw[],
  business: AppRouteRecordRaw[],
  authorized: AppRouteRecordRaw[],
  can: (permissions: string[]) => boolean
): AppRouteRecordRaw[] {
  // 业务路由保持 /zs 扁平注册；此处仅按 meta.group 重组菜单树。
  // 分组目录 path 用 /zs-<key> 占位以保证 el-menu index 唯一；子项改写为绝对路径后可正常导航。
  const childrenOf = (business[0]?.children || []) as AppRouteRecordRaw[]
  const project = businessGroups.flatMap((group) => {
    const children = childrenOf
      .filter(
        (child) =>
          child.meta?.group === group.key &&
          !child.meta?.hidden &&
          businessPermissions[child.path] &&
          can([businessPermissions[child.path]])
      )
      .map((child) => ({ ...child, path: fullPath('/zs', child.path) }))
    if (!children.length) return []
    return [
      {
        path: `/zs-${group.key}`,
        name: `ZsGroup${capitalize(group.key)}`,
        meta: { title: group.title, icon: group.icon, alwaysShow: true },
        children
      }
    ]
  })
  return [...base.filter((route) => route.path === '/'), ...project, ...supportMenus(authorized)]
}

// B 总部仅展示其本地运营实体；授权仍由服务端 @ss 校验。
const platformPermissions: Record<string, string> = {
  wallets: 'platform:wallet:query',
  plans: 'platform:wallet:plan:query',
  orders: 'platform:wallet:order:query',
  ledger: 'platform:wallet:ledger:query',
  anomalies: 'platform:wallet:anomaly:query',
  audit: 'platform:ops:audit:query'
}

export function buildPlatformMenus(
  base: AppRouteRecordRaw[],
  authorized: AppRouteRecordRaw[],
  can: (permissions: string[]) => boolean
): AppRouteRecordRaw[] {
  const operations = authorized
    .filter((route) => route.path === '/platform-ops' && !route.meta?.hidden)
    .flatMap((route) => {
      const children = (route.children || []).filter(
        (child) =>
          !child.meta?.hidden &&
          platformPermissions[child.path] &&
          can([platformPermissions[child.path]])
      )
      return children.length
        ? [{ ...route, children, redirect: fullPath(route.path, children[0].path) }]
        : []
    })
  // 页面由 remainingRouter 注册；只补展示入口，避免注册同路径的空动态路由。
  const pricing: AppRouteRecordRaw[] =
    base.some((route) => route.path === '/') && can(['platform:org-point:price:query'])
      ? [
          {
            path: '/platform-pricing',
            name: 'PlatformPricingEntry',
            meta: { title: '定价管理', icon: 'ep:price-tag' }
          }
        ]
      : []
  return [
    ...base.filter((route) => route.path === '/'),
    ...(base.some((route) => route.path === '/') && can(['platform:announcement:query'])
      ? [{
          path: '/platform-announcements',
          name: 'PlatformAnnouncementsEntry',
          meta: { title: '公告管理', icon: 'ep:notification' }
        }]
      : []),
    ...pricing,
    ...(base.some((route) => route.path === '/') && can(['platform:operations:query'])
      ? [{path:'/platform-task-ops',name:'PlatformTaskOperationsEntry',meta:{title:'任务与积分核对',icon:'ep:finished'}}]
      : []),
    ...operations,
    ...supportMenus(authorized)
  ]
}

export function buildStaffMenus(
  routes: AppRouteRecordRaw[],
  can: (permission: string) => boolean,
  headquartersAnnouncements = false
): AppRouteRecordRaw[] {
  const staff = routes
    .filter((route) => route.path === '/fr')
    .map((route) => ({
      ...route,
      meta: { ...route.meta, title: '组织管理', icon: 'ep:user-filled', alwaysShow: true },
      // 逐路由授权映射：无 meta.permission 的子路由（如 identity 身份切换）恒显；
      // 有 permission 的按各路由自己的权限标识过滤，不再统一套 platform:org:query。
      // 菜单过滤只控制可见性，授权真源仍是服务端 @post.has 与页内守卫。
      children: (route.children || []).filter(
        (child) => !child.meta?.hidden && (!child.meta?.permission || can(child.meta.permission))
      )
    }))
  return [
    ...staff,
    ...(headquartersAnnouncements && can('platform:announcement:query')
      ? [{
          path: '/platform-announcements',
          name: 'StaffAnnouncementsEntry',
          meta: { title: '公告管理', icon: 'ep:notification' }
        }]
      : [])
  ]
}

export function searchProjectMenus(routes: AppRouteRecordRaw[], keyword: string) {
  const query = keyword.trim().toLowerCase()
  const results: { label: string; value: string }[] = []
  if (!query) return results
  const visit = (items: AppRouteRecordRaw[], parent: string) => {
    for (const item of items) {
      if (item.meta?.hidden || /^[a-z]+:/i.test(item.path)) continue
      const path = fullPath(parent, item.path)
      if (item.children?.length) {
        visit(item.children, path)
        continue
      }
      const title = String(item.meta?.title || '')
      if (path.includes(':') || !title) continue
      if (
        `${title} ${path}`.toLowerCase().includes(query) &&
        !results.some((result) => result.value === path)
      ) {
        results.push({ label: `${title} ${path}`, value: path })
      }
    }
  }
  visit(routes, '/')
  return results
}

// Display-only grouping of already-authorized headquarters routes. Router registration stays unchanged.
export function groupPlatformNavigation(routes: AppRouteRecordRaw[]): AppRouteRecordRaw[] {
  const leaves: AppRouteRecordRaw[] = []
  const visit = (items: AppRouteRecordRaw[], parent = '/') => {
    for (const route of items) {
      if (route.meta?.hidden) continue
      const path = fullPath(parent, route.path)
      if (route.children?.length) visit(route.children, path)
      else leaves.push({ ...route, path })
    }
  }
  visit(routes)
  const groups = [
    { key: 'organization', title: '组织与权限', icon: 'ep:user-filled', paths: ['/platform-org/onboard', '/system/user', '/system/role', '/system/dept'] },
    { key: 'training', title: '课程培训', icon: 'ep:reading', paths: ['/training/courses', '/training/materials'] },
    { key: 'operations', title: '运营与积分', icon: 'ep:coin', paths: ['/platform-task-ops', '/platform-ops/wallets', '/platform-ops/orders', '/platform-ops/ledger', '/platform-ops/anomalies'] },
    { key: 'pricing', title: '定价与套餐', icon: 'ep:price-tag', paths: ['/platform-pricing', '/platform-ops/plans'] },
    { key: 'system', title: '系统与审计', icon: 'ep:setting', paths: ['/infra/config', '/infra/file/file', '/infra/file/file-config', '/system/log/operate-log', '/system/log/login-log', '/platform-ops/audit', '/infra/log/api-access-log', '/infra/log/api-error-log'] }
  ]
  const used = new Set<string>(['/index', '/platform-announcements'])
  const result = groups.flatMap(group => {
    const children = group.paths.flatMap(path => { const route = leaves.find(r => r.path === path); if (!route) return []; used.add(path); return [route] })
    return children.length ? [{ path: `/navigation-${group.key}`, name: `PlatformNavigation${capitalize(group.key)}`, meta: { title: group.title, icon: group.icon, alwaysShow: true }, children }] : []
  })
  const other = leaves.filter(route => !used.has(route.path))
  if (other.length) result.push({ path: '/navigation-other', name: 'PlatformNavigationOther', meta: { title: '其他功能', icon: 'ep:more-filled', alwaysShow: true }, children: other })
  const primary = ['/index', '/platform-announcements'].flatMap((path) =>
    leaves.filter((route) => route.path === path)
  )
  return [...primary, ...result]
}
