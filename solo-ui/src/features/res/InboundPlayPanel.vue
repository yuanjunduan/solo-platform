<template>
  <div class="space-y-3">
    <div class="aspect-video overflow-hidden rounded-md bg-black">
      <GbPlayer v-if="playUrl" :src="playUrl" :video-buffer="0.2" />
      <div v-else class="grid h-full place-items-center text-xs text-white/60">
        <div class="flex flex-col items-center gap-2">
          <IconLoader2 v-if="loading" class="size-5 animate-spin" />
          <span>{{ loading ? '正在向对端平台发起点播…' : failed ? '点播失败，请检查对端平台与通道状态' : '未获取到可用的播放地址' }}</span>
        </div>
      </div>
    </div>

    <div v-if="urls.length" class="space-y-1.5">
      <div
        v-if="switched"
        class="rounded-md border border-amber-500/30 bg-amber-500/10 px-2.5 py-1.5 text-[11px] text-amber-600"
      >
        已自动切换到同源组内可用的来源取流（实际来源行 {{ info?.sourceChannelRowId }}）
      </div>
      <div
        v-for="item in urls"
        :key="item.label"
        class="flex items-center gap-2 rounded-md border bg-muted/40 px-2.5 py-1.5"
      >
        <span class="w-16 shrink-0 text-[11px] text-muted-foreground">{{ item.label }}</span>
        <code class="min-w-0 flex-1 truncate font-mono text-xs" :title="item.url">{{ item.url }}</code>
        <Button variant="ghost" size="xs" class="h-6 shrink-0 px-1.5" @click="copy(item.url)">
          <IconCopy class="size-3.5" />
        </Button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { IconCopy, IconLoader2 } from '@tabler/icons-vue'
import Button from '@/components/ui/button/Button.vue'
import GbPlayer from '@/features/gb/GbPlayer.vue'
import { startGbInboundPlayApi, stopGbPlayApi } from '@/api/gb28181'
import { toast } from '@/lib/toast'
import type { GbPlayResult } from '@/types/api'

/**
 * 入站通道播放面板（资源目录 → 通道）。
 *
 * 与本地通道点播的区别全在后端：入站通道没有 gb_device_channel 行，
 * 由 startInbound 按行 id 反查来源平台，用 platform.code 当 deviceId 发 INVITE。
 * 停止走同一个 stop 接口，streamId 与本地点播同一套规则。
 */
const props = defineProps<{
  /** res_channel.id */
  channelRowId: string
}>()

const loading = ref(false)
const failed = ref(false)
const info = ref<GbPlayResult | null>(null)

const toWss = (url?: string) =>
  url && window.location.protocol === 'https:' ? url.replace(/^ws:\/\//, 'wss://') : url

const urls = computed(() => {
  const u = info.value
  if (!u) return []
  return [
    { label: 'WS-FLV', url: toWss(u.wsFlvUrl) },
    { label: 'HTTP-FLV', url: u.flvUrl },
    { label: 'HLS', url: u.hlsUrl },
    { label: 'WebRTC', url: u.rtcUrl },
  ].filter((x): x is { label: string; url: string } => !!x.url)
})

const playUrl = computed(() => {
  const u = info.value
  if (!u) return ''
  return toWss(u.wsFlvUrl) || u.flvUrl || ''
})

/** 后端在同源组内换了源：实际取流行 ≠ 请求行，必须让人知道看到的是另一条链路 */
const switched = computed(() => {
  const actual = info.value?.sourceChannelRowId
  return !!actual && actual !== props.channelRowId
})

const stop = async () => {
  const streamId = info.value?.streamId
  info.value = null
  if (!streamId) return
  try {
    await stopGbPlayApi({ streamId })
  } catch {
    // 停止是尽力而为：会话可能已被服务端超时清扫
  }
}

const start = async () => {
  await stop()
  loading.value = true
  failed.value = false
  try {
    info.value = (await startGbInboundPlayApi({ channelRowId: props.channelRowId })).data
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

watch(() => props.channelRowId, start, { immediate: true })
onBeforeUnmount(stop)

const copy = async (text: string) => {
  try {
    await navigator.clipboard.writeText(text)
    toast.success('已复制到剪贴板')
  } catch {
    // 剪贴板权限被拒时忽略
  }
}
</script>
