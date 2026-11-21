<template>
  <PopoverRoot v-model:open="open">
    <PopoverTrigger as-child>
      <button
        type="button"
        class="flex h-7 w-[268px] items-center gap-1.5 rounded-md px-2 text-left text-[13px] outline-none hover:bg-muted/70 focus:bg-muted/70"
        :title="selected ? `${displayName(selected)} ${selected.platformId}` : '请选择下级平台'"
      >
        <template v-if="selected">
          <span class="min-w-0 flex-1 truncate">{{ displayName(selected) }}</span>
          <span class="shrink-0 font-mono text-[11px] text-muted-foreground">{{ selected.platformId }}</span>
          <span
            class="shrink-0 rounded-sm p-0.5 text-muted-foreground hover:bg-muted-foreground/15 hover:text-foreground"
            @click.stop="onClear"
          >
            <IconX class="size-3" />
          </span>
        </template>
        <span v-else class="flex-1 truncate text-muted-foreground">请选择下级平台</span>
      </button>
    </PopoverTrigger>

    <PopoverPortal>
      <PopoverContent
        align="start"
        :side-offset="4"
        class="z-50 w-[360px] rounded-md border border-border bg-popover p-0 text-popover-foreground shadow-md"
      >
        <div class="flex items-center gap-2 border-b border-border px-2.5">
          <IconSearch class="size-3.5 shrink-0 text-muted-foreground" />
          <input
            ref="inputRef"
            v-model="keyword"
            class="h-8 flex-1 bg-transparent text-[13px] outline-none placeholder:text-muted-foreground"
            placeholder="搜索备注名 / 国标编码"
          />
          <IconLoader2 v-if="loading" class="size-3.5 animate-spin text-muted-foreground" />
        </div>

        <div class="max-h-72 overflow-auto p-1">
          <button
            v-for="p in filtered"
            :key="p.id"
            type="button"
            class="flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left text-[13px] hover:bg-accent"
            :class="modelValue === p.platformId ? 'bg-accent' : ''"
            @click="onSelect(p.platformId)"
          >
            <span class="size-1.5 shrink-0 rounded-full" :class="statusDotClass(p.status)" />
            <span class="min-w-0 flex-1 truncate">{{ displayName(p) }}</span>
            <span class="shrink-0 font-mono text-[11px] text-muted-foreground">{{ p.platformId }}</span>
          </button>

          <div
            v-if="!loading && !filtered.length"
            class="px-2 py-6 text-center text-xs text-muted-foreground"
          >
            {{ platforms.length ? '无匹配平台' : '还没有登记下级平台' }}
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
import { getGbPlatformPageApi } from '@/api/gb28181'
import type { GbPlatform } from '@/types/api'

const props = defineProps<{
  /** 下级平台国标编码；空 = 未选择 */
  modelValue?: string
}>()
const emit = defineEmits<{
  'update:modelValue': [value: string | undefined]
}>()

const open = ref(false)
const keyword = ref('')
const loading = ref(false)
const platforms = ref<GbPlatform[]>([])
const loaded = ref(false)
const inputRef = ref<HTMLInputElement | null>(null)

const displayName = (p: GbPlatform) => p.name || p.platformId

/** 关联设备状态：1 在线 / 0 离线 / 2 未注册 */
const statusDotClass = (status?: number) => {
  if (status === 1) return 'bg-emerald-500'
  if (status === 0) return 'bg-red-500'
  return 'bg-muted-foreground/40'
}

const selected = computed(
  () => platforms.value.find((p) => p.platformId === props.modelValue) ?? null,
)

/** 平台数量通常很少（个位数），一次拉全量后本地过滤，避免每敲一个字打一次接口 */
const load = async () => {
  if (loading.value) return
  loading.value = true
  try {
    const res = (await getGbPlatformPageApi({ page: 1, pageSize: 200 })).data
    platforms.value = res.rows ?? []
    loaded.value = true
  } finally {
    loading.value = false
  }
}

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return platforms.value
  return platforms.value.filter(
    (p) => p.platformId.toLowerCase().includes(kw) || (p.name ?? '').toLowerCase().includes(kw),
  )
})

watch(open, async (v) => {
  if (!v) return
  keyword.value = ''
  if (!loaded.value) await load()
  await nextTick()
  inputRef.value?.focus()
})

// 预加载一次：宿主页可能自动选中平台（如通道页进入即选在线平台），
// 不预加载会导致触发按钮一直显示「请选择下级平台」，看不出当前选的是谁
onMounted(() => {
  if (!loaded.value) void load()
})

const onSelect = (platformId: string) => {
  emit('update:modelValue', platformId)
  open.value = false
}

const onClear = () => emit('update:modelValue', undefined)

defineExpose({ load, platforms })
</script>
