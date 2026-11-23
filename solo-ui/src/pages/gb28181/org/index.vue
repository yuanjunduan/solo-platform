<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  IconPlus,
  IconChevronRight,
  IconChevronsDown,
  IconChevronsUp,
  IconLoader2,
  IconSitemap,
  IconArrowUpRight,
} from '@tabler/icons-vue'
import { getGbOrgTreeApi, deleteGbOrgApi } from '@/api/gb28181'
import type { GbOrgTreeNode } from '@/types/api'
import { PERM } from '@/features/access/permissions'
import { confirm } from '@/lib/confirm'
import { toast } from '@/lib/toast'
import Button from '@/components/ui/button/Button.vue'
import RefreshButton from '@/components/RefreshButton.vue'
import GbOrgInspector from '@/features/gb/GbOrgInspector.vue'

interface FlatRow {
  node: GbOrgTreeNode
  depth: number
}

const loading = ref(false)
const treeData = ref<GbOrgTreeNode[]>([])
const router = useRouter()
const expandedIds = ref<Set<string>>(new Set())
const expandedInitialized = ref(false)

const selected = ref<GbOrgTreeNode | null>(null)
const creating = ref(false)
const presetParentId = ref<string | undefined>()

const inspectorOpen = computed(() => !!selected.value || creating.value)

const collectIds = (list: GbOrgTreeNode[], acc: string[] = []): string[] => {
  list.forEach((n) => {
    acc.push(n.id)
    if (n.children?.length) collectIds(n.children, acc)
  })
  return acc
}

const findNode = (list: GbOrgTreeNode[], id: string): GbOrgTreeNode | undefined => {
  for (const n of list) {
    if (n.id === id) return n
    if (n.children?.length) {
      const hit = findNode(n.children, id)
      if (hit) return hit
    }
  }
}

const flatRows = computed<FlatRow[]>(() => {
  const acc: FlatRow[] = []
  const walk = (list: GbOrgTreeNode[], depth: number) => {
    for (const n of list) {
      acc.push({ node: n, depth })
      if (n.children?.length && expandedIds.value.has(n.id)) walk(n.children, depth + 1)
    }
  }
  walk(treeData.value, 0)
  return acc
})

/** 全树汇总（直接归属数在每个节点上，这里只是给底部状态栏用） */
const totals = computed(() => {
  let orgs = 0
  let devices = 0
  let channels = 0
  const walk = (list: GbOrgTreeNode[]) => {
    for (const n of list) {
      orgs += 1
      devices += n.directDeviceCount ?? 0
      channels += n.directChannelCount ?? 0
      if (n.children?.length) walk(n.children)
    }
  }
  walk(treeData.value)
  return { orgs, devices, channels }
})

const allExpanded = computed(
  () => flatRows.value.length > 0 && collectIds(treeData.value).every((id) => expandedIds.value.has(id)),
)

const fetchData = async () => {
  loading.value = true
  try {
    treeData.value = (await getGbOrgTreeApi()).data ?? []
    if (!expandedInitialized.value) {
      expandedIds.value = new Set(collectIds(treeData.value))
      expandedInitialized.value = true
    }
    // 刷新后把选中项换成新对象，避免 inspector 里展示过期计数
    if (selected.value) selected.value = findNode(treeData.value, selected.value.id) ?? null
  } finally {
    loading.value = false
  }
}

const toggleExpandAll = () => {
  expandedIds.value = allExpanded.value ? new Set() : new Set(collectIds(treeData.value))
}

const onToggle = (row: GbOrgTreeNode) => {
  const next = new Set(expandedIds.value)
  next.has(row.id) ? next.delete(row.id) : next.add(row.id)
  expandedIds.value = next
}

const onRowClick = (row: GbOrgTreeNode) => {
  selected.value = row
  creating.value = false
}

const openCreate = (parent?: GbOrgTreeNode) => {
  presetParentId.value = parent?.id
  selected.value = null
  creating.value = true
}

