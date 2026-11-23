<template>
  <div class="flex h-full flex-col gap-3 overflow-hidden p-3">
    <div class="flex min-h-0 flex-1 flex-col overflow-hidden rounded-xl border border-border bg-card">
      <div class="flex h-12 shrink-0 items-center gap-2 border-b border-border px-3">
        <span class="text-[13px] font-medium">推送记录</span>
        <span class="text-xs text-muted-foreground">先看轮次（哪一轮降级/失败几条），再按轮次筛出站事件</span>
        <div class="flex-1" />
        <Select v-model="platformId">
          <SelectTrigger size="sm" class="w-40 text-xs">
            <SelectValue placeholder="全部上级" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">全部上级</SelectItem>
            <SelectItem v-for="t in targets" :key="t.id" :value="t.id">{{ t.name }}</SelectItem>
          </SelectContent>
        </Select>
        <Button
          v-access="PERM.SHARE_PUBLISH_RETRY"
          variant="ghost"
          size="sm"
          class="gap-1.5"
          :disabled="dispatching || !dispatchPlatformId"
          @click="onDispatch"
        >
          <IconLoader2 v-if="dispatching" class="size-3.5 animate-spin" />
          <IconUpload v-else class="size-3.5" />
          立即推送
        </Button>
        <RefreshButton :loading="outbox.loading.value || rounds.loading.value" :on-refresh="refreshAll" />
      </div>

      <!-- 状态计数条 -->
      <div class="flex h-9 shrink-0 items-center gap-2 border-b border-border px-3 text-[11px]">
        <span class="text-muted-foreground">出站队列</span>
        <button
          type="button"
          class="rounded px-1.5 py-0.5"
          :class="statusFilter === null ? 'bg-primary/10 text-primary' : 'text-muted-foreground hover:bg-muted'"
          @click="setOutboxStatus(null)"
        >
          全部 {{ totalCount }}
        </button>
        <button
          v-for="c in countList"
          :key="c.status"
          type="button"
          class="rounded px-1.5 py-0.5"
          :class="statusFilter === c.status ? 'bg-primary/10 text-primary' : 'text-muted-foreground hover:bg-muted'"
          @click="setOutboxStatus(c.status)"
        >
          {{ outboxStatusText(c.status) }} {{ c.total }}
        </button>
        <div class="flex-1" />
        <span class="text-muted-foreground/70">序号在平台内单调递增，重试不会改变序号</span>
      </div>

      <Tabs default-value="rounds" class="min-h-0 flex-1" @update:model-value="onTabChange">
        <div class="flex h-10 shrink-0 items-center gap-2 border-b border-border px-3">
          <TabsList>
            <TabsTrigger value="rounds">轮次台账</TabsTrigger>
            <TabsTrigger value="outbox">出站事件</TabsTrigger>
          </TabsList>
          <div class="flex-1" />
          <template v-if="activeTab === 'rounds'">
            <Select :model-value="roundStatusFilter ?? 'ALL'" @update:model-value="onRoundStatusChange">
              <SelectTrigger size="sm" class="w-28 text-xs">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">全部状态</SelectItem>
                <SelectItem value="RUNNING">进行中</SelectItem>
                <SelectItem value="DONE">完成</SelectItem>
                <SelectItem value="FAILED">失败</SelectItem>
                <SelectItem value="SKIPPED">已跳过</SelectItem>
              </SelectContent>
            </Select>
          </template>
          <template v-else>
            <Input
              v-model="outboxKeyword"
              class="h-8 w-44 text-xs"
              placeholder="原始码 / 暴露码"
              @update:model-value="triggerOutboxSearch"
            />
            <Button size="sm" variant="outline" :disabled="!retryableSelected.length" @click="onRetry">
              重试选中（{{ retryableSelected.length }}）
            </Button>
          </template>
        </div>

        <!-- 轮次台账 -->
        <TabsContent value="rounds" class="mt-0 min-h-0 flex-1 overflow-auto">
          <table class="w-full table-fixed border-collapse text-left text-[13px]">
            <thead class="sticky top-0 z-10">
              <tr class="bg-card text-xs text-muted-foreground">
                <th class="w-40 px-3 py-2 font-normal">轮次号</th>
                <th class="w-36 px-3 py-2 font-normal">平台</th>
                <th class="w-20 px-3 py-2 font-normal">触发</th>
                <th class="w-24 px-3 py-2 font-normal">状态</th>
                <th class="w-36 px-3 py-2 font-normal">计划 / 成功 / 失败</th>
                <th class="w-20 px-3 py-2 font-normal">降级</th>
                <th class="w-28 px-3 py-2 font-normal">开始时间</th>
                <th class="px-3 py-2 font-normal">摘要</th>
                <th class="w-16 px-3 py-2 font-normal">详情</th>
              </tr>
            </thead>
            <tbody v-if="!rounds.loading.value || rounds.items.value.length">
              <tr v-for="r in rounds.items.value" :key="r.id" class="border-b border-border/50 hover:bg-muted/20">
                <td class="truncate px-3 py-1.5 font-mono text-xs" :title="r.id">#{{ r.id }}</td>
                <td class="truncate px-3 py-1.5 text-xs" :title="r.platformName ?? ''">{{ r.platformName || `#${r.platformId}` }}</td>
                <td class="px-3 py-1.5 text-xs text-muted-foreground">{{ triggerText(r.triggerType) }}</td>
                <td class="px-3 py-1.5">
                  <span class="text-xs" :class="roundStatusClass(r.status)">{{ roundStatusText(r.status) }}</span>
                </td>
                <td class="px-3 py-1.5 font-mono text-[11px] text-muted-foreground">
                  {{ r.planned ?? 0 }} / {{ r.sent ?? 0 }} / {{ r.failed ?? 0 }}
                </td>
                <td class="px-3 py-1.5 text-xs">
                  <span v-if="r.degraded === 1" class="text-amber-600" :title="`阈值 ${r.threshold ?? '—'}`">已降级</span>
                  <span v-else class="text-muted-foreground/60">—</span>
                </td>
                <td class="truncate px-3 py-1.5 font-mono text-[11px] text-muted-foreground">{{ fmtTime(r.beginTime) }}</td>
                <td class="truncate px-3 py-1.5 text-xs text-muted-foreground" :title="r.summary ?? r.errorMsg ?? ''">
                  {{ r.errorMsg || r.summary || '—' }}
                </td>
                <td class="px-3 py-1.5">
                  <button
                    class="flex size-6 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-foreground"
                    title="轮次详情"
                    @click="openRound(r)"
                  >
                    <IconEye class="size-3.5" />
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-if="rounds.loading.value && !rounds.items.value.length" class="grid place-items-center py-16">
            <IconLoader2 class="size-5 animate-spin text-muted-foreground" />
          </div>
          <div v-else-if="!rounds.loading.value && !rounds.items.value.length" class="flex flex-col items-center gap-2 py-16 text-muted-foreground">
            <IconList class="size-8" />
            <div class="text-[13px]">还没有推送轮次</div>
            <div class="text-[11px] text-muted-foreground/70">点右上角「立即推送」跑一轮，或等定时任务触发</div>
          </div>
        </TabsContent>

        <!-- 出站事件 -->
        <TabsContent value="outbox" class="mt-0 min-h-0 flex-1 overflow-auto">
          <table class="w-full table-fixed border-collapse text-left text-[13px]">
            <thead class="sticky top-0 z-10">
              <tr class="bg-card text-xs text-muted-foreground">
                <th class="w-10 px-3 py-2 font-normal">
                  <Checkbox :checked="allRetryableChecked" @update:checked="toggleAllRetryable" />
                </th>
                <th class="w-20 px-3 py-2 font-normal">序号</th>
                <th class="w-32 px-3 py-2 font-normal">平台</th>
                <th class="w-20 px-3 py-2 font-normal">事件</th>
                <th class="w-44 px-3 py-2 font-normal">原始码</th>
                <th class="w-40 px-3 py-2 font-normal">暴露码</th>
                <th class="w-20 px-3 py-2 font-normal">状态</th>
                <th class="w-16 px-3 py-2 font-normal">重试</th>
                <th class="px-3 py-2 font-normal">最近错误</th>
                <th class="w-28 px-3 py-2 font-normal">创建时间</th>
              </tr>
            </thead>
            <tbody v-if="!outbox.loading.value || outbox.items.value.length">
              <tr v-for="e in outbox.items.value" :key="e.id" class="border-b border-border/50 hover:bg-muted/20">
                <td class="px-3 py-1.5">
                  <Checkbox
                    :checked="selectedOutboxIds.includes(e.id)"
                    :disabled="!isRetryable(e)"
                    @update:checked="(v: boolean | 'indeterminate') => toggleOutbox(e.id, v === true)"
                  />
                </td>
                <td class="px-3 py-1.5 font-mono text-xs">{{ e.seqNo }}</td>
                <td class="truncate px-3 py-1.5 text-xs" :title="e.platformName ?? ''">{{ e.platformName || `#${e.platformId}` }}</td>
                <td class="px-3 py-1.5 text-xs">{{ eventTypeText(e.eventType) }}</td>
                <td class="truncate px-3 py-1.5 font-mono text-[11px]" :title="e.refCode ?? ''">{{ e.refCode || '—' }}</td>
                <td class="truncate px-3 py-1.5 font-mono text-[11px] text-muted-foreground" :title="e.exposedCode ?? ''">
                  {{ e.exposedCode || '—' }}
                </td>
                <td class="px-3 py-1.5">
                  <span class="text-xs" :class="outboxStatusClass(e.status)">{{ outboxStatusText(e.status) }}</span>
                </td>
                <td class="px-3 py-1.5 font-mono text-[11px] text-muted-foreground">{{ e.retryCount ?? 0 }}</td>
                <td class="truncate px-3 py-1.5 text-[11px] text-red-500/80" :title="e.lastError ?? ''">{{ e.lastError || '—' }}</td>
                <td class="truncate px-3 py-1.5 font-mono text-[11px] text-muted-foreground">{{ fmtTime(e.createTime) }}</td>
              </tr>
            </tbody>
          </table>
          <div v-if="outbox.loading.value && !outbox.items.value.length" class="grid place-items-center py-16">
            <IconLoader2 class="size-5 animate-spin text-muted-foreground" />
          </div>
          <div v-else-if="!outbox.loading.value && !outbox.items.value.length" class="flex flex-col items-center gap-2 py-16 text-muted-foreground">
            <IconList class="size-8" />
            <div class="text-[13px]">队列是空的</div>
            <div class="text-[11px] text-muted-foreground/70">共享变更会先入队，再按轮次投递</div>
          </div>
        </TabsContent>
      </Tabs>

      <div class="flex h-8 shrink-0 items-center gap-3 border-t border-border px-3 text-[11px] text-muted-foreground/70">
        <span v-if="activeTab === 'rounds'">共 {{ rounds.total.value }} 轮</span>
        <span v-else>共 {{ outbox.total.value }} 条事件</span>
        <div class="flex-1" />
        <Button
          variant="ghost"
          size="sm"
          class="h-6 text-[11px]"
          :disabled="activeTab === 'rounds' ? rounds.finished.value : outbox.finished.value"
          @click="loadMore"
        >
          加载更多
        </Button>
      </div>
    </div>

    <!-- 轮次详情 -->
    <Dialog :open="detailOpen" @update:open="(v) => (detailOpen = v)">
      <DialogContent class="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>推送轮次详情</DialogTitle>
          <DialogDescription>{{ detail ? `${detail.platformName || '#' + detail.platformId} · #${detail.id}` : '—' }}</DialogDescription>
        </DialogHeader>

        <div v-if="detail" class="space-y-4">
          <div v-if="detail.errorMsg" class="rounded-md border border-red-500/30 bg-red-500/5 p-2.5 text-xs text-red-600">
            <span class="font-mono">{{ detail.errorCode || 'ERROR' }}</span>
            · {{ detail.errorMsg }}
          </div>

          <div class="grid grid-cols-2 gap-x-4 gap-y-2 text-xs md:grid-cols-4">
            <div>
              <div class="text-muted-foreground">状态</div>
              <div :class="roundStatusClass(detail.status)">{{ roundStatusText(detail.status) }}</div>
            </div>
            <div>
              <div class="text-muted-foreground">触发</div>
              <div>{{ triggerText(detail.triggerType) }}</div>
            </div>
            <div>
              <div class="text-muted-foreground">计划 / 成功 / 失败</div>
              <div class="font-mono">{{ detail.planned ?? 0 }} / {{ detail.sent ?? 0 }} / {{ detail.failed ?? 0 }}</div>
            </div>
            <div>
              <div class="text-muted-foreground">是否降级</div>
              <div :class="detail.degraded === 1 ? 'text-amber-600' : ''">
                {{ detail.degraded === 1 ? '是（合并为 REFRESH）' : '否' }}
              </div>
            </div>
            <div>
              <div class="text-muted-foreground">降级阈值</div>
              <div class="font-mono">{{ detail.threshold ?? '—' }}</div>
            </div>
            <div class="col-span-2">
              <div class="text-muted-foreground">开始 / 结束</div>
              <div class="font-mono text-[11px]">{{ fmtTime(detail.beginTime) }} → {{ fmtTime(detail.endTime) }}</div>
            </div>
            <div class="col-span-4">
              <div class="text-muted-foreground">摘要</div>
              <div class="text-[11px]">{{ detail.summary || '—' }}</div>
            </div>
          </div>
        </div>

        <DialogFooter>
          <Button size="sm" variant="outline" @click="detailOpen = false">关闭</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { IconEye, IconList, IconLoader2, IconUpload } from '@tabler/icons-vue'
