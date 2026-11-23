import { createRouter, createWebHistory } from 'vue-router'
import type { RouteLocationNormalized } from 'vue-router'
import { h, type Component } from 'vue'
import WorkspaceLayout from '@/layouts/WorkspaceLayout.vue'
import { useAuthStore } from '@/stores/auth'
import { registerDynamicRoutes, resetDynamicRoutes } from '@/app/dynamic-routes'

/**
 * 深链直入的占位页面：后端菜单驱动的动态路由要等守卫里 loadProfile 之后才注册，
 * 这之前的首次导航若匹配不到路由，vue-router 会打 "No match found for location" 警告。
 * 预置一个占位兜底让首屏导航能匹配上，守卫加载完菜单后会按 path 重新导航到真实页面。
 * 注意必须是 component 而不是 redirect：redirect 会在守卫之前就改写目标，丢掉原始深链。
 * 内容给一行"加载中"而不是 null：万一将来有分支放行到这里，也不至于看到纯白屏。
 */
const DeepLinkPlaceholder: Component = {
  render: () => h('div', { class: 'p-6 text-sm text-muted-foreground' }, '加载中…'),
}

/** 本次会话是否已尝试拉过菜单：菜单为空的账号每次都拉会变成"每切一个路由 3 个请求" */
let menusAttempted = false

/**
 * 注册动态路由后按 path 重新匹配。
 * 不能展开 to：深链直入时 to 命中的是占位兜底，带着它的 name/matched 二次导航会再跑回兜底。
 */
const retarget = (to: RouteLocationNormalized) => ({
  path: to.path,
  query: to.query,
  hash: to.hash,
  replace: true,
})

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/pages/LoginPage.vue') },
    {
      path: '/',
      name: 'workspace',
      component: WorkspaceLayout,
      children: [
        { path: '', name: 'home', component: () => import('@/pages/HomePage.vue') },
        { path: '/profile', name: 'profile', component: () => import('@/pages/ProfilePage.vue') },
        { path: '/messages', name: 'messages', component: () => import('@/pages/MessagesPage.vue') },
      ],
    },
    // 动态路由注册后，同名的 not-found 会被 registerDynamicRoutes 覆盖为 redirect: '/'，故真实未知路径仍回首页
    { path: '/:pathMatch(.*)*', name: 'not-found', component: DeepLinkPlaceholder },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  if (!auth.isLogin) {
    menusAttempted = false
    resetDynamicRoutes(router)
    return to.name === 'login' ? true : { name: 'login', query: { redirect: to.fullPath } }
  }

  // 已登录但菜单尚未加载（刷新页面的场景）：先拉取菜单，再注册动态路由并重新导航。
  // 菜单确实为空的账号（角色未配菜单）只会尝试一次，避免每次导航都重复拉三份档案数据。
  if (auth.menus.length === 0) {
    if (!menusAttempted) {
      menusAttempted = true
      try {
        await auth.loadProfile()
      } catch {
        // 档案/菜单加载失败（典型：登录态已失效，如 Redis 会话被清）——回到登录页
        menusAttempted = false
        auth.logout()
        resetDynamicRoutes(router)
        return { name: 'login', query: { redirect: to.fullPath } }
      }
    }
    // 只有真正注册过路由才重导航；菜单为空时 register 不会产生任何路由，
    // 无条件重导航会让守卫原地打转（menus 依旧为空）
    if (registerDynamicRoutes(router, auth.menus)) return retarget(to)
  } else if (registerDynamicRoutes(router, auth.menus)) {
    // 登录后首次跳转：菜单已在内存，但动态路由尚未注册
    // 首次注册动态路由后需重新导航：目标动态路由在本次导航发起时尚不存在，
    // 直接放行会渲染空白页面（刷新才恢复）。
    return retarget(to)
  }

  // 404 决策集中在这里：兜底路由只保留 router.ts 里那一处定义，不再靠另一个模块隐式覆盖
  if (to.name === 'not-found') return { path: '/', replace: true }
  if (to.name === 'login') return { name: 'home' }
  return true
})

export default router
