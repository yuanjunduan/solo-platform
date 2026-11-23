<template>
  <PopoverRoot v-model:open="open">
    <PopoverTrigger as-child>
      <button
        type="button"
        class="flex h-7 w-[300px] items-center gap-1.5 rounded-md px-2 text-left text-[13px] outline-none hover:bg-muted/70 focus:bg-muted/70"
        :title="selected ? `${displayName(selected)} ${selected.deviceId}` : '请选择目录来源'"
      >
        <template v-if="selected">
          <span class="size-1.5 shrink-0 rounded-full" :class="statusDotClass(selected.onlineStatus)" />
          <span class="min-w-0 flex-1 truncate">{{ displayName(selected) }}</span>
          <span
            class="shrink-0 rounded-sm px-1 py-0.5 text-[10px]"
            :class="domainClass(selected.domain)"
            >{{ domainText(selected.domain) }}</span
          >
          <span class="shrink-0 font-mono text-[11px] text-muted-foreground">{{ selected.deviceId }}</span>
          <span
            class="shrink-0 rounded-sm p-0.5 text-muted-foreground hover:bg-muted-foreground/15 hover:text-foreground"
            @click.stop="onClear"
          >
            <IconX class="size-3" />
          </span>
        </template>
        <span v-else class="flex-1 truncate text-muted-foreground">请选择目录来源</span>
      </button>
    </PopoverTrigger>

    <PopoverPortal>
      <PopoverContent
        align="start"
        :side-offset="4"
        class="z-50 w-[400px] rounded-md border border-border bg-popover p-0 text-popover-foreground shadow-md"
      >
        <div class="flex items-center gap-2 border-b border-border px-2.5">
          <IconSearch class="size-3.5 shrink-0 text-muted-foreground" />
          <input
            ref="inputRef"
            v-model="keyword"
            class="h-8 flex-1 bg-transparent text-[13px] outline-none placeholder:text-muted-foreground"
            placeholder="搜索名称 / 国标编码"
          />
          <IconLoader2 v-if="loading" class="size-3.5 animate-spin text-muted-foreground" />
        </div>

        <div class="max-h-72 overflow-auto p-1">
          <button
            v-for="s in filtered"
            :key="s.deviceId"
            type="button"
            class="flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left text-[13px] hover:bg-accent"
            :class="modelValue === s.deviceId ? 'bg-accent' : ''"
            :title="`${kindText(s.kind)} · ${syncText(s)}`"
            @click="onSelect(s.deviceId)"
          >
            <span class="size-1.5 shrink-0 rounded-full" :class="statusDotClass(s.onlineStatus)" />
            <span class="min-w-0 flex-1 truncate">{{ displayName(s) }}</span>
            <span
              class="shrink-0 rounded-sm px-1 py-0.5 text-[10px]"
              :class="domainClass(s.domain)"
            >
              {{ domainText(s.domain) }}
            </span>
            <span class="shrink-0 font-mono text-[11px] text-muted-foreground">{{ s.deviceId }}</span>
            <span
              class="size-1.5 shrink-0 rounded-full"
              :class="syncDotClass(s.sync?.status)"
              :title="syncText(s)"
            />
            <span class="w-14 shrink-0 text-right text-[11px] text-muted-foreground">
              {{ s.channelCount }} 路
            </span>
          </button>

          <div
            v-if="!loading && !filtered.length"
            class="px-2 py-6 text-center text-xs text-muted-foreground"
          >
            {{ sources.length ? '无匹配来源' : '还没有同步过目录' }}
          </div>
        </div>
      </PopoverContent>
    </PopoverPortal>
  </PopoverRoot>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { PopoverRoot, PopoverTrigger, PopoverPortal, PopoverContent } from 'reka-ui'
import { IconX, IconSearch } from '@tabler/icons-vue'
import { Loader2 as IconLoader2 } from 'lucide-vue-next'
import { getGbCatalogSourceListApi } from '@/api/gb28181'
import type { GbCatalogSource } from '@/types/api'

