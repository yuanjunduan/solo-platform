<template>
  <div class="flex h-full gap-3 overflow-hidden p-3">
    <div class="flex min-w-0 flex-1 flex-col overflow-hidden rounded-xl border border-border bg-card">
      <div class="flex h-12 shrink-0 items-center gap-2 border-b border-border px-3">
        <span class="text-[13px] font-medium">同步中心</span>
        <span class="text-xs text-muted-foreground">每轮全量目录同步一条台账，闸门与跳过删除都可事后回查</span>
        <div class="flex-1" />
        <Select :model-value="queryPlatformId ?? 'ALL'" @update:model-value="onPlatformChange">
          <SelectTrigger size="sm" class="w-40 text-xs">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">全部平台</SelectItem>
            <SelectItem v-for="p in platformOptions" :key="p.id" :value="p.id">
              {{ p.name }}
            </SelectItem>
          </SelectContent>
        </Select>
        <Select :model-value="queryStatus ?? 'ALL'" @update:model-value="onStatusChange">
          <SelectTrigger size="sm" class="w-28 text-xs">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">全部状态</SelectItem>
            <SelectItem value="RUNNING">进行中</SelectItem>
            <SelectItem value="SYNCHING">合并中</SelectItem>
            <SelectItem value="SUCCESS">成功</SelectItem>
            <SelectItem value="FAILED">失败</SelectItem>
            <SelectItem value="CANCELLED">已取消</SelectItem>
          </SelectContent>
        </Select>
        <RefreshButton :loading="resource.loading.value" :on-refresh="refreshList" />
      </div>

      <div ref="scrollerRef" class="min-h-0 flex-1 overflow-auto">
        <table class="w-full table-fixed border-collapse text-left text-[13px]">
          <thead class="sticky top-0 z-10">
            <tr class="bg-card text-xs text-muted-foreground">
              <th class="w-40 px-3 py-2 font-normal">轮次号</th>
              <th class="w-40 px-3 py-2 font-normal">平台</th>
              <th class="w-20 px-3 py-2 font-normal">触发</th>
              <th class="w-24 px-3 py-2 font-normal">状态</th>
              <th class="w-32 px-3 py-2 font-normal">进度</th>
              <th class="w-40 px-3 py-2 font-normal">收 / 新增 / 更新 / 消失</th>
              <th class="w-24 px-3 py-2 font-normal">G2</th>
              <th class="w-40 px-3 py-2 font-normal">开始时间</th>
              <th class="w-24 px-3 py-2 font-normal">操作</th>
            </tr>
          </thead>
          <tbody v-if="!resource.loading.value || resource.items.value.length">
            <tr
              v-for="t in resource.items.value"
              :key="t.id"
              class="border-b border-border/50 hover:bg-muted/20"
            >
              <td class="truncate px-3 py-1.5 font-mono text-xs" :title="t.taskSn ?? t.id">
                {{ t.taskSn || t.id }}
              </td>
              <td class="truncate px-3 py-1.5 text-xs">{{ platformName(t.platformId) }}</td>
              <td class="px-3 py-1.5 text-xs text-muted-foreground">{{ triggerText(t.triggerType) }}</td>
              <td class="px-3 py-1.5">
                <span class="text-xs" :class="taskStatusClass(t.status)">{{ taskStatusText(t.status) }}</span>
              </td>
              <td class="px-3 py-1.5">
                <div class="flex items-center gap-2">
                  <div class="h-1.5 min-w-0 flex-1 overflow-hidden rounded bg-muted">
                    <div
                      class="h-full rounded transition-all duration-500"
                      :class="t.status === 'FAILED' ? 'bg-red-400' : 'bg-primary/70'"
                      :style="{ width: `${t.progress ?? 0}%` }"
                    />
                  </div>
                  <span class="w-8 shrink-0 font-mono text-[10px] text-muted-foreground">{{ t.progress ?? 0 }}%</span>
                </div>
              </td>
              <td class="px-3 py-1.5 font-mono text-[11px] text-muted-foreground">
                {{ t.recvTotal ?? 0 }} / {{ t.addedCount ?? 0 }} / {{ t.updatedCount ?? 0 }} / {{ t.staleCount ?? 0 }}
              </td>
              <td class="px-3 py-1.5 text-xs">
                <span v-if="t.deleteSkipped === 1" class="text-amber-600" :title="`阈值 ${t.guardRatio ?? '—'}`">
                  已跳过
                </span>
                <span v-else class="text-muted-foreground/60">—</span>
              </td>
              <td class="truncate px-3 py-1.5 font-mono text-[11px] text-muted-foreground">
                {{ fmtTime(t.beginTime ?? t.createTime) }}
              </td>
              <td class="px-3 py-1.5">
                <div class="flex items-center gap-1">
                  <button
                    class="flex size-6 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-foreground"
                    title="详情"
                    @click="openDetail(t)"
                  >
                    <IconEye class="size-3.5" />
                  </button>
                  <button
                    v-if="isActive(t.status)"
                    v-access="PERM.SYNC_CANCEL"
                    class="flex size-6 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-amber-500"
                    title="取消本轮"
                    @click="onCancel(t)"
                  >
                    <IconPlayerStop class="size-3.5" />
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
          v-else-if="!resource.loading.value && !resource.items.value.length"
          class="flex flex-col items-center gap-2 py-16 text-muted-foreground"
        >
          <IconRefresh class="size-8" />
          <div class="text-[13px]">还没有同步轮次</div>
          <div class="text-[11px] text-muted-foreground/70">到「平台接入」里对某个下级平台点"立即同步"</div>
        </div>

        <div ref="sentinelRef" class="grid place-items-center py-4">
          <IconLoader2
            v-if="resource.loading.value && resource.items.value.length"
            class="size-4 animate-spin text-muted-foreground"
          />
          <span v-else-if="resource.finished.value && resource.items.value.length" class="text-xs text-muted-foreground">
            已全部加载
          </span>
        </div>
      </div>

      <div class="flex h-8 shrink-0 items-center gap-3 border-t border-border px-3 text-[11px] text-muted-foreground/70">
        <span>共 {{ resource.total.value }} 轮</span>
        <div class="flex-1" />
        <span>失败轮次会进冷却窗口，冷却期内不允许重复触发</span>
      </div>
    </div>

    <!-- 详情 -->
    <Dialog :open="detailOpen" @update:open="onDetailOpenChange">
      <DialogContent class="sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>同步轮次详情</DialogTitle>
          <DialogDescription>
            {{ detail?.platformName || '—' }} · {{ detail?.task.taskSn || detail?.task.id }}
          </DialogDescription>
        </DialogHeader>

        <div v-if="detail" class="space-y-4">
          <div v-if="detail.task.errorMsg" class="rounded-md border border-red-500/30 bg-red-500/5 p-2.5 text-xs text-red-600">
            <span class="font-mono">{{ detail.task.errorCode || 'ERROR' }}</span>
            · {{ detail.task.errorMsg }}
          </div>

          <div class="grid grid-cols-2 gap-x-4 gap-y-2 text-xs md:grid-cols-4">
            <div>
              <div class="text-muted-foreground">状态</div>
              <div :class="taskStatusClass(detail.task.status)">{{ taskStatusText(detail.task.status) }}</div>
            </div>
            <div>
              <div class="text-muted-foreground">进度</div>
              <div class="font-mono">{{ detail.task.progress ?? 0 }}%</div>
            </div>
            <div>
              <div class="text-muted-foreground">对端通告 / 已收</div>
              <div class="font-mono">{{ detail.task.expectedTotal ?? '未通告' }} / {{ detail.task.recvTotal ?? 0 }}</div>
            </div>
            <div>
              <div class="text-muted-foreground">分级收数（组织/设备/通道）</div>
              <div class="font-mono">
                {{ detail.task.orgRecv ?? 0 }} / {{ detail.task.devRecv ?? 0 }} / {{ detail.task.chRecv ?? 0 }}
              </div>
            </div>
            <div>
              <div class="text-muted-foreground">新增 / 更新 / 消失</div>
              <div class="font-mono">
                {{ detail.task.addedCount ?? 0 }} / {{ detail.task.updatedCount ?? 0 }} / {{ detail.task.staleCount ?? 0 }}
              </div>
            </div>
            <div>
              <div class="text-muted-foreground">失败条数</div>
              <div class="font-mono">{{ detail.task.failedCount ?? 0 }}</div>
            </div>
            <div>
              <div class="text-muted-foreground">G2 阈值快照</div>
              <div class="font-mono">{{ detail.task.guardRatio ?? '—' }}</div>
            </div>
            <div>
              <div class="text-muted-foreground">G2 是否跳过删除</div>
              <div class="font-mono" :class="detail.task.deleteSkipped === 1 ? 'text-amber-600' : ''">
                {{ detail.task.deleteSkipped === 1 ? '是（本轮未删）' : '否' }}
              </div>
            </div>
            <div>
              <div class="text-muted-foreground">回填到[未分类]</div>
              <div class="font-mono">{{ detail.task.orgMissingCount ?? 0 }} 行</div>
            </div>
            <div class="col-span-2">
              <div class="text-muted-foreground">目录指纹（本轮 / 上一轮）</div>
              <div class="truncate font-mono text-[11px]">
                {{ detail.task.catalogFingerprint || '—' }}
                <span class="text-muted-foreground/60"> vs {{ detail.lastFingerprint || '—' }}</span>
              </div>
            </div>
            <div class="col-span-2">
              <div class="text-muted-foreground">开始 / 结束</div>
              <div class="font-mono text-[11px]">
                {{ fmtTime(detail.task.beginTime) }} → {{ fmtTime(detail.task.endTime) }}
              </div>
            </div>
          </div>

          <div>
            <div class="mb-1.5 flex items-center gap-2">
              <span class="text-[13px] font-medium">变更留痕</span>
              <span class="text-[11px] text-muted-foreground">最多展示最近 200 条</span>
            </div>
            <div class="max-h-64 overflow-auto rounded-md border">
              <table class="w-full border-collapse text-left text-[12px]">
                <thead class="sticky top-0 bg-muted/60">
                  <tr class="text-muted-foreground">
                    <th class="w-20 px-2 py-1.5 font-normal">类型</th>
                    <th class="w-24 px-2 py-1.5 font-normal">变更</th>
                    <th class="w-48 px-2 py-1.5 font-normal">编码</th>
                    <th class="px-2 py-1.5 font-normal">前后摘要</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="r in detail.records" :key="r.id" class="border-t border-border/50">
                    <td class="px-2 py-1 font-mono">{{ r.itemType }}</td>
                    <td class="px-2 py-1" :class="changeClass(r.changeType)">{{ changeText(r.changeType) }}</td>
                    <td class="truncate px-2 py-1 font-mono" :title="r.code ?? ''">{{ r.code || '—' }}</td>
                    <td class="truncate px-2 py-1 text-muted-foreground" :title="`${r.beforeSummary ?? ''} → ${r.afterSummary ?? ''}`">
                      {{ r.beforeSummary || '—' }} → {{ r.afterSummary || '—' }}
                    </td>
                  </tr>
                  <tr v-if="!detail.records.length">
                    <td colspan="4" class="px-2 py-6 text-center text-muted-foreground">本轮没有产生变更留痕</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>

        <DialogFooter>
          <Button v-if="detail && detail.active" size="sm" variant="outline" class="text-amber-600" @click="onCancel(detail.task)">
            <IconPlayerStop class="mr-1 size-3.5" />
            取消本轮
          </Button>
          <Button size="sm" variant="outline" @click="detailOpen = false">关闭</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { IconEye, IconLoader2, IconPlayerStop, IconRefresh } from '@tabler/icons-vue'
