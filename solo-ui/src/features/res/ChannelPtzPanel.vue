<template>
  <div class="space-y-3">
    <div class="flex flex-wrap gap-3">
      <!-- 方向键 -->
      <div class="grid grid-cols-3 gap-1">
        <span />
        <Button variant="outline" size="xs" class="h-8 w-10" title="上" @mousedown.prevent="hold('up')" @mouseup="release" @mouseleave="release">
          <IconChevronUp class="size-4" />
        </Button>
        <span />
        <Button variant="outline" size="xs" class="h-8 w-10" title="左" @mousedown.prevent="hold('left')" @mouseup="release" @mouseleave="release">
          <IconChevronLeft class="size-4" />
        </Button>
        <Button variant="outline" size="xs" class="h-8 w-10 text-[11px]" title="停止" @click="stop">停</Button>
        <Button variant="outline" size="xs" class="h-8 w-10" title="右" @mousedown.prevent="hold('right')" @mouseup="release" @mouseleave="release">
          <IconChevronRight class="size-4" />
        </Button>
        <span />
        <Button variant="outline" size="xs" class="h-8 w-10" title="下" @mousedown.prevent="hold('down')" @mouseup="release" @mouseleave="release">
          <IconChevronDown class="size-4" />
        </Button>
        <span />
        <span />
        <span />
        <Button variant="outline" size="xs" class="h-8 w-10 text-[11px]" title="拉近（变倍）" @mousedown.prevent="hold('zoomin')" @mouseup="release" @mouseleave="release">＋</Button>
        <Button variant="outline" size="xs" class="h-8 w-10 text-[11px]" title="拉远（变倍）" @mousedown.prevent="hold('zoomout')" @mouseup="release" @mouseleave="release">－</Button>
      </div>

      <!-- 镜头 -->
      <div class="grid grid-cols-2 gap-1">
        <Button variant="outline" size="xs" class="h-8 text-[11px]" @click="focus('near')">聚焦近</Button>
        <Button variant="outline" size="xs" class="h-8 text-[11px]" @click="focus('far')">聚焦远</Button>
        <Button variant="outline" size="xs" class="h-8 text-[11px]" @click="iris('open')">光圈开</Button>
        <Button variant="outline" size="xs" class="h-8 text-[11px]" @click="iris('close')">光圈关</Button>
        <Button variant="outline" size="xs" class="h-8 text-[11px]" @click="wiper('on')">雨刷开</Button>
        <Button variant="outline" size="xs" class="h-8 text-[11px]" @click="wiper('off')">雨刷关</Button>
        <Button variant="outline" size="xs" class="col-span-2 h-8 text-[11px]" @click="focusStop">镜头停</Button>
      </div>

      <!-- 看守位 -->
      <div class="flex min-w-48 flex-1 flex-col gap-1">
        <div class="flex items-center gap-2">
          <Label class="w-16 shrink-0 text-[11px] text-muted-foreground">看守位</Label>
          <Input v-model.number="homeResetTime" class="h-7 w-20 text-xs" placeholder="秒" />
          <Button variant="outline" size="xs" class="h-7 text-[11px]" @click="toggleHome">{{ homeEnabled ? '关闭' : '开启' }}</Button>
        </div>
        <div class="text-[11px] leading-relaxed text-muted-foreground/70">
          开启后设备在指定秒数无操作时自动回到预置位 {{ homePresetIndex || '（未设）' }}；入站通道由来源平台转发指令。
        </div>
      </div>
    </div>

    <!-- 预置位 -->
    <div class="rounded-md border">
      <div class="flex items-center gap-2 border-b px-2.5 py-1.5">
        <span class="text-[12px] font-medium">预置位</span>
        <span class="text-[11px] text-muted-foreground">{{ presets.length }} 个</span>
        <div class="flex-1" />
        <Input v-model.number="newIndex" class="h-7 w-16 text-xs" placeholder="编号" />
        <Input v-model="newName" class="h-7 w-32 text-xs" placeholder="名称（可空）" />
        <Button variant="secondary" size="xs" class="h-7 gap-1 text-[11px]" :disabled="saving" @click="savePreset">
          <IconLoader2 v-if="saving" class="size-3 animate-spin" />
          设置当前视角
        </Button>
      </div>
      <div v-if="presets.length" class="max-h-40 overflow-auto">
        <div
          v-for="p in presets"
          :key="p.id"
          class="flex items-center gap-2 border-b border-border/40 px-2.5 py-1 text-[12px] last:border-0 hover:bg-muted/30"
        >
          <span class="w-8 shrink-0 font-mono text-[11px] text-muted-foreground">{{ p.presetIndex }}</span>
          <span class="min-w-0 flex-1 truncate">{{ p.name }}</span>
          <Button variant="ghost" size="xs" class="h-6 px-1.5 text-[11px]" @click="callPreset(p)">调用</Button>
          <Button variant="ghost" size="xs" class="h-6 px-1.5 text-[11px] text-red-500" @click="removePreset(p)">删除</Button>
        </div>
      </div>
      <div v-else class="px-2.5 py-3 text-center text-[11px] text-muted-foreground">
        还没有预置位：把云台转到目标视角，填编号后点「设置当前视角」
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import {
  IconChevronDown,
  IconChevronLeft,
  IconChevronRight,
  IconChevronUp,
  IconLoader2,
} from '@tabler/icons-vue'
import Button from '@/components/ui/button/Button.vue'
import Input from '@/components/ui/input/Input.vue'
import Label from '@/components/ui/label/Label.vue'
import { confirm } from '@/lib/confirm'
import { toast } from '@/lib/toast'
import {
  callChannelPresetApi,
  channelPtzFocusApi,
  channelPtzHomePositionApi,
  channelPtzIrisApi,
  channelPtzMoveApi,
  channelPtzWiperApi,
  deleteChannelPresetApi,
  getChannelPresetsApi,
  saveChannelPresetApi,
} from '@/api/res'
import type { ResChannelPreset } from '@/types/api'

