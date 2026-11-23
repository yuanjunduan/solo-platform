<template>
  <PopoverRoot v-model:open="open">
    <PopoverTrigger as-child>
      <button
        type="button"
        class="flex h-7 shrink-0 items-center gap-1.5 rounded-md px-2 text-[13px] outline-none"
        :class="
          active
            ? 'bg-primary/10 text-primary hover:bg-primary/15'
            : 'text-muted-foreground hover:bg-muted/70 hover:text-foreground'
        "
        :title="active ? `按组织筛选：${label}` : '按本地组织筛选'"
      >
        <IconSitemap class="size-3.5 shrink-0" />
        <span class="max-w-[120px] truncate">{{ label }}</span>
        <IconChevronDown class="size-3 shrink-0 opacity-70" />
      </button>
    </PopoverTrigger>

    <PopoverPortal>
      <PopoverContent
        align="start"
        :side-offset="4"
        class="z-50 w-[300px] rounded-md border border-border bg-popover p-0 text-popover-foreground shadow-md"
      >
        <div class="flex items-center gap-2 border-b border-border px-2.5">
          <IconSearch class="size-3.5 shrink-0 text-muted-foreground" />
          <input
            ref="inputRef"
            v-model="keyword"
            class="h-8 flex-1 bg-transparent text-[13px] outline-none placeholder:text-muted-foreground"
            placeholder="搜索组织名称 / 国标编码"
          />
        </div>

        <div class="max-h-72 overflow-auto p-1">
          <button
            type="button"
            class="flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left text-[13px] hover:bg-accent"
            :class="!modelValue ? 'bg-accent' : ''"
            @click="pick(undefined)"
          >
            <IconApps class="size-3.5 shrink-0 text-muted-foreground" />
            <span class="flex-1">全部组织</span>
          </button>
          <button
            type="button"
            class="flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left text-[13px] hover:bg-accent"
            :class="modelValue === GB_ORG_UNASSIGNED ? 'bg-accent' : ''"
            @click="pick(GB_ORG_UNASSIGNED)"
          >
            <IconCircleDashed class="size-3.5 shrink-0 text-amber-500/80" />
            <span class="flex-1">未归属</span>
          </button>

          <div class="my-1 h-px bg-border" />

          <button
            v-for="row in rows"
            :key="row.node.id"
            type="button"
            class="flex w-full items-center gap-1.5 rounded-sm px-2 py-1.5 text-left text-[13px] hover:bg-accent"
            :class="modelValue === row.node.id ? 'bg-accent' : ''"
            :style="{ paddingLeft: `${8 + row.depth * 14}px` }"
            @click="pick(row.node.id)"
          >
            <span
              v-if="row.node.children?.length"
              class="flex size-3.5 shrink-0 items-center justify-center text-muted-foreground hover:text-foreground"
              @click.stop="toggle(row.node)"
            >
              <IconChevronRight
                class="size-3 transition-transform"
                :class="isOpen(row.node) ? 'rotate-90' : ''"
              />
            </span>
            <span v-else class="size-3.5 shrink-0" />
            <span class="min-w-0 flex-1 truncate">{{ row.node.name }}</span>
            <span
              v-if="row.node.directDeviceCount || row.node.directChannelCount"
              class="shrink-0 text-[11px] text-muted-foreground"
            >
              {{ row.node.directDeviceCount ?? 0 }}/{{ row.node.directChannelCount ?? 0 }}
            </span>
          </button>

          <div v-if="!rows.length" class="px-2 py-6 text-center text-xs text-muted-foreground">
            {{ tree.length ? '无匹配组织' : '还没有本地组织' }}
          </div>
        </div>

        <!-- 含子组织：只在选中具体组织时出现 -->
        <div
          v-if="selectedOrg"
          class="flex items-center justify-between border-t border-border px-2.5 py-1.5"
        >
          <span class="text-[11px] text-muted-foreground">包含下级组织</span>
          <Switch
            :model-value="includeSubtree"
            @update:model-value="(v: boolean) => emit('update:includeSubtree', v)"
          />
        </div>
      </PopoverContent>
    </PopoverPortal>
  </PopoverRoot>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { PopoverRoot, PopoverTrigger, PopoverPortal, PopoverContent } from 'reka-ui'