import Button from '@/components/ui/button/Button.vue'
import RefreshButton from '@/components/RefreshButton.vue'
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
import { getPlatformListApi } from '@/api/platform'
import { cancelSyncApi, getSyncTaskDetailApi, getSyncTaskPageApi } from '@/api/sync'
import type { Platform, SyncTask, SyncTaskDetail, SyncTaskQueryRequest, SyncTaskStatus } from '@/types/api'

const queryPlatformId = ref<string | null>(null)
const queryStatus = ref<SyncTaskStatus | null>(null)
const platformOptions = ref<Platform[]>([])

const resource = useResource<SyncTaskQueryRequest, SyncTask>({
  fetcher: (q) =>
    getSyncTaskPageApi({
      ...q,
      platformId: queryPlatformId.value ?? undefined,
      status: queryStatus.value ?? undefined,
    }),
  initial: { page: 1, pageSize: 30 },
})

const firstLoading = computed(() => resource.loading.value && resource.items.value.length === 0)
const refreshList = () => resource.load(true)

const platformName = (id?: string) =>
  platformOptions.value.find((p) => p.id === id)?.name ?? (id ? `#${id}` : '—')

const onPlatformChange = (v: unknown) => {
  queryPlatformId.value = v === 'ALL' ? null : (v as string)
  resource.search()
}