/**
 * 统一通道云台面板（M6）。
 *
 * <p>按住方向键发指令、松开立刻发 stop——云台是"持续运动"语义，只发一次会被设备当成
 * 一次步进或一直转到限位。预置位先让设备存当前视角，成功后再写本地名称。</p>
 */
const props = defineProps<{
  /** res_channel.id */
  channelRowId: string
}>()

const presets = ref<ResChannelPreset[]>([])
const newIndex = ref(1)
const newName = ref('')
const saving = ref(false)

const homeEnabled = ref(false)
const homeResetTime = ref(30)
const homePresetIndex = ref<number | null>(null)

const loadPresets = async () => {
  try {
    presets.value = (await getChannelPresetsApi(props.channelRowId)).data ?? []
    // 当前编号已被占用（首次进来自带 1、或别的端刚设过）时，换成最小空闲编号
    const used = new Set(presets.value.map((p) => p.presetIndex))
    if (used.has(newIndex.value)) {
      let idx = 1
      while (used.has(idx) && idx < 255) idx++
      newIndex.value = idx
    }
    homePresetIndex.value = presets.value[0]?.presetIndex ?? null
  } catch {
    // 预置位列表读失败不阻断云台控制；错误已由请求层提示
  }
}

const hold = async (command: string) => {
  try {
    await channelPtzMoveApi(props.channelRowId, { command })
  } catch {
    // 已由请求层提示
  }
}

const release = async () => {
  await hold('stop')
}

const stop = async () => {
  await hold('stop')
}

const focus = async (direction: 'near' | 'far') => {
  try {
    await channelPtzFocusApi(props.channelRowId, direction)
    // 聚焦与光圈同样是持续动作，发一次后自动停，避免镜头一直跑
    window.setTimeout(() => void channelPtzFocusApi(props.channelRowId, 'stop').catch(() => {}), 500)
  } catch {
    // 已由请求层提示
  }
}

const focusStop = async () => {
  try {
    await channelPtzFocusApi(props.channelRowId, 'stop')
  } catch {
    // 忽略
  }
}

const iris = async (direction: 'open' | 'close') => {
  try {
    await channelPtzIrisApi(props.channelRowId, direction)
    window.setTimeout(() => void channelPtzIrisApi(props.channelRowId, 'stop').catch(() => {}), 500)
  } catch {
    // 已由请求层提示
  }
}

const wiper = async (action: 'on' | 'off') => {
  try {
    await channelPtzWiperApi(props.channelRowId, action)
  } catch {
    // 已由请求层提示
  }
}

const toggleHome = async () => {
  const enabled = !homeEnabled.value
  try {
    await channelPtzHomePositionApi(props.channelRowId, {
      enabled,
      resetTime: enabled ? homeResetTime.value : undefined,
      presetIndex: enabled ? homePresetIndex.value ?? undefined : undefined,
    })
    homeEnabled.value = enabled
    toast.success(enabled ? '看守位已开启' : '看守位已关闭')
  } catch {
    // 已由请求层提示
  }
}

const savePreset = async () => {
  const idx = Number(newIndex.value)
  if (!Number.isInteger(idx) || idx < 1 || idx > 255) {
    toast.error('预置位编号需在 1-255 之间')
    return
  }
  saving.value = true
  try {
    await saveChannelPresetApi(props.channelRowId, {
      presetIndex: idx,
      name: newName.value.trim() || undefined,
    })
    toast.success('预置位已保存')
    newName.value = ''
    await loadPresets()
  } finally {
    saving.value = false
  }
}

const callPreset = async (preset: ResChannelPreset) => {
  try {
    await callChannelPresetApi(props.channelRowId, preset.id)
  } catch {
    // 已由请求层提示
  }
}

const removePreset = async (preset: ResChannelPreset) => {
  const ok = await confirm({
    title: '删除预置位',
    description: `确定删除「${preset.name}」吗？会同时通知设备删除该槽位。`,
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await deleteChannelPresetApi(props.channelRowId, preset.id)
    toast.success('预置位已删除')
    await loadPresets()
  } catch {
    // 已由请求层提示
  }
}

watch(
  () => props.channelRowId,
  () => {
    newIndex.value = 1
    void loadPresets()
  },
)

onMounted(loadPresets)
</script>