import Button from '@/components/ui/button/Button.vue'
import Checkbox from '@/components/ui/checkbox/Checkbox.vue'
import Input from '@/components/ui/input/Input.vue'
import RefreshButton from '@/components/RefreshButton.vue'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { useResource } from '@/shared/composables/useResource'
import { PERM } from '@/features/access/permissions'
import { confirm } from '@/lib/confirm'
import { toast } from '@/lib/toast'
import {
  dispatchPlatformApi,
  getOutboxCountsApi,
  getPublishTargetsApi,
  getRoundDetailApi,
  pageOutboxApi,
  pageRoundsApi,
  retryOutboxApi,
} from '@/api/share'
import type {
  OutboxEvent,
  OutboxQuery,
  OutboxStatus,
  OutboxStatusCount,
  PublishRound,
  RoundQuery,
  RoundStatus,
  ShareTarget,
} from '@/types/api'

/* ---------------- 平台 ---------------- */
const targets = ref<ShareTarget[]>([])
const platformId = ref<string>('ALL')

const dispatchPlatformId = computed(() => (platformId.value === 'ALL' ? null : platformId.value))

const loadTargets = async () => {
  try {
    targets.value = (await getPublishTargetsApi()).data ?? []
  } catch {
    targets.value = []
  }
}