const onCloseInspector = () => {
  selected.value = null
  creating.value = false
}

const onSaved = async () => {
  await fetchData()
}

const onCreated = async () => {
  creating.value = false
  await fetchData()
}

const onDelete = async (org: GbOrgTreeNode) => {
  const ok = await confirm({
    title: '删除组织',
    description: `确定删除组织 "${org.name}" 吗？组织下还有子组织或资源时会被拒绝。`,
    danger: true,
    confirmText: '删除',
  })
  if (!ok) return
  try {
    await deleteGbOrgApi(org.id)
    toast.success('删除成功')
    if (selected.value?.id === org.id) selected.value = null
    await fetchData()
  } catch {
    // 拦截器已提示具体原因（子组织 / 设备 / 通道未清空）
  }
}

/** 下钻：概况里的"直接设备/直接通道"跳对应页面，subtree=0 表示只看该组织自身（与计数口径一致） */
const onDrill = (kind: 'device' | 'channel', org: GbOrgTreeNode) => {
  router.push({
    path: kind === 'device' ? '/gb28181/device' : '/gb28181/device-channel',
    query: { orgId: org.id, subtree: '0' },
  })
}

onMounted(() => {
  void fetchData()
})
</script>

<template>
  <div class="flex h-full gap-3 overflow-hidden p-3">
    <!-- 左侧：组织树 -->
    <div class="flex min-w-0 flex-1 flex-col overflow-hidden rounded-xl border border-border bg-card">
      <div class="flex h-12 shrink-0 items-center gap-1 border-b border-border px-3">
        <span class="text-[13px] font-medium">组织管理</span>
        <span class="ml-2 text-xs text-muted-foreground">本地域资源的归属节点</span>
        <div class="flex-1" />
        <RefreshButton :loading="loading" :on-refresh="fetchData" />
        <Button variant="ghost" size="sm" class="gap-1.5" @click="toggleExpandAll">
          <IconChevronsUp v-if="allExpanded" class="size-3.5" />
          <IconChevronsDown v-else class="size-3.5" />
          {{ allExpanded ? '全部折叠' : '全部展开' }}
        </Button>
        <Button
          v-access="PERM.GB_ORG_ADD"
          variant="ghost"
          size="sm"
          class="ml-1 gap-1.5"
          @click="openCreate()"
        >
          <IconPlus class="size-3.5" />
          新增
        </Button>
      </div>

      <div class="min-h-0 flex-1 overflow-auto">
        <table class="w-full table-fixed border-collapse text-left text-[13px]">
          <thead class="sticky top-0 z-10">
            <tr class="bg-card text-xs text-muted-foreground">
              <th class="w-64 px-3 py-2 font-normal">组织名称</th>
              <th class="w-52 px-3 py-2 font-normal">国标编码</th>
              <th class="w-20 px-3 py-2 font-normal">层级</th>
              <th class="w-20 px-3 py-2 font-normal">设备</th>
              <th class="w-20 px-3 py-2 font-normal">通道</th>
              <th class="w-20 px-3 py-2 font-normal">排序</th>
              <th class="px-3 py-2 font-normal">备注</th>
            </tr>
          </thead>
          <tbody v-if="!loading">
            <tr
              v-for="row in flatRows"
              :key="row.node.id"
              class="cursor-pointer"
              :class="selected?.id === row.node.id ? 'bg-primary/[0.07]' : 'hover:bg-muted/30'"
              @click="onRowClick(row.node)"
            >
              <td class="px-3 py-1.5">
                <div class="flex min-w-0 items-center gap-1.5" :style="{ paddingLeft: `${row.depth * 18}px` }">
                  <button
                    v-if="row.node.children?.length"
                    type="button"
                    class="flex size-4 shrink-0 items-center justify-center text-muted-foreground hover:text-foreground"
                    @click.stop="onToggle(row.node)"
                  >
                    <IconChevronRight
                      class="size-3.5 transition-transform"
                      :class="expandedIds.has(row.node.id) ? 'rotate-90' : ''"
                    />
                  </button>
                  <span v-else class="size-4 shrink-0" />
                  <IconSitemap class="size-3.5 shrink-0 text-muted-foreground" />
                  <span class="min-w-0 truncate">{{ row.node.name }}</span>
                </div>
              </td>
              <td class="px-3 py-1.5 font-mono text-xs text-muted-foreground">
                {{ row.node.code || '—' }}
              </td>
              <td class="px-3 py-1.5 text-xs text-muted-foreground">L{{ row.node.level }}</td>
              <td class="px-3 py-1.5 text-xs">
                <button
                  v-if="row.node.directDeviceCount"
                  type="button"
                  class="inline-flex items-center gap-0.5 text-primary hover:underline"
                  title="到设备页查看该组织直接归属的设备"
                  @click.stop="onDrill('device', row.node)"
                >
                  {{ row.node.directDeviceCount }}
                  <IconArrowUpRight class="size-3" />
                </button>
                <span v-else class="text-muted-foreground/50">0</span>
              </td>
              <td class="px-3 py-1.5 text-xs">
                <button
                  v-if="row.node.directChannelCount"
                  type="button"
                  class="inline-flex items-center gap-0.5 text-primary hover:underline"
                  title="到通道页查看该组织直接归属的通道"
                  @click.stop="onDrill('channel', row.node)"
                >
                  {{ row.node.directChannelCount }}
                  <IconArrowUpRight class="size-3" />
                </button>
                <span v-else class="text-muted-foreground/50">0</span>
              </td>
              <td class="px-3 py-1.5 text-xs text-muted-foreground">{{ row.node.sort }}</td>
              <td class="truncate px-3 py-1.5 text-xs text-muted-foreground" :title="row.node.remark ?? ''">
                {{ row.node.remark || '—' }}
              </td>
            </tr>
          </tbody>
        </table>

        <div v-if="loading" class="grid place-items-center py-16">
          <IconLoader2 class="size-5 animate-spin text-muted-foreground" />
        </div>
        <div
          v-else-if="!flatRows.length"
          class="flex flex-col items-center gap-2 py-16 text-muted-foreground"
        >
          <IconSitemap class="size-8" />
          <div class="text-[13px]">还没有组织节点</div>
          <div class="text-[11px] text-muted-foreground/70">
            建议先保留默认根组织，再在其下按楼栋/楼层建子组织
          </div>
        </div>
      </div>

      <div
        class="flex h-8 shrink-0 items-center gap-3 border-t border-border px-3 text-[11px] text-muted-foreground/70"
      >
        <span>共 {{ totals.orgs }} 个组织</span>
        <span>·</span>
        <span>直接归属设备 {{ totals.devices }} 台</span>
        <span>·</span>
        <span>直接归属通道 {{ totals.channels }} 路</span>
        <div class="flex-1" />
        <span>点击数字下钻查看资源；归属在设备/通道页的「归属」按钮调整</span>
      </div>
    </div>

    <!-- 右侧：组织详情 / 表单 -->
    <Transition name="inspector">
      <aside
        v-if="inspectorOpen"
        class="flex w-[400px] shrink-0 flex-col overflow-hidden rounded-xl border border-border bg-card"
      >
        <div class="min-h-0 flex-1 overflow-y-auto p-5">
          <GbOrgInspector
            :org="selected ?? undefined"
            :preset-parent-id="presetParentId"
            :tree="treeData"
            :creating="creating"
            @close="onCloseInspector"
            @saved="onSaved"
            @created="onCreated"
            @deleted="onDelete"
            @add-child="openCreate"
            @drill="onDrill"
          />
        </div>
      </aside>
    </Transition>
  </div>
</template>

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
</style>
