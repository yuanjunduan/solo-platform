import { onBeforeUnmount, readonly, ref } from 'vue'

/**
 * 秒级“当前时间”tick，用于在客户端把时间点换算成实时时长（如推流已持续）。
 *
 * <p>模块级共享一个定时器：多个组件同时订阅时只有一份 setInterval，
 * 订阅数为 0 时立刻清掉，不常驻后台空转。</p>
 */
const now = ref(Date.now())
let timer: ReturnType<typeof setInterval> | null = null
let subscribers = 0

export function useNowTick(intervalMs = 1000) {
  subscribers += 1
  if (!timer) {
    now.value = Date.now()
    timer = setInterval(() => {
      now.value = Date.now()
    }, intervalMs)
  }
  onBeforeUnmount(() => {
    subscribers -= 1
    if (subscribers <= 0 && timer) {
      clearInterval(timer)
      timer = null
    }
  })
  return readonly(now)
}
