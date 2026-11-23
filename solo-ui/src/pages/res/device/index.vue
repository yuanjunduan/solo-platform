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
        <span class="text-[13px] font-medium">设备</span>
        <span class="text-xs text-muted-foreground">{{ orgLabel }}</span>
        <div class="flex-1" />
        <Input
          v-model="keyword"
          class="h-8 w-52 text-xs"
          placeholder="编码 / 名称 / 地址"
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
        <Button v-access="PERM.RES_DEVICE_ADD" variant="ghost" size="sm" class="gap-1.5" @click="openCreate">
          <IconPlus class="size-3.5" />
          新增
        </Button>
      </div>

      <div ref="scrollerRef" class="min-h-0 flex-1 overflow-auto">
        <table class="w-full table-fixed border-collapse text-left text-[13px]">
          <thead class="sticky top-0 z-10">
            <tr class="bg-card text-xs text-muted-foreground">
              <th class="w-52 px-3 py-2 font-normal">设备编码</th>
              <th class="w-56 px-3 py-2 font-normal">名称</th>
              <th class="w-44 px-3 py-2 font-normal">所属组织</th>
              <th class="w-40 px-3 py-2 font-normal">接入地址</th>
              <th class="w-24 px-3 py-2 font-normal">通道数</th>
              <th class="w-24 px-3 py-2 font-normal">来源</th>
              <th class="w-24 px-3 py-2 font-normal">状态</th>
              <th class="w-24 px-3 py-2 font-normal">操作</th>
            </tr>
          </thead>
          <tbody v-if="!resource.loading.value || resource.items.value.length">
            <tr
              v-for="d in resource.items.value"
              :key="d.id"
              class="border-b border-border/50 hover:bg-muted/20"
            >
              <td class="truncate px-3 py-1.5 font-mono text-xs" :title="d.code">{{ d.code }}</td>
              <td class="truncate px-3 py-1.5">
                {{ d.localName || d.name }}
                <span v-if="d.localName && d.localName !== d.name" class="ml-1 text-[11px] text-muted-foreground/70">
                  ({{ d.name }})
                </span>
              </td>
              <td class="truncate px-3 py-1.5 text-xs text-muted-foreground" :title="orgPathName(d.orgId)">
                {{ orgPathName(d.orgId) }}
              </td>
              <td class="truncate px-3 py-1.5 font-mono text-xs text-muted-foreground">{{ d.deviceIp || '—' }}</td>
              <td class="px-3 py-1.5 text-xs text-muted-foreground">{{ d.channelCount ?? 0 }}</td>
              <td class="px-3 py-1.5">
                <span
                  class="rounded px-1.5 py-0.5 text-[10px]"
                  :class="d.sourceType === 'INBOUND' ? 'bg-amber-500/10 text-amber-600' : 'bg-muted text-muted-foreground'"
                >
                  {{ d.sourceType === 'INBOUND' ? '入站' : '本地' }}
                </span>
              </td>
              <td class="px-3 py-1.5">
                <span class="text-xs" :class="statusClass(d.status)">{{ statusText(d.status) }}</span>
              </td>
              <td class="px-3 py-1.5">
                <div class="flex items-center gap-1">
                  <button
                    class="flex size-6 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-foreground disabled:cursor-not-allowed disabled:opacity-30"
                    title="编辑"
                    :disabled="d.sourceType === 'INBOUND'"
                    @click="openEdit(d)"
                  >
                    <IconPencil class="size-3.5" />
                  </button>
                  <button
                    class="flex size-6 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-red-400 disabled:cursor-not-allowed disabled:opacity-30"
                    title="删除"
                    :disabled="d.sourceType === 'INBOUND'"
                    @click="onDelete(d)"
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
          <IconDeviceCctv class="size-8" />
          <div class="text-[13px]">该范围内还没有设备</div>
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
        <span>共 {{ resource.total.value }} 台设备</span>
        <div class="flex-1" />
        <span>入站设备由同步链路维护，本地只读</span>
      </div>
    </div>

    <!-- 新增 / 编辑 -->
    <Dialog :open="formOpen" @update:open="(v) => (formOpen = v)">
      <DialogContent class="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{{ editingId ? '编辑设备' : '新增设备' }}</DialogTitle>
          <DialogDescription />
        </DialogHeader>

        <div class="space-y-3">
          <div class="flex items-center gap-3">
            <Label class="w-20 shrink-0 text-xs text-muted-foreground">设备编码</Label>
            <div class="flex-1">
              <Input v-model="form.code" class="h-8 font-mono text-xs" :disabled="!!editingId" placeholder="业务键，建立后不可修改" />
            </div>
          </div>
          <div class="flex items-center gap-3">
            <Label class="w-20 shrink-0 text-xs text-muted-foreground">设备名称</Label>
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
            <Label class="w-20 shrink-0 text-xs text-muted-foreground">接入地址</Label>
            <div class="flex-1"><Input v-model="form.deviceIp" class="h-8 font-mono text-xs" placeholder="设备 IP / 域名，可留空" /></div>
          </div>
          <div class="flex items-center gap-3">
            <Label class="w-20 shrink-0 text-xs text-muted-foreground">厂商 / 型号</Label>
            <div class="flex flex-1 gap-2">
              <Input v-model="form.vendor" class="h-8 text-xs" placeholder="厂商" />
              <Input v-model="form.model" class="h-8 text-xs" placeholder="型号" />
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
import { IconDeviceCctv, IconLoader2, IconPencil, IconPlus, IconTrash } from '@tabler/icons-vue'
import Button from '@/components/ui/button/Button.vue'
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
import { useResTree } from '@/features/res/useResTree'
import { useResource } from '@/shared/composables/useResource'
import { PERM } from '@/features/access/permissions'
import { confirm } from '@/lib/confirm'
import { toast } from '@/lib/toast'
import { createResDeviceApi, deleteResDeviceApi, getResDevicePageApi, updateResDeviceApi } from '@/api/res'
import type { ResDevice, ResOrgTreeNode, ResourcePageRequest, SourceType } from '@/types/api'

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

