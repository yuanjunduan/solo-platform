<template>
  <div class="flex h-full gap-3 overflow-hidden p-3">
    <ResOrgTreePanel
      title="资源组织"
      :rows="flatRows"
      :loading="treeLoading"
      :selected-id="selectedId"
      :expanded-ids="expandedIds"
      :all-expanded="allExpanded"
      @select="(id) => (select(id), refreshList())"
      @toggle="toggle"
      @toggle-all="toggleAll"
      @refresh="fetchTree"
    />

    <div class="flex min-w-0 flex-1 flex-col overflow-hidden rounded-xl border border-border bg-card">
      <div class="flex h-12 shrink-0 items-center gap-2 border-b border-border px-3">
        <span class="text-[13px] font-medium">通道</span>
        <span class="text-xs text-muted-foreground">{{ orgLabel }}</span>
        <div class="flex-1" />
        <Input
          v-model="keyword"
          class="h-8 w-52 text-xs"
          placeholder="编码 / 名称"
          @update:model-value="triggerSearch"
        />
        <Select :model-value="sourceType ?? 'ALL'" @update:model-value="onSourceChange">
          <SelectTrigger size="sm" class="w-28 text-xs">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">全部来源</SelectItem>
            <SelectItem value="NATIVE">本地自建</SelectItem>
            <SelectItem value="INBOUND">下级入站</SelectItem>
          </SelectContent>
        </Select>
        <RefreshButton :loading="resource.loading.value" :on-refresh="refreshList" />
        <Button
          v-access="PERM.RES_CHANNEL_EXPORT"
          variant="ghost"
          size="sm"
          class="gap-1.5"
          :disabled="exporting"
          @click="onExport"
        >
          <IconLoader2 v-if="exporting" class="size-3.5 animate-spin" />
          <IconDownload v-else class="size-3.5" />
          导出
        </Button>
        <Button v-access="PERM.RES_CHANNEL_ADD" variant="ghost" size="sm" class="gap-1.5" @click="openCreate">
          <IconPlus class="size-3.5" />
          新增
        </Button>
        <Button
          v-access="PERM.RES_CHANNEL_BIND"
          variant="ghost"
          size="sm"
          class="gap-1.5"
          :disabled="selectedIds.length < 2"
          title="把选中的通道标记为同一台物理通道：只做逻辑关联，不合并行"
          @click="onBindSelected"
        >
          <IconLink class="size-3.5" />
          同源绑定
        </Button>
      </div>

      <div ref="scrollerRef" class="min-h-0 flex-1 overflow-auto">
        <table class="w-full table-fixed border-collapse text-left text-[13px]">
          <thead class="sticky top-0 z-10">
            <tr class="bg-card text-xs text-muted-foreground">
              <th class="w-10 px-3 py-2 font-normal">
                <Checkbox :checked="allChecked" @update:checked="toggleAllSelect" />
              </th>
              <th class="w-48 px-3 py-2 font-normal">通道编码</th>
              <th class="w-52 px-3 py-2 font-normal">名称</th>
              <th class="w-36 px-3 py-2 font-normal">所属组织</th>
              <th class="w-40 px-3 py-2 font-normal">所属设备</th>
              <th class="w-20 px-3 py-2 font-normal">码流</th>
              <th class="w-20 px-3 py-2 font-normal">在线</th>
              <th class="w-20 px-3 py-2 font-normal">来源</th>
              <th class="w-20 px-3 py-2 font-normal">状态</th>
              <th class="w-24 px-3 py-2 font-normal">操作</th>
            </tr>
          </thead>
          <tbody v-if="!resource.loading.value || resource.items.value.length">
            <tr
              v-for="row in resource.items.value"
              :key="row.channel.id"
              class="border-b border-border/50 hover:bg-muted/20"
            >
              <td class="px-3 py-1.5">
                <Checkbox
                  :checked="selectedIds.includes(row.channel.id)"
                  :disabled="row.channel.status === 'REMOVED' || (row.sourceCount ?? 1) > 1"
                  @update:checked="(v: boolean | 'indeterminate') => toggleSelect(row.channel.id, v === true)"
                />
              </td>
              <td class="truncate px-3 py-1.5 font-mono text-xs" :title="row.channel.code">{{ row.channel.code }}</td>
              <td class="truncate px-3 py-1.5">
                {{ row.channel.localName || row.channel.name }}
                <span
                  v-if="row.channel.orgMissing === 1"
                  class="ml-1 rounded bg-amber-500/10 px-1 text-[10px] text-amber-600"
                  title="4 步回填未命中，暂时挂在[未分类]组织下"
                >
                  未归类
                </span>
                <button
                  v-if="(row.sourceCount ?? 1) > 1"
                  class="ml-1 rounded bg-sky-500/10 px-1 text-[10px] text-sky-600 hover:bg-sky-500/20"
                  title="同一台物理通道还有其它来源，点击查看组内成员或解除关联"
                  @click="openSources(row.channel.id)"
                >
                  同源 {{ row.sourceCount }}
                </button>
              </td>
              <td class="truncate px-3 py-1.5 text-xs text-muted-foreground" :title="row.orgName ?? ''">
                {{ row.orgName || '—' }}
              </td>
              <td class="truncate px-3 py-1.5 text-xs text-muted-foreground" :title="row.deviceName ?? ''">
                {{ row.deviceName || '—' }}
              </td>
              <td class="px-3 py-1.5 text-xs text-muted-foreground">{{ row.channel.streamType || '—' }}</td>
              <td class="px-3 py-1.5">
                <span class="text-xs" :class="row.channel.onlineStatus === 'ON' ? 'text-emerald-600' : 'text-muted-foreground'">
                  {{ row.channel.onlineStatus === 'ON' ? '在线' : '离线' }}
                </span>
              </td>
              <td class="px-3 py-1.5">
                <span
                  class="rounded px-1.5 py-0.5 text-[10px]"
                  :class="row.channel.sourceType === 'INBOUND' ? 'bg-amber-500/10 text-amber-600' : 'bg-muted text-muted-foreground'"
                >
                  {{ row.channel.sourceType === 'INBOUND' ? '入站' : '本地' }}
                </span>
              </td>
              <td class="px-3 py-1.5">
                <span class="text-xs" :class="statusClass(row.channel.status)">{{ statusText(row.channel.status) }}</span>
              </td>
              <td class="px-3 py-1.5">
                <div class="flex items-center gap-1">
                  <button
                    v-access="PERM.RES_CHANNEL_PLAY"
                    class="flex size-6 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-primary"
                    title="监控（实时 / 回放 / 云台）"
                    @click="openMonitor(row.channel, 'live')"
                  >
                    <IconVideo class="size-3.5" />
                  </button>
                  <button
                    v-access="PERM.RES_CHANNEL_PLAYBACK"
                    class="flex size-6 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-foreground"
                    title="录像回放"
                    @click="openMonitor(row.channel, 'playback')"
                  >
                    <IconHistory class="size-3.5" />
                  </button>
                  <button
                    v-access="PERM.RES_CHANNEL_PTZ"
                    class="flex size-6 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-foreground"
                    title="云台与预置位"
                    @click="openMonitor(row.channel, 'ptz')"
                  >
                    <IconDeviceCctv class="size-3.5" />
                  </button>
                  <button
                    class="flex size-6 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-foreground disabled:cursor-not-allowed disabled:opacity-30"
                    title="编辑"
                    :disabled="row.channel.sourceType === 'INBOUND'"
                    @click="openEdit(row)"
                  >
                    <IconPencil class="size-3.5" />
                  </button>
                  <button
                    class="flex size-6 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-red-400 disabled:cursor-not-allowed disabled:opacity-30"
                    title="删除"
                    :disabled="row.channel.sourceType === 'INBOUND'"
                    @click="onDelete(row)"
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
          v-else-if="!resource.loading.value && !resource.items.value.length"
          class="flex flex-col items-center gap-2 py-16 text-muted-foreground"
        >
          <IconList class="size-8" />
          <div class="text-[13px]">该范围内还没有通道</div>
          <div class="text-[11px] text-muted-foreground/70">入站通道需要先在「平台接入」里同步一次目录</div>
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
        <span>共 {{ resource.total.value }} 路通道</span>
        <span v-if="selectedIds.length">已勾选 {{ selectedIds.length }} 路</span>
        <div class="flex-1" />
        <span>入站通道只读；点播放会向来源平台发起 INVITE，同源组内自动选路</span>
      </div>
    </div>

    <!-- 同源组（3.10.3） -->
    <Dialog :open="sourcesOpen" @update:open="(v) => (sourcesOpen = v)">
      <DialogContent class="sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>同源组</DialogTitle>
          <DialogDescription>
            同一台物理通道经多条链路进来时各保留一行；播放按「在线 → 跳数少 → id 小」自动选路，手动点选的行优先。解除只影响该行。
          </DialogDescription>
        </DialogHeader>

        <div v-if="sourcesLoading" class="grid place-items-center py-10">
          <IconLoader2 class="size-5 animate-spin text-muted-foreground" />
        </div>
        <div v-else-if="sources" class="space-y-2">
          <table class="w-full table-fixed border-collapse text-left text-[13px]">
            <thead>
              <tr class="text-xs text-muted-foreground">
                <th class="w-44 px-2 py-1.5 font-normal">通道编码</th>
                <th class="w-40 px-2 py-1.5 font-normal">名称</th>
                <th class="w-44 px-2 py-1.5 font-normal">来源</th>
                <th class="w-16 px-2 py-1.5 font-normal">在线</th>
                <th class="w-14 px-2 py-1.5 font-normal">跳数</th>
                <th class="w-16 px-2 py-1.5 font-normal">角色</th>
                <th class="w-16 px-2 py-1.5 font-normal">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="m in sources.members" :key="m.id" class="border-t border-border/50">
                <td class="truncate px-2 py-1.5 font-mono text-xs" :title="m.code">{{ m.code }}</td>
                <td class="truncate px-2 py-1.5">{{ m.name }}</td>
                <td class="truncate px-2 py-1.5 text-xs text-muted-foreground">
                  {{ m.sourceType === 'INBOUND' ? m.sourcePlatformName || `平台 ${m.sourcePlatformId}` : '本地' }}
                </td>
                <td class="px-2 py-1.5 text-xs" :class="m.onlineStatus === 'ON' ? 'text-emerald-600' : 'text-muted-foreground'">
                  {{ m.onlineStatus === 'ON' ? '在线' : '离线' }}
                </td>
                <td class="px-2 py-1.5 text-xs text-muted-foreground">{{ m.hopCount ?? 0 }}</td>
                <td class="px-2 py-1.5">
                  <span v-if="m.leader" class="rounded bg-sky-500/10 px-1.5 py-0.5 text-[10px] text-sky-600">组主</span>
                  <span v-else class="text-[11px] text-muted-foreground/60">成员</span>
                </td>
                <td class="px-2 py-1.5">
                  <Button
                    v-if="sources.members.length > 1"
                    v-access="PERM.RES_CHANNEL_BIND"
                    variant="ghost"
                    size="xs"
                    class="h-6 px-1.5 text-[11px] text-amber-600"
                    @click="onUnbind(m)"
                  >
                    解除
                  </Button>
                  <span v-else class="text-[11px] text-muted-foreground/60">—</span>
                </td>
              </tr>
            </tbody>
          </table>
          <div class="text-[11px] leading-relaxed text-muted-foreground/70">
            组主优先选本地行（上级抖动时组里仍有一条自己能支配的链路）；解除组主会自动从剩余成员里提升新组主。系统不会自动解除关联。
          </div>
        </div>

        <DialogFooter>
          <Button size="sm" variant="outline" @click="sourcesOpen = false">关闭</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <!-- 视频监控：实时 / 回放 / 云台 -->
    <ChannelVideoDialog
      v-model:open="monitorOpen"
      :channel="monitorChannel"
      :initial-tab="monitorTab"
    />

    <!-- 新增 / 编辑 -->
    <Dialog :open="formOpen" @update:open="(v) => (formOpen = v)">
      <DialogContent class="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{{ editingId ? '编辑通道' : '新增通道' }}</DialogTitle>
          <DialogDescription />
        </DialogHeader>

        <div class="space-y-3">
          <div class="flex items-center gap-3">
            <Label class="w-20 shrink-0 text-xs text-muted-foreground">通道编码</Label>
            <div class="flex-1">
              <Input v-model="form.code" class="h-8 font-mono text-xs" :disabled="!!editingId" placeholder="业务键，建立后不可修改" />
            </div>
          </div>
          <div class="flex items-center gap-3">
            <Label class="w-20 shrink-0 text-xs text-muted-foreground">通道名称</Label>
            <div class="flex-1"><Input v-model="form.name" class="h-8 text-xs" /></div>
          </div>
          <div class="flex items-center gap-3">
            <Label class="w-20 shrink-0 text-xs text-muted-foreground">所属组织</Label>
            <div class="flex-1">
              <Select v-model="form.orgId">
                <SelectTrigger class="h-8 w-full text-xs"><SelectValue placeholder="选择组织" /></SelectTrigger>
                <SelectContent>
                  <SelectItem v-for="opt in orgOptions" :key="opt.id" :value="opt.id">
                    {{ '　'.repeat(opt.depth) }}{{ opt.name }}
                  </SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>
          <div class="flex items-center gap-3">
            <Label class="w-20 shrink-0 text-xs text-muted-foreground">所属设备</Label>
            <div class="flex-1">
              <Select v-model="form.deviceId">
                <SelectTrigger class="h-8 w-full text-xs"><SelectValue placeholder="选择载体设备" /></SelectTrigger>
                <SelectContent>
                  <SelectItem v-for="d in deviceOptions" :key="d.id" :value="d.id">
                    {{ d.localName || d.name }}（{{ d.code }}）
                  </SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>
          <div class="flex items-center gap-3">
            <Label class="w-20 shrink-0 text-xs text-muted-foreground">码流类型</Label>
            <div class="flex-1">
              <Select v-model="form.streamType">
                <SelectTrigger class="h-8 w-full text-xs"><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="MAIN">主码流</SelectItem>
                  <SelectItem value="SUB">子码流</SelectItem>
                  <SelectItem value="THIRD">第三码流</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>
          <div class="flex items-center gap-3">
            <Label class="w-20 shrink-0 text-xs text-muted-foreground">取流线索</Label>
            <div class="flex-1">
              <Input v-model="form.mediaHint" class="h-8 font-mono text-xs" placeholder="上游通道码 / RTSP 模板，禁止写账号密码" />
            </div>
          </div>
          <div class="flex items-center gap-3">
            <Label class="w-20 shrink-0 text-xs text-muted-foreground">本地别名</Label>
            <div class="flex-1"><Input v-model="form.localName" class="h-8 text-xs" placeholder="同步不覆盖，可留空" /></div>
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
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import {
  IconDeviceCctv,
  IconDownload,
  IconHistory,
  IconLink,
  IconList,
  IconLoader2,
  IconPencil,
  IconPlus,
  IconTrash,
  IconVideo,
} from '@tabler/icons-vue'
import Button from '@/components/ui/button/Button.vue'
import Checkbox from '@/components/ui/checkbox/Checkbox.vue'
import Input from '@/components/ui/input/Input.vue'
import Label from '@/components/ui/label/Label.vue'
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
import ResOrgTreePanel from '@/features/res/ResOrgTreePanel.vue'
import ChannelVideoDialog from '@/features/res/ChannelVideoDialog.vue'
import { useResTree } from '@/features/res/useResTree'
import { useResource } from '@/shared/composables/useResource'
import { PERM } from '@/features/access/permissions'
import { confirm } from '@/lib/confirm'
import { toast } from '@/lib/toast'
import {
  bindChannelSourcesApi,
  createResChannelApi,
  deleteResChannelApi,
  exportResChannelApi,
  getChannelSourcesApi,
  getResChannelPageApi,
  getResDevicePageApi,
  unbindChannelSourceApi,
  updateResChannelApi,
} from '@/api/res'
import type {
  ChannelListItem,
  ChannelSourceGroup,
  ChannelSourceGroupMember,
  ResChannel,
  ResDevice,
  ResOrgTreeNode,
  ResourcePageRequest,
  SourceType,
} from '@/types/api'

