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
        path: 'dashboard',
        name: 'FranchiseDashboard',
        component: () => import('@/views/fr/dashboard/index.vue'),
        meta: {
          title: '经营看板',
          icon: 'ep:data-analysis',
          noCache: true,
          permission: 'franchise:performance:operate'
        }
      },
      {
        path: 'customers',
        name: 'FranchiseCustomers',
        component: () => import('@/views/fr/customers/index.vue'),
        meta: {
          title: '客资',
          icon: 'ep:user-filled',
          noCache: true,
          permission: 'franchise:lead:handle'
        }
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
        path: 'wallet',
        name: 'FranchiseWallet',
        component: () => import('@/views/fr/wallet/index.vue'),
        meta: {
          title: '钱包对账',
          icon: 'ep:wallet',
          noCache: true,
          permission: 'platform:wallet:read'
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
