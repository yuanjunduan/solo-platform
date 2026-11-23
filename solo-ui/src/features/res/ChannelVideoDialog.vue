<template>
  <Dialog :open="open" @update:open="(v) => emit('update:open', v)">
    <DialogContent class="sm:max-w-4xl">
      <DialogHeader>
        <DialogTitle>
          通道监控 · {{ channel?.localName || channel?.name || channel?.code }}
        </DialogTitle>
        <DialogDescription>
          {{ channel?.sourceType === 'INBOUND' ? '入站通道：指令由来源平台转发，录像不落本地存储。' : '本地通道：直接向设备发 INVITE。' }}
        </DialogDescription>
      </DialogHeader>

      <div class="flex items-center gap-1 rounded-lg bg-muted/60 p-1">
        <button
          v-for="tab in TABS"
          :key="tab.value"
          class="rounded-md px-3 py-1 text-[12px] transition"
          :class="active === tab.value ? 'bg-card text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'"
          @click="active = tab.value"
        >
          {{ tab.label }}
        </button>
      </div>

      <div v-if="channel">
        <ChannelLivePanel v-if="active === 'live'" :channel-row-id="channel.id" />
        <ChannelPlaybackPanel v-else-if="active === 'playback'" :channel-row-id="channel.id" />
        <ChannelPtzPanel v-else :channel-row-id="channel.id" />
      </div>
    </DialogContent>
  </Dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import ChannelLivePanel from './ChannelLivePanel.vue'
import ChannelPlaybackPanel from './ChannelPlaybackPanel.vue'
import ChannelPtzPanel from './ChannelPtzPanel.vue'
import type { ResChannel } from '@/types/api'

/**
 * 统一通道监控弹窗：实时 / 回放 / 云台 三段。
 *
 * <p>各段面板在切换时整体卸载，卸载即停流——不会出现"切到云台后视频还在后台拉流"。</p>
 */
type TabValue = 'live' | 'playback' | 'ptz'

const props = withDefaults(
  defineProps<{
    open: boolean
    channel?: ResChannel | null
    /** 打开时默认显示哪一段 */
    initialTab?: TabValue
  }>(),
  { channel: null, initialTab: 'live' },
)

const emit = defineEmits<{
  'update:open': [value: boolean]
}>()

const TABS: Array<{ label: string; value: TabValue }> = [
  { label: '实时', value: 'live' },
  { label: '回放', value: 'playback' },
  { label: '云台', value: 'ptz' },
]

const active = ref<TabValue>(props.initialTab)

watch(
  () => [props.open, props.initialTab],
  () => {
    if (props.open) active.value = props.initialTab
  },
)
</script>
