import type { RouteRecordRaw } from 'vue-router'
import { Layout } from '@/utils/routerHelper'

const frRouter: RouteRecordRaw[] = [
  {
    path: '/platform-login',
    name: 'PlatformStaffLogin',
    component: () => import('@/views/fr/platform-login/index.vue'),
    meta: { hidden: true, title: '员工短信登录' }
  },
  {
    path: '/fr',
    component: Layout,
    name: 'FranchiseStaffWorkspace',
    redirect: '/fr/identity',
    meta: { title: '组织管理', icon: 'ep:user-filled' },
    children: [
      {
        path: 'identity',
        name: 'FranchiseIdentity',
        component: () => import('@/views/fr/identity/index.vue'),
        meta: { title: '工作身份', icon: 'ep:switch', noCache: true }
      },
      {
        path: 'org/staff',
        name: 'FranchiseOrgStaff',
        component: () => import('@/views/fr/org-staff/index.vue'),
        meta: {
          title: '部门与员工',
          icon: 'ep:user',
          noCache: true,
          permission: 'platform:org:query'
        }
      },
      {
        path: 'finance',
        name: 'FranchiseFinance',
        component: () => import('@/views/fr/finance/index.vue'),
        meta: {
          title: '财务核对',
          icon: 'ep:money',
          noCache: true,
          permission: 'franchise:fin:query'
        }
      }
    ]
  }
]

export default frRouter