const {
  tree,
  flatRows,
  loading: treeLoading,
  selectedId,
  expandedIds,
  allExpanded,
  fetchTree,
  toggle,
  toggleAll,
  select,
} = useResTree()

const keyword = ref('')
const sourceType = ref<SourceType | null>(null)

const resource = useResource<ResourcePageRequest, ChannelListItem>({
  fetcher: (q) =>
    getResChannelPageApi({
      ...q,
      orgId: selectedId.value ?? undefined,
      keyword: keyword.value.trim() || undefined,
      sourceType: sourceType.value ?? undefined,
    }),
  initial: { page: 1, pageSize: 50 },
})

const firstLoading = computed(() => resource.loading.value && resource.items.value.length === 0)

/* ---------------- 勾选（同源绑定用） ---------------- */
const selectedIds = ref<string[]>([])

/** 已消失、或已在别的同源组里的行不能参与本次绑定（后端同样会拒） */
const selectable = (row: ChannelListItem) =>
  row.channel.status !== 'REMOVED' && (row.sourceCount ?? 1) <= 1

const allChecked = computed(
  () =>
    resource.items.value.length > 0 &&
    resource.items.value.every((r) => !selectable(r) || selectedIds.value.includes(r.channel.id)),
)

const toggleSelect = (id: string, checked: boolean) => {
  if (checked) {
    if (!selectedIds.value.includes(id)) selectedIds.value = [...selectedIds.value, id]
  } else {
    selectedIds.value = selectedIds.value.filter((x) => x !== id)
  }
}