watch(platformId, async () => {
  selectedOutboxIds.value = []
  await loadCounts()
  await Promise.all([outbox.search(), rounds.search()])
})

/* ---------------- 计数 ---------------- */
const counts = ref<OutboxStatusCount[]>([])
const countList = computed(() => counts.value.filter((c) => c.total > 0))
const totalCount = computed(() => counts.value.reduce((sum, c) => sum + c.total, 0))

const loadCounts = async () => {
  try {
    counts.value = (await getOutboxCountsApi(dispatchPlatformId.value ?? undefined)).data ?? []
  } catch {
    counts.value = []
  }
}

/* ---------------- 页签 ---------------- */
const activeTab = ref<'rounds' | 'outbox'>('rounds')

const onTabChange = (v: unknown) => {
  activeTab.value = v === 'outbox' ? 'outbox' : 'rounds'
  if (activeTab.value === 'outbox' && !outbox.items.value.length) outbox.search()
  if (activeTab.value === 'rounds' && !rounds.items.value.length) rounds.search()
}

/* ---------------- 轮次 ---------------- */
const roundStatusFilter = ref<RoundStatus | null>(null)

const rounds = useResource<RoundQuery, PublishRound>({
  fetcher: (q) =>
    pageRoundsApi({
      ...q,
      platformId: dispatchPlatformId.value ?? undefined,
      status: roundStatusFilter.value ?? undefined,
    }),
  initial: { page: 1, pageSize: 30 },
})

