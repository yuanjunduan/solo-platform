<template>
  <div class="flex h-full gap-3 overflow-hidden p-3">
    <!-- 左：平台汇总卡片 -->
    <div class="flex w-72 shrink-0 flex-col overflow-hidden rounded-xl border border-border bg-card">
      <div class="flex h-12 shrink-0 items-center gap-2 border-b border-border px-3">
        <IconShare2 class="size-4 text-muted-foreground" />
        <span class="text-[13px] font-medium">目标上级</span>
        <div class="flex-1" />
        <RefreshButton :loading="targetsLoading" :on-refresh="loadTargets" />
      </div>

      <div class="min-h-0 flex-1 space-y-2 overflow-auto p-2">
        <button
          type="button"
          class="w-full rounded-lg border px-2.5 py-2 text-left text-[12px] transition-colors"
          :class="selectedPlatformId === null ? 'border-primary/40 bg-primary/[0.06]' : 'border-border hover:bg-muted/30'"
          @click="selectPlatform(null)"
        >
          <div class="flex items-center gap-2">
            <IconStack2 class="size-3.5 text-muted-foreground" />
            <span class="font-medium">全部平台</span>
          </div>
          <div class="mt-1 text-[11px] text-muted-foreground">跨上级查看共享项</div>
        </button>

        <button
          v-for="t in targets"
          :key="t.id"
          type="button"
          class="w-full rounded-lg border px-2.5 py-2 text-left transition-colors"
          :class="selectedPlatformId === t.id ? 'border-primary/40 bg-primary/[0.06]' : 'border-border hover:bg-muted/30'"
          @click="selectPlatform(t.id)"
        >
          <div class="flex items-center gap-2">
            <span class="min-w-0 flex-1 truncate text-[12px] font-medium" :title="t.name">{{ t.name }}</span>
            <span class="shrink-0 rounded px-1 py-0.5 text-[10px]" :class="statusPillClass(t.summaryStatus)">
              {{ statusText(t.summaryStatus) }}
            </span>
          </div>
          <div class="mt-1 truncate font-mono text-[10px] text-muted-foreground/70">{{ t.serverGbId || '未登记国标码' }}</div>
          <div class="mt-1.5 grid grid-cols-4 gap-1 text-center text-[10px] text-muted-foreground">
            <div>
              <div class="font-mono text-[12px] text-foreground">{{ t.channelCount }}</div>
              <div>通道</div>
            </div>
            <div>
              <div class="font-mono text-[12px] text-foreground">{{ t.ruleCount }}</div>
              <div>规则</div>
            </div>
            <div>
              <div class="font-mono text-[12px]" :class="t.excludedCount ? 'text-amber-600' : ''">{{ t.excludedCount }}</div>
              <div>剔除</div>
            </div>
            <div>
              <div class="font-mono text-[12px]" :class="t.pendingCount ? 'text-sky-600' : ''">{{ t.pendingCount }}</div>
              <div>待推</div>
            </div>
          </div>
          <div class="mt-1 text-[10px] text-muted-foreground/70">
            最近一轮：{{ roundStatusText(t.lastRoundStatus) }}
            <span v-if="t.lastRoundStatus"> · 成功 {{ t.lastRoundSent ?? 0 }} / 失败 {{ t.lastRoundFailed ?? 0 }}</span>
          </div>
        </button>

        <div v-if="!targets.length && !targetsLoading" class="px-2 py-8 text-center text-[11px] text-muted-foreground">
          还没有登记上级平台
        </div>
      </div>
    </div>

    <!-- 右：共享项 -->
    <div class="flex min-w-0 flex-1 flex-col overflow-hidden rounded-xl border border-border bg-card">
      <div class="flex h-12 shrink-0 items-center gap-2 border-b border-border px-3">
        <span class="text-[13px] font-medium">共享项</span>
        <span class="text-xs text-muted-foreground">{{ selectedTargetName }}</span>
        <div class="flex-1" />
        <Input
          v-model="keyword"
          class="h-8 w-44 text-xs"
          placeholder="编码 / 组织路径"
          @update:model-value="triggerSearch"
        />
        <Select :model-value="refAliveFilter ?? 'ALL'" @update:model-value="onRefAliveChange">
          <SelectTrigger size="sm" class="w-28 text-xs">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">全部存活</SelectItem>
            <SelectItem value="1">存活</SelectItem>
            <SelectItem value="0">已撤销</SelectItem>
          </SelectContent>
        </Select>
        <Select :model-value="statusFilter ?? 'ALL'" @update:model-value="onStatusChange">
          <SelectTrigger size="sm" class="w-28 text-xs">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">全部状态</SelectItem>
            <SelectItem value="PENDING">待推送</SelectItem>
            <SelectItem value="SYNCED">已同步</SelectItem>
            <SelectItem value="FAILED">推送失败</SelectItem>
            <SelectItem value="DEAD">已死亡</SelectItem>
            <SelectItem value="REVOKING">撤销中</SelectItem>
          </SelectContent>
        </Select>
        <RefreshButton :loading="itemLoading" :on-refresh="refreshItems" />
      </div>

      <div class="flex h-9 shrink-0 items-center gap-2 border-b border-border px-3">
        <span class="text-[11px] text-muted-foreground">维护动作</span>
        <div class="flex-1" />
        <Button
          v-access="PERM.SHARE_OVERVIEW_EDIT"
          variant="ghost"
          size="sm"
          class="gap-1.5"
          :disabled="!selectedPlatformId || busy"
          @click="onRebuild"
        >
          <IconRefresh class="size-3.5" />
          重算派生
        </Button>
        <Button
          v-access="PERM.SHARE_OVERVIEW_EDIT"
          variant="ghost"
          size="sm"
          class="gap-1.5"
          :disabled="!selectedPlatformId || busy"
          @click="onReconcile"
        >
          <IconArrowsExchange class="size-3.5" />
          对账
        </Button>
        <Button
          v-access="PERM.SHARE_OVERVIEW_EDIT"
          variant="ghost"
          size="sm"
          class="gap-1.5 text-red-500 hover:text-red-600"
          :disabled="!selectedPlatformId || busy"
          @click="onRevokePlatform"
        >
          <IconTrash class="size-3.5" />
          撤销整平台
        </Button>
      </div>

      <div ref="scrollerRef" class="min-h-0 flex-1 overflow-auto">
        <table class="w-full table-fixed border-collapse text-left text-[13px]">
          <thead class="sticky top-0 z-10">
            <tr class="bg-card text-xs text-muted-foreground">
              <th class="w-28 px-3 py-2 font-normal">平台</th>
              <th class="w-16 px-3 py-2 font-normal">类型</th>
              <th class="w-52 px-3 py-2 font-normal">资源</th>
              <th class="w-36 px-3 py-2 font-normal">挂载组织</th>
              <th class="w-20 px-3 py-2 font-normal">模式</th>
              <th class="w-20 px-3 py-2 font-normal">来源</th>
              <th class="w-24 px-3 py-2 font-normal">状态</th>
              <th class="w-20 px-3 py-2 font-normal">可见</th>
              <th class="w-36 px-3 py-2 font-normal">最近推送</th>
              <th class="w-24 px-3 py-2 font-normal">操作</th>
            </tr>
          </thead>
          <tbody v-if="!itemLoading || resource.items.value.length">
            <tr v-for="r in resource.items.value" :key="r.id" class="border-b border-border/50 hover:bg-muted/20">
              <td class="truncate px-3 py-1.5 text-xs" :title="r.platformName ?? ''">{{ r.platformName || `#${r.platformId}` }}</td>
              <td class="px-3 py-1.5 text-xs">{{ refTypeText(r.refType) }}</td>
              <td class="truncate px-3 py-1.5" :title="r.refName ?? r.refCode ?? ''">
                {{ r.refName || r.refCode || r.refId || '—' }}
                <span class="ml-1 font-mono text-[10px] text-muted-foreground/70">{{ r.refCode }}</span>
                <span v-if="r.refAlive === 0" class="ml-1 rounded bg-red-500/10 px-1 text-[10px] text-red-500">已撤销</span>
              </td>
              <td class="truncate px-3 py-1.5 text-xs text-muted-foreground" :title="r.groupName ?? ''">{{ r.groupName || '根' }}</td>
              <td class="px-3 py-1.5 text-xs text-muted-foreground">{{ r.scopeMode === 'SUBTREE' ? '子树' : '显式' }}</td>
              <td class="px-3 py-1.5 text-xs text-muted-foreground">{{ r.derived === 1 ? '规则派生' : '手工' }}</td>
              <td class="px-3 py-1.5">
                <span class="text-xs" :class="shareStatusClass(r.status)">{{ statusText(r.status) }}</span>
                <div v-if="r.lastError" class="truncate text-[10px] text-red-500/80" :title="r.lastError">{{ r.lastError }}</div>
              </td>
              <td class="px-3 py-1.5">
                <span class="text-xs" :class="r.enabled === 0 ? 'text-amber-600' : 'text-emerald-600'">
                  {{ r.enabled === 0 ? '已剔除' : '可见' }}
                </span>
              </td>
              <td class="truncate px-3 py-1.5 font-mono text-[11px] text-muted-foreground">{{ fmtTime(r.lastPushedAt) }}</td>
              <td class="px-3 py-1.5">
                <div class="flex items-center gap-1">
                  <button
                    v-if="r.refType === 'CHANNEL' && r.refAlive === 1 && r.enabled === 1"
                    v-access="PERM.SHARE_OVERVIEW_EDIT"
                    class="flex size-6 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-amber-600"
                    title="剔除（保留派生关系）"
                    @click="onExclude(r)"
                  >
                    <IconBan class="size-3.5" />
                  </button>
                  <button
                    v-else-if="r.refType === 'CHANNEL' && r.refAlive === 1 && r.enabled === 0"
                    v-access="PERM.SHARE_OVERVIEW_EDIT"
                    class="flex size-6 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-emerald-600"
                    title="取消剔除"
                    @click="onRestore(r)"
                  >
                    <IconArrowBackUp class="size-3.5" />
                  </button>
                  <button
                    v-if="r.refType === 'CHANNEL' && r.refAlive === 1"
                    v-access="PERM.SHARE_OVERVIEW_EDIT"
                    class="flex size-6 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-red-500"
                    title="撤销共享（置死 + 推 DELETE）"
                    @click="onRevoke(r)"
                  >
                    <IconTrash class="size-3.5" />
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>

        <div v-if="firstLoading" class="grid place-items-center py-16">
          <IconLoader2 class="size-5 animate-spin text-muted-foreground" />
        </div>
        <div
          v-else-if="!itemLoading && !resource.items.value.length"
          class="flex flex-col items-center gap-2 py-16 text-muted-foreground"
        >
          <IconList class="size-8" />
          <div class="text-[13px]">还没有共享项</div>
          <div class="text-[11px] text-muted-foreground/70">到「共享编排」里选择组织或通道并提交</div>
        </div>

        <div ref="sentinelRef" class="grid place-items-center py-4">
          <IconLoader2 v-if="itemLoading && resource.items.value.length" class="size-4 animate-spin text-muted-foreground" />
          <span v-else-if="resource.finished.value && resource.items.value.length" class="text-xs text-muted-foreground">
            已全部加载
          </span>
        </div>
      </div>

      <div class="flex h-8 shrink-0 items-center gap-3 border-t border-border px-3 text-[11px] text-muted-foreground/70">
        <span>共 {{ resource.total.value }} 条共享项</span>
        <div class="flex-1" />
        <span>剔除保留派生关系；撤销把行置死，恢复需重新勾选</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  IconArrowBackUp,
  IconArrowsExchange,
  IconBan,
  IconList,
  IconLoader2,
  IconRefresh,
  IconShare2,
  IconStack2,
  IconTrash,
} from '@tabler/icons-vue'
import Button from '@/components/ui/button/Button.vue'
import Input from '@/components/ui/input/Input.vue'
import RefreshButton from '@/components/RefreshButton.vue'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { useResource } from '@/shared/composables/useResource'
import { PERM } from '@/features/access/permissions'
import { confirm } from '@/lib/confirm'
import { toast } from '@/lib/toast'
import {
  getOverviewTargetsApi,
  overviewExcludeApi,
  overviewRebuildApi,
  overviewReconcileApi,
  overviewRestoreApi,
  overviewRevokeApi,
  overviewRevokePlatformApi,
  pageOverviewItemsApi,
} from '@/api/share'
import type { OutShare, ShareItemQuery, ShareSummaryStatus, ShareTarget } from '@/types/api'

