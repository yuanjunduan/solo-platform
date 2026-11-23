<template>
  <div class="flex h-full min-w-0 flex-col gap-3 overflow-hidden p-3">
    <div class="flex min-w-0 flex-1 flex-col overflow-hidden rounded-xl border border-border bg-card">
      <div class="flex h-12 shrink-0 items-center gap-2 border-b border-border px-3">
        <span class="text-[13px] font-medium">播放记录</span>
        <span class="text-xs text-muted-foreground">实时点播与回放，一次请求一行；失败也留痕</span>
        <div class="flex-1" />
        <RefreshButton :loading="loading" :on-refresh="() => load(page)" />
        <Button
          v-access="PERM.RES_PLAY_RECORD_EXPORT"
          variant="ghost"
          size="sm"
          class="gap-1.5"
          :disabled="exporting"
          @click="onExport"
        >
          <IconLoader2 v-if="exporting" class="size-3.5 animate-spin" />
          <IconDownload v-else class="size-3.5" />
          导出
        </Button>
      </div>

      <!-- 筛选 -->
      <div class="flex shrink-0 flex-wrap items-center gap-2 border-b border-border px-3 py-2">
        <Input
          v-model="query.channelCode"
          class="h-8 w-40 text-xs"
          placeholder="通道编码"
          @keyup.enter="load(1)"
        />
        <Input
          v-model="query.actorId"
          class="h-8 w-40 text-xs"
          placeholder="操作者 ID"
          @keyup.enter="load(1)"
        />
        <Select v-model="query.action">
          <SelectTrigger class="h-8 w-28 text-xs"><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem value="__all__">全部动作</SelectItem>
            <SelectItem value="PLAY">实时点播</SelectItem>
            <SelectItem value="PLAYBACK">录像回放</SelectItem>
          </SelectContent>
        </Select>
        <Select v-model="query.result">
          <SelectTrigger class="h-8 w-28 text-xs"><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem value="__all__">全部结果</SelectItem>
            <SelectItem value="SUCCESS">成功</SelectItem>
            <SelectItem value="FAILED">失败</SelectItem>
          </SelectContent>
        </Select>
        <span class="text-[11px] text-muted-foreground">开始</span>
        <input
          v-model="query.begin"
          type="datetime-local"
          class="h-8 rounded-md border border-input bg-transparent px-2 text-xs outline-none focus:border-ring"
        />
        <span class="text-[11px] text-muted-foreground">结束</span>
        <input
          v-model="query.end"
          type="datetime-local"
          class="h-8 rounded-md border border-input bg-transparent px-2 text-xs outline-none focus:border-ring"
        />
        <Button size="sm" class="h-8" :loading="loading" @click="load(1)">查询</Button>
        <Button v-if="hasCondition" variant="ghost" size="sm" class="h-8 text-xs text-muted-foreground" @click="onClear">
          <IconX class="size-3.5" />
          清除
        </Button>
      </div>

      <div class="min-h-0 flex-1 overflow-auto">
        <table class="w-full table-fixed border-collapse text-left text-[13px]">
          <thead class="sticky top-0 z-10">
            <tr class="bg-card text-xs text-muted-foreground">
              <th class="w-40 px-3 py-2 font-normal">开始时间</th>
              <th class="w-20 px-3 py-2 font-normal">动作</th>
              <th class="w-20 px-3 py-2 font-normal">结果</th>
              <th class="w-48 px-3 py-2 font-normal">通道</th>
              <th class="w-20 px-3 py-2 font-normal">来源</th>
              <th class="w-32 px-3 py-2 font-normal">操作者</th>
              <th class="w-28 px-3 py-2 font-normal">客户端 IP</th>
              <th class="w-20 px-3 py-2 font-normal">时长</th>
              <th class="w-56 px-3 py-2 font-normal">失败原因</th>
            </tr>
          </thead>
          <tbody v-if="rows.length">
            <tr v-for="r in rows" :key="r.id" class="border-b border-border/50 hover:bg-muted/20">
              <td class="px-3 py-1.5 font-mono text-xs text-muted-foreground">{{ r.beginTime || '—' }}</td>
              <td class="px-3 py-1.5">
                <span
                  class="rounded px-1.5 py-0.5 text-[10px]"
                  :class="r.action === 'PLAYBACK' ? 'bg-sky-500/10 text-sky-600' : 'bg-emerald-500/10 text-emerald-600'"
                >
                  {{ r.action === 'PLAYBACK' ? '回放' : '实时' }}
                </span>
              </td>
              <td class="px-3 py-1.5">
                <span class="text-xs" :class="r.result === 'SUCCESS' ? 'text-emerald-600' : 'text-red-500'">
                  {{ r.result === 'SUCCESS' ? '成功' : '失败' }}
                </span>
              </td>
              <td class="truncate px-3 py-1.5" :title="r.channelCode ?? ''">
                {{ r.channelName || r.channelCode || '—' }}
                <span v-if="r.channelCode" class="ml-1 font-mono text-[10px] text-muted-foreground/70">{{ r.channelCode }}</span>
              </td>
              <td class="px-3 py-1.5 text-xs text-muted-foreground">
                {{ r.sourceType === 'INBOUND' ? '入站' : '本地' }}
              </td>
              <td class="truncate px-3 py-1.5 text-xs" :title="r.actorId ?? ''">{{ r.actorName || r.actorId || '—' }}</td>
              <td class="px-3 py-1.5 font-mono text-xs text-muted-foreground">{{ r.clientIp || '—' }}</td>
              <td class="px-3 py-1.5 text-xs text-muted-foreground">{{ durationText(r.durationSec) }}</td>
              <td class="truncate px-3 py-1.5 text-xs text-red-500/80" :title="r.failReason ?? ''">
                {{ r.failReason || '—' }}
              </td>
            </tr>
          </tbody>
        </table>

        <div v-if="loading && !rows.length" class="grid place-items-center py-16">
          <IconLoader2 class="size-5 animate-spin text-muted-foreground" />
        </div>
        <div v-else-if="!loading && !rows.length" class="grid place-items-center gap-2 py-16 text-muted-foreground">
          <IconHistory class="size-8" />
          <div class="text-[13px]">该条件下没有播放记录</div>
          <div class="text-[11px] text-muted-foreground/70">点播放/回放后即时落一行；成功与失败都会留痕</div>
        </div>
      </div>

      <div class="flex h-9 shrink-0 items-center gap-3 border-t border-border px-3 text-[11px] text-muted-foreground/70">
        <span>共 {{ total }} 行</span>
        <span>第 {{ page }} / {{ totalPages }} 页</span>
        <div class="flex-1" />
        <Button variant="ghost" size="sm" class="h-7 text-xs" :disabled="page <= 1 || loading" @click="load(page - 1)">
          上一页
        </Button>
        <Button
          variant="ghost"
          size="sm"
          class="h-7 text-xs"
          :disabled="page >= totalPages || loading"
          @click="load(page + 1)"
        >
          下一页
        </Button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { IconDownload, IconHistory, IconLoader2, IconX } from '@tabler/icons-vue'
