<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  IconSearch,
  IconX,
  IconLoader2,
} from '@tabler/icons-vue'
import { Eraser, Plus, Send, Trash2 } from 'lucide-vue-next'
import Button from '@/components/ui/button/Button.vue'
import Checkbox from '@/components/ui/checkbox/Checkbox.vue'
import Input from '@/components/ui/input/Input.vue'
import GroupTreeNode from './GroupTreeNode.vue'
import {
  getParentPlatformShareGroupTreeApi,
  getGbLocalPlatformInfoApi,
  savePlatformGroupApi,
  updatePlatformGroupApi,
  deletePlatformGroupApi,
  getParentPlatformSharedByGroupPageApi,
  getParentPlatformUnmountedChannelsApi,
  addSharesApi,
  removeShareApi,
  clearSharesApi,
  clearAllSharesByPlatformApi,
  pushCatalogApi,
} from '@/api/gb28181'
import { PERM } from '@/features/access/permissions'
import { confirm } from '@/lib/confirm'
import { toast } from '@/lib/toast'
import type {
  GbDeviceChannel,
  GbPlatformGroup,
  GbSharedChannel,
} from '@/types/api'

const props = defineProps<{
  platformId: string
  platformName: string
}>()

const emit = defineEmits<{ close: [] }>()

/* ---------------- 分组树 ---------------- */
const tree = ref<GbPlatformGroup[]>([])
const expanded = ref<Set<string>>(new Set())
const selectedGroupId = ref('')

const loadTree = async () => {
  tree.value = (await getParentPlatformShareGroupTreeApi(props.platformId)).data
}