const props = defineProps<{
  /** 目录来源端点编码（= gb_device_channel.device_id）；空 = 未选择 */
  modelValue?: string
}>()
const emit = defineEmits<{
  'update:modelValue': [value: string | undefined]
}>()

const open = ref(false)
const keyword = ref('')
const loading = ref(false)
const sources = ref<GbCatalogSource[]>([])
const loaded = ref(false)
const inputRef = ref<HTMLInputElement | null>(null)

const displayName = (s: GbCatalogSource) => s.name || s.deviceId
/** 类型只来自国标编码判定（200=平台），不看任何登记表 */
const kindText = (kind: GbCatalogSource['kind']) => (kind === 'PLATFORM' ? '平台' : '设备')

/** 资源域徽标：本地可维护/可推送，下级是同步来的镜像 */
const domainText = (domain: GbCatalogSource['domain']) =>
  domain === 'LOCAL' ? '本地' : domain === 'SUBORDINATE' ? '下级' : '远程'
const domainClass = (domain: GbCatalogSource['domain']) =>
  domain === 'LOCAL'
    ? 'bg-emerald-500/10 text-emerald-600'
    : domain === 'SUBORDINATE'
      ? 'bg-sky-500/10 text-sky-600'
      : 'bg-violet-500/10 text-violet-600'

/** 目录同步状态点：绿=上次成功，蓝闪=进行中，红=超时/异常，灰=从未同步 */
const syncDotClass = (status?: string) => {
  if (status === 'RUNNING') return 'animate-pulse bg-sky-500'
  if (status === 'SUCCESS') return 'bg-emerald-500'
  if (status === 'FAILED') return 'bg-red-500'
  return 'bg-muted-foreground/30'
}
const syncText = (s: GbCatalogSource) => {
  const sync = s.sync
  if (!sync || sync.status === 'IDLE') return '从未同步'
  if (sync.status === 'RUNNING') return `同步中 ${sync.received}/${sync.total}（${sync.percent}%）`
  if (sync.status === 'FAILED') return `同步失败：${sync.errorMsg || '超时'}`
  return `已同步 ${sync.received}/${sync.total} · ${sync.finishedAt || ''}`
}

/** 端点在线状态：1 在线 / 0 离线 / 2 未注册；无 gb_device 行时为空 */
const statusDotClass = (status?: number | null) => {
  if (status === 1) return 'bg-emerald-500'
  if (status === 0) return 'bg-red-500'
  return 'bg-muted-foreground/40'
}

const selected = computed(
  () => sources.value.find((s) => s.deviceId === props.modelValue) ?? null,
)

/** 来源数量受端点规模限制（每台设备/每个下级平台一个），一次拉全量后本地过滤 */
const load = async () => {
  if (loading.value) return
  loading.value = true
  try {
    sources.value = (await getGbCatalogSourceListApi()).data ?? []
    loaded.value = true
  } finally {
    loading.value = false
  }
}

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return sources.value
  return sources.value.filter(
    (s) => s.deviceId.toLowerCase().includes(kw) || (s.name ?? '').toLowerCase().includes(kw),
  )
})

watch(open, async (v) => {
  if (!v) return
  keyword.value = ''
  // 每次打开都重拉：目录同步后通道数会变，缓存 200 条没必要，接口本身是一条聚合 SQL
  await load()
  await nextTick()
  inputRef.value?.focus()
})

// 预加载一次：宿主页可能自动选中来源，不预加载会让触发按钮一直显示占位文案
onMounted(() => {
  if (!loaded.value) void load()
})

const onSelect = (deviceId: string) => {
  emit('update:modelValue', deviceId)
  open.value = false
}

const onClear = () => emit('update:modelValue', undefined)

defineExpose({ load, sources })
</script>