import Button from '@/components/ui/button/Button.vue'
import Input from '@/components/ui/input/Input.vue'
import RefreshButton from '@/components/RefreshButton.vue'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { PERM } from '@/features/access/permissions'
import { toast } from '@/lib/toast'
import { exportResPlayRecordApi, getResPlayRecordPageApi } from '@/api/res'
import type { PlayRecordQueryRequest, ResPlayRecord } from '@/types/api'

/**
 * 播放记录（3.11.6）：谁、哪条通道、什么时候、成功还是失败、看了多久。
 *
 * <p>列表与导出共用同一套筛选条件；时间控件是 datetime-local，提交前补成后端要的
 * "yyyy-MM-dd HH:mm:ss"（缺秒补 :00）。</p>
 */
const PAGE_SIZE = 50

const rows = ref<ResPlayRecord[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const exporting = ref(false)

const query = reactive({
  channelCode: '',
  actorId: '',
  action: '__all__' as string,
  result: '__all__' as string,
  begin: '',
  end: '',
})

const hasCondition = computed(
  () =>
    !!query.channelCode.trim() ||
    !!query.actorId.trim() ||
    query.action !== '__all__' ||
    query.result !== '__all__' ||
    !!query.begin ||
    !!query.end,
)

const toApiTime = (v: string): string | undefined => {
  if (!v) return undefined
  const s = v.replace('T', ' ')
  return s.length === 16 ? `${s}:00` : s
}

const buildParams = (p: number): PlayRecordQueryRequest => ({
  page: p,
  size: PAGE_SIZE,
  channelCode: query.channelCode.trim() || undefined,
  actorId: query.actorId.trim() || undefined,
  action: query.action === '__all__' ? undefined : (query.action as 'PLAY' | 'PLAYBACK'),
  result: query.result === '__all__' ? undefined : (query.result as 'SUCCESS' | 'FAILED'),
  begin: toApiTime(query.begin),
  end: toApiTime(query.end),
})

const load = async (p = page.value) => {
  loading.value = true
  try {
    const res = await getResPlayRecordPageApi(buildParams(p))
    rows.value = res.data.rows ?? []
    total.value = res.data.total ?? 0
    page.value = p
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

const onClear = () => {
  query.channelCode = ''
  query.actorId = ''
  query.action = '__all__'
  query.result = '__all__'
  query.begin = ''
  query.end = ''
  load(1)
}

const durationText = (sec?: number) => {
  if (sec == null) return '—'
  if (sec < 60) return `${sec} 秒`
  if (sec < 3600) return `${Math.floor(sec / 60)} 分 ${sec % 60} 秒`
  return `${Math.floor(sec / 3600)} 时 ${Math.floor((sec % 3600) / 60)} 分`
}

const onExport = async () => {
  exporting.value = true
  try {
    const res = await exportResPlayRecordApi({ ...buildParams(1), page: 1 })
    const url = URL.createObjectURL(res.data as Blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `播放记录-${Date.now()}.csv`
    a.click()
    URL.revokeObjectURL(url)
    toast.success('导出成功')
  } finally {
    exporting.value = false
  }
}

onMounted(() => load(1))
</script>