const onRoundStatusChange = (v: unknown) => {
  roundStatusFilter.value = v === 'ALL' ? null : (v as RoundStatus)
  rounds.search()
}

const detailOpen = ref(false)
const detail = ref<PublishRound | null>(null)

const openRound = async (r: PublishRound) => {
  detail.value = null
  detailOpen.value = true
  try {
    detail.value = (await getRoundDetailApi(r.id)).data
  } catch {
    detailOpen.value = false
  }
}

/* ---------------- 出站事件 ---------------- */
const outboxKeyword = ref('')
const statusFilter = ref<OutboxStatus | null>(null)
const selectedOutboxIds = ref<string[]>([])

const outbox = useResource<OutboxQuery, OutboxEvent>({
  fetcher: (q) =>
    pageOutboxApi({
      ...q,
      platformId: dispatchPlatformId.value ?? undefined,
      status: statusFilter.value ?? undefined,
      eventType: undefined,
    }),
  initial: { page: 1, pageSize: 30, keyword: '' },
})

let searchTimer: ReturnType<typeof setTimeout> | undefined
const triggerOutboxSearch = () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    outbox.query.keyword = outboxKeyword.value
    outbox.search()
  }, 300)
}

const setOutboxStatus = (s: OutboxStatus | null) => {
  statusFilter.value = s
  selectedOutboxIds.value = []
  outbox.search()
}

