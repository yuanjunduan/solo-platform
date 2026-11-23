<template>
  <div class="relative flex h-full gap-3 overflow-hidden p-3">
    <!-- 左侧：订阅列表 -->
    <div class="flex min-w-0 flex-1 flex-col overflow-hidden rounded-xl border border-border bg-card">
      <!-- 工具栏 -->
      <div class="flex h-12 shrink-0 items-center gap-2 border-b border-border px-3">
        <span class="text-[13px] font-medium">资源事件订阅</span>
        <span class="text-[11px] text-muted-foreground">
          第三方首次调 SSE 时自动创建，此处只维护范围
        </span>
        <div class="flex-1" />
        <RefreshButton :loading="resource.loading" :on-refresh="onRefresh" />
      </div>

      <!-- 搜索行 -->
      <div class="flex h-12 shrink-0 items-center gap-2 px-3">
        <IconSearch class="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
        <input
          ref="appKeyRef"
          v-model="appKey"
          class="h-7 w-56 rounded-md bg-transparent px-2 text-[13px] outline-none placeholder:text-muted-foreground focus:bg-muted/70"
          placeholder="搜索 appKey"
        />

        <div class="group flex items-center">
          <Select v-model="statusFilter">
            <SelectTrigger
              class="inline-flex items-center gap-1 text-xs font-normal text-muted-foreground outline-none hover:text-foreground"
            >
              状态
              <IconFilter
                class="size-3 opacity-40 transition-opacity group-hover:opacity-100"
                :class="statusFilter !== '__all__' ? 'fill-primary text-primary opacity-100' : ''"
              />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="__all__">全部</SelectItem>
              <SelectItem value="1">启用</SelectItem>
              <SelectItem value="0">停用</SelectItem>
            </SelectContent>
          </Select>
        </div>

        <span v-if="hasCondition" class="text-xs text-muted-foreground">
          匹配 {{ resource.total.value }} 个
        </span>

        <div class="flex-1" />

        <Button
          v-if="hasCondition"
          variant="ghost"
          size="sm"
          class="h-7 text-xs text-muted-foreground"
          @click="onClear"
        >
          <IconX class="size-3.5" />
          清除
        </Button>
      </div>

      <!-- 列表 -->
      <div ref="scrollerRef" class="min-h-0 flex-1 overflow-auto">
        <table class="w-full table-fixed border-collapse text-left text-[13px]">
          <thead class="sticky top-0 z-10">
            <tr class="bg-card text-xs text-muted-foreground">
              <th class="w-44 px-3 py-2 font-normal">应用</th>
              <th class="w-44 px-3 py-2 font-normal">appKey</th>
              <th class="w-32 px-3 py-2 font-normal">来源平台</th>
              <th class="w-40 px-3 py-2 font-normal">组织范围</th>
              <th class="w-24 px-3 py-2 font-normal">游标</th>
              <th class="w-16 px-3 py-2 font-normal">状态</th>
              <th class="w-40 px-3 py-2 font-normal">最近更新</th>
            </tr>
          </thead>

          <tbody>
            <tr
              v-for="(s, i) in resource.items.value"
              :key="s.id"
              :data-row="i"
              class="group cursor-pointer"
              :class="
                selected?.id === s.id
                  ? 'bg-primary/[0.07]'
                  : cursor === i
                    ? 'bg-muted/50'
                    : 'hover:bg-muted/30'
              "
              @click="onRowClick(s, i)"
            >
              <td class="relative px-3 py-2">
                <span
                  v-if="selected?.id === s.id"
                  class="absolute inset-y-0 left-0 w-0.5 bg-primary"
                />
                <span class="truncate" :title="s.appName || s.appKey">
                  {{ s.appName || '—' }}
                </span>
              </td>
              <td class="truncate px-3 py-2 font-mono text-xs text-muted-foreground" :title="s.appKey">
                {{ s.appKey }}
              </td>
              <td class="truncate px-3 py-2" :title="s.platformName || '全部平台'">
                <span v-if="s.platformName">{{ s.platformName }}</span>
                <span v-else class="text-muted-foreground">全部平台</span>
              </td>
              <td class="truncate px-3 py-2 font-mono text-xs" :title="s.orgPath || '整租户'">
                <span v-if="s.orgPath">{{ s.orgPath }}</span>
                <span v-else class="text-muted-foreground">整租户</span>
              </td>
              <td class="truncate px-3 py-2 font-mono text-xs text-muted-foreground" :title="s.lastEventId">
                {{ s.lastEventId || '0' }}
              </td>
              <td class="px-3 py-2">
                <span
                  class="text-xs"
                  :class="s.status === 1 ? 'text-emerald-500' : 'text-muted-foreground'"
                >
                  {{ s.status === 1 ? '启用' : '停用' }}
                </span>
              </td>
              <td class="truncate px-3 py-2 font-mono text-xs text-muted-foreground" :title="s.updateTime || ''">
                {{ s.updateTime || '—' }}
              </td>
            </tr>
          </tbody>
        </table>

        <div v-if="firstLoading" class="grid place-items-center py-16">
          <IconLoader2 class="size-5 animate-spin text-muted-foreground" />
        </div>

        <div
          v-else-if="!resource.loading.value && resource.items.value.length === 0"
          class="flex flex-col items-center gap-2 py-16 text-muted-foreground"
        >
          <IconBroadcast class="size-8" />
          <div class="text-[13px]">
            {{ hasCondition ? '没有匹配的订阅' : '还没有任何订户' }}
          </div>
          <div class="max-w-md text-center text-[11px]">
            订户用 appKey 签名调用
            <span class="font-mono">/open-api/v1/events/stream</span>
            后会自动在这里出现一条订阅，事件从这里按游标推送
          </div>
          <Button v-if="hasCondition" variant="ghost" size="sm" @click="onClear">
            清除搜索条件
          </Button>
        </div>

        <div ref="sentinelRef" class="grid place-items-center py-4">
          <IconLoader2
            v-if="resource.loading.value && resource.items.value.length"
            class="size-4 animate-spin text-muted-foreground"
          />
          <span
            v-else-if="resource.finished.value && resource.items.value.length"
            class="text-xs text-muted-foreground"
          >
            已全部加载
          </span>
        </div>
      </div>

      <!-- 快捷键条 -->
      <div
        class="flex h-8 shrink-0 items-center gap-3 border-t border-border px-3 text-[11px] text-muted-foreground/70"
      >
        <span><kbd>/</kbd> 搜索</span>
        <span><kbd>J</kbd><kbd>K</kbd> 移动</span>
        <span><kbd>Enter</kbd> 查看</span>
        <span><kbd>R</kbd> 刷新</span>
        <div class="flex-1" />
        <span>共 {{ resource.total.value }} 条订阅</span>
      </div>
    </div>

    <!-- 右侧：检视器 -->
    <Transition name="inspector">
      <aside
        v-if="selected"
        class="flex w-[26rem] shrink-0 flex-col overflow-hidden rounded-xl border border-border bg-card"
      >
        <div class="min-h-0 flex-1 overflow-y-auto p-5">
          <OpenSubscriptionInspector
            :sub="selected"
            @close="onCloseInspector"
            @changed="onChanged"
          />
        </div>
      </aside>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  IconSearch,
  IconX,
  IconFilter,
  IconBroadcast,
  IconLoader2,
} from '@tabler/icons-vue'
import OpenSubscriptionInspector from '@/features/openapi/OpenSubscriptionInspector.vue'
import { getSubscriptionPageApi } from '@/api/openapi'
import { useResource } from '@/shared/composables/useResource'
import Button from '@/components/ui/button/Button.vue'
import RefreshButton from '@/components/RefreshButton.vue'
import { Select, SelectContent, SelectItem, SelectTrigger } from '@/components/ui/select'
import type { EventSubscription, EventSubscriptionPageRequest } from '@/types/api'