const onStatusChange = (v: unknown) => {
  queryStatus.value = v === 'ALL' ? null : (v as SyncTaskStatus)
  resource.search()
}

const isActive = (status?: string) =>
  status === 'PENDING' || status === 'RUNNING' || status === 'SYNCHING'

const onCancel = async (t: SyncTask) => {
  const ok = await confirm({
    title: '取消本轮同步',
    description: '取消后本轮已收到的暂存数据会被丢弃，资源表不受影响。',
    confirmText: '取消同步',
  })
  if (!ok) return
  try {
    await cancelSyncApi(t.id)
    toast.success('已取消')
    detailOpen.value = false
    await refreshList()
  } catch {
    // 拦截器已提示
  }
}

/* ---------------- 详情 ---------------- */
const detailOpen = ref(false)
const detail = ref<SyncTaskDetail | null>(null)

const openDetail = async (t: SyncTask) => {
  detail.value = null
  detailOpen.value = true
  try {
    detail.value = (await getSyncTaskDetailApi(t.id)).data
  } catch {
    detailOpen.value = false
  }
}

const onDetailOpenChange = (v: boolean) => {
  detailOpen.value = v
  if (!v) detail.value = null
}

/* ---------------- 展示辅助 ---------------- */
const fmtTime = (v?: string) => (v ? v.replace('T', ' ').slice(0, 19) : '—')

