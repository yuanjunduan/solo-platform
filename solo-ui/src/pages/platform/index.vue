<template>
  <div class="flex h-full flex-col gap-3 overflow-hidden p-3">
    <div class="flex min-h-0 flex-1 flex-col overflow-hidden rounded-xl border border-border bg-card">
      <div class="flex h-12 shrink-0 items-center gap-2 border-b border-border px-3">
        <span class="text-[13px] font-medium">平台接入</span>
        <span class="text-xs text-muted-foreground">一个外部平台一条记录，方向由"收目录 / 发目录"两个开关表达</span>
        <div class="flex-1" />
        <Input
          v-model="keyword"
          class="h-8 w-52 text-xs"
          placeholder="名称 / 编码"
          @update:model-value="triggerSearch"
        />
        <Select :model-value="direction ?? 'ALL'" @update:model-value="onDirectionChange">
          <SelectTrigger size="sm" class="w-32 text-xs">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">全部方向</SelectItem>
            <SelectItem value="INBOUND">它给我目录</SelectItem>
            <SelectItem value="OUTBOUND">我给它目录</SelectItem>
          </SelectContent>
        </Select>
        <RefreshButton :loading="loading" :on-refresh="fetchData" />
        <Button v-access="PERM.PLATFORM_ADD" variant="ghost" size="sm" class="gap-1.5" @click="openCreate">
          <IconPlus class="size-3.5" />
          新增平台
        </Button>
      </div>

      <div class="min-h-0 flex-1 overflow-auto">
        <table class="w-full table-fixed border-collapse text-left text-[13px]">
          <thead class="sticky top-0 z-10">
            <tr class="bg-card text-xs text-muted-foreground">
              <th class="w-56 px-3 py-2 font-normal">平台</th>
              <th class="w-32 px-3 py-2 font-normal">方向</th>
              <th class="w-28 px-3 py-2 font-normal">入站资源</th>
              <th class="w-52 px-3 py-2 font-normal">最近同步</th>
              <th class="w-24 px-3 py-2 font-normal">连接状态</th>
              <th class="px-3 py-2 font-normal">操作</th>
            </tr>
          </thead>
          <tbody v-if="!loading || rows.length">
            <tr v-for="row in rows" :key="row.platform.id" class="border-b border-border/50 hover:bg-muted/20">
              <td class="px-3 py-2">
                <div class="truncate font-medium">{{ row.platform.name }}</div>
                <div class="truncate font-mono text-[11px] text-muted-foreground">{{ row.platform.code }}</div>
              </td>
              <td class="px-3 py-2">
                <div class="flex flex-wrap gap-1">
                  <span
                    v-if="row.platform.acceptInbound === 1"
                    class="rounded bg-emerald-500/10 px-1.5 py-0.5 text-[10px] text-emerald-600"
                    title="它是我的下级：可向本平台推目录"
                  >
                    收目录
                  </span>
                  <span
                    v-if="row.platform.pushOutbound === 1"
                    class="rounded bg-sky-500/10 px-1.5 py-0.5 text-[10px] text-sky-600"
                    title="它是我的上级：本平台可向它推目录"
                  >
                    发目录
                  </span>
                  <span
                    v-if="row.platform.acceptInbound !== 1 && row.platform.pushOutbound !== 1"
                    class="text-[11px] text-muted-foreground"
                  >
                    仅登记
                  </span>
                </div>
              </td>
              <td class="px-3 py-2 text-xs">
                <div class="font-mono">
                  组 {{ row.inboundOrg }} · 设备 {{ row.inboundDevice }} · 通道 {{ row.inboundChannel }}
                </div>
              </td>
              <td class="px-3 py-2 text-xs">
                <div class="flex items-center gap-2">
                  <span :class="syncStatusClass(row.lastSyncStatus)">
                    {{ syncStatusText(row.lastSyncStatus) }}
                  </span>
                  <span class="truncate font-mono text-[11px] text-muted-foreground">
                    {{ fmtTime(row.lastSyncAt) }}
                  </span>
                </div>
                <div v-if="row.lastDeleteSkipped === 1" class="mt-0.5 text-[11px] text-amber-600">
                  G2 保护：本轮跳过删除
                </div>
                <div v-if="row.activeTaskId" class="mt-1">
                  <div class="h-1.5 w-full overflow-hidden rounded bg-muted">
                    <div
                      class="h-full rounded bg-primary/70 transition-all duration-500"
                      :style="{ width: `${row.activeProgress ?? 0}%` }"
                    />
                  </div>
                  <div class="mt-0.5 font-mono text-[10px] text-muted-foreground">
                    同步中 {{ row.activeProgress ?? 0 }}%
                  </div>
                </div>
              </td>
              <td class="px-3 py-2 text-xs">
                <span :class="platformStatusClass(row.platform.status)">
                  {{ platformStatusText(row.platform.status) }}
                </span>
              </td>
              <td class="px-3 py-2">
                <div class="flex flex-wrap items-center gap-1">
                  <Button
                    v-if="row.activeTaskId"
                    v-access="PERM.SYNC_CANCEL"
                    variant="ghost"
                    size="xs"
                    class="h-6 px-1.5 text-xs text-amber-600"
                    @click="onCancel(row)"
                  >
                    <IconPlayerStop class="mr-1 size-3.5" />
                    取消
                  </Button>
                  <Button
                    v-else
                    v-access="PERM.SYNC_TRIGGER"
                    variant="ghost"
                    size="xs"
                    class="h-6 px-1.5 text-xs"
                    :disabled="!canSync(row)"
                    :title="syncTitle(row)"
                    :loading="triggeringId === row.platform.id"
                    @click="onSync(row)"
                  >
                    <IconRefresh class="mr-1 size-3.5" />
                    立即同步
                  </Button>
                  <Button v-access="PERM.PLATFORM_EDIT" variant="ghost" size="xs" class="h-6 px-1.5 text-xs" @click="openEdit(row)">
                    编辑
                  </Button>
                  <Button
                    v-access="PERM.PLATFORM_EDIT"
                    variant="ghost"
                    size="xs"
                    class="h-6 px-1.5 text-xs"
                    :disabled="!!row.activeTaskId"
                    @click="onToggleStatus(row)"
                  >
                    {{ row.platform.status === 'DISABLED' ? '启用' : '停用' }}
                  </Button>
                  <Button
                    v-access="PERM.PLATFORM_DELETE"
                    variant="ghost"
                    size="xs"
                    class="h-6 px-1.5 text-xs text-red-500"
                    :disabled="!!row.activeTaskId"
                    @click="onDelete(row)"
                  >
                    删除
                  </Button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>

        <div v-if="loading && !rows.length" class="grid place-items-center py-16">
          <IconLoader2 class="size-5 animate-spin text-muted-foreground" />
        </div>
        <div
          v-else-if="!loading && !rows.length"
          class="flex flex-col items-center gap-2 py-16 text-muted-foreground"
        >
          <IconPlugConnected class="size-8" />
          <div class="text-[13px]">还没有接入任何平台</div>
          <div class="text-[11px] text-muted-foreground/70">
            下级平台先用国标编码注册上来，再在这里登记，之后就能拉它的目录
          </div>
        </div>
      </div>

      <div class="flex h-8 shrink-0 items-center gap-3 border-t border-border px-3 text-[11px] text-muted-foreground/70">
        <span>共 {{ rows.length }} 个平台</span>
        <div class="flex-1" />
        <span>首次接入建议把批量删除保护设为 0.2，确认目录稳定后再调整</span>
      </div>
    </div>

    <Dialog :open="formOpen" @update:open="(v) => (formOpen = v)">
      <DialogContent class="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{{ editingId ? '编辑平台' : '新增平台' }}</DialogTitle>
          <DialogDescription>
            编码建议直接用对端国标编码：它是资源表的来源标识，建立后不可修改。
          </DialogDescription>
        </DialogHeader>

        <div class="grid grid-cols-2 gap-3">
          <div class="space-y-1.5">
            <Label class="text-xs text-muted-foreground">平台编码 *</Label>
            <Input v-model="form.code" class="h-8 font-mono text-xs" :disabled="!!editingId" placeholder="20 位国标编码" />
          </div>
          <div class="space-y-1.5">
            <Label class="text-xs text-muted-foreground">平台名称 *</Label>
            <Input v-model="form.name" class="h-8 text-xs" />
          </div>
          <div class="space-y-1.5">
            <Label class="text-xs text-muted-foreground">厂商</Label>
            <Input v-model="form.vendor" class="h-8 text-xs" placeholder="HIKVISION / DAHUA / GB28181 / OTHER" />
          </div>
          <div class="space-y-1.5">
            <Label class="text-xs text-muted-foreground">平台地址 / 端口</Label>
            <div class="flex gap-2">
              <Input v-model="form.host" class="h-8 font-mono text-xs" placeholder="IP / 域名" />
              <Input v-model.number="form.port" type="number" min="1" max="65535" class="h-8 w-24 font-mono text-xs" />
            </div>
          </div>

          <div class="space-y-1.5">
            <Label class="text-xs text-muted-foreground">方向</Label>
            <div class="flex h-8 items-center gap-4 text-xs">
              <label class="flex items-center gap-1.5">
                <Switch
                  :model-value="form.acceptInbound === 1"
                  @update:model-value="(v: boolean) => (form.acceptInbound = v ? 1 : 0)"
                />
                收目录（它是下级）
              </label>
              <label class="flex items-center gap-1.5">
                <Switch
                  :model-value="form.pushOutbound === 1"
                  @update:model-value="(v: boolean) => (form.pushOutbound = v ? 1 : 0)"
                />
                发目录（它是上级）
              </label>
            </div>
          </div>

          <div class="space-y-1.5">
            <Label class="text-xs text-muted-foreground">同步策略</Label>
            <Select v-model="form.syncPolicy">
              <SelectTrigger class="h-8 w-full text-xs"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="MANUAL">仅手动</SelectItem>
                <SelectItem value="SCHEDULED">定时</SelectItem>
                <SelectItem value="NOTIFY">目录通知驱动</SelectItem>
                <SelectItem value="BOTH">定时 + 通知</SelectItem>
              </SelectContent>
            </Select>
          </div>

          <div v-if="form.syncPolicy === 'SCHEDULED' || form.syncPolicy === 'BOTH'" class="space-y-1.5">
            <Label class="text-xs text-muted-foreground">定时表达式</Label>
            <Input v-model="form.syncCron" class="h-8 font-mono text-xs" placeholder="0 0 2 * * ?" />
          </div>

          <div class="space-y-1.5">
            <Label class="text-xs text-muted-foreground">批量删除保护（0-1，0 = 关闭）</Label>
            <Input
              v-model.number="form.deleteGuardRatio"
              type="number"
              min="0"
              max="1"
              step="0.05"
              class="h-8 font-mono text-xs"
              placeholder="首次接入建议 0.2"
            />
            <p class="text-[11px] text-muted-foreground/70">
              预计消失的条数超过「本地条数 × 该比例」时只跳过删除并标记，不会清空目录
            </p>
          </div>

          <div class="col-span-2 space-y-1.5">
            <Label class="text-xs text-muted-foreground">备注</Label>
            <Input v-model="form.remark" class="h-8 text-xs" />
          </div>
        </div>

        <DialogFooter>
          <Button size="sm" variant="outline" @click="formOpen = false">取消</Button>
          <Button size="sm" :loading="saving" @click="onSave">保存</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import {
  IconLoader2,
  IconPlayerStop,
  IconPlugConnected,
  IconPlus,
  IconRefresh,
} from '@tabler/icons-vue'
import Button from '@/components/ui/button/Button.vue'
import Input from '@/components/ui/input/Input.vue'
import Label from '@/components/ui/label/Label.vue'
import RefreshButton from '@/components/RefreshButton.vue'
import Switch from '@/components/ui/switch/Switch.vue'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { PERM } from '@/features/access/permissions'
import { confirm } from '@/lib/confirm'
import { toast } from '@/lib/toast'
import {
  changePlatformStatusApi,
  createPlatformApi,
  deletePlatformApi,
  getPlatformListApi,
  updatePlatformApi,
} from '@/api/platform'
import { cancelSyncApi, triggerSyncApi } from '@/api/sync'
import type { PlatformListItem, SyncPolicy } from '@/types/api'