const onToggle = (id: string) => {
  const next = new Set(expanded.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expanded.value = next
}

const onSelectGroup = (id: string) => {
  selectedGroupId.value = id
  keyword.value = ''
  shared.value = []
  sharedPage.value = 1
}

/* ---------------- 分组 CRUD（inline 表单） ---------------- */
const groupFormOpen = ref(false)
const groupIsEdit = ref(false)
const groupSaving = ref(false)
/** 本平台 SIP 域（编码前 10 位），开弹窗时拉一次；拉不到则该段保持可手输 */
const localDomain = ref('')
/** 编码拆三段：域(10) + 类型(215/216) + 序号(7)，避免让运营肉眼数 20 位 */
const groupForm = ref<{
  id?: string
  parentId: string
  name: string
  codeDomain: string
  codeType: string
  codeSeq: string
}>({
  parentId: '0',
  name: '',
  codeDomain: '',
  codeType: '215',
  codeSeq: '',
})
const groupErrors = ref<Record<string, string>>({})

/** 拼回 20 位国标码（序号不足 7 位自动左补零） */
const groupCode = computed(
  () =>
    groupForm.value.codeDomain + groupForm.value.codeType + groupForm.value.codeSeq.padStart(7, '0'),
)

const flattenGroups = (nodes: GbPlatformGroup[]): GbPlatformGroup[] =>
  nodes.flatMap((n) => [n, ...flattenGroups(n.children ?? [])])

/** 同类型下一个可用序号（7 位）：新建时预填，省去手输与撞码 */
const nextSeq = (type: string) => {
  let max = 0
  for (const g of flattenGroups(tree.value)) {
    const c = g.code ?? ''
    if (c.length === 20 && c.slice(10, 13) === type) {
      max = Math.max(max, Number(c.slice(13)) || 0)
    }
  }
  return String(max + 1).padStart(7, '0')
}

/** 按 id 在分组树里找节点 */
const findGroupNode = (
  id: string,
  nodes: GbPlatformGroup[] = tree.value,
): GbPlatformGroup | undefined => {
  for (const n of nodes) {
    if (n.id === id) return n
    const hit = n.children?.length ? findGroupNode(id, n.children) : undefined
    if (hit) return hit
  }
  return undefined
}

/** 自身 + 全部后代 id：换父时这些都不能选，否则会把分组挂进自己的子树（后端会报成环） */
const collectSelfAndDescendants = (
  node: GbPlatformGroup,
  out = new Set<string>(),
): Set<string> => {
  out.add(node.id)
  for (const c of node.children ?? []) collectSelfAndDescendants(c, out)
  return out
}

const blockedParentIds = computed(() => {
  const id = groupForm.value.id
  if (!groupIsEdit.value || !id) return new Set<string>()
  const node = findGroupNode(id)
  return node ? collectSelfAndDescendants(node) : new Set<string>()
})

/** 可选父分组：215 只能作根（下拉禁用）；216 可挂 215/216，但排除自身与后代 */
const parentOptions = computed(() => {
  if (groupForm.value.codeType === '215') return []
  const out: { id: string; label: string }[] = []
  const walk = (nodes: GbPlatformGroup[], depth: number) => {
    for (const n of nodes) {
      // 命中的节点整棵子树都不可选，直接跳过即可
      if (blockedParentIds.value.has(n.id)) continue
      out.push({ id: n.id, label: `${'　'.repeat(depth)}${n.name} (${n.code})` })
      if (n.children?.length) walk(n.children, depth + 1)
    }
  }
  walk(tree.value, 0)
  return out
})

const ensureLocalDomain = async () => {
  if (localDomain.value) return
  try {
    localDomain.value = (await getGbLocalPlatformInfoApi()).data.domain ?? ''
  } catch {
    // 拉不到就让域段保持可编辑，由运营手填
  }
}

const onCreateGroup = (parentId: string) => {
  groupIsEdit.value = false
  // 层级即类型：根节点只能是 215 业务分组，子节点只能是 216 虚拟组织（GB/T 28181-2016 附录 O）
  const isRoot = !parentId || parentId === '0'
  const codeType = isRoot ? '215' : '216'
  groupForm.value = {
    parentId,
    name: '',
    codeDomain: localDomain.value,
    codeType,
    codeSeq: nextSeq(codeType),
  }
  groupErrors.value = {}
  groupFormOpen.value = true
  // 网速慢/首次进弹窗时域还没拉回，补齐一次
  if (!localDomain.value) {
    ensureLocalDomain().then(() => {
      if (!groupForm.value.codeDomain) groupForm.value.codeDomain = localDomain.value
    })
  }
}

const onEditGroup = (node: GbPlatformGroup) => {
  const code = node.code ?? ''
  groupForm.value = {
    id: node.id,
    parentId: node.parentId,
    name: node.name,
    codeDomain: code.length === 20 ? code.slice(0, 10) : localDomain.value,
    codeType: code.length === 20 ? code.slice(10, 13) : '215',
    codeSeq: code.length === 20 ? code.slice(13) : code,
  }
  groupIsEdit.value = true
  groupErrors.value = {}
  groupFormOpen.value = true
}

/* 只允许数字，防止粘进空格/字母后拼接出非法编码 */
watch(
  () => groupForm.value.codeSeq,
  (v) => {
    const digits = v.replace(/\D/g, '').slice(0, 7)
    if (digits !== v) groupForm.value.codeSeq = digits
  },
)
watch(
  () => groupForm.value.codeDomain,
  (v) => {
    const digits = v.replace(/\D/g, '').slice(0, 10)
    if (digits !== v) groupForm.value.codeDomain = digits
  },
)
/* 新建时切类型自动换一个不撞码的序号，编辑时不动用户已有值；父分组随类型联动 */
watch(
  () => groupForm.value.codeType,
  (t, prev) => {
    if (prev && !groupIsEdit.value) groupForm.value.codeSeq = nextSeq(t)
    if (!prev) return
    // 215 业务分组只能作根
    if (t === '215') {
      groupForm.value.parentId = '0'
      return
    }
    // 216 必须有父：原父是根或已被排除（自身/后代）时，自动挑第一个可用父
    const ids = parentOptions.value.map((o) => o.id)
    if (!ids.includes(groupForm.value.parentId)) {
      groupForm.value.parentId = ids[0] ?? '0'
    }
  },
)

const validateGroup = () => {
  const e: Record<string, string> = {}
  if (!groupForm.value.name.trim()) e.name = '请输入分组名称'
  const { codeDomain, codeType, codeSeq, parentId } = groupForm.value
  if (!/^\d{10}$/.test(codeDomain)) {
    e.code = '前 10 位域编码需为 10 位数字'
  } else if (!/^\d{1,7}$/.test(codeSeq)) {
    e.code = '序号需为 1-7 位数字'
  } else {
    // GB/T 28181-2016 附录 O：215 业务分组只能作根节点，216 虚拟组织才能逐级嵌套
    const isRoot = !parentId || parentId === '0'
    if (codeType === '215' && !isRoot) e.code = '业务分组（215）只能建在根节点下，下级分组请用 216 虚拟组织'
    if (codeType === '216' && isRoot) e.code = '虚拟组织（216）必须挂在业务分组或上级虚拟组织下'
  }
  groupErrors.value = e
  return Object.keys(e).length === 0
}

const onSubmitGroup = async () => {
  if (groupSaving.value || !validateGroup()) return
  groupSaving.value = true
  try {
    const { name, parentId } = groupForm.value
    const code = groupCode.value
    if (groupIsEdit.value) {
      await updatePlatformGroupApi(props.platformId, groupForm.value.id!, {
        name,
        code,
        parentId,
      })
      toast.success('修改成功')
    } else {
      await savePlatformGroupApi(props.platformId, { name, code, parentId })
      toast.success('新建成功')
    }
    groupFormOpen.value = false
    // 换父后把新父展开，否则移动过去的节点藏在折叠节点里
    if (groupIsEdit.value && parentId && parentId !== '0') {
      expanded.value = new Set(expanded.value).add(parentId)
    }
    await loadTree()
  } finally {
    groupSaving.value = false
  }
}

const onDeleteGroup = async (node: GbPlatformGroup) => {
  const ok = await confirm({
    title: `确定删除分组 “${node.name}” 吗？`,
    description: '不影响已挂载的通道记录。',
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  await deletePlatformGroupApi(props.platformId, node.id)
  toast.success('删除成功')
  if (selectedGroupId.value === node.id) selectedGroupId.value = ''
  await loadTree()
}

const onClearGroup = async (node: GbPlatformGroup) => {
  const ok = await confirm({
    title: `确定清空分组 “${node.name}” 的挂载吗？`,
    description: '该分组下所有通道将被卸载。',
    confirmText: '清空',
    danger: true,
  })
  if (!ok) return
  const res = await clearSharesApi(props.platformId, node.id)
  toast.success(`已清空 ${res.data.cleared} 条`)
  if (selectedGroupId.value === node.id) {
    shared.value = []
    sharedTotal.value = 0
  }
}

/* ---------------- 已挂载列表 ---------------- */
const shared = ref<GbSharedChannel[]>([])
const sharedTotal = ref(0)
const sharedPage = ref(1)
const loadingShared = ref(false)
const keyword = ref('')
const sharedScroller = ref<HTMLElement>()
const sharedSentinel = ref<HTMLElement>()
const selectedIds = ref<Set<string>>(new Set())

const displayName = (c: GbDeviceChannel) => c.name || c.reportName || c.channelId

/** 通道来源接入平台（每行通道必有来源平台，platformId 为空只可能是数据异常，展示层给占位符） */
const sourcePlatform = (c: GbDeviceChannel) => c.platformId || '—'

const loadShared = async (reset = false) => {
  if (!selectedGroupId.value || loadingShared.value) return
  if (reset) {
    sharedPage.value = 1
    shared.value = []
    selectedIds.value = new Set()
  }
  loadingShared.value = true
  try {
    const res = await getParentPlatformSharedByGroupPageApi(
      props.platformId,
      selectedGroupId.value,
      { page: sharedPage.value, pageSize: 30, keyword: keyword.value.trim() || undefined },
    )
    shared.value = [...shared.value, ...res.data.rows]
    sharedTotal.value = res.data.total
    if (shared.value.length < res.data.total) sharedPage.value += 1
  } finally {
    loadingShared.value = false
  }
}

watch(selectedGroupId, () => loadShared(true))

let kwTimer: ReturnType<typeof setTimeout> | null = null
watch(keyword, () => {
  if (kwTimer) clearTimeout(kwTimer)
  kwTimer = setTimeout(() => loadShared(true), 250)
})

const allSelected = computed(
  () => shared.value.length > 0 && shared.value.every((c) => selectedIds.value.has(c.id)),
)

const toggleAll = (v: boolean) => {
  const next = new Set<string>()
  if (v) shared.value.forEach((c) => next.add(c.id))
  selectedIds.value = next
}

const toggleOne = (id: string, checked: boolean) => {
  const next = new Set(selectedIds.value)
  if (checked) next.add(id)
  else next.delete(id)
  selectedIds.value = next
}

const onUnmountSelected = async () => {
  if (selectedIds.value.size === 0) {
    toast.warning('请先勾选要卸载的通道')
    return
  }
  const ok = await confirm({
    title: `确定卸载选中的 ${selectedIds.value.size} 个通道吗？`,
    description: '不影响通道本身。',
    confirmText: '卸载',
    danger: true,
  })
  if (!ok) return
  await Promise.all(
    [...selectedIds.value].map((id) =>
      removeShareApi(props.platformId, selectedGroupId.value, id),
    ),
  )
  toast.success('卸载成功')
  shared.value = shared.value.filter((x) => !selectedIds.value.has(x.id))
  sharedTotal.value = Math.max(0, sharedTotal.value - selectedIds.value.size)
  selectedIds.value = new Set()
}

const onClearAll = async () => {
  const ok = await confirm({
    title: '确定清空该平台的全部挂载吗？',
    description: '所有分组下的通道都将被卸载。',
    confirmText: '全部清空',
    danger: true,
  })
  if (!ok) return
  const res = await clearAllSharesByPlatformApi(props.platformId)
  toast.success(`已清空 ${res.data.cleared} 条`)
  shared.value = []
  sharedTotal.value = 0
  selectedIds.value = new Set()
}

const onPushCatalog = async () => {
  await pushCatalogApi(props.platformId)
  toast.success('已触发推送')
}

/* ---------------- 通道选择（覆盖面板） ---------------- */
const pickerOpen = ref(false)
const pickerItems = ref<GbDeviceChannel[]>([])
const pickerPage = ref(1)
const loadingPicker = ref(false)
const pickerKeyword = ref('')
const pickedIds = ref<Set<string>>(new Set())
const adding = ref(false)
const pickerScroller = ref<HTMLElement>()
const pickerSentinel = ref<HTMLElement>()

const loadPicker = async (reset = false) => {
  if (!selectedGroupId.value || loadingPicker.value) return
  if (reset) {
    pickerPage.value = 1
    pickerItems.value = []
  }
  loadingPicker.value = true
  try {
    const res = await getParentPlatformUnmountedChannelsApi(
      props.platformId,
      selectedGroupId.value,
      { page: pickerPage.value, pageSize: 30, keyword: pickerKeyword.value.trim() || undefined },
    )
    pickerItems.value = [...pickerItems.value, ...res.data.rows]
    if (pickerItems.value.length < res.data.total) pickerPage.value += 1
  } finally {
    loadingPicker.value = false
  }
}

const openPicker = () => {
  pickedIds.value = new Set()
  pickerKeyword.value = ''
  pickerOpen.value = true
  loadPicker(true)
}

let pkTimer: ReturnType<typeof setTimeout> | null = null
watch(pickerKeyword, () => {
  if (pkTimer) clearTimeout(pkTimer)
  pkTimer = setTimeout(() => loadPicker(true), 250)
})

const pickerAllOnPage = () =>
  pickerItems.value.length > 0 &&
  pickerItems.value.every((c) => pickedIds.value.has(c.id))

const onPickerTogglePage = (v: boolean) => {
  const next = new Set(pickedIds.value)
  pickerItems.value.forEach((c) => {
    if (v) next.add(c.id)
    else next.delete(c.id)
  })
  pickedIds.value = next
}

const onPickOne = (id: string, checked: boolean) => {
  const next = new Set(pickedIds.value)
  if (checked) next.add(id)
  else next.delete(id)
  pickedIds.value = next
}

const onConfirmPicker = async () => {
  if (pickedIds.value.size === 0) {
    toast.warning('请先勾选要挂载的通道')
    return
  }
  adding.value = true
  try {
    const res = await addSharesApi(props.platformId, selectedGroupId.value, {
      resourceIds: [...pickedIds.value],
    })
    toast.success(`挂载成功，新增 ${res.data.added} 条`)
    pickerOpen.value = false
    loadShared(true)
  } finally {
    adding.value = false
  }
}

/* ---------------- observers ---------------- */
let sharedObserver: IntersectionObserver | null = null
let pickerObserver: IntersectionObserver | null = null

const setupObservers = () => {
  sharedObserver = new IntersectionObserver(
    (entries) => {
      if (entries[0].isIntersecting) loadShared(false)
    },
    { root: sharedScroller.value, rootMargin: '60px' },
  )
  if (sharedSentinel.value) sharedObserver.observe(sharedSentinel.value)

  pickerObserver = new IntersectionObserver(
    (entries) => {
      if (entries[0].isIntersecting) loadPicker(false)
    },
    { root: pickerScroller.value, rootMargin: '60px' },
  )
  if (pickerSentinel.value) pickerObserver.observe(pickerSentinel.value)
}

onMounted(async () => {
  await Promise.all([loadTree(), ensureLocalDomain()])
  setupObservers()
})

onBeforeUnmount(() => {
  sharedObserver?.disconnect()
  pickerObserver?.disconnect()
  if (kwTimer) clearTimeout(kwTimer)
  if (pkTimer) clearTimeout(pkTimer)
})
</script>

<template>
  <div class="flex h-full flex-col">
    <!-- 头部 -->
    <div class="flex items-center gap-3">
      <div class="min-w-0 flex-1">
        <div class="truncate text-base font-semibold">推送目录 · {{ platformName }}</div>
        <div class="text-xs text-muted-foreground">
          维护向上级平台推送的目录分组与通道
        </div>
      </div>
      <Button variant="ghost" size="icon-sm" @click="emit('close')">
        <IconX class="size-4" />
      </Button>
    </div>

    <div class="my-4 h-px bg-border" />

    <!-- 左右分栏 -->
    <div class="flex min-h-0 flex-1 gap-3">
      <!-- 左：分组树 -->
      <div class="flex w-80 shrink-0 flex-col overflow-hidden rounded-lg border border-border">
        <!-- inline 分组表单 -->
        <div v-if="groupFormOpen" class="space-y-2 border-b border-border p-2">
          <Input
            v-model="groupForm.name"
            maxlength="64"
            placeholder="分组名称"
            class="h-7 text-xs"
          />
          <!-- 父分组：仅编辑态可改；215 只能作根，216 排除自身与后代 -->
          <div v-if="groupIsEdit" class="flex items-center gap-1">
            <span class="shrink-0 text-[11px] text-muted-foreground">父分组</span>
            <select
              v-model="groupForm.parentId"
              :disabled="groupForm.codeType === '215'"
              title="215 业务分组只能作根；216 虚拟组织可挂 215 或 216"
              class="h-7 min-w-0 flex-1 rounded-md border border-border bg-transparent px-1 text-xs outline-none disabled:opacity-60"
            >
              <option v-if="groupForm.codeType === '215'" value="0">（根节点）</option>
              <option v-else-if="!parentOptions.length" disabled value="0">
                没有可用的父分组，请先建 215 分组
              </option>
              <option v-for="o in parentOptions" :key="o.id" :value="o.id">
                {{ o.label }}
              </option>
            </select>
          </div>
          <div class="flex items-center gap-1">
            <Input
              v-model="groupForm.codeDomain"
              maxlength="10"
              :disabled="!!localDomain"
              placeholder="域编码"
              title="本平台国标域（前 10 位），自动带入"
              class="h-7 w-[104px] font-mono text-xs"
            />
            <select
              v-model="groupForm.codeType"
              title="第 11-13 位：215 业务分组只能作根，216 虚拟组织逐级嵌套"
              class="h-7 shrink-0 rounded-md border border-border bg-transparent px-1 font-mono text-xs outline-none"
            >
              <option value="215">215</option>
              <option value="216">216</option>
            </select>
            <Input
              v-model="groupForm.codeSeq"
              maxlength="7"
              placeholder="0000001"
              title="后 7 位序号，自动预填下一个可用号"
              class="h-7 flex-1 font-mono text-xs"
            />
          </div>
          <p class="truncate font-mono text-[10px] text-muted-foreground" :title="groupCode">
            {{ groupCode }}
          </p>
          <p v-if="groupErrors.name" class="text-[11px] text-destructive">
            {{ groupErrors.name }}
          </p>
          <p v-if="groupErrors.code" class="text-[11px] text-destructive">
            {{ groupErrors.code }}
          </p>
          <div class="flex justify-end gap-1">
            <Button variant="ghost" size="sm" class="h-7 text-xs" @click="groupFormOpen = false">
              取消
            </Button>
            <Button size="sm" class="h-7 text-xs" :disabled="groupSaving" @click="onSubmitGroup">
              确定
            </Button>
          </div>
        </div>

        <div class="flex h-9 shrink-0 items-center gap-1 border-b border-border px-2">
          <span class="text-xs font-medium">推送分组</span>
          <div class="flex-1" />
          <Button
            v-access="PERM.GB_SHARE_GROUP_ADD"
            variant="ghost"
            size="icon-sm"
            title="新建顶级分组"
            @click="onCreateGroup('0')"
          >
            <Plus class="size-3.5" />
          </Button>
        </div>

        <div class="min-h-0 flex-1 overflow-y-auto p-1.5">
          <GroupTreeNode
            v-for="node in tree"
            :key="node.id"
            :node="node"
            :selected-id="selectedGroupId"
            :expanded="expanded"
            @select="onSelectGroup"
            @toggle="onToggle"
            @create-child="onCreateGroup"
            @edit="onEditGroup"
            @delete="onDeleteGroup"
            @clear="onClearGroup"
          />
          <div v-if="!tree.length" class="grid place-items-center py-10 text-xs text-muted-foreground">
            暂无分组，点右上角新建
          </div>
        </div>
      </div>

      <!-- 右：通道列表 -->
      <div class="relative flex min-w-0 flex-1 flex-col overflow-hidden rounded-lg border border-border">
        <div class="flex h-9 shrink-0 items-center gap-2 border-b border-border px-2">
          <template v-if="selectedGroupId">
            <IconSearch class="size-3.5 text-muted-foreground" />
            <input
              v-model="keyword"
              class="h-6 w-48 rounded bg-transparent px-1.5 text-xs outline-none focus:bg-muted/70"
              placeholder="名称 / 编码"
            />
            <span class="text-[11px] text-muted-foreground">共 {{ sharedTotal }} 条</span>
          </template>
          <template v-else>
            <span class="text-xs text-muted-foreground">请先在左侧选择一个推送分组</span>
          </template>
          <div class="flex-1" />
          <Button
            v-access="PERM.GB_PARENT_PLATFORM_DELETE"
            variant="ghost"
            size="icon-sm"
            title="清空平台全部挂载"
            @click="onClearAll"
          >
            <Eraser class="size-3.5" />
          </Button>
          <Button
            v-access="PERM.GB_PARENT_PLATFORM_EDIT"
            variant="ghost"
            size="icon-sm"
            title="主动推送"
            @click="onPushCatalog"
          >
            <Send class="size-3.5" />
          </Button>
          <template v-if="selectedGroupId">
            <Button
              v-access="PERM.GB_PARENT_PLATFORM_EDIT"
              variant="ghost"
              size="icon-sm"
              title="删除"
              @click="onUnmountSelected"
            >
              <Trash2 class="size-3.5" />
            </Button>
            <Button
              v-access="PERM.GB_PARENT_PLATFORM_EDIT"
              variant="ghost"
              size="sm"
              class="h-7 text-xs"
              @click="openPicker"
            >
              <Plus class="size-3.5" />
              添加
            </Button>
          </template>
        </div>

        <div ref="sharedScroller" class="min-h-0 flex-1 overflow-y-auto">
          <table v-if="selectedGroupId" class="w-full table-fixed text-left text-xs">
            <thead class="sticky top-0 z-10">
              <tr class="bg-card text-muted-foreground">
                <th class="w-10 px-3 py-2 font-normal">
                  <Checkbox
                    :model-value="allSelected"
                    @update:model-value="toggleAll($event === true)"
                  />
                </th>
                <th class="w-40 px-3 py-2 font-normal">通道名称</th>
                <th class="w-44 px-3 py-2 font-normal">国标编码</th>
                <th class="w-44 px-3 py-2 font-normal">来源平台</th>
                <th class="w-44 px-3 py-2 font-normal">推送码</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in shared" :key="c.id" class="hover:bg-muted/30">
                <td class="px-3 py-2">
                  <Checkbox
                    :model-value="selectedIds.has(c.id)"
                    @update:model-value="toggleOne(c.id, $event === true)"
                  />
                </td>
                <td class="truncate px-3 py-2" :title="displayName(c)">{{ displayName(c) }}</td>
                <td class="px-3 py-2 font-mono text-muted-foreground">{{ c.channelId }}</td>
                <td class="truncate px-3 py-2 font-mono text-muted-foreground" :title="sourcePlatform(c)">
                  {{ sourcePlatform(c) }}
                </td>
                <td class="px-3 py-2 font-mono text-muted-foreground">
                  {{ c.exposedCode || '—' }}
                </td>
              </tr>
            </tbody>
          </table>

          <div
            v-if="selectedGroupId && !loadingShared && shared.length === 0"
            class="grid place-items-center py-12 text-xs text-muted-foreground"
          >
            该分组还没有挂载通道
          </div>

          <div ref="sharedSentinel" class="grid place-items-center py-3">
            <IconLoader2
              v-if="loadingShared && shared.length"
              class="size-4 animate-spin text-muted-foreground"
            />
          </div>
        </div>

        <!-- 通道选择覆盖面板 -->
        <div
          v-if="pickerOpen"
          class="absolute inset-0 z-10 flex flex-col bg-card"
        >
          <div class="flex h-9 shrink-0 items-center gap-2 border-b border-border px-2">
            <IconSearch class="size-3.5 text-muted-foreground" />
            <input
              v-model="pickerKeyword"
              class="h-6 w-40 rounded bg-transparent px-1.5 text-xs outline-none focus:bg-muted/70"
              placeholder="名称 / 编码"
            />
            <div class="flex-1" />
            <Checkbox :model-value="pickerAllOnPage()" @update:model-value="onPickerTogglePage($event === true)" />
            <span class="text-[11px] text-muted-foreground">本页全选</span>
          </div>

          <div ref="pickerScroller" class="min-h-0 flex-1 overflow-y-auto p-2">
            <label
              v-for="c in pickerItems"
              :key="c.id"
              class="flex cursor-pointer items-center gap-2 rounded px-2 py-1.5 text-xs hover:bg-muted/50"
            >
              <Checkbox
                :model-value="pickedIds.has(c.id)"
                @update:model-value="onPickOne(c.id, $event === true)"
              />
              <span class="min-w-0 flex-1 truncate">{{ displayName(c) }}</span>
              <span class="shrink-0 font-mono text-[10px] text-muted-foreground/80" :title="`来源平台：${sourcePlatform(c)}`">
                {{ sourcePlatform(c) }}
              </span>
              <span class="font-mono text-muted-foreground">{{ c.channelId }}</span>
            </label>
            <div
              v-if="!loadingPicker && pickerItems.length === 0"
              class="grid place-items-center py-10 text-xs text-muted-foreground"
            >
              没有可挂载的通道
            </div>
            <div ref="pickerSentinel" class="grid place-items-center py-2">
              <IconLoader2
                v-if="loadingPicker && pickerItems.length"
                class="size-4 animate-spin text-muted-foreground"
              />
            </div>
          </div>

          <div class="flex h-9 shrink-0 items-center gap-2 border-t border-border px-2">
            <span class="mr-auto text-[11px] text-muted-foreground">
              已选 {{ pickedIds.size }} 条
            </span>
            <Button variant="outline" size="sm" class="h-7 text-xs" @click="pickerOpen = false">
              取消
            </Button>
            <Button size="sm" class="h-7 text-xs" :disabled="adding" @click="onConfirmPicker">
              <IconLoader2 v-if="adding" class="size-3.5 animate-spin" />
              挂载
            </Button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