/* ---------------- 平台汇总 ---------------- */
const targets = ref<ShareTarget[]>([])
const targetsLoading = ref(false)
const selectedPlatformId = ref<string | null>(null)
const busy = ref(false)

const selectedTargetName = computed(() =>
  selectedPlatformId.value ? (targets.value.find((t) => t.id === selectedPlatformId.value)?.name ?? `#${selectedPlatformId.value}`) : '全部平台',
)

const loadTargets = async () => {
  targetsLoading.value = true
  try {
    targets.value = (await getOverviewTargetsApi()).data ?? []
    if (selectedPlatformId.value && !targets.value.some((t) => t.id === selectedPlatformId.value)) {
      selectedPlatformId.value = null
    }
  } catch {
    targets.value = []
  } finally {
    targetsLoading.value = false
  }
}

const selectPlatform = (id: string | null) => {
  selectedPlatformId.value = id
  resource.search()
}

/* ---------------- 共享项 ---------------- */
const keyword = ref('')
const statusFilter = ref<ShareSummaryStatus | null>(null)
const refAliveFilter = ref<number | null>(1)

const resource = useResource<ShareItemQuery, OutShare>({
  fetcher: (q) =>
    pageOverviewItemsApi({
      ...q,
      platformId: selectedPlatformId.value ?? undefined,
      status: statusFilter.value ?? undefined,
      refAlive: refAliveFilter.value ?? undefined,
      enabled: undefined,
      scopeMode: undefined,
    }),
  initial: { page: 1, pageSize: 30, keyword: '' },
})

