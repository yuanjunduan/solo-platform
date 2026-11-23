<template>
  <div
    class="flex w-[292px] shrink-0 flex-col overflow-hidden rounded-xl border border-border bg-card"
  >
    <div class="flex h-12 shrink-0 items-center gap-1 border-b border-border px-3">
      <span class="text-[13px] font-medium">{{ title }}</span>
      <div class="flex-1" />
      <RefreshButton :loading="loading" :on-refresh="onRefresh" />
      <Button variant="ghost" size="sm" class="gap-1.5" @click="emit('toggle-all')">
        <IconChevronsUp v-if="allExpanded" class="size-3.5" />
        <IconChevronsDown v-else class="size-3.5" />
      </Button>
    </div>

    <div class="min-h-0 flex-1 overflow-auto py-1">
      <!-- "全部组织"：选中即表示不按组织过滤 -->
      <button
        type="button"
        class="flex w-full items-center gap-2 px-3 py-1.5 text-left text-[13px]"
        :class="selectedId === null ? 'bg-primary/[0.07] text-primary' : 'hover:bg-muted/40'"
        @click="emit('select', null)"
      >
        <IconStack2 class="size-3.5 shrink-0" />
        <span class="min-w-0 truncate">全部组织</span>
      </button>

      <button
        v-for="row in rows"
        :key="row.node.id"
        type="button"
        class="flex w-full items-center gap-1.5 px-3 py-1.5 text-left text-[13px]"
        :class="selectedId === row.node.id ? 'bg-primary/[0.07] text-primary' : 'hover:bg-muted/40'"
        :style="{ paddingLeft: `${12 + row.depth * 16}px` }"
        @click="emit('select', row.node.id)"
      >
        <span
          class="flex size-4 shrink-0 items-center justify-center text-muted-foreground hover:text-foreground"
          @click.stop="row.node.children?.length && emit('toggle', row.node.id)"
        >
          <IconChevronRight
            v-if="row.node.children?.length"
            class="size-3.5 transition-transform"
            :class="expandedIds.has(row.node.id) ? 'rotate-90' : ''"
          />
        </span>
        <IconSitemap
          class="size-3.5 shrink-0"
          :class="row.node.sourceType === 'INBOUND' ? 'text-amber-500/80' : 'text-muted-foreground'"
        />
        <span class="min-w-0 flex-1 truncate" :title="row.node.path ?? row.node.name">
          {{ row.node.name }}
        </span>
        <span
          v-if="row.node.sourceType === 'INBOUND'"
          class="shrink-0 rounded bg-amber-500/10 px-1 text-[10px] text-amber-600"
          title="下级平台同步而来的镜像组织，本地只读"
        >
          入站
        </span>
        <span v-if="showCounts && (row.node.deviceCount || row.node.channelCount)" class="shrink-0 text-[10px] text-muted-foreground/70">
          {{ row.node.deviceCount ?? 0 }}/{{ row.node.channelCount ?? 0 }}
        </span>
      </button>

      <div v-if="loading && !rows.length" class="grid place-items-center py-10">
        <IconLoader2 class="size-5 animate-spin text-muted-foreground" />
      </div>
      <div v-else-if="!loading && !rows.length" class="px-3 py-10 text-center text-xs text-muted-foreground">
        还没有组织节点
      </div>
    </div>

    <slot name="footer" />
  </div>
</template>

<script setup lang="ts">
import { IconChevronRight, IconChevronsDown, IconChevronsUp, IconLoader2, IconSitemap, IconStack2 } from '@tabler/icons-vue'
import Button from '@/components/ui/button/Button.vue'
import RefreshButton from '@/components/RefreshButton.vue'
import type { FlatOrgRow } from '@/features/res/useResTree'

/**
 * 资源目录左树（纯展示）：展开状态与选中项都由页面持有的 useResTree 管，
 * 组件只负责渲染与派发事件，避免三处页面各写一套树。
 */
withDefaults(
  defineProps<{
    title: string
    rows: FlatOrgRow[]
    loading: boolean
    /** null = 全部组织 */
    selectedId: string | null
    expandedIds: Set<string>
    allExpanded: boolean
    /** 是否显示 设备数/通道数 */
    showCounts?: boolean
  }>(),
  { showCounts: true },
)

const emit = defineEmits<{
  (e: 'select', id: string | null): void
  (e: 'toggle', id: string): void
  (e: 'toggle-all'): void
  (e: 'refresh'): void
}>()

const onRefresh = () => emit('refresh')
</script>