const toggleAllSelect = (checked: boolean | 'indeterminate') => {
  selectedIds.value =
    checked === true ? resource.items.value.filter(selectable).map((r) => r.channel.id) : []
}

/** 任何重新加载都清空勾选：列表可能已经换页/换组织，残留 id 会绑错行 */
const refreshList = () => {
  selectedIds.value = []
  return resource.load(true)
}

const orgMap = computed(() => {
  const map = new Map<string, ResOrgTreeNode>()
  const walk = (list: ResOrgTreeNode[]) => {
    list.forEach((n) => {
      map.set(n.id, n)
      if (n.children?.length) walk(n.children)
    })
  }
  walk(tree.value)
  return map
})

const orgOptions = computed(() => {
  const acc: Array<{ id: string; name: string; depth: number }> = []
  const walk = (list: ResOrgTreeNode[], depth: number) => {
    list.forEach((n) => {
      if (n.writable !== false) acc.push({ id: n.id, name: n.name, depth })
      if (n.children?.length) walk(n.children, depth + 1)
    })
  }
  walk(tree.value, 0)
  return acc
})

const orgLabel = computed(() => {
  if (!selectedId.value) return '全部组织'
  return orgMap.value.get(selectedId.value)?.name ?? '全部组织'
})

let debounceTimer: ReturnType<typeof setTimeout> | null = null
const triggerSearch = () => {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => resource.search(), 300)
}