const itemLoading = computed(() => resource.loading.value)
const firstLoading = computed(() => resource.loading.value && resource.items.value.length === 0)
const refreshItems = () => resource.load(true)

let searchTimer: ReturnType<typeof setTimeout> | undefined
const triggerSearch = () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    resource.query.keyword = keyword.value
    resource.search()
  }, 300)
}

const onStatusChange = (v: unknown) => {
  statusFilter.value = v === 'ALL' ? null : (v as ShareSummaryStatus)
  resource.search()
}

const onRefAliveChange = (v: unknown) => {
  refAliveFilter.value = v === 'ALL' ? null : Number(v)
  resource.search()
}

/* ---------------- 行操作 ---------------- */
const afterMutation = async (msg: string) => {
  toast.success(msg)
  await Promise.all([resource.load(true), loadTargets()])
}

const onExclude = async (r: OutShare) => {
  if (!r.refId) return
  const ok = await confirm({
    title: '剔除该通道',
    description: '剔除后上级不再看到该通道，派生关系保留，可随时取消剔除。',
    confirmText: '剔除',
  })
  if (!ok) return
  try {
    await overviewExcludeApi({ platformId: r.platformId, channelId: r.refId })
    await afterMutation('已剔除')
  } catch {
    // 拦截器已提示
  }
}

