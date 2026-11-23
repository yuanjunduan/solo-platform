import type { Router, RouteRecordRaw } from 'vue-router'
import type { UserAuthResponse } from '@/types/api'

/**
 * 约定式动态路由：
 * 后端菜单 url 为 /system/user，对应页面文件 src/pages/system/user/index.vue。
 * 文件系统即路由表，新增页面只需在对应目录建 index.vue，无需手写路由。
 */
const pageModules = import.meta.glob('@/pages/**/index.vue')

/** 已存在页面组件的 url 集合（key 形如 /system/user） */
const EXISTING_PATHS: ReadonlySet<string> = new Set(
  Object.keys(pageModules)
    .map((key) => key.replace(/^.*\/src\/pages/, '').replace(/\/index\.vue$/, ''))
    .filter((path) => path.length > 0),
)

/** 判断某个菜单 url 是否有对应的前端页面 */
export const hasPage = (url?: string | null): boolean => !!url && EXISTING_PATHS.has(url)

let registered = false

/** 拍平菜单树，取出所有节点（路由注册需遍历全部，含目录与叶子） */
const flattenMenus = (menus: UserAuthResponse[]): UserAuthResponse[] => {
  const result: UserAuthResponse[] = []
  const walk = (list: UserAuthResponse[]) => {
    list.forEach((menu) => {
      result.push(menu)
      if (menu.children?.length) walk(menu.children)
    })
  }
  walk(menus)
  return result
}

/** 动态路由名字前缀：登出时据此精确回收，避免上一个账号的路由残留给下一个账号 */
const DYNAMIC_NAME_PREFIX = 'dynamic-'

/**
 * 依据后端菜单树，在 WorkspaceLayout 下注册动态路由。
 * 仅注册“有对应页面文件”的叶子菜单，目录节点与未迁移模块自然跳过。
 * 每个会话只注册一次，登出时调用 resetDynamicRoutes 卸载并复位。
 *
 * @returns 本次调用是否真正执行了注册（首次为 true，之后幂等返回 false）。
 *          在导航守卫中首次注册后需据此重新导航，否则目标动态路由在本次
 *          导航发起时尚不存在，确认 true 会渲染空白。
 */
export const registerDynamicRoutes = (router: Router, menus: UserAuthResponse[]): boolean => {
  if (registered) return false
  flattenMenus(menus).forEach((menu) => {
    if (!menu.url || !hasPage(menu.url)) return
    const route:RouteRecordRaw = {
      path: menu.url,
      name: `${DYNAMIC_NAME_PREFIX}${menu.url.replace(/\//g, '-')}`,
      component: pageModules[`/src/pages${menu.url}/index.vue`],
      meta: { title: menu.permissionName },
    }
    router.addRoute('workspace', route)
  })
  // 兜底（/:pathMatch(.*)*）由 router.ts 预置的占位路由承担，未匹配路径统一在守卫里判定回落首页，
  // 这里不再重复定义 not-found：同名覆盖依赖 vue-router 的隐式 remove 语义，容易在重构中静默失效。
  registered = true
  return true
}

/**
 * 登出/切换账号：卸载本次会话注册的动态路由并复位，允许下次登录重新注册。
 *
 * <p>只复位 registered 会把上个账号的路由留在 matcher 里，新账号仍可用深链直达这些页面
 * （后端接口仍会鉴权，但前端路由层已经泄漏了入口与菜单结构），因此必须逐个 removeRoute。</p>
 */
export const resetDynamicRoutes = (router: Router) => {
  if (!registered) return
  router
    .getRoutes()
    .filter((route) => typeof route.name === 'string' && route.name.startsWith(DYNAMIC_NAME_PREFIX))
    .forEach((route) => router.removeRoute(route.name as string))
  registered = false
}
