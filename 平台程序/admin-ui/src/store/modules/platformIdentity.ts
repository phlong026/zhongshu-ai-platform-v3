import { defineStore } from 'pinia'
import { store } from '@/store'
import { getCurrentPostContext, getMyPosts } from '@/api/platform/staff'
import { getAccessToken } from '@/utils/auth'
import {
  beginPostSwitch,
  clearPlatformIdentity,
  currentPostId,
  isCurrentGeneration,
  posts,
  saveCurrentContext,
  savePosts,
  setPortalMode,
  type CurrentPostContext,
  type PlatformId,
  type StaffPost
} from '@/utils/platformIdentity'

export const usePlatformIdentityStore = defineStore('platform-identity', {
  state: () => ({
    posts: posts() as StaffPost[],
    // 刷新后只保留候选岗位，权限必须经服务端重新确认。
    context: null as CurrentPostContext | null,
    loading: false,
    error: ''
  }),
  getters: {
    currentPost: (state) => state.context?.post || null,
    can: (state) => (permission: string) => state.context?.permissions.includes(permission) === true
  },
  actions: {
    async loadPosts() {
      this.error = ''
      const token = getAccessToken()
      let rows: StaffPost[]
      try {
        rows = (await getMyPosts()).filter(Boolean)
      } catch (error) {
        this.error = '工作身份加载失败，请检查网络后重试。'
        throw error
      }
      if (!token || token !== getAccessToken()) return this.posts
      this.posts = rows
      savePosts(rows)
      return rows
    },
    async selectPost(postId: PlatformId, clearViews = true) {
      if (!this.posts.some((post) => String(post.postId) === String(postId))) {
        throw new Error('所选工作身份已失效，请刷新后重试')
      }
      const generation = beginPostSwitch(postId)
      this.context = null
      this.error = ''
      this.loading = true
      if (clearViews) {
        const { useTagsViewStoreWithOut } = await import('@/store/modules/tagsView')
        useTagsViewStoreWithOut().delAllViews()
      }
      try {
        const context = await getCurrentPostContext()
        if (!isCurrentGeneration(generation)) return null
        if (!saveCurrentContext(generation, context)) return null
        this.context = context
        return context
      } finally {
        if (isCurrentGeneration(generation)) this.loading = false
      }
    },
    async restoreContext() {
      const rows = await this.loadPosts()
      if (!rows.length) {
        this.clear(true)
        return null
      }
      const selected = rows.find((post) => String(post.postId) === currentPostId())
      if (!selected && rows.length > 1) {
        beginPostSwitch('')
        this.context = null
        this.loading = false
        return null
      }
      return this.selectPost((selected || rows[0]).postId, false)
    },
    hasCurrentPostPermission(permission: string) {
      return this.context?.permissions.includes(permission) === true
    },
    clear(preservePortal = false) {
      clearPlatformIdentity()
      if (preservePortal && getAccessToken()) setPortalMode('staff', getAccessToken()!)
      this.posts = []
      this.context = null
      this.loading = false
      this.error = ''
    }
  }
})

export const usePlatformIdentityStoreWithOut = () => usePlatformIdentityStore(store)