const resource = useResource<ResourcePageRequest, ResDevice>({
  fetcher: (q) =>
    getResDevicePageApi({
      ...q,
      orgId: selectedId.value ?? undefined,
      keyword: keyword.value.trim() || undefined,
      sourceType: sourceType.value ?? undefined,
    }),
  initial: { page: 1, pageSize: 50 },
})

const firstLoading = computed(() => resource.loading.value && resource.items.value.length === 0)

const refreshList = () => resource.load(true)

/** 组织 id → 名称 / 路径，表格里直接显示名字而不是一串雪花 id */
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

const orgPathName = (orgId?: string) => {
  if (!orgId) return '—'
  const node = orgMap.value.get(orgId)
  return node?.localName || node?.name || '—'
}

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

/* ---------------- 新增 / 编辑 ---------------- */
const formOpen = ref(false)
const saving = ref(false)
const editingId = ref<string | null>(null)
const form = reactive({
  code: '',
  name: '',
  orgId: '',
  deviceIp: '',
  vendor: '',
  model: '',
  localName: '',
})

const openCreate = () => {
  editingId.value = null
  form.code = ''
  form.name = ''
  form.orgId = selectedId.value ?? orgOptions.value[0]?.id ?? ''
  form.deviceIp = ''
  form.vendor = ''
  form.model = ''
  form.localName = ''
  formOpen.value = true
}

const openEdit = (d: ResDevice) => {
  if (d.sourceType === 'INBOUND') {
    toast.warning('入站设备由同步链路维护，不能本地编辑')
    return
  }
  editingId.value = d.id
  form.code = d.code
  form.name = d.name
  form.orgId = d.orgId ?? ''
  form.deviceIp = d.deviceIp ?? ''
  form.vendor = d.vendor ?? ''
  form.model = d.model ?? ''
  form.localName = d.localName ?? ''
  formOpen.value = true
}

const onSave = async () => {
  if (!form.code.trim() || !form.name.trim()) {
    toast.warning('设备编码与名称不能为空')
    return
  }
  if (!form.orgId) {
    toast.warning('请选择所属组织')
    return
  }
  saving.value = true
  try {
    const payload = {
      code: form.code.trim(),
      name: form.name.trim(),
      orgId: form.orgId,
      deviceIp: form.deviceIp.trim() || undefined,
      vendor: form.vendor.trim() || undefined,
      model: form.model.trim() || undefined,
      localName: form.localName.trim() || undefined,
    }
    if (editingId.value) await updateResDeviceApi(editingId.value, payload)
    else await createResDeviceApi(payload)
    toast.success('保存成功')
    formOpen.value = false
    await Promise.all([refreshList(), fetchTree()])
  } catch {
    // 拦截器已提示（编码重复 / 编码不可改 / 入站只读）
  } finally {
    saving.value = false
  }
}

const onDelete = async (d: ResDevice) => {
  if (d.sourceType === 'INBOUND') return
  const ok = await confirm({
    title: '删除设备',
    description: `确定删除设备「${d.localName || d.name}」吗？设备下还有通道时会被拒绝。`,
    danger: true,
    confirmText: '删除',
  })
  if (!ok) return
  try {
    await deleteResDeviceApi(d.id)
    toast.success('删除成功')
    resource.removeWhere((item) => item.id === d.id)
    await fetchTree()
  } catch {
    // 拦截器已提示（通道未清空）
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