const appKey = ref('')
const statusFilter = ref<'__all__' | '1' | '0'>('__all__')

const hasCondition = computed(
  () => !!appKey.value.trim() || statusFilter.value !== '__all__',
)

const resource = useResource<EventSubscriptionPageRequest, EventSubscription>({
  fetcher: (q) =>
    getSubscriptionPageApi({
      ...q,
      appKey: appKey.value.trim() || undefined,
      status: statusFilter.value === '__all__' ? undefined : Number(statusFilter.value),
    }),
  initial: { page: 1, pageSize: 50 },
})

const onRefresh = () => resource.load(true)

let debounceTimer: ReturnType<typeof setTimeout> | null = null
watch(appKey, () => {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => {
    cursor.value = 0
    resource.search()
  }, 300)
})
watch(statusFilter, () => {
  cursor.value = 0
  resource.search()
})

const onClear = () => {
  appKey.value = ''
  statusFilter.value = '__all__'
  cursor.value = 0
  resource.search()
}

const cursor = ref(0)
const selected = ref<EventSubscription | null>(null)

const scrollerRef = ref<HTMLElement>()
const sentinelRef = ref<HTMLElement>()
const appKeyRef = ref<HTMLInputElement>()

const firstLoading = computed(
  () => resource.loading.value && resource.items.value.length === 0,
)