import {
  IconSitemap,
  IconChevronDown,
  IconChevronRight,
  IconSearch,
  IconApps,
  IconCircleDashed,
} from '@tabler/icons-vue'
import Switch from '@/components/ui/switch/Switch.vue'
import { GB_ORG_UNASSIGNED } from '@/types/api'
import type { GbOrgTreeNode } from '@/types/api'

const props = withDefaults(
  defineProps<{
    /** 组织 id；undefined = 全部组织，'-1' = 未归属 */
    modelValue?: string
    /** 是否含子孙组织（仅选具体组织时有意义） */
    includeSubtree?: boolean
    tree: GbOrgTreeNode[]
  }>(),
  { modelValue: undefined, includeSubtree: true },
)

const emit = defineEmits<{
  'update:modelValue': [value: string | undefined]
  'update:includeSubtree': [value: boolean]
}>()

const open = ref(false)
const keyword = ref('')
const inputRef = ref<HTMLInputElement | null>(null)
const expanded = ref<Set<string>>(new Set())

const active = computed(() => props.modelValue !== undefined)

const nameMap = computed(() => {
  const map = new Map<string, string>()
  const walk = (list: GbOrgTreeNode[]) => {
    for (const n of list) {
      map.set(n.id, n.name)
      if (n.children?.length) walk(n.children)
    }
  }
  walk(props.tree)
  return map
})

const selectedOrg = computed(() => {
  const id = props.modelValue
  if (!id || id === GB_ORG_UNASSIGNED || !props.tree.length) return null
  return findOrg(props.tree, id)
})

const label = computed(() => {
  if (props.modelValue === undefined) return '全部组织'
  if (props.modelValue === GB_ORG_UNASSIGNED) return '未归属'
  return nameMap.value.get(props.modelValue) ?? '所选组织'
})

function findOrg(list: GbOrgTreeNode[], id: string): GbOrgTreeNode | null {
  for (const n of list) {
    if (n.id === id) return n
    if (n.children?.length) {
      const hit = findOrg(n.children, id)
      if (hit) return hit
    }
  }
  return null
}

const hasHit = (node: GbOrgTreeNode, kw: string): boolean => {
  if (!kw) return true
  if (node.name.toLowerCase().includes(kw) || (node.code ?? '').includes(kw)) return true
  return (node.children ?? []).some((c) => hasHit(c, kw))
}

const isOpen = (node: GbOrgTreeNode) =>
  keyword.value.trim() ? true : expanded.value.has(node.id)

const rows = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  const acc: Array<{ node: GbOrgTreeNode; depth: number }> = []
  const walk = (list: GbOrgTreeNode[], depth: number) => {
    for (const n of list) {
      if (kw && !hasHit(n, kw)) continue
      acc.push({ node: n, depth })
      if (n.children?.length && isOpen(n)) walk(n.children, depth + 1)
    }
  }
  walk(props.tree, 0)
  return acc
})

const toggle = (node: GbOrgTreeNode) => {
  const next = new Set(expanded.value)
  next.has(node.id) ? next.delete(node.id) : next.add(node.id)
  expanded.value = next
}

const pick = (value: string | undefined) => {
  emit('update:modelValue', value)
  open.value = false
}

/** 首次打开时展开一层，避免大面积空白 */
watch(open, async (v) => {
  if (!v) {
    keyword.value = ''
    return
  }
  if (!expanded.value.size) {
    expanded.value = new Set(props.tree.map((n) => n.id))
  }
  await nextTick()
  inputRef.value?.focus()
})
</script>