const isRetryable = (e: OutboxEvent) => e.status === 'FAILED' || e.status === 'DEAD'

const retryable = computed(() => outbox.items.value.filter(isRetryable))
const retryableSelected = computed(() => retryable.value.filter((e) => selectedOutboxIds.value.includes(e.id)))
const allRetryableChecked = computed(
  () => retryable.value.length > 0 && retryable.value.every((e) => selectedOutboxIds.value.includes(e.id)),
)

const toggleOutbox = (id: string, checked: boolean) => {
  if (checked) {
    if (!selectedOutboxIds.value.includes(id)) selectedOutboxIds.value = [...selectedOutboxIds.value, id]
  } else {
    selectedOutboxIds.value = selectedOutboxIds.value.filter((x) => x !== id)
  }
}

const toggleAllRetryable = (v: boolean | 'indeterminate') => {
  const ids = retryable.value.map((e) => e.id)
  if (v === true) selectedOutboxIds.value = Array.from(new Set([...selectedOutboxIds.value, ...ids]))
  else selectedOutboxIds.value = selectedOutboxIds.value.filter((id) => !ids.includes(id))
}

const onRetry = async () => {
  const ids = retryableSelected.value.map((e) => e.id)
  if (!ids.length) return
  const ok = await confirm({
    title: `重试 ${ids.length} 条失败事件`,
    description: '只把 FAILED / DEAD 放回待推队列，重试次数清零、序号不变。',
    confirmText: '重试',
  })
  if (!ok) return
  try {
    const r = await retryOutboxApi(ids)
    toast.success(`已放回队列 ${r.data} 条`)
    selectedOutboxIds.value = []
    await Promise.all([outbox.load(true), loadCounts()])
  } catch {
    // 拦截器已提示
  }
}