const onSourceChange = (v: unknown) => {
  sourceType.value = v === 'ALL' ? null : (v as SourceType)
  resource.search()
}

/* ---------------- 导出（CSV，条件与列表一致，上限 5 万行） ---------------- */
const exporting = ref(false)
const onExport = async () => {
  exporting.value = true
  try {
    const res = await exportResChannelApi({
      page: 1,
      pageSize: 50000,
      orgId: selectedId.value ?? undefined,
      keyword: keyword.value.trim() || undefined,
      sourceType: sourceType.value ?? undefined,
    })
    const url = URL.createObjectURL(res.data as Blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `资源通道-${Date.now()}.csv`
    a.click()
    URL.revokeObjectURL(url)
    toast.success('导出成功')
  } finally {
    exporting.value = false
  }
}

/* ---------------- 视频监控（实时 / 回放 / 云台） ---------------- */
type MonitorTab = 'live' | 'playback' | 'ptz'
const monitorOpen = ref(false)
const monitorTab = ref<MonitorTab>('live')
const monitorChannel = ref<ResChannel | null>(null)

const openMonitor = (c: ResChannel, tab: MonitorTab) => {
  monitorChannel.value = c
  monitorTab.value = tab
  monitorOpen.value = true
}

/* ---------------- 同源组（3.10.3） ---------------- */
const sourcesOpen = ref(false)
const sourcesLoading = ref(false)
const sources = ref<ChannelSourceGroup | null>(null)

const openSources = async (id: string) => {
  sourcesOpen.value = true
  sourcesLoading.value = true
  sources.value = null
  try {
    sources.value = (await getChannelSourcesApi(id)).data
  } catch {
    // 拦截器已提示
    sourcesOpen.value = false
  } finally {
    sourcesLoading.value = false
  }
}

const onBindSelected = async () => {
  if (selectedIds.value.length < 2) return
  const codes = resource.items.value
    .filter((r) => selectedIds.value.includes(r.channel.id))
    .map((r) => r.channel.code)
  const ok = await confirm({
    title: '同源绑定',
    description:
      `把 ${selectedIds.value.length} 条通道标记为同一台物理通道？\n${codes.join('、')}\n` +
      '只做逻辑关联（canonical_id），不合并行；绑定后可点列表里的「同源 N」查看或解除。',
    confirmText: '绑定',
  })
  if (!ok) return
  try {
    const group = (await bindChannelSourcesApi(selectedIds.value)).data
    toast.success(`已绑定 ${group.members.length} 条通道`)
    await refreshList()
  } catch {
    // 拦截器已提示（已属他组 / 已是组主 / 已消失）
  }
}

const onUnbind = async (member: ChannelSourceGroupMember) => {
  const ok = await confirm({
    title: '解除同源关联',
    description: `确定把「${member.name}」从同源组里解除吗？只影响这一行，组内其余成员保持不变。`,
    danger: true,
    confirmText: '解除',
  })
  if (!ok) return
  try {
    await unbindChannelSourceApi(member.id)
    toast.success('已解除关联')
    // 解除后组主可能已经换人、或整组只剩自己：关窗刷新比留在旧视图上更不容易误导
    sourcesOpen.value = false
    await refreshList()
  } catch {
    // 拦截器已提示
  }
}

/* ---------------- 新增 / 编辑 ---------------- */
const formOpen = ref(false)
const saving = ref(false)
const editingId = ref<string | null>(null)
const deviceOptions = ref<ResDevice[]>([])
const form = reactive({
  code: '',
  name: '',
  orgId: '',
  deviceId: '',
  streamType: 'MAIN',
  mediaHint: '',
  localName: '',
})

/** 载体设备只能选本地可写的设备：把通道挂到入站设备上会被领域层直接拒 */
const loadDeviceOptions = async () => {
  try {
    const res = await getResDevicePageApi({
      page: 1,
      pageSize: 500,
      sourceType: 'NATIVE',
      orgId: selectedId.value ?? undefined,
    })
    deviceOptions.value = res.data.rows ?? []
  } catch {
    deviceOptions.value = []
  }
}

const openCreate = async () => {
  editingId.value = null
  form.code = ''
  form.name = ''
  form.orgId = selectedId.value ?? orgOptions.value[0]?.id ?? ''
  form.streamType = 'MAIN'
  form.mediaHint = ''
  form.localName = ''
  await loadDeviceOptions()
  form.deviceId = deviceOptions.value[0]?.id ?? ''
  formOpen.value = true
}

const openEdit = async (row: ChannelListItem) => {
  const c = row.channel
  if (c.sourceType === 'INBOUND') {
    toast.warning('入站通道由同步链路维护，不能本地编辑')
    return
  }
  editingId.value = c.id
  form.code = c.code
  form.name = c.name
  form.orgId = c.orgId ?? ''
  form.streamType = c.streamType || 'MAIN'
  form.mediaHint = c.mediaHint ?? ''
  form.localName = c.localName ?? ''
  await loadDeviceOptions()
  form.deviceId = c.deviceId ?? deviceOptions.value[0]?.id ?? ''
  formOpen.value = true
}

const onSave = async () => {
  if (!form.code.trim() || !form.name.trim()) {
    toast.warning('通道编码与名称不能为空')
    return
  }
  if (!form.orgId || !form.deviceId) {
    toast.warning('所属组织与所属设备都必须选择')
    return
  }
  saving.value = true
  try {
    const payload = {
      code: form.code.trim(),
      name: form.name.trim(),
      orgId: form.orgId,
      deviceId: form.deviceId,
      streamType: form.streamType,
      mediaHint: form.mediaHint.trim() || undefined,
      localName: form.localName.trim() || undefined,
    }
    if (editingId.value) await updateResChannelApi(editingId.value, payload)
    else await createResChannelApi(payload)
    toast.success('保存成功')
    formOpen.value = false
    await Promise.all([refreshList(), fetchTree()])
  } catch {
    // 拦截器已提示（编码重复 / 编码不可改 / 组织或设备只读）
  } finally {
    saving.value = false
  }
}

const onDelete = async (row: ChannelListItem) => {
  if (row.channel.sourceType === 'INBOUND') return
  const c = row.channel
  const ok = await confirm({
    title: '删除通道',
    description: `确定删除通道「${c.localName || c.name}」吗？`,
    danger: true,
    confirmText: '删除',
  })
  if (!ok) return
  try {
    await deleteResChannelApi(c.id)
    toast.success('删除成功')
    resource.removeWhere((item) => item.channel.id === c.id)
    await fetchTree()
  } catch {
    // 拦截器已提示
  }
}

const statusText = (status?: string) =>
  ({ ACTIVE: '正常', STALE: '疑似消失', OFFLINE: '离线', REMOVED: '已消失' })[status ?? 'ACTIVE'] ?? status ?? '—'

const statusClass = (status?: string) => {
  switch (status) {
    case 'STALE':
      return 'text-amber-600'
    case 'OFFLINE':
      return 'text-muted-foreground'
    case 'REMOVED':
      return 'text-red-500'
    default:
      return 'text-emerald-600'
  }
}

/* ---------------- 无限滚动 ---------------- */
const scrollerRef = ref<HTMLElement>()
const sentinelRef = ref<HTMLElement>()
let observer: IntersectionObserver | null = null

onMounted(async () => {
  await fetchTree()
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
  if (debounceTimer) clearTimeout(debounceTimer)
})
</script>
