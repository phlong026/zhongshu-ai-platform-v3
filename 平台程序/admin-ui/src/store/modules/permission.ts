import { defineStore } from 'pinia'
import { store } from '@/store'
import { cloneDeep } from 'lodash-es'
import remainingRouter from '@/router/modules/remaining'
import frRouter from '@/router/modules/fr'
import { buildPlatformMenus, buildStaffMenus, groupPlatformNavigation } from '@/utils/projectNavigation'
import { checkPermi } from '@/utils/permission'
import { flatMultiLevelRoutes, generateRoute } from '@/utils/routerHelper'
import { CACHE_KEY, useCache } from '@/hooks/web/useCache'
import { usePlatformIdentityStoreWithOut } from '@/store/modules/platformIdentity'
import { canReadHeadquartersAnnouncements, portalMode } from '@/utils/platformIdentity'
import { getAccessToken } from '@/utils/auth'

const { wsCache } = useCache()

export interface PermissionState {
  routers: AppRouteRecordRaw[]
  addRouters: AppRouteRecordRaw[]
  menuTabRouters: AppRouteRecordRaw[]
  menuRootPath: string
}

export const usePermissionStore = defineStore('permission', {
  state: (): PermissionState => ({
    routers: [],
    addRouters: [],
    menuTabRouters: [],
    menuRootPath: ''
  }),
  getters: {
    getRouters(): AppRouteRecordRaw[] {
      return this.routers
    },
    getAddRouters(): AppRouteRecordRaw[] {
      return flatMultiLevelRoutes(cloneDeep(this.addRouters))
    },
    getMenuTabRouters(): AppRouteRecordRaw[] {
      return this.menuTabRouters
    },
    getMenuRootPath(): string {
      return this.menuRootPath
    }
  },
  actions: {
    async generateRoutes(): Promise<unknown> {
      return new Promise<void>(async (resolve) => {
        const platformIdentity = usePlatformIdentityStoreWithOut()
        if (portalMode(getAccessToken()) === 'staff') {
          try {
            if (!platformIdentity.context) await platformIdentity.restoreContext()
          } catch {
            platformIdentity.error = '工作身份加载失败，请检查网络后重试。'
          }
          this.addRouters = []
          this.routers = [
            ...remainingRouter.filter((route) => route.path === '/'),
            ...buildStaffMenus(
              cloneDeep(frRouter) as AppRouteRecordRaw[],
              (permission) => platformIdentity.hasCurrentPostPermission(permission),
              canReadHeadquartersAnnouncements(platformIdentity.currentPost, (permission) =>
                platformIdentity.hasCurrentPostPermission(permission)
              )
            )
          ]
          resolve()
          return
        }
        // 获得菜单列表，它在登录的时候，setUserInfoAction 方法中已经进行获取
        let res: AppCustomRouteRecordRaw[] = []
        const roleRouters = wsCache.get(CACHE_KEY.ROLE_ROUTERS)
        if (roleRouters) {
          res = roleRouters as AppCustomRouteRecordRaw[]
        }
        const routerMap = buildPlatformMenus([], generateRoute(res), checkPermi)
        // 动态路由，404一定要放到最后面
        // preschooler：vue-router@4以后已支持静态404路由，此处可不再追加
        this.addRouters = routerMap.concat([
          {
            path: '/:path(.*)*',
            // redirect: '/404',
            component: () => import('@/views/Error/404.vue'),
            name: '404Page',
            meta: {
              hidden: true,
              breadcrumb: false
            }
          }
        ])
        // 渲染菜单的所有路由
        this.routers = groupPlatformNavigation(buildPlatformMenus(cloneDeep(remainingRouter), routerMap, checkPermi))
        resolve()
      })
    },
    setMenuTabRouters(routers: AppRouteRecordRaw[]): void {
      this.menuTabRouters = routers
    },
    setMenuRootPath(path: string): void {
      this.menuRootPath = path
    }
  },
  persist: false
})

export const usePermissionStoreWithOut = () => {
  return usePermissionStore(store)
}
