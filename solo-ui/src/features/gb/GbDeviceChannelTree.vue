<script setup lang="ts">
import { ChevronRight, Folder, Video, VideoOff } from 'lucide-vue-next'
import type { GbDeviceChannelTreeNode } from '@/types/api'

defineOptions({ name: 'GbDeviceChannelTree' })

const props = withDefaults(
  defineProps<{
    /** 本层节点（懒加载：children 只会挂已展开过的层） */
    nodes: GbDeviceChannelTreeNode[]
    /** 选中节点国标码 */
    selectedCode?: string
    /** 已展开节点国标码集合 */
    expanded: Set<string>
    /** 正在加载下级的节点国标码集合 */
    loading?: Set<string>
    depth?: number
  }>(),
  { depth: 0, loading: () => new Set<string>() },
)

const emit = defineEmits<{
  select: [node: GbDeviceChannelTreeNode]
  toggle: [code: string]
}>()

const isFolder = (node: GbDeviceChannelTreeNode) => node.parental === 1
const hasChildren = (node: GbDeviceChannelTreeNode) => !!node.hasChildren
const isOpen = (node: GbDeviceChannelTreeNode) => props.expanded.has(node.channelId)
const isLoading = (node: GbDeviceChannelTreeNode) => props.loading.has(node.channelId)
const displayName = (node: GbDeviceChannelTreeNode) =>
  node.name || node.reportName || node.channelId

/** 分组显示「整棵子树通道数」，懒加载下不用再递归本地节点 */
const summary = (node: GbDeviceChannelTreeNode) =>
  `${node.subtreeChannelCount == null ? 0 : node.subtreeChannelCount} 路`

const statusText = (node: GbDeviceChannelTreeNode) =>
  node.status === 'ON' ? '在线' : node.status === 'OFF' ? '离线' : '未注册'
const statusClass = (node: GbDeviceChannelTreeNode) =>
  node.status === 'ON' ? 'text-emerald-500' : node.status === 'OFF' ? 'text-red-500' : 'text-muted-foreground/70'
</script>

<template>
  <div>
    <div v-for="node in nodes" :key="node.id">
      <div
        class="flex h-7 cursor-pointer items-center gap-1 rounded pr-2 text-xs"
        :class="selectedCode === node.channelId ? 'bg-primary/10 text-foreground' : 'hover:bg-muted/50'"
        :style="{ paddingLeft: `${depth * 14 + 6}px` }"
        @click="emit('select', node)"
      >
        <button
          type="button"
          class="grid size-4 shrink-0 place-items-center text-muted-foreground outline-none"
          @click.stop="hasChildren(node) && emit('toggle', node.channelId)"
        >
          <ChevronRight
            v-if="hasChildren(node)"
            class="size-3 transition-transform"
            :class="isOpen(node) ? 'rotate-90' : ''"
          />
        </button>
        <Folder v-if="isFolder(node)" class="size-3.5 shrink-0 text-amber-400/80" />
        <Video v-else-if="node.status === 'ON'" class="size-3.5 shrink-0 text-emerald-500/80" />
        <VideoOff v-else class="size-3.5 shrink-0 text-muted-foreground/60" />
        <span class="min-w-0 truncate" :title="displayName(node)">{{ displayName(node) }}</span>
        <span class="shrink-0 font-mono text-[10px] text-muted-foreground/70">{{
          node.channelId
        }}</span>
        <span
          class="ml-auto shrink-0 text-[10px]"
          :class="isFolder(node) ? 'text-muted-foreground/70' : statusClass(node)"
        >
          {{ isFolder(node) ? summary(node) : statusText(node) }}
        </span>
      </div>
      <template v-if="isFolder(node) && isOpen(node)">
        <div
          v-if="isLoading(node) && !node.children?.length"
          class="h-7 text-[11px] text-muted-foreground/70"
          :style="{ paddingLeft: `${(depth + 1) * 14 + 6}px`, lineHeight: '28px' }"
        >
          加载中…
        </div>
        <GbDeviceChannelTree
          v-else-if="node.children?.length"
          :nodes="node.children"
          :selected-code="selectedCode"
          :expanded="expanded"
          :loading="loading"
          :depth="depth + 1"
          @select="emit('select', $event)"
          @toggle="emit('toggle', $event)"
        />
      </template>
    </div>
  </div>
</template>