const onRestore = async (r: OutShare) => {
  if (!r.refId) return
  try {
    await overviewRestoreApi({ platformId: r.platformId, channelId: r.refId })
    await afterMutation('已取消剔除')
  } catch {
    // 拦截器已提示
  }
}

const onRevoke = async (r: OutShare) => {
  if (!r.refId) return
  const ok = await confirm({
    title: '撤销该条共享',
    description: '撤销会把该行置死并推送 DELETE；DELETE 推送成功前，上级点播/控制会被直接拒绝。恢复需重新勾选。',
    confirmText: '撤销',
    danger: true,
  })
  if (!ok) return
  try {
    await overviewRevokeApi({ platformId: r.platformId, channelId: r.refId })
    await afterMutation('已撤销')
  } catch {
    // 拦截器已提示
  }
}

/* ---------------- 平台级维护 ---------------- */
const onRevokePlatform = async () => {
  if (!selectedPlatformId.value) return
  const ok = await confirm({
    title: `撤销「${selectedTargetName.value}」的全部共享`,
    description: '全部共享行置死、虚拟组织树清空，并合并为一条 REFRESH 推送。该操作不可撤销，需要重新编排。',
    confirmText: '确认撤销',
    danger: true,
  })
  if (!ok) return
  busy.value = true
  try {
    await overviewRevokePlatformApi(selectedPlatformId.value)
    await afterMutation('已撤销该平台全部共享')
  } catch {
    // 拦截器已提示
  } finally {
    busy.value = false
  }
}

