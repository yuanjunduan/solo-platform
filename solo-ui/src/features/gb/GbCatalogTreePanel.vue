<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Building2, ChevronRight, Folder, Loader2, RefreshCw, Server } from 'lucide-vue-next'
import { getGbCatalogNavApi } from '@/api/gb28181'
import type { GbCatalogDomain, GbCatalogNavNode } from '@/types/api'
import { toast } from '@/lib/toast'

defineOptions({ name: 'GbCatalogTreePanel' })

const props = withDefaults(
  defineProps<{
    /** 资源域：由页面顶部的域切换控制，变化时自动重载根层 */
    domain: GbCatalogDomain
    /** 当前选中节点 key（受控，父页面持有） */
    selectedKey?: string
    /** 面板标题 */
    title?: string
    /** 首行"全部"入口的文案（点击清空选中） */
    allLabel?: string
    /** 是否展开镜像分组层；设备页不需要分组，传 false 时平台节点不再有展开箭头 */
    groups?: boolean
  }>(),
  { selectedKey: '', title: '目录', allLabel: '全部', groups: true },
)

const emit = defineEmits<{
  /** 选中节点；null 表示点了"全部"入口（清空组织/平台/分组限定） */
  select: [node: GbCatalogNavNode | null]
}>()

/** 根层在 levels 里的 key；后端约定的根层 parentKey 也是 root */
const ROOT = 'root'

interface Row {
  node: GbCatalogNavNode
  depth: number
}

/** parentKey -> 该层节点（懒加载：只存已请求过的层） */
const levels = reactive<Record<string, GbCatalogNavNode[]>>({})
/** parentKey -> 该层是否被后端 limit 截断 */
const hasMore = reactive<Record<string, boolean>>({})
/** 已展开节点 key */
const expanded = reactive(new Set<string>())
/** 正在请求下级的节点 key（含 root） */
const loading = reactive(new Set<string>())
const rootLoaded = ref(false)

/** 当前可见行（展开的层依次拼进来，带缩进深度） */
const rows = computed<Row[]>(() => {
  const out: Row[] = []
  const walk = (parentKey: string, depth: number) => {
    const children = levels[parentKey]
    if (!children || !children.length) return
    for (const node of children) {
      out.push({ node, depth })
      if (canExpand(node) && expanded.has(node.key)) walk(node.key, depth + 1)
    }
  }
  walk(ROOT, 0)
  return out
})

const truncated = computed(() => Object.values(hasMore).some(Boolean))

async function loadLevel(parentKey: string, force = false) {
  if (!force && levels[parentKey]) return
  loading.add(parentKey)
  try {
    const res = await getGbCatalogNavApi({
      domain: props.domain,
      parentKey: parentKey === ROOT ? undefined : parentKey,
    })
    const level = res.data
    levels[parentKey] = level?.children || []
    hasMore[parentKey] = !!level?.hasMore
    if (parentKey === ROOT) rootLoaded.value = true
  } catch {
    levels[parentKey] = []
    hasMore[parentKey] = false
    if (parentKey === ROOT) toast.error('目录树加载失败')
  } finally {
    loading.delete(parentKey)
  }
}

/** 切域 / 手动刷新：清空本地缓存后重拉根层 */
function reload() {
  Object.keys(levels).forEach((k) => delete levels[k])
  Object.keys(hasMore).forEach((k) => delete hasMore[k])
  expanded.clear()
  rootLoaded.value = false
  void loadLevel(ROOT, true)
}

async function toggle(node: GbCatalogNavNode) {
  if (!canExpand(node)) return
  if (expanded.has(node.key)) {
    expanded.delete(node.key)
    return
  }
  expanded.add(node.key)
  await loadLevel(node.key)
}

/** 组织节点永远可展开；分组层只有 groups=true 时才展开 */
const canExpand = (node: GbCatalogNavNode) =>
  !!node.hasChildren && (props.groups || node.nodeType === 'ORG')

const isSelected = (node: GbCatalogNavNode) => props.selectedKey === node.key
const isOpen = (node: GbCatalogNavNode) => expanded.has(node.key)
const isLoading = (node: GbCatalogNavNode) => loading.has(node.key)

/** 只对已展开且还有下级的节点显示加载中的箭头位 */
const showSpinner = (node: GbCatalogNavNode) =>
  isLoading(node) && !levels[node.key]?.length

