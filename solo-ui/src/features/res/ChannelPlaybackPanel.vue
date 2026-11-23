<template>
  <div class="space-y-3">
    <!-- 查询段 -->
    <div class="flex flex-wrap items-end gap-2">
      <div class="flex flex-col gap-1">
        <Label class="text-[11px] text-muted-foreground">起始时间</Label>
        <Input v-model="startTime" class="h-8 w-44 text-xs" placeholder="yyyy-MM-dd HH:mm:ss" />
      </div>
      <div class="flex flex-col gap-1">
        <Label class="text-[11px] text-muted-foreground">结束时间</Label>
        <Input v-model="endTime" class="h-8 w-44 text-xs" placeholder="yyyy-MM-dd HH:mm:ss" />
      </div>
      <Button variant="secondary" size="sm" class="gap-1.5" :disabled="querying" @click="query">
        <IconLoader2 v-if="querying" class="size-3.5 animate-spin" />
        <IconSearch v-else class="size-3.5" />
        查询录像
      </Button>
      <div class="flex-1" />
      <span class="text-[11px] text-muted-foreground/70">入站通道的录像由来源平台提供，本地不落存储</span>
    </div>

    <!-- 录像段列表 -->
    <div v-if="records.length" class="max-h-40 overflow-auto rounded-md border">
      <table class="w-full table-fixed border-collapse text-left text-[12px]">
        <thead class="sticky top-0 bg-muted/60 text-[11px] text-muted-foreground">
          <tr>
            <th class="w-40 px-2 py-1.5 font-normal">时间段</th>
            <th class="px-2 py-1.5 font-normal">名称</th>
            <th class="w-24 px-2 py-1.5 font-normal">类型</th>
            <th class="w-20 px-2 py-1.5 font-normal">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(r, idx) in records" :key="`${r.startTime}-${idx}`" class="border-t border-border/50">
            <td class="px-2 py-1 font-mono text-[11px]">{{ recClock(r.startTime) }} ~ {{ recClock(r.endTime) }}</td>
            <td class="truncate px-2 py-1 text-muted-foreground" :title="r.name">{{ r.name || '—' }}</td>
            <td class="px-2 py-1 text-[11px] text-muted-foreground">{{ r.type || '—' }}</td>
            <td class="px-2 py-1">
              <Button variant="ghost" size="xs" class="h-6 px-1.5 text-[11px]" :disabled="starting" @click="start(r)">
                <IconPlayerPlay class="size-3" />
                回放
              </Button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else-if="queried && !querying" class="rounded-md border border-dashed px-3 py-4 text-center text-xs text-muted-foreground">
      该时间段没有录像
    </div>

    <!-- 播放段 -->
    <div class="aspect-video overflow-hidden rounded-md bg-black">
      <GbPlayer v-if="playUrl" :src="playUrl" :video-buffer="0.2" />
      <div v-else class="grid h-full place-items-center text-xs text-white/60">
        <div class="flex flex-col items-center gap-2">
          <IconLoader2 v-if="starting" class="size-5 animate-spin" />
          <span>{{ starting ? '正在拉起回放流…' : playError || '选择上方录像段开始回放' }}</span>
        </div>
      </div>
    </div>

    <!-- 控制条 -->
    <div v-if="info" class="flex flex-wrap items-center gap-2 rounded-md border bg-muted/30 px-2.5 py-1.5">
      <Button variant="ghost" size="xs" class="h-7 gap-1 px-2" @click="togglePause">
        <IconPlayerPause v-if="!paused" class="size-3.5" />
        <IconPlayerPlay v-else class="size-3.5" />
        {{ paused ? '继续' : '暂停' }}
      </Button>
      <Select :model-value="String(speed)" @update:model-value="onSpeedChange">
        <SelectTrigger size="sm" class="h-7 w-20 text-xs">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem v-for="s in SPEEDS" :key="s" :value="String(s)">{{ s }}×</SelectItem>
        </SelectContent>
      </Select>
      <input
        v-model.number="sliderValue"
        type="range"
        min="0"
        :max="durationSec || 0"
        step="1"
        class="h-1.5 min-w-0 flex-1 accent-primary"
        :disabled="!durationSec"
        @change="onSeek"
      />
      <span class="w-28 shrink-0 text-right font-mono text-[11px] text-muted-foreground">
        {{ fmtDuration(sliderValue) }} / {{ fmtDuration(durationSec) }}
      </span>
      <Button variant="ghost" size="xs" class="h-7 px-2 text-[11px]" @click="stop(true)">停止</Button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  IconLoader2,
  IconPlayerPause,
  IconPlayerPlay,
  IconSearch,
} from '@tabler/icons-vue'
import Button from '@/components/ui/button/Button.vue'
import Input from '@/components/ui/input/Input.vue'
import Label from '@/components/ui/label/Label.vue'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import GbPlayer from '@/features/gb/GbPlayer.vue'
import type { AcceptableValue } from 'reka-ui'
import {
  controlChannelPlaybackApi,
  queryChannelPlaybackApi,
  startChannelPlaybackApi,
  stopChannelPlaybackApi,
} from '@/api/res'
import type { GbPlaybackResult, GbRecordItem } from '@/types/api'