const rows = ref<PlatformListItem[]>([])
const loading = ref(false)
const keyword = ref('')
const direction = ref<'INBOUND' | 'OUTBOUND' | null>(null)
const triggeringId = ref<string | null>(null)

const fetchData = async () => {
  loading.value = true
  try {
    rows.value = (
      await getPlatformListApi({
        keyword: keyword.value.trim() || undefined,
        direction: direction.value ?? undefined,
      })
    ).data ?? []
  } finally {
    loading.value = false
    schedulePoll()
  }
}

let debounceTimer: ReturnType<typeof setTimeout> | null = null
const triggerSearch = () => {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => void fetchData(), 300)
}

const onDirectionChange = (v: unknown) => {
  direction.value = v === 'ALL' ? null : (v as 'INBOUND' | 'OUTBOUND')
  void fetchData()
}

/* 有活跃轮次或冷却中才轮询：同步进度与冷却倒计时都靠它刷新 */
let pollTimer: number | null = null
const schedulePoll = () => {
  const need = rows.value.some((r) => !!r.activeTaskId || r.cooldownMs > 0)
  if (need && pollTimer === null) {
    pollTimer = window.setInterval(() => void fetchData(), 4000)
  } else if (!need && pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

const canSync = (row: PlatformListItem) =>
  row.platform.acceptInbound === 1 &&
  row.platform.status !== 'DISABLED' &&
  row.cooldownMs <= 0 &&
  !row.activeTaskId

const syncTitle = (row: PlatformListItem) => {
  if (row.platform.acceptInbound !== 1) return '该平台未开启"收目录"，无法拉取目录'
  if (row.platform.status === 'DISABLED') return '平台已停用'
  if (row.activeTaskId) return '已有活跃轮次'
  if (row.cooldownMs > 0) return `上一轮失败，冷却中（约 ${Math.ceil(row.cooldownMs / 1000)} 秒）`
  return '立即发起一轮全量目录同步'
}

const onSync = async (row: PlatformListItem) => {
  triggeringId.value = row.platform.id
  try {
    await triggerSyncApi(row.platform.id, 'MANUAL')
    toast.success('已发起同步，可在同步中心查看进度')
    await fetchData()
  } catch {
    // 拦截器已提示（对端未注册 / 已有活跃轮次 / 冷却中）
  } finally {
    triggeringId.value = null
  }
}

const onCancel = async (row: PlatformListItem) => {
  if (!row.activeTaskId) return
  const ok = await confirm({
    title: '取消本轮同步',
    description: '取消后本轮已收到的暂存数据会被丢弃，资源表不受影响。',
    confirmText: '取消同步',
  })
  if (!ok) return
  try {
    await cancelSyncApi(row.activeTaskId)
    toast.success('已取消')
    await fetchData()
  } catch {
    // 拦截器已提示
  }
}

const onToggleStatus = async (row: PlatformListItem) => {
  const next = row.platform.status === 'DISABLED' ? 'OFFLINE' : 'DISABLED'
  const ok = await confirm({
    title: next === 'DISABLED' ? '停用平台' : '启用平台',
    description:
      next === 'DISABLED'
        ? '停用后不再接受该平台的目录同步与点播，已有入站资源保留。'
        : '启用后可以重新发起目录同步与入站点播。',
    confirmText: next === 'DISABLED' ? '停用' : '启用',
    danger: next === 'DISABLED',
  })
  if (!ok) return
  try {
    await changePlatformStatusApi(row.platform.id, next)
    toast.success('已更新')
    await fetchData()
  } catch {
    // 拦截器已提示
  }
}

const onDelete = async (row: PlatformListItem) => {
  const ok = await confirm({
    title: '删除平台',
    description: `确定删除「${row.platform.name}」吗？平台下还有入站资源时会拒绝，需先清空资源。`,
    danger: true,
    confirmText: '删除',
  })
  if (!ok) return
  try {
    await deletePlatformApi(row.platform.id)
    toast.success('删除成功')
    await fetchData()
  } catch {
    // 拦截器已提示（有入站资源 / 有活跃轮次）
  }
}

/* ---------------- 新增 / 编辑 ---------------- */
const formOpen = ref(false)
const saving = ref(false)
const editingId = ref<string | null>(null)
const form = reactive({
  code: '',
  name: '',
  vendor: '',
  host: '',
  port: 5060,
  acceptInbound: 1,
  pushOutbound: 0,
  syncPolicy: 'MANUAL' as SyncPolicy,
  syncCron: '',
  deleteGuardRatio: 0.2,
  remark: '',
})

const openCreate = () => {
  editingId.value = null
  form.code = ''
  form.name = ''
  form.vendor = 'GB28181'
  form.host = ''
  form.port = 5060
  form.acceptInbound = 1
  form.pushOutbound = 0
  form.syncPolicy = 'MANUAL'
  form.syncCron = ''
  form.deleteGuardRatio = 0.2
  form.remark = ''
  formOpen.value = true
}

const openEdit = (row: PlatformListItem) => {
  const p = row.platform
  editingId.value = p.id
  form.code = p.code
  form.name = p.name
  form.vendor = p.vendor ?? ''
  form.host = p.host ?? ''
  form.port = p.port ?? 5060
  form.acceptInbound = p.acceptInbound ?? 0
  form.pushOutbound = p.pushOutbound ?? 0
  form.syncPolicy = (p.syncPolicy as SyncPolicy) ?? 'MANUAL'
  form.syncCron = p.syncCron ?? ''
  form.deleteGuardRatio = p.deleteGuardRatio != null ? Number(p.deleteGuardRatio) : 0
  form.remark = p.remark ?? ''
  formOpen.value = true
}

const onSave = async () => {
  if (!form.code.trim() || !form.name.trim()) {
    toast.warning('平台编码与名称不能为空')
    return
  }
  if (form.acceptInbound !== 1 && form.pushOutbound !== 1) {
    const ok = await confirm({
      title: '两个方向都没开',
      description: '该平台将只作为登记信息，不会同步目录也不会推送目录。仍然保存吗？',
      confirmText: '仍然保存',
    })
    if (!ok) return
  }
  saving.value = true
  try {
    const payload = {
      code: form.code.trim(),
      name: form.name.trim(),
      vendor: form.vendor.trim() || undefined,
      host: form.host.trim() || undefined,
      port: form.port,
      acceptInbound: form.acceptInbound,
      pushOutbound: form.pushOutbound,
      syncPolicy: form.syncPolicy,
      syncCron: form.syncCron.trim() || undefined,
      deleteGuardRatio: form.deleteGuardRatio,
      remark: form.remark.trim() || undefined,
    }
    if (editingId.value) await updatePlatformApi(editingId.value, payload)
    else await createPlatformApi(payload)
    toast.success('保存成功')
    formOpen.value = false
    await fetchData()
  } catch {
    // 拦截器已提示（编码重复 / 编码不可改）
  } finally {
    saving.value = false
  }
}

/* ---------------- 展示辅助 ---------------- */
const fmtTime = (v?: string) => (v ? v.replace('T', ' ').slice(0, 19) : '—')

const syncStatusText = (status?: string) =>
  ({
    SUCCESS: '成功',
    FAILED: '失败',
    CANCELLED: '已取消',
    RUNNING: '进行中',
    SYNCHING: '合并中',
    PENDING: '排队中',
  })[status ?? ''] ?? '从未同步'

const syncStatusClass = (status?: string) => {
  switch (status) {
    case 'SUCCESS':
      return 'text-emerald-600'
    case 'FAILED':
      return 'text-red-500'
    case 'CANCELLED':
      return 'text-muted-foreground'
    case 'RUNNING':
    case 'SYNCHING':
    case 'PENDING':
      return 'text-sky-600'
    default:
      return 'text-muted-foreground'
  }
}

const platformStatusText = (status?: string) =>
  ({
    ONLINE: '在线',
    OFFLINE: '离线',
    UNREACHABLE: '不可达',
    DISABLED: '已停用',
  })[status ?? ''] ?? '未知'

const platformStatusClass = (status?: string) => {
  switch (status) {
    case 'ONLINE':
      return 'text-emerald-600'
    case 'DISABLED':
      return 'text-muted-foreground'
    case 'UNREACHABLE':
      return 'text-red-500'
    default:
      return 'text-amber-600'
  }
}

onMounted(() => {
  void fetchData()
})

onBeforeUnmount(() => {
  if (pollTimer !== null) window.clearInterval(pollTimer)
  if (debounceTimer) clearTimeout(debounceTimer)
})
</script>