const scrollCursorIntoView = () => {
  scrollerRef.value
    ?.querySelector(`[data-row="${cursor.value}"]`)
    ?.scrollIntoView({ block: 'nearest' })
}

const onRowClick = (s: EventSubscription, i: number) => {
  cursor.value = i
  selected.value = s
}

const onCloseInspector = () => {
  selected.value = null
}

const onChanged = () => resource.load(true)

const isTypingTarget = (el: EventTarget | null) =>
  el instanceof HTMLElement &&
  (el.tagName === 'INPUT' ||
    el.tagName === 'TEXTAREA' ||
    el.tagName === 'SELECT' ||
    el.isContentEditable)

const onGlobalKeydown = (e: KeyboardEvent) => {
  if (isTypingTarget(e.target)) {
    if (e.key === 'Escape') (e.target as HTMLElement).blur()
    return
  }
  const list = resource.items.value
  switch (e.key) {
    case '/':
      e.preventDefault()
      appKeyRef.value?.focus()
      break
    case 'j':
    case 'J':
      e.preventDefault()
      if (list.length) {
        cursor.value = Math.min(list.length - 1, cursor.value + 1)
        scrollCursorIntoView()
      }
      break
    case 'k':
    case 'K':
      e.preventDefault()
      cursor.value = Math.max(0, cursor.value - 1)
      scrollCursorIntoView()
      break
    case 'Enter':
      e.preventDefault()
      if (list[cursor.value]) onRowClick(list[cursor.value], cursor.value)
      break
    case 'r':
    case 'R':
      e.preventDefault()
      onRefresh()
      break
    case 'Escape':
      onCloseInspector()
      break
  }
}

let observer: IntersectionObserver | null = null

onMounted(async () => {
  window.addEventListener('keydown', onGlobalKeydown)
  await resource.load(true)
  observer = new IntersectionObserver(
    (entries) => {
      if (entries[0].isIntersecting) resource.load(false)
    },
    { root: scrollerRef.value, rootMargin: '80px' },
  )
  if (sentinelRef.value) observer.observe(sentinelRef.value)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onGlobalKeydown)
  observer?.disconnect()
  if (debounceTimer) clearTimeout(debounceTimer)
})

// 刷新后把选中项换成新对象，避免检视器继续显示改之前的旧值
watch(
  () => resource.items.value,
  (list) => {
    if (selected.value) {
      const fresh = list.find((s) => s.id === selected.value!.id)
      if (fresh) selected.value = fresh
    }
    if (cursor.value > list.length - 1) cursor.value = Math.max(0, list.length - 1)
  },
)
</script>

<style scoped>
.inspector-enter-active,
.inspector-leave-active {
  transition:
    opacity 0.18s ease,
    transform 0.18s ease;
}
.inspector-enter-from,
.inspector-leave-to {
  opacity: 0;
  transform: translateX(12px);
}

kbd {
  display: inline-block;
  border: 1px solid var(--border);
  border-radius: 3px;
  background: var(--muted);
  padding: 0 4px;
  font-size: 10px;
  line-height: 16px;
  color: var(--muted-foreground);
  font-family: ui-monospace, monospace;
}
</style>