/**
 * 统一通道回放面板（M6，3.11.4）。
 *
 * <p>两段式：先查录像段，再对选中的段发起回放；控制条支持暂停/继续、倍速与跳转。
 * 回放不做同源换源——换源意味着"另一个时间段的录像"，宁可直接失败。</p>
 */
const props = defineProps<{
  /** res_channel.id */
  channelRowId: string
}>()

const SPEEDS = [0.25, 0.5, 1, 2, 4] as const

const pad = (n: number) => String(n).padStart(2, '0')

const defaultRange = (): [string, string] => {
  const now = new Date()
  const day = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
  return [`${day} 00:00:00`, `${day} 23:59:59`]
}

const [defStart, defEnd] = defaultRange()
const startTime = ref(defStart)
const endTime = ref(defEnd)

const querying = ref(false)
const queried = ref(false)
const records = ref<GbRecordItem[]>([])

const starting = ref(false)
const playError = ref('')
const info = ref<GbPlaybackResult | null>(null)
const paused = ref(false)
const speed = ref<number>(1)
const sliderValue = ref(0)
const durationSec = ref(0)

const toWss = (url?: string) =>
  url && window.location.protocol === 'https:' ? url.replace(/^ws:\/\//, 'wss://') : url

const playUrl = computed(() => {
  const u = info.value
  if (!u) return ''
  return toWss(u.wsFlvUrl) || u.flvUrl || ''
})

/** ISO-8601 "2026-06-30T08:00:00" → "2026-06-30 08:00:00" */
const fmtIso = (iso?: string) => (iso ? iso.replace('T', ' ') : '')
const recClock = (iso?: string) => fmtIso(iso).slice(5, 19)

const fmtDuration = (sec: number) => {
  const s = Math.max(0, Math.floor(sec))
  return `${pad(Math.floor(s / 3600))}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}`
}

const query = async () => {
  querying.value = true
  try {
    const res = await queryChannelPlaybackApi(props.channelRowId, {
      startTime: startTime.value,
      endTime: endTime.value,
    })
    records.value = res.data ?? []
    queried.value = true
  } finally {
    querying.value = false
  }
}

const resetControl = () => {
  paused.value = false
  speed.value = 1
  sliderValue.value = 0
  durationSec.value = 0
}

const stop = async (manual = false) => {
  const streamId = info.value?.streamId
  info.value = null
  resetControl()
  if (manual) playError.value = ''
  if (!streamId) return
  try {
    await stopChannelPlaybackApi(streamId)
  } catch {
    // 停止是尽力而为：会话可能已被超时清扫
  }
}

const start = async (record: GbRecordItem) => {
  await stop()
  starting.value = true
  playError.value = ''
  try {
    const res = await startChannelPlaybackApi(props.channelRowId, {
      startTime: fmtIso(record.startTime),
      endTime: fmtIso(record.endTime),
    })
    info.value = res.data
    durationSec.value = res.data?.durationSec ?? 0
  } catch (e) {
    playError.value = e instanceof Error ? e.message : '回放失败，请检查设备与录像段'
  } finally {
    starting.value = false
  }
}

const control = async (action: 'pause' | 'resume' | 'seek' | 'speed', param?: string) => {
  const streamId = info.value?.streamId
  if (!streamId) return
  try {
    await controlChannelPlaybackApi({ streamId, action, param })
  } catch {
    // 控制失败不打断播放；错误已由请求层 toast
  }
}

const togglePause = async () => {
  if (paused.value) {
    await control('resume')
    paused.value = false
  } else {
    await control('pause')
    paused.value = true
  }
}

const onSpeedChange = async (value: AcceptableValue) => {
  const next = Number(value)
  speed.value = next
  await control('speed', String(next))
}

const onSeek = async () => {
  await control('seek', String(sliderValue.value))
}

watch(
  () => props.channelRowId,
  () => {
    records.value = []
    queried.value = false
    void stop()
  },
)

onBeforeUnmount(() => {
  void stop()
})
</script>