function iconOf(node: GbCatalogNavNode) {
  if (node.nodeType === 'ORG') return Building2
  if (node.nodeType === 'PLATFORM') return Server
  return Folder
}

function iconClass(node: GbCatalogNavNode) {
  if (node.nodeType === 'ORG') return 'text-sky-500/80'
  if (node.nodeType === 'PLATFORM') return 'text-violet-500/80'
  return 'text-amber-400/80'
}

/** 右侧摘要：组织=直属设备数；平台/分组=子树通道数 */
function summary(node: GbCatalogNavNode) {
  if (node.nodeType === 'ORG') return `${node.directDeviceCount ?? 0} 台`
  return `${node.subtreeChannelCount ?? 0} 路`
}

/** 平台节点在线状态圆点；非平台节点不显示 */
function dotClass(node: GbCatalogNavNode) {
  if (node.nodeType !== 'PLATFORM') return ''
  if (node.onlineStatus === 1) return 'bg-emerald-500'
  if (node.onlineStatus === 0) return 'bg-red-500'
  return 'bg-muted-foreground/50'
}

watch(() => props.domain, reload)
onMounted(() => void loadLevel(ROOT))

/** 父页面可在注册/目录同步后主动刷新 */
defineExpose({ reload })
</script>

<template>
  <div class="flex h-full min-h-0 flex-col">
    <div class="flex h-8 shrink-0 items-center gap-1 px-2">
      <span class="text-xs font-medium text-muted-foreground">{{ title }}</span>
      <button
        type="button"
        class="ml-auto grid size-5 place-items-center rounded text-muted-foreground outline-none hover:bg-muted/60 hover:text-foreground"
        title="刷新目录树"
        @click="reload"
      >
        <RefreshCw class="size-3" :class="loading.has(ROOT) ? 'animate-spin' : ''" />
      </button>
    </div>

    <div class="min-h-0 flex-1 overflow-auto pb-2">
      <!-- "全部"入口：清空组织/平台/分组限定，按当前域查全量 -->
      <div
        class="flex h-7 cursor-pointer items-center gap-1 rounded pr-2 text-xs"
        :class="!selectedKey ? 'bg-primary/10 text-foreground' : 'hover:bg-muted/50'"
        :style="{ paddingLeft: '22px' }"
        @click="emit('select', null)"
      >
        <span class="min-w-0 truncate">{{ allLabel }}</span>
      </div>

      <div
        v-if="!rootLoaded && loading.has(ROOT)"
        class="h-7 text-[11px] text-muted-foreground/70"
        :style="{ paddingLeft: '22px', lineHeight: '28px' }"
      >
        加载中…
      </div>
      <div
        v-else-if="rootLoaded && !rows.length"
        class="h-7 text-[11px] text-muted-foreground/70"
        :style="{ paddingLeft: '22px', lineHeight: '28px' }"
      >
        暂无目录数据
      </div>

      <div
        v-for="row in rows"
        :key="row.node.key"
        class="flex h-7 cursor-pointer items-center gap-1 rounded pr-2 text-xs"
        :class="isSelected(row.node) ? 'bg-primary/10 text-foreground' : 'hover:bg-muted/50'"
        :style="{ paddingLeft: `${row.depth * 14 + 6}px` }"
        :title="row.node.label"
        @click="emit('select', row.node)"
      >
        <button
          type="button"
          class="grid size-4 shrink-0 place-items-center text-muted-foreground outline-none"
          @click.stop="toggle(row.node)"
        >
          <Loader2 v-if="showSpinner(row.node)" class="size-3 animate-spin" />
          <ChevronRight
            v-else-if="canExpand(row.node)"
            class="size-3 transition-transform"
            :class="isOpen(row.node) ? 'rotate-90' : ''"
          />
        </button>
        <span v-if="dotClass(row.node)" class="size-1.5 shrink-0 rounded-full" :class="dotClass(row.node)" />
        <component :is="iconOf(row.node)" class="size-3.5 shrink-0" :class="iconClass(row.node)" />
        <span class="min-w-0 truncate">{{ row.node.label }}</span>
        <span class="ml-auto shrink-0 text-[10px] text-muted-foreground/70">{{
          summary(row.node)
        }}</span>
      </div>

      <div
        v-if="truncated"
        class="px-3 pt-1 text-[10px] leading-4 text-amber-500/90"
        :style="{ paddingLeft: '22px' }"
      >
        部分目录项未显示（单层上限）
      </div>
    </div>
  </div>
</template>