const triggerText = (t?: string) =>
  ({ MANUAL: '手动', SCHEDULED: '定时', NOTIFY: '通知' })[t ?? ''] ?? '—'

const taskStatusText = (status?: string) =>
  ({
    PENDING: '排队中',
    RUNNING: '进行中',
    SYNCHING: '合并中',
    SUCCESS: '成功',
    FAILED: '失败',
    CANCELLED: '已取消',
  })[status ?? ''] ?? '—'

const taskStatusClass = (status?: string) => {
  switch (status) {
    case 'SUCCESS':
      return 'text-emerald-600'
    case 'FAILED':
      return 'text-red-500'
    case 'CANCELLED':
      return 'text-muted-foreground'
    case 'PENDING':
    case 'RUNNING':
    case 'SYNCHING':
      return 'text-sky-600'
    default:
      return 'text-muted-foreground'
  }
}

const changeText = (type?: string) =>
  ({ ADDED: '新增', UPDATED: '更新', STALE: '消失', FAILED: '失败' })[type ?? ''] ?? type ?? '—'

const changeClass = (type?: string) => {
  switch (type) {
    case 'ADDED':
      return 'text-emerald-600'
    case 'UPDATED':
      return 'text-sky-600'
    case 'STALE':
      return 'text-amber-600'
    case 'FAILED':
      return 'text-red-500'
    default:
      return 'text-muted-foreground'
  }
}

/* ---------------- 无限滚动 ---------------- */
const scrollerRef = ref<HTMLElement>()
const sentinelRef = ref<HTMLElement>()
let observer: IntersectionObserver | null = null

onMounted(async () => {
  try {
    platformOptions.value = (await getPlatformListApi()).data.map((i) => i.platform)
  } catch {
    platformOptions.value = []
  }
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
})
</script>