const onRebuild = async () => {
  if (!selectedPlatformId.value) return
  busy.value = true
  try {
    const r = await overviewRebuildApi(selectedPlatformId.value)
    toast.success(`已重算：新增 ${r.data.added}、恢复 ${r.data.restored}、移除 ${r.data.removed}`)
    await Promise.all([resource.load(true), loadTargets()])
  } catch {
    // 拦截器已提示
  } finally {
    busy.value = false
  }
}

const onReconcile = async () => {
  if (!selectedPlatformId.value) return
  busy.value = true
  try {
    const r = await overviewReconcileApi(selectedPlatformId.value)
    const d = r.data
    if (d.derivedAdded + d.derivedRestored + d.derivedRemoved + d.readded + d.redeleted === 0 && !d.refreshed) {
      toast.success('对账完成，没有发现偏差')
    } else {
      toast.success(
        `对账完成：补派生 ${d.derivedAdded}、恢复 ${d.derivedRestored}、移除 ${d.derivedRemoved}、补推 ${d.readded}、补删 ${d.redeleted}${d.refreshed ? '、已合并 REFRESH' : ''}`,
      )
    }
    await Promise.all([resource.load(true), loadTargets()])
  } catch {
    // 拦截器已提示
  } finally {
    busy.value = false
  }
}

/* ---------------- 展示辅助 ---------------- */
const refTypeText = (t?: string) => ({ ORG: '组织', DEVICE: '设备', CHANNEL: '通道' })[t ?? ''] ?? '—'

const fmtTime = (v?: string | null) => (v ? v.replace('T', ' ').slice(0, 19) : '—')

const STATUS_TEXT: Record<string, string> = {
  PENDING: '待推送',
  SYNCED: '已同步',
  FAILED: '推送失败',
  DEAD: '已死亡',
  REVOKING: '撤销中',
}

const statusText = (s?: ShareSummaryStatus | null) => (s ? STATUS_TEXT[s] : undefined) ?? '无共享'

const shareStatusClass = (s?: ShareSummaryStatus | null) => {
  switch (s) {
    case 'SYNCED':
      return 'text-emerald-600'
    case 'FAILED':
    case 'DEAD':
      return 'text-red-500'
    case 'REVOKING':
      return 'text-amber-600'
    case 'PENDING':
      return 'text-sky-600'
    default:
      return 'text-muted-foreground'
  }
}

const statusPillClass = (s?: ShareSummaryStatus | null) => {
  switch (s) {
    case 'SYNCED':
      return 'bg-emerald-500/10 text-emerald-600'
    case 'FAILED':
    case 'DEAD':
      return 'bg-red-500/10 text-red-500'
    case 'REVOKING':
      return 'bg-amber-500/10 text-amber-600'
    case 'PENDING':
      return 'bg-sky-500/10 text-sky-600'
    default:
      return 'bg-muted text-muted-foreground'
  }
}

const roundStatusText = (s?: string | null) =>
  ({ RUNNING: '进行中', DONE: '完成', FAILED: '失败', SKIPPED: '已跳过' })[s ?? ''] ?? '—'

/* ---------------- 无限滚动 ---------------- */
const scrollerRef = ref<HTMLElement>()
const sentinelRef = ref<HTMLElement>()
let observer: IntersectionObserver | null = null

onMounted(async () => {
  await loadTargets()
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
  observer?.disconnect()
  clearTimeout(searchTimer)
})
</script>
