import router from './router'
import type { RouteRecordRaw } from 'vue-router'
import { isRelogin } from '@/config/axios/service'
import { getAccessToken } from '@/utils/auth'
import { useTitle } from '@/hooks/web/useTitle'
import { useNProgress } from '@/hooks/web/useNProgress'
import { usePageLoading } from '@/hooks/web/usePageLoading'
import { useDictStoreWithOut } from '@/store/modules/dict'
import { useUserStoreWithOut } from '@/store/modules/user'
import { usePermissionStoreWithOut } from '@/store/modules/permission'
import { parseRouteLocation } from '@/utils/routeParams'
import { canReadHeadquartersAnnouncements, portalMode } from '@/utils/platformIdentity'
import { usePlatformIdentityStoreWithOut } from '@/store/modules/platformIdentity'

const { start, done } = useNProgress()

const { loadStart, loadDone } = usePageLoading()

// 路由不重定向白名单
const whiteList = [
  '/login',
  '/social-login',
  '/auth-redirect',
  '/bind',
  '/register',
  '/oauthLogin/gitee',
  '/platform-login'
]

// 路由加载前（vue-router 4 推荐 return 风格守卫：返回目标即跳转，true/undefined 放行）
router.beforeEach(async (to, from) => {
  start()
  loadStart()
  if (getAccessToken()) {
    if (portalMode(getAccessToken()) === 'staff') {
      const permissionStore = usePermissionStoreWithOut()
      const identity = usePlatformIdentityStoreWithOut()
      if (!permissionStore.getRouters.length || !identity.context) {
        await permissionStore.generateRoutes()
      }
      if (!identity.posts.length && !identity.error) {
        return to.path === '/platform-login' ? true : { path: '/platform-login', replace: true }
      }
      if (!identity.context && to.path !== '/fr/identity') {
        return { path: '/fr/identity', replace: true }
      }
      if (to.path === '/platform-login') return { path: '/fr/identity', replace: true }
      if (to.path === '/platform-announcements') {
        return canReadHeadquartersAnnouncements(identity.currentPost, (permission) => identity.can(permission))
          ? true
          : { path: '/fr/identity', replace: true }
      }
      if (!to.path.startsWith('/fr')) return { path: '/fr/identity', replace: true }
      return true
    }
    if (to.path === '/login') {
      return { path: '/' }
    }
    const dictStore = useDictStoreWithOut()
    const userStore = useUserStoreWithOut()
    const permissionStore = usePermissionStoreWithOut()
    // 异步加载字典
    // 另外，间接 issue：https://gitee.com/yudaocode/yudao-ui-admin-vue3/issues/ID9FLI
    if (!dictStore.getIsSetDict) {
      dictStore.setDictMap().then()
    }
    if (!userStore.getIsSetUser) {
      isRelogin.show = true
      await userStore.setUserInfoAction()
      isRelogin.show = false
      // 后端过滤菜单
      await permissionStore.generateRoutes()
      permissionStore.getAddRouters.forEach((route) => {
        router.addRoute(route as unknown as RouteRecordRaw) // 动态添加可访问路由表
      })
      const redirectPath = from.query.redirect
      // 修复跳转时不带参数的问题
      const redirect = typeof redirectPath === 'string' ? redirectPath : to.fullPath
      const redirectLocation = parseRouteLocation(redirect)
      return to.fullPath === redirect
        ? { ...to, replace: true }
        : { ...redirectLocation, replace: true }
    }
    return true
  }
  if (whiteList.indexOf(to.path) !== -1) {
    return true
  }
  // 否则全部重定向到登录页
  return `/login?redirect=${encodeURIComponent(to.fullPath)}`
})

router.afterEach((to) => {
  useTitle(to?.meta?.title as string)
  done() // 结束Progress
  loadDone()
})
