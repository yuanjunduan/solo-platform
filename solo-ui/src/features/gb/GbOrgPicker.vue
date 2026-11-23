<template>
  <DialogRoot :open="open" @update:open="(v: boolean) => emit('update:open', v)">
    <DialogContent class="w-full max-w-md gap-0 overflow-hidden p-0 sm:max-w-md">
      <DialogHeader class="border-b border-border px-4 py-3">
        <DialogTitle class="text-sm">
          {{ kind === 'device' ? '调整设备归属' : '调整通道归属' }}
        </DialogTitle>
        <DialogDescription class="text-xs">
          已选 {{ count }} {{ kind === 'device' ? '台设备' : '条通道' }}，选择目标组织后确认
        </DialogDescription>
      </DialogHeader>

      <div class="flex items-center gap-2 border-b border-border px-3">
        <IconSearch class="size-3.5 shrink-0 text-muted-foreground" />
        <input
          ref="inputRef"
          v-model="keyword"
          class="h-9 flex-1 bg-transparent text-[13px] outline-none placeholder:text-muted-foreground"
          placeholder="搜索组织名称 / 国标编码"
        />
      </div>

      <div class="max-h-80 overflow-auto p-1">
        <!-- 清空归属：设备变未归属，通道回落跟随设备 -->
        <button
          type="button"
          class="flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left text-[13px] hover:bg-accent"
          :class="selected === GB_ORG_UNASSIGNED ? 'bg-accent' : ''"
          @click="selected = GB_ORG_UNASSIGNED"
        >
          <IconCircleDashed class="size-3.5 shrink-0 text-amber-500/80" />
          <span class="flex-1">{{ clearText }}</span>
          <IconCheck v-if="selected === GB_ORG_UNASSIGNED" class="size-3.5 shrink-0 text-primary" />
        </button>

        <div class="my-1 h-px bg-border" />

        <button
          v-for="row in rows"
          :key="row.node.id"
          type="button"
          class="flex w-full items-center gap-1.5 rounded-sm px-2 py-1.5 text-left text-[13px] hover:bg-accent"
          :class="selected === row.node.id ? 'bg-accent' : ''"
          :style="{ paddingLeft: `${8 + row.depth * 14}px` }"
          @click="selected = row.node.id"
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
          <IconCheck v-if="selected === row.node.id" class="size-3.5 shrink-0 text-primary" />
        </button>

        <div v-if="!rows.length" class="px-2 py-6 text-center text-xs text-muted-foreground">
          {{ tree.length ? '无匹配组织' : '还没有本地组织' }}
        </div>
      </div>

      <DialogFooter class="border-t border-border px-4 py-3">
        <span class="mr-auto truncate text-[11px] text-muted-foreground">{{ targetHint }}</span>
        <Button variant="outline" size="sm" @click="emit('update:open', false)">取消</Button>
        <Button size="sm" :disabled="!selected || submitting" @click="onSubmit">
          <IconLoader2 v-if="submitting" class="mr-1 size-3.5 animate-spin" />
          确定
        </Button>
      </DialogFooter>
    </DialogContent>
  </DialogRoot>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import {
  DialogRoot,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import {
  IconSearch,
  IconChevronRight,
  IconCircleDashed,
  IconCheck,
  IconLoader2,
} from '@tabler/icons-vue'
import { GB_ORG_UNASSIGNED } from '@/types/api'
import type { GbOrgTreeNode } from '@/types/api'

const props = withDefaults(
  defineProps<{
    open: boolean
    /** 组织树（本地组织） */
    tree: GbOrgTreeNode[]
    /** 已选资源数量，仅用于文案 */
    count: number
    /** 资源类型，仅影响文案 */
    kind?: 'device' | 'channel'
    /** 当前组织筛选，作为打开时的默认目标 */
    defaultOrgId?: string
    /** 提交中，禁用确定按钮 */
    submitting?: boolean
  }>(),
  { kind: 'device', defaultOrgId: undefined, submitting: false },
)

const emit = defineEmits<{
  'update:open': [value: boolean]
  /** orgId='-1' 表示清空归属 */
  submit: [orgId: string]
}>()

const keyword = ref('')
const inputRef = ref<HTMLInputElement | null>(null)
const expanded = ref<Set<string>>(new Set())
/** 选中值：null=未选；'-1'=清空归属；其他=组织 id */
const selected = ref<string | null>(null)

const clearText = computed(() =>
  props.kind === 'device' ? '清空归属（变为未归属）' : '清空归属（回落跟随所属设备）',
)

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

const targetHint = computed(() => {
  if (!selected.value) return '请选择目标组织'
  if (selected.value === GB_ORG_UNASSIGNED) return '将清空所选资源的组织归属'
  return `将归入「${nameMap.value.get(selected.value) ?? '所选组织'}」`
})

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

const onSubmit = () => {
  if (!selected.value || props.submitting) return
  emit('submit', selected.value)
}

/** 每次打开重置：搜索词清空、默认展开一层、预选当前组织筛选（未归属不作为默认） */
watch(
  () => props.open,
  async (v) => {
    if (!v) return
    keyword.value = ''
    expanded.value = new Set(props.tree.map((n) => n.id))
    selected.value =
      props.defaultOrgId && props.defaultOrgId !== GB_ORG_UNASSIGNED
        ? props.defaultOrgId
        : null
    await nextTick()
    inputRef.value?.focus()
  },
)
</script>
