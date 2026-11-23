<template>
  <div class="flex h-full min-w-0 flex-col gap-3 overflow-auto p-3">
    <!-- 顶部：概览与刷新 -->
    <div class="flex shrink-0 items-center gap-3">
      <span class="text-[13px] font-medium">运行自检</span>
      <span class="text-xs text-muted-foreground">
        取流会话 / 节点流 / 带宽 / 级联消费 / 今日记录 / 关键阈值；数据为点查快照
      </span>
      <span v-if="summary?.generatedAt" class="font-mono text-[11px] text-muted-foreground/60">
        {{ summary.generatedAt }}
      </span>
      <div class="flex-1" />
      <RefreshButton :loading="loading" :on-refresh="load" />
    </div>

    <div v-if="firstLoading" class="grid flex-1 place-items-center py-20">
      <IconLoader2 class="size-5 animate-spin text-muted-foreground" />
    </div>

    <template v-else-if="summary">
      <!-- 统计卡片 -->
      <div class="grid shrink-0 grid-cols-2 gap-3 xl:grid-cols-4">
        <div class="rounded-xl border border-border bg-card p-3">
          <div class="text-xs text-muted-foreground">取流会话</div>
          <div class="mt-1 flex items-end gap-2">
            <span class="text-2xl font-semibold leading-none">{{ summary.sessions.total }}</span>
            <span class="pb-0.5 text-[11px] text-muted-foreground">路</span>
          </div>
          <div class="mt-2 flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-muted-foreground">
            <span>邀请中 <b class="font-medium text-foreground">{{ summary.sessions.inviting }}</b></span>
            <span>已就绪 <b class="font-medium text-foreground">{{ summary.sessions.ready }}</b></span>
            <span>实时 <b class="font-medium text-foreground">{{ summary.sessions.play }}</b></span>
            <span>回放 <b class="font-medium text-foreground">{{ summary.sessions.playback }}</b></span>
          </div>
        </div>

        <div class="rounded-xl border border-border bg-card p-3">
          <div class="text-xs text-muted-foreground">级联与广播消费</div>
          <div class="mt-1 flex items-end gap-2">
            <span class="text-2xl font-semibold leading-none">{{ summary.cascadeSendCount }}</span>
            <span class="pb-0.5 text-[11px] text-muted-foreground">路级联</span>
            <span class="pb-0.5 text-[11px] text-muted-foreground/50">/</span>
            <span class="text-lg font-medium leading-none">{{ summary.broadcastCount }}</span>
            <span class="pb-0.5 text-[11px] text-muted-foreground">路广播</span>
          </div>
          <div class="mt-2 text-[11px] leading-relaxed text-muted-foreground">
            被上级或广播消费的流视为有人观看，空闲回收不会切断
          </div>
        </div>

        <div class="rounded-xl border border-border bg-card p-3">
          <div class="text-xs text-muted-foreground">今日播放记录</div>
          <div class="mt-1 flex items-end gap-2">
            <span class="text-2xl font-semibold leading-none">{{ summary.recordsToday.total }}</span>
            <span class="pb-0.5 text-[11px] text-muted-foreground">次</span>
          </div>
          <div class="mt-2 flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-muted-foreground">
            <span class="text-emerald-600">成功 {{ summary.recordsToday.success }}</span>
            <span class="text-red-500">失败 {{ summary.recordsToday.failed }}</span>
            <span>未结束 {{ summary.recordsToday.open }}</span>
          </div>
        </div>

        <div class="rounded-xl border border-border bg-card p-3">
          <div class="text-xs text-muted-foreground">节点概况</div>
          <div class="mt-1 flex items-end gap-2">
            <span class="text-2xl font-semibold leading-none">{{ reachableNodes }}</span>
            <span class="pb-0.5 text-[11px] text-muted-foreground">/ {{ summary.nodes.length }} 节点可达</span>
          </div>
          <div class="mt-2 flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-muted-foreground">
            <span>流 <b class="font-medium text-foreground">{{ totalStreams }}</b></span>
            <span>观看者 <b class="font-medium text-foreground">{{ totalReaders }}</b></span>
          </div>
        </div>
      </div>

      <!-- 带宽监控（6.8 流量风险） -->
      <div class="shrink-0 rounded-xl border border-border bg-card">
        <div class="flex h-10 items-center gap-2 border-b border-border px-3">
          <span class="text-xs font-medium text-foreground">出流带宽</span>
          <span class="text-[11px] text-muted-foreground">
            各节点 bytesSpeed 求和；换算 Mbps 与小时流量，用于判断带宽与专线是否够用
          </span>
        </div>
        <div class="grid gap-3 p-3 xl:grid-cols-[240px_1fr]">
          <div>
            <div class="flex items-end gap-2">
              <span class="text-2xl font-semibold leading-none">{{ mbpsText(summary.bandwidth.totalMbps) }}</span>
              <span class="pb-0.5 text-[11px] text-muted-foreground">合计</span>
            </div>
            <div class="mt-2 space-y-1 text-[11px] text-muted-foreground">
              <div>约 <b class="font-medium text-foreground">{{ hourVolumeText(summary.bandwidth.totalBytesPerSec) }}</b> /小时</div>
              <div>
                有码率的流 <b class="font-medium text-foreground">{{ summary.bandwidth.streamCount }}</b>
                <span class="mx-1 text-muted-foreground/40">/</span>
                观看者 <b class="font-medium text-foreground">{{ summary.bandwidth.totalReaders }}</b>
              </div>
            </div>
          </div>
          <table v-if="summary.bandwidth.topStreams.length" class="w-full table-fixed border-collapse text-left text-[12px]">
            <thead>
              <tr class="bg-muted/30 text-[11px] text-muted-foreground">
                <th class="w-56 px-2 py-1 font-normal">流</th>
                <th class="w-24 px-2 py-1 font-normal">节点</th>
                <th class="w-24 px-2 py-1 font-normal">码率</th>
                <th class="w-20 px-2 py-1 font-normal">观看者</th>
                <th class="px-2 py-1 font-normal">消费方</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in summary.bandwidth.topStreams" :key="`${s.serverId}/${s.app}/${s.stream}`" class="border-t border-border/40">
                <td class="truncate px-2 py-1 font-mono text-[11px]" :title="s.stream">{{ s.stream }}</td>
                <td class="truncate px-2 py-1 font-mono text-[11px] text-muted-foreground" :title="s.serverId ?? ''">
                  {{ s.serverId || '—' }}
                </td>
                <td class="px-2 py-1 text-xs text-foreground">{{ mbpsText(s.mbps) }}</td>
                <td class="px-2 py-1 text-xs" :class="s.readers > 0 ? 'text-emerald-600' : 'text-muted-foreground'">
                  {{ s.readers }}
                </td>
                <td class="px-2 py-1 text-[11px]">
                  <span v-if="s.externalConsumer" class="text-amber-600">级联/广播</span>
                  <span v-else class="text-muted-foreground/60">—</span>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-else class="grid place-items-center py-4 text-[11px] text-muted-foreground/60">
            当前没有上报码率的流（刚起流或节点不可达）
          </div>
        </div>
      </div>

      <!-- 阈值快照 -->
      <div class="shrink-0 rounded-xl border border-border bg-card p-3">
        <div class="mb-2 text-xs font-medium text-foreground">关键阈值（当前生效）</div>
        <div class="grid grid-cols-2 gap-x-6 gap-y-1.5 text-[11px] text-muted-foreground md:grid-cols-3 xl:grid-cols-6">
          <div class="flex items-baseline justify-between gap-2">
            <span>空闲回收</span><span class="font-mono text-foreground">{{ summary.config.idleReclaimSec }}s</span>
          </div>
          <div class="flex items-baseline justify-between gap-2">
            <span>INVITE 超时</span><span class="font-mono text-foreground">{{ summary.config.inviteTimeoutSec }}s</span>
          </div>
          <div class="flex items-baseline justify-between gap-2">
            <span>回放超时</span><span class="font-mono text-foreground">{{ summary.config.playbackTimeoutSec }}s</span>
          </div>
          <div class="flex items-baseline justify-between gap-2">
            <span>级联 RTP 超时</span><span class="font-mono text-foreground">{{ summary.config.cascadeRtpTimeoutSec }}s</span>
          </div>
          <div class="flex items-baseline justify-between gap-2">
            <span>跨跳冷却</span><span class="font-mono text-foreground">{{ summary.config.hopCooldownSec }}s</span>
          </div>
          <div class="flex items-baseline justify-between gap-2">
            <span>记录保留</span><span class="font-mono text-foreground">{{ summary.config.recordRetentionDays }}d</span>
          </div>
        </div>
      </div>

      <!-- 节点与流 -->
      <div class="shrink-0 rounded-xl border border-border bg-card">
        <div class="flex h-10 items-center gap-2 border-b border-border px-3">
          <span class="text-xs font-medium text-foreground">媒体节点</span>
          <span class="text-[11px] text-muted-foreground">reader 取 readerCount / totalReaderCount 较大值</span>
        </div>
        <div v-if="!summary.nodes.length" class="grid place-items-center py-10 text-[13px] text-muted-foreground">
          还没有可用的媒体节点
        </div>
        <div v-for="n in summary.nodes" :key="n.serverId" class="border-b border-border/50 last:border-0">
          <div class="flex items-center gap-3 px-3 py-2 text-xs">
            <span
              class="size-1.5 shrink-0 rounded-full"
              :class="!n.reachable ? 'bg-red-500' : n.status === 1 ? 'bg-emerald-500' : 'bg-amber-500'"
            />
            <span class="font-medium text-foreground">{{ n.name || n.serverId }}</span>
            <span class="font-mono text-[11px] text-muted-foreground/70">{{ n.hostIp || '—' }}</span>
            <span v-if="!n.reachable" class="rounded bg-red-500/10 px-1.5 py-0.5 text-[10px] text-red-500">不可达</span>
            <span v-else class="rounded bg-muted px-1.5 py-0.5 text-[10px] text-muted-foreground">流 {{ n.streamCount }}</span>
            <span class="text-[11px] text-muted-foreground">观看者 {{ n.totalReaders }}</span>
          </div>
          <table v-if="n.streams.length" class="w-full table-fixed border-collapse text-left text-[12px]">
            <thead>
              <tr class="bg-muted/30 text-[11px] text-muted-foreground">
                <th class="w-24 px-3 py-1 font-normal">app</th>
                <th class="w-56 px-3 py-1 font-normal">stream</th>
                <th class="w-20 px-3 py-1 font-normal">观看者</th>
                <th class="w-24 px-3 py-1 font-normal">码率</th>
                <th class="w-24 px-3 py-1 font-normal">已持续</th>
                <th class="w-24 px-3 py-1 font-normal">编码</th>
                <th class="px-3 py-1 font-normal">消费方</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in n.streams" :key="`${s.app}/${s.stream}`" class="border-t border-border/40">
                <td class="px-3 py-1 font-mono text-[11px] text-muted-foreground">{{ s.app }}</td>
                <td class="truncate px-3 py-1 font-mono text-[11px]" :title="s.stream">{{ s.stream }}</td>
                <td class="px-3 py-1">
                  <span class="text-xs" :class="s.readers > 0 ? 'text-emerald-600' : 'text-muted-foreground'">{{ s.readers }}</span>
                </td>
                <td class="px-3 py-1 text-xs text-muted-foreground">{{ bitrateText(s.bytesSpeed) }}</td>
                <td class="px-3 py-1 text-xs text-muted-foreground">{{ aliveText(s.aliveSeconds) }}</td>
                <td class="px-3 py-1 text-xs text-muted-foreground">{{ s.videoCodec || '—' }}</td>
                <td class="px-3 py-1 text-[11px]">
                  <span v-if="zeroReader(s) && isExternal(s.stream)" class="text-amber-600">级联/广播</span>
                  <span v-else-if="zeroReader(s)" class="text-muted-foreground/60">无人观看（计入回收）</span>
                  <span v-else class="text-muted-foreground/60">—</span>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-else class="px-6 pb-2 text-[11px] text-muted-foreground/60">
            {{ n.reachable ? '当前没有流' : '节点不可达，无法读取流列表（本轮空闲回收会放行）' }}
          </div>
        </div>
      </div>

      <!-- 活跃会话 -->
      <div class="flex min-h-0 flex-col rounded-xl border border-border bg-card">
        <div class="flex h-10 shrink-0 items-center gap-2 border-b border-border px-3">
          <span class="text-xs font-medium text-foreground">活跃取流会话</span>
          <span class="text-[11px] text-muted-foreground">{{ summary.activeSessions.length }} 路</span>
        </div>
        <div v-if="!summary.activeSessions.length" class="grid place-items-center py-10 text-[13px] text-muted-foreground">
          当前没有取流会话
        </div>
        <div v-else class="max-h-[420px] overflow-auto">
          <table class="w-full table-fixed border-collapse text-left text-[12px]">
            <thead class="sticky top-0 z-10">
              <tr class="bg-card text-[11px] text-muted-foreground">
                <th class="w-56 px-3 py-1.5 font-normal">流 ID</th>
                <th class="w-20 px-3 py-1.5 font-normal">类型</th>
                <th class="w-24 px-3 py-1.5 font-normal">状态</th>
                <th class="w-40 px-3 py-1.5 font-normal">开始</th>
                <th class="w-40 px-3 py-1.5 font-normal">最近观看</th>
                <th class="w-40 px-3 py-1.5 font-normal">最近 RTP</th>
                <th class="w-20 px-3 py-1.5 font-normal">观看者</th>
                <th class="w-24 px-3 py-1.5 font-normal">外部消费</th>
                <th class="px-3 py-1.5 font-normal">节点</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in summary.activeSessions" :key="s.streamId" class="border-t border-border/40">
                <td class="truncate px-3 py-1 font-mono text-[11px]" :title="s.streamId">{{ s.streamId }}</td>
                <td class="px-3 py-1">
                  <span
                    class="rounded px-1.5 py-0.5 text-[10px]"
                    :class="s.sessionType === 'PLAYBACK' ? 'bg-sky-500/10 text-sky-600' : 'bg-emerald-500/10 text-emerald-600'"
                  >
                    {{ s.sessionType === 'PLAYBACK' ? '回放' : '实时' }}
                  </span>
                </td>
                <td class="px-3 py-1 text-[11px]">
                  <span :class="s.status === 'READY' ? 'text-emerald-600' : 'text-amber-600'">
                    {{ s.status === 'READY' ? '已就绪' : s.status }}
                  </span>
                </td>
                <td class="px-3 py-1 font-mono text-[11px] text-muted-foreground">{{ s.startTime || '—' }}</td>
                <td class="px-3 py-1 font-mono text-[11px] text-muted-foreground">{{ s.lastReaderTime || '—' }}</td>
                <td class="px-3 py-1 font-mono text-[11px] text-muted-foreground">{{ s.lastRtpTime || '—' }}</td>
                <td class="px-3 py-1 text-xs" :class="s.readers > 0 ? 'text-emerald-600' : 'text-muted-foreground'">
                  {{ s.readers }}
                </td>
                <td class="px-3 py-1 text-[11px]">
                  <span v-if="s.externalConsumer" class="text-amber-600">是</span>
                  <span v-else class="text-muted-foreground/60">—</span>
                </td>
                <td class="truncate px-3 py-1 text-[11px] text-muted-foreground" :title="s.serverId ?? ''">
                  {{ s.serverId || '—' }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>

    <div v-else class="grid flex-1 place-items-center py-20 text-[13px] text-muted-foreground">
      没有取到自检数据，点右上角刷新重试
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { IconLoader2 } from '@tabler/icons-vue'
import RefreshButton from '@/components/RefreshButton.vue'
import { getObservabilitySummaryApi } from '@/api/res'
import type { ObservabilityStreamItem, ObservabilitySummary } from '@/types/api'

/**
 * 运行自检（3.11.x 可观测）：一屏回答"现在谁在看、流在哪、多少人在看、为什么没回收"。
 *
 * <p>所有数据由后端点查聚合，前端只做展示与单位换算；节点不可达时会明确写出来，
 * 因为那一轮空闲回收是放行的（问不到 reader 数就不能判死）。</p>
 */
const summary = ref<ObservabilitySummary | null>(null)
const loading = ref(false)

const firstLoading = computed(() => loading.value && !summary.value)

const load = async () => {
  loading.value = true
  try {
    summary.value = (await getObservabilitySummaryApi()).data
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

const reachableNodes = computed(() => summary.value?.nodes.filter((n) => n.reachable).length ?? 0)
const totalStreams = computed(() => summary.value?.nodes.reduce((acc, n) => acc + n.streamCount, 0) ?? 0)
const totalReaders = computed(() => summary.value?.nodes.reduce((acc, n) => acc + n.totalReaders, 0) ?? 0)

/** 有外部消费（级联/广播）但没有 reader 的流：属于正常，不是无人观看 */
const externalStreams = computed(() => {
  const set = new Set<string>()
  summary.value?.activeSessions.forEach((s) => {
    if (s.externalConsumer) set.add(s.streamId)
  })
  return set
})

const isExternal = (stream: string) => externalStreams.value.has(stream)

const zeroReader = (s: ObservabilityStreamItem) => s.readers === 0

const bitrateText = (bytesPerSec: number) => {
  if (!bytesPerSec) return '0 B/s'
  const bps = bytesPerSec * 8
  if (bps >= 1_000_000) return `${(bps / 1_000_000).toFixed(1)} Mbps`
  if (bps >= 1_000) return `${(bps / 1_000).toFixed(0)} kbps`
  return `${bps.toFixed(0)} bps`
}

/** 带宽（Mbps）：小于 1 时给三位小数，避免"0.0 Mbps"看不出量级 */
const mbpsText = (mbps: number) => {
  if (!mbps) return '0 Mbps'
  if (mbps < 1) return `${mbps.toFixed(3)} Mbps`
  if (mbps < 1000) return `${mbps.toFixed(1)} Mbps`
  return `${(mbps / 1000).toFixed(2)} Gbps`
}

/** 按当前码率折算的小时流量：运维估算专线账单用 */
const hourVolumeText = (bytesPerSec: number) => {
  const gb = (bytesPerSec * 3600) / 1_000_000_000
  if (!gb) return '0 GB'
  if (gb < 1) return `${(gb * 1000).toFixed(0)} MB`
  return `${gb.toFixed(2)} GB`
}

const aliveText = (sec: number) => {
  if (!sec) return '—'
  if (sec < 60) return `${sec}s`
  if (sec < 3600) return `${Math.floor(sec / 60)}m ${sec % 60}s`
  return `${Math.floor(sec / 3600)}h ${Math.floor((sec % 3600) / 60)}m`
}

onMounted(load)
</script>