/* ---------------- 立即推送 ---------------- */
const dispatching = ref(false)

const onDispatch = async () => {
  if (!dispatchPlatformId.value) return
  const name = targets.value.find((t) => t.id === dispatchPlatformId.value)?.name ?? `#${dispatchPlatformId.value}`
  const ok = await confirm({
    title: `立即推送「${name}」`,
    description: '将同步跑完一轮：真的走 SIP 把积压队列发出去，页面会等到本轮结束。',
    confirmText: '开始推送',
  })
  if (!ok) return
  dispatching.value = true
  try {
    const r = await dispatchPlatformApi(dispatchPlatformId.value)
    const d = r.data
    toast.success(`本轮${roundStatusText(d.status)}：计划 ${d.planned}、成功 ${d.sent}、失败 ${d.failed}${d.degraded ? '（已降级为 REFRESH）' : ''}`)
    await Promise.all([rounds.load(true), outbox.load(true), loadCounts(), loadTargets()])
  } catch {
    // 拦截器已提示
  } finally {
    dispatching.value = false
  }
}

/* ---------------- 展示辅助 ---------------- */
const fmtTime = (v?: string | null) => (v ? v.replace('T', ' ').slice(0, 19) : '—')

const triggerText = (t?: string) =>
  ({ MANUAL: '手动', SCHEDULED: '定时', RECONCILE: '对账', RESOURCE: '资源' })[t ?? ''] ?? '—'

const ROUND_STATUS_TEXT: Record<string, string> = {
  RUNNING: '进行中',
  DONE: '完成',
  FAILED: '失败',
  SKIPPED: '已跳过',
}

const OUTBOX_STATUS_TEXT: Record<string, string> = {
  PENDING: '待推送',
  SENT: '已发送',
  FAILED: '失败',
  DEAD: '已死亡',
  PAUSED: '已暂停',
}

const roundStatusText = (s?: RoundStatus | null) => (s ? ROUND_STATUS_TEXT[s] : undefined) ?? '—'

const roundStatusClass = (s?: RoundStatus | null) => {
  switch (s) {
    case 'DONE':
      return 'text-emerald-600'
    case 'FAILED':
      return 'text-red-500'
    case 'RUNNING':
      return 'text-sky-600'
    default:
      return 'text-muted-foreground'
  }
}

const eventTypeText = (t?: string) =>
  ({ ADD: '新增', UPDATE: '更新', DELETE: '删除', REFRESH: '全量刷新' })[t ?? ''] ?? '—'

const outboxStatusText = (s?: OutboxStatus | null) => (s ? OUTBOX_STATUS_TEXT[s] : undefined) ?? '—'

const outboxStatusClass = (s?: OutboxStatus | null) => {
  switch (s) {
    case 'SENT':
      return 'text-emerald-600'
    case 'FAILED':
    case 'DEAD':
      return 'text-red-500'
    case 'PENDING':
      return 'text-sky-600'
    default:
      return 'text-muted-foreground'
  }
}

/* ---------------- 刷新 ---------------- */
const refreshAll = async () => {
  await Promise.all([loadTargets(), loadCounts(), rounds.load(true), outbox.load(true)])
}

const loadMore = () => {
  if (activeTab.value === 'rounds') rounds.load(false)
  else outbox.load(false)
}

onMounted(async () => {
  await loadTargets()
  await Promise.all([loadCounts(), rounds.load(true), outbox.load(true)])
})

onBeforeUnmount(() => {
  clearTimeout(searchTimer)
})
</script>
