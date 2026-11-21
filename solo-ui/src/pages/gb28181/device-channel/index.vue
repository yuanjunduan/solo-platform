<template>
  <div class="flex h-full gap-3 overflow-hidden p-3">
    <!-- 左侧：列表卡片 -->
    <div class="flex min-w-0 flex-1 flex-col overflow-hidden rounded-xl border border-border bg-card">
      <!-- 工具栏 -->
      <div class="flex h-12 shrink-0 items-center gap-1 border-b border-border px-3">
        <span class="text-[13px] font-medium">通道</span>

        <div class="ml-3 flex h-full items-center">
          <!-- 视图切换放在最前：目录树不是筛选维度，不带计数 -->
          <button
            class="relative flex h-full items-center gap-1.5 px-2 text-[13px] transition-colors"
            :class="
              activeKey === 'tree'
                ? 'font-medium text-foreground'
                : 'text-muted-foreground hover:text-foreground'
            "
            title="按组织目录查看"
            @click="onTab('tree')"
          >
            <Folder class="size-3.5" />
            目录树
            <span
              v-if="activeKey === 'tree'"
              class="absolute inset-x-2 -bottom-px h-0.5 rounded-full bg-primary"
            />
          </button>

          <span class="mx-1 h-4 w-px bg-border" />

          <button
            v-for="tab in filterTabs"
            :key="tab.key"
            class="relative flex h-full items-center gap-1.5 px-2 text-[13px] transition-colors"
            :class="
              activeKey === tab.key
                ? 'font-medium text-foreground'
                : 'text-muted-foreground hover:text-foreground'
            "
            :title="tab.hint"
            @click="onTab(tab.key)"
          >
            {{ tab.label }}
            <span class="rounded-full bg-muted px-1.5 py-px text-[11px] text-muted-foreground">{{
              counts[tab.key]
            }}</span>
            <span
              v-if="activeKey === tab.key"
              class="absolute inset-x-2 -bottom-px h-0.5 rounded-full bg-primary"
            />
          </button>
        </div>

        <span v-if="checkedIds.size" class="ml-2 text-xs text-muted-foreground">
          已选 {{ checkedIds.size }} 项
        </span>
        <div class="flex-1" />
        <RefreshButton :loading="resource.loading" :on-refresh="onRefresh" />
        <Button
          v-access="PERM.GB_DEVICE_CHANNEL_DELETE"
          variant="ghost"
          size="icon"
          class="size-8"
          title="批量删除"
          :disabled="!checkedIds.size || batchDeleting"
          @click="onBatchDelete"
        >
          <Loader2 v-if="batchDeleting" class="size-4 animate-spin" />
          <Trash2 v-else class="size-4" />
        </Button>

        <Button
          v-access="PERM.GB_DEVICE_CHANNEL_ADD"
          variant="ghost"
          size="sm"
          class="ml-1 gap-1.5"
          @click="onCreate"
        >
          <Plus class="size-3.5" />
          新建
          <kbd class="rounded border border-border bg-muted px-1 text-[10px] text-muted-foreground"
            >N</kbd
          >
        </Button>
      </div>

      <!-- 搜索行 -->
      <div class="flex h-12 shrink-0 items-center gap-2 px-3">
        <IconSearch class="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
        <input
          ref="channelIdRef"
          v-model="channelIdQ"
          class="h-7 w-44 rounded-md bg-transparent px-2 font-mono text-[13px] outline-none placeholder:font-sans placeholder:text-muted-foreground focus:bg-muted/70"
          placeholder="请输入通道国标编码"
        />
        <input
          v-model="nameQ"
          class="h-7 w-44 rounded-md bg-transparent px-2 text-[13px] outline-none placeholder:text-muted-foreground focus:bg-muted/70"
          placeholder="请输入通道名称"
        />
        <GbPlatformSelect v-model="platformIdQ" />

        <span v-if="hasCondition" class="text-xs text-muted-foreground">
          匹配 {{ resource.total.value }} 条
        </span>

        <div class="flex-1" />

        <Button
          v-if="hasCondition"
          variant="ghost"
          size="sm"
          class="h-7 text-xs text-muted-foreground"
          @click="onClear"
        >
          <IconX class="size-3.5" />
          清除
        </Button>
      </div>

      <!-- 列表 -->
      <div v-show="activeKey !== 'tree'" ref="scrollerRef" class="min-h-0 flex-1 overflow-auto">
        <!-- 未选下级平台：不查询，先引导选择 -->
        <div
          v-if="!platformIdQ"
          class="flex flex-col items-center gap-3 py-16 text-[13px] text-muted-foreground"
        >
          <div>请先选择下级平台，再查看其上报的通道目录</div>
          <GbPlatformSelect v-model="platformIdQ" />
        </div>
        <table v-else class="w-full table-fixed border-collapse text-left text-[13px]">
          <thead class="sticky top-0 z-10">
            <tr class="bg-card text-xs text-muted-foreground">
              <th class="w-10 px-3 py-2">
                <Checkbox
                  :model-value="allChecked ? true : indeterminate ? 'indeterminate' : false"
                  @update:model-value="onToggleAll"
                />
              </th>
              <th class="w-48 px-3 py-2 font-normal">通道名称</th>
              <th class="w-52 px-3 py-2 font-normal">通道编码</th>
              <th class="w-52 px-3 py-2 font-normal">设备编码</th>
              <th class="w-[72px] px-3 py-2">
                <span class="inline-flex items-center gap-1 font-normal">
                  状态
                  <Select v-model="statusFilter">
                    <RawSelectTrigger
                      title="按状态筛选"
                      class="inline-flex size-4 items-center justify-center rounded outline-none hover:text-foreground"
                    >
                      <IconFilter
                        class="size-3.5"
                        :class="
                          statusFilter !== '__all__'
                            ? 'text-primary'
                            : 'text-muted-foreground/60'
                        "
                      />
                    </RawSelectTrigger>
                    <SelectContent>
                      <SelectItem value="__all__">全部</SelectItem>
                      <SelectItem value="ON">在线</SelectItem>
                      <SelectItem value="OFF">离线</SelectItem>
                      <SelectItem value="UNREGISTERED">未注册</SelectItem>
                    </SelectContent>
                  </Select>
                </span>
              </th>
              <th class="w-20 px-3 py-2 font-normal">资源类型</th>
              <th class="w-24 px-3 py-2">
                <span class="inline-flex items-center gap-1 font-normal">
                  类型
                  <Select v-model="ptzFilter">
                    <RawSelectTrigger
                      title="按云台类型筛选"
                      class="inline-flex size-4 items-center justify-center rounded outline-none hover:text-foreground"
                    >
                      <IconFilter
                        class="size-3.5"
                        :class="
                          ptzFilter !== '__all__'
                            ? 'text-primary'
                            : 'text-muted-foreground/60'
                        "
                      />
                    </RawSelectTrigger>
                    <SelectContent>
                      <SelectItem value="__all__">全部</SelectItem>
                      <SelectItem value="0">未知</SelectItem>
                      <SelectItem value="1">球机</SelectItem>
                      <SelectItem value="2">半球</SelectItem>
                      <SelectItem value="3">固定枪机</SelectItem>
                      <SelectItem value="4">遥控枪机</SelectItem>
                    </SelectContent>
                  </Select>
                </span>
              </th>
              <th class="w-24 px-3 py-2">
                <span class="inline-flex items-center gap-1 font-normal">
                  数据来源
                  <Select v-model="sourceFilter">
                    <RawSelectTrigger
                      title="按数据来源筛选"
                      class="inline-flex size-4 items-center justify-center rounded outline-none hover:text-foreground"
                    >
                      <IconFilter
                        class="size-3.5"
                        :class="
                          sourceFilter !== '__all__'
                            ? 'text-primary'
                            : 'text-muted-foreground/60'
                        "
                      />
                    </RawSelectTrigger>
                    <SelectContent>
                      <SelectItem value="__all__">全部</SelectItem>
                      <SelectItem value="1">系统新增</SelectItem>
                      <SelectItem value="2">表格导入</SelectItem>
                      <SelectItem value="3">设备注册</SelectItem>
                    </SelectContent>
                  </Select>
                </span>
              </th>
            </tr>
          </thead>

          <tbody>
            <tr
              v-for="(c, i) in resource.items.value"
              :key="c.id"
              :data-row="i"
              class="group cursor-pointer"
              :class="
                !creating && selected?.id === c.id
                  ? 'bg-primary/[0.07]'
                  : cursor === i
                    ? 'bg-muted/50'
                    : 'hover:bg-muted/30'
              "
              @click="onRowClick(c, i)"
            >
              <td class="px-3 py-2" @click.stop>
                <Checkbox
                  :model-value="checkedIds.has(c.id)"
                  @update:model-value="(v) => onToggleOne(c, v === true)"
                />
              </td>
              <td class="relative px-3 py-2">
                <span
                  v-if="!creating && selected?.id === c.id"
                  class="absolute inset-y-0 left-0 w-0.5 bg-primary"
                />
                <span class="block min-w-0 truncate" :title="displayName(c)">
                  {{ displayName(c) }}
                </span>
              </td>
              <td class="truncate px-3 py-2 font-mono" :title="c.channelId">
                {{ c.channelId }}
              </td>
              <td class="truncate px-3 py-2 font-mono text-muted-foreground" :title="c.deviceId || ''">
                {{ c.deviceId || '—' }}
              </td>
              <td class="px-3 py-2">
                <span
                  class="text-xs"
                  :class="
                    c.parental !== 1 && c.status === 'ON'
                      ? 'text-emerald-500'
                      : c.parental !== 1 && c.status === 'OFF'
                        ? 'text-red-500'
                        : 'text-muted-foreground'
                  "
                >
                  {{ statusText(c) }}
                </span>
              </td>
              <td class="px-3 py-2 text-xs">
                <span
                  class="rounded px-1.5 py-0.5"
                  :class="
                    c.parental === 1
                      ? 'bg-warning/10 text-warning'
                      : 'bg-primary/10 text-primary'
                  "
                >
                  {{ c.parental === 1 ? '分组' : '通道' }}
                </span>
              </td>
              <td class="px-3 py-2 text-xs text-muted-foreground">{{ ptzText(c) }}</td>
              <td class="px-3 py-2 text-xs text-muted-foreground">{{ sourceText(c.source) }}</td>
            </tr>
          </tbody>
        </table>

        <div v-if="firstLoading" class="grid place-items-center py-16">
          <IconLoader2 class="size-5 animate-spin text-muted-foreground" />
        </div>

        <div
          v-else-if="platformIdQ && !resource.loading.value && resource.items.value.length === 0"
          class="flex flex-col items-center gap-2 py-16 text-muted-foreground"
        >
          <IconVideoOff class="size-8" />
          <div class="text-[13px]">{{ hasCondition ? '没有匹配的通道' : '还没有通道' }}</div>
          <Button v-if="hasCondition" variant="ghost" size="sm" @click="onClear">
            清除搜索条件
          </Button>
        </div>

        <div ref="sentinelRef" class="grid place-items-center py-4">
          <IconLoader2
            v-if="resource.loading.value && resource.items.value.length"
            class="size-4 animate-spin text-muted-foreground"
          />
          <span
            v-else-if="resource.finished.value && resource.items.value.length"
            class="text-xs text-muted-foreground"
          >
            已全部加载
          </span>
        </div>
      </div>

      <!-- 目录树：左侧组织树懒加载（每层一次请求），右侧选中组织的通道分页 -->
      <div v-show="activeKey === 'tree'" class="min-h-0 flex-1 overflow-hidden">
        <div
          v-if="!platformIdQ"
          class="flex flex-col items-center gap-3 py-16 text-[13px] text-muted-foreground"
        >
          <div>目录树按下级平台展示，请先选择下级平台</div>
          <GbPlatformSelect v-model="platformIdQ" />
        </div>
        <div v-else class="flex h-full min-h-0">
          <!-- 左：组织树 -->
          <div class="flex w-[340px] shrink-0 flex-col border-r border-border">
            <div
              class="flex h-8 shrink-0 items-center gap-2 border-b border-border px-2 text-[11px] text-muted-foreground"
            >
              <span class="truncate">组织目录</span>
              <span class="ml-auto shrink-0">{{ treeRootLevel?.subtreeChannelCount ?? 0 }} 路</span>
            </div>
            <div class="min-h-0 flex-1 overflow-auto py-1">
              <div v-if="treeLoading" class="grid place-items-center py-16">
                <IconLoader2 class="size-5 animate-spin text-muted-foreground" />
              </div>
              <div
                v-else-if="!treeRootNodes.length"
                class="flex flex-col items-center gap-2 py-16 text-[13px] text-muted-foreground"
              >
                <IconVideoOff class="size-8" />
                <div>该设备下暂无目录数据，请先同步通道目录</div>
              </div>
              <template v-else>
                <div
                  class="flex h-7 cursor-pointer items-center gap-1 rounded pr-2 pl-1.5 text-xs"
                  :class="
                    treeSelectedCode === ''
                      ? 'bg-primary/10 text-foreground'
                      : 'hover:bg-muted/50'
                  "
                  @click="selectOrg('')"
                >
                  <Folder class="size-3.5 shrink-0 text-amber-400/80" />
                  <span class="truncate">设备（全部组织）</span>
                  <span class="ml-auto shrink-0 text-[10px] text-muted-foreground/70">
                    {{ treeRootLevel?.subtreeChannelCount ?? 0 }} 路
                  </span>
                </div>
                <GbDeviceChannelTree
                  :nodes="treeRootNodes"
                  :selected-code="treeSelectedCode"
                  :expanded="treeExpanded"
                  :loading="treeNodeLoading"
                  @select="onTreeSelect"
                  @toggle="onToggleTree"
                />
              </template>
            </div>
          </div>

          <!-- 右：选中组织的通道 -->
          <div class="flex min-w-0 flex-1 flex-col">
            <div
              class="flex h-8 shrink-0 items-center gap-3 border-b border-border px-3 text-[11px] text-muted-foreground"
            >
              <span class="max-w-[200px] truncate" :title="treeSelectedLabel">
                {{ treeSelectedLabel }}
              </span>
              <label class="flex shrink-0 cursor-pointer items-center gap-1.5">
                <Switch
                  :model-value="includeSubtree"
                  @update:model-value="onToggleIncludeSubtree"
                />
                包含子组织
              </label>
              <input
                v-model="treeKeyword"
                class="h-6 w-44 rounded border border-border bg-transparent px-2 text-[11px] outline-none focus:border-primary"
                placeholder="名称 / 国标码"
              />
              <span class="ml-auto shrink-0">共 {{ treeChannelTotal }} 路</span>
            </div>
            <div class="min-h-0 flex-1 overflow-auto">
              <table class="w-full table-fixed border-collapse text-left text-[13px]">
                <thead class="sticky top-0 z-10">
                  <tr class="bg-card text-xs text-muted-foreground">
                    <th class="px-3 py-2 font-normal">通道名称</th>
                    <th class="w-52 px-3 py-2 font-normal">通道编码</th>
                    <th class="w-[64px] px-3 py-2 font-normal">状态</th>
                    <th class="w-24 px-3 py-2 font-normal">类型</th>
                    <th class="w-24 px-3 py-2 font-normal">数据来源</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="c in treeChannels"
                    :key="c.id"
                    class="cursor-pointer"
                    :class="
                      !creating && selected?.id === c.id ? 'bg-primary/[0.07]' : 'hover:bg-muted/30'
                    "
                    @click="onTreeChannelClick(c)"
                  >
                    <td class="truncate px-3 py-2" :title="displayName(c)">
                      {{ displayName(c) }}
                    </td>
                    <td class="truncate px-3 py-2 font-mono" :title="c.channelId">
                      {{ c.channelId }}
                    </td>
                    <td class="px-3 py-2">
                      <span
                        class="text-xs"
                        :class="
                          c.status === 'ON'
                            ? 'text-emerald-500'
                            : c.status === 'OFF'
                              ? 'text-red-500'
                              : 'text-muted-foreground'
                        "
                      >
                        {{ statusText(c) }}
                      </span>
                    </td>
                    <td class="px-3 py-2 text-xs text-muted-foreground">{{ ptzText(c) }}</td>
                    <td class="px-3 py-2 text-xs text-muted-foreground">
                      {{ sourceText(c.source) }}
                    </td>
                  </tr>
                </tbody>
              </table>
              <div v-if="treeChannelLoading" class="grid place-items-center py-16">
                <IconLoader2 class="size-5 animate-spin text-muted-foreground" />
              </div>
              <div
                v-else-if="!treeChannels.length"
                class="flex flex-col items-center gap-2 py-16 text-[13px] text-muted-foreground"
              >
                <IconVideoOff class="size-8" />
                <div>{{ includeSubtree ? '该组织及其子组织下暂无通道' : '该组织下暂无直属通道' }}</div>
              </div>
            </div>
            <div
              class="flex h-8 shrink-0 items-center gap-2 border-t border-border px-3 text-[11px] text-muted-foreground"
            >
              <span>第 {{ treeChannelPage }} / {{ treeChannelPages }} 页</span>
              <div class="flex-1" />
              <Button
                variant="ghost"
                size="sm"
                class="h-6 text-xs"
                :disabled="treeChannelPage <= 1"
                @click="gotoTreePage(treeChannelPage - 1)"
              >
                上一页
              </Button>
              <Button
                variant="ghost"
                size="sm"
                class="h-6 text-xs"
                :disabled="treeChannelPage >= treeChannelPages"
                @click="gotoTreePage(treeChannelPage + 1)"
              >
                下一页
              </Button>
            </div>
          </div>
        </div>
      </div>

      <!-- 快捷键条 -->
      <div
        class="flex h-8 shrink-0 items-center gap-3 border-t border-border px-3 text-[11px] text-muted-foreground/70"
      >
        <span><kbd>/</kbd> 搜索</span>
        <span><kbd>J</kbd><kbd>K</kbd> 移动</span>
        <span><kbd>Enter</kbd> 查看</span>
        <span><kbd>N</kbd> 新建</span>
        <span><kbd>R</kbd> 刷新</span>
        <div class="flex-1" />
        <span v-if="activeKey === 'tree'">
          选中 {{ treeSelectedLabel }}：{{ treeChannelTotal }} 路通道（含子组织
          {{ includeSubtree ? '是' : '否' }}）
        </span>
        <span v-else>共 {{ resource.total.value }} 条通道</span>
      </div>
    </div>

    <!-- 右侧：检视器 -->
    <Transition name="inspector">
      <aside
        v-if="creating || selected"
        class="flex w-[420px] shrink-0 flex-col overflow-hidden rounded-xl border border-border bg-card"
      >
        <div class="min-h-0 flex-1 overflow-y-auto p-5">
          <GbChannelInspector
            :channel="selected ?? undefined"
            :creating="creating"
            @close="onCloseInspector"
            @saved="onSaved"
            @created="onCreated"
            @deleted="onDeleted"
          />
        </div>
      </aside>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { IconSearch, IconX, IconFilter, IconLoader2, IconVideoOff } from '@tabler/icons-vue'
import { Plus, Trash2, Loader2, Folder } from 'lucide-vue-next'
import GbChannelInspector from '@/features/gb/GbChannelInspector.vue'
import GbDeviceChannelTree from '@/features/gb/GbDeviceChannelTree.vue'
import GbPlatformSelect from '@/features/gb/GbPlatformSelect.vue'
import RefreshButton from '@/components/RefreshButton.vue'
import {
  getGbDeviceChannelPageApi,
  getGbDeviceChannelTreeChildrenApi,
  getGbChannelSubtreePageApi,
  batchDeleteGbDeviceChannelApi,
  getGbChannelStatusCountApi,
  getGbPlatformPageApi,
} from '@/api/gb28181'
import { useResource } from '@/shared/composables/useResource'
import { PERM } from '@/features/access/permissions'
import { confirm } from '@/lib/confirm'
import { toast } from '@/lib/toast'
import Button from '@/components/ui/button/Button.vue'
import Checkbox from '@/components/ui/checkbox/Checkbox.vue'
import Switch from '@/components/ui/switch/Switch.vue'
import { Select, SelectContent, SelectItem } from '@/components/ui/select'
import { SelectTrigger as RawSelectTrigger } from 'reka-ui'
import type {
  GbDeviceChannel,
  GbDeviceChannelPageRequest,
  GbDeviceChannelTreeNode,
  GbChannelTreeLevel,
} from '@/types/api'

/* ---------------- 搜索 / 状态 tab ---------------- */
type TabKey = 'all' | 'group' | 'tree' | 'channel' | 'online' | 'offline' | 'unregistered'

const channelIdQ = ref('')
const nameQ = ref('')
/** 当前下级平台（gb_platform.platform_id）：页面第一维度，未选不查询 */
const platformIdQ = ref<string | undefined>(undefined)
/** 首次挂载期间由 onMounted 统一加载，避开自动选平台触发的重复请求 */
let booted = false
const activeKey = ref<TabKey>('all')
const sourceFilter = ref<'__all__' | '1' | '2' | '3'>('__all__')
const ptzFilter = ref<'__all__' | '0' | '1' | '2' | '3' | '4'>('__all__')
const statusFilter = ref<'__all__' | 'ON' | 'OFF' | 'UNREGISTERED'>('__all__')

// 表头状态漏斗 ↔ 顶部状态 tab：在线/离线/未注册映射到 activeKey，双向同步
watch(statusFilter, (v) => {
  const key: TabKey =
    v === 'ON' ? 'online' : v === 'OFF' ? 'offline' : v === 'UNREGISTERED' ? 'unregistered' : 'all'
  if (activeKey.value === key) return
  activeKey.value = key
  cursor.value = 0
  clearChecked()
  resource.search()
})
watch(activeKey, (key) => {
  const v: '__all__' | 'ON' | 'OFF' | 'UNREGISTERED' =
    key === 'online' ? 'ON' : key === 'offline' ? 'OFF' : key === 'unregistered' ? 'UNREGISTERED' : '__all__'
  if (statusFilter.value !== v) statusFilter.value = v
})
const counts = ref<Record<TabKey, number>>({
  all: 0,
  group: 0,
  tree: 0,
  channel: 0,
  online: 0,
  offline: 0,
  unregistered: 0,
})

/** 带计数的筛选 tab（目录树是视图切换，不在其中） */
const filterTabs = computed(() => [
  { key: 'all' as const, label: '全部', hint: '分组 + 通道 合计' },
  { key: 'group' as const, label: '分组', hint: '目录分组（组织节点）数' },
  { key: 'channel' as const, label: '通道', hint: '真实通道数（= 在线 + 离线 + 未注册）' },
  { key: 'online' as const, label: '在线', hint: '通道在线数' },
  { key: 'offline' as const, label: '离线', hint: '通道离线数' },
  { key: 'unregistered' as const, label: '未注册', hint: '通道未注册数' },
])

/** tab → 查询参数：分组/通道走 parental；在线等状态走 status（后端自动追加 parental=0） */
const tabQuery = (key: TabKey): { status?: string; parental?: number } => {
  if (key === 'group') return { parental: 1 }
  if (key === 'channel') return { parental: 0 }
  if (key === 'online') return { status: 'ON' }
  if (key === 'offline') return { status: 'OFF' }
  if (key === 'unregistered') return { status: 'UNREGISTERED' }
  return {}
}

const hasCondition = computed(
  () =>
    !!channelIdQ.value.trim() ||
    !!nameQ.value.trim() ||
    activeKey.value !== 'all' ||
    sourceFilter.value !== '__all__' ||
    ptzFilter.value !== '__all__',
)

const resource = useResource<GbDeviceChannelPageRequest, GbDeviceChannel>({
  fetcher: (q) => {
    // 未选下级平台不查询：避免把多个平台上报的目录混在一起
    if (!platformIdQ.value) return Promise.resolve({ data: { rows: [], total: 0 } })
    return getGbDeviceChannelPageApi({
      ...q,
      channelId: channelIdQ.value.trim() || undefined,
      name: nameQ.value.trim() || undefined,
      platformId: platformIdQ.value,
      source: sourceFilter.value === '__all__' ? undefined : Number(sourceFilter.value),
      ptzType: ptzFilter.value === '__all__' ? undefined : Number(ptzFilter.value),
      ...tabQuery(activeKey.value),
    })
  },
  initial: { page: 1, pageSize: 50 },
})

const fetchStatusCount = async () => {
  // 计数跟随所选平台，与列表口径一致；未选平台时全部归零
  const rows = platformIdQ.value
    ? (await getGbChannelStatusCountApi(platformIdQ.value)).data
    : []
  const sumWhere = (parental: number, status?: string) =>
    rows
      .filter((r) => r.parental === parental && (status === undefined || r.status === status))
      .reduce((acc, r) => acc + (r.count ?? 0), 0)
  // 分组没有在线/离线语义：按 parental=1 全量汇总，避免状态非 ON 的分组被漏计
  const group = sumWhere(1)
  const online = sumWhere(0, 'ON')
  const offline = sumWhere(0, 'OFF')
  const unregistered = sumWhere(0, 'UNREGISTERED')
  const channel = online + offline + unregistered
  counts.value = {
    all: group + channel,
    group,
    tree: 0,
    channel,
    online,
    offline,
    unregistered,
  }
}

/* ---------------- 目录树：左侧组织树懒加载，右侧选中组织通道分页 ---------------- */
/** 设备根一层的统计（子树通道总数 / 下级分组数 / 下级通道数） */
const treeRootLevel = ref<GbChannelTreeLevel | null>(null)
const treeRootNodes = ref<GbDeviceChannelTreeNode[]>([])
const treeLoading = ref(false)
/** 已展开节点（按国标码，节点可能在多层） */
const treeExpanded = ref<Set<string>>(new Set())
const treeNodeLoading = ref<Set<string>>(new Set())
/** 当前选中组织：'' = 设备根 */
const treeSelectedCode = ref('')
const treeSelectedNode = ref<GbDeviceChannelTreeNode | null>(null)
const treeSelectedLabel = computed(() =>
  treeSelectedNode.value ? displayName(treeSelectedNode.value) : '设备（全部组织）',
)

/** 右侧通道列表（子树分页，默认 200/页） */
const treeChannels = ref<GbDeviceChannel[]>([])
const treeChannelTotal = ref(0)
const treeChannelPage = ref(1)
const TREE_PAGE_SIZE = 200
const treeChannelLoading = ref(false)
const includeSubtree = ref(true)
const treeKeyword = ref('')
const treeChannelPages = computed(() =>
  Math.max(1, Math.ceil(treeChannelTotal.value / TREE_PAGE_SIZE)),
)

const findTreeNode = (
  list: GbDeviceChannelTreeNode[],
  code: string,
): GbDeviceChannelTreeNode | null => {
  for (const node of list) {
    if (node.channelId === code) return node
    const hit = node.children?.length ? findTreeNode(node.children, code) : null
    if (hit) return hit
  }
  return null
}

/** 拉平台根一层：顺带把右侧列表重置到「设备（全部组织）」 */
const loadRootTree = async () => {
  if (!platformIdQ.value) {
    treeRootLevel.value = null
    treeRootNodes.value = []
    treeExpanded.value = new Set()
    treeChannels.value = []
    treeChannelTotal.value = 0
    treeSelectedCode.value = ''
    treeSelectedNode.value = null
    return
  }
  treeLoading.value = true
  try {
    treeRootLevel.value = (await getGbDeviceChannelTreeChildrenApi(platformIdQ.value)).data ?? null
    treeRootNodes.value = treeRootLevel.value?.children ?? []
    treeExpanded.value = new Set()
    treeNodeLoading.value = new Set()
    await selectOrg('')
  } finally {
    treeLoading.value = false
  }
}

/** 展开/收起：首次展开才发起下级请求（懒加载） */
const onToggleTree = async (code: string) => {
  const next = new Set(treeExpanded.value)
  if (next.has(code)) {
    next.delete(code)
    treeExpanded.value = next
    return
  }
  const node = findTreeNode(treeRootNodes.value, code)
  if (!node) return
  next.add(code)
  treeExpanded.value = next
  if (node.children?.length) return

  treeNodeLoading.value = new Set(treeNodeLoading.value).add(code)
  try {
    const level = (await getGbDeviceChannelTreeChildrenApi(platformIdQ.value!, code)).data
    node.children = level?.children ?? []
    node.hasChildren = !!level?.children?.length
    node.subtreeChannelCount = level?.subtreeChannelCount ?? node.subtreeChannelCount
  } finally {
    const pending = new Set(treeNodeLoading.value)
    pending.delete(code)
    treeNodeLoading.value = pending
  }
}

/** 点树节点：分组=看该组织及其子组织的通道；通道=直接打开检视器 */
const onTreeSelect = (node: GbDeviceChannelTreeNode) => {
  if (node.parental === 1) {
    selectOrg(node.channelId, node)
    return
  }
  creating.value = false
  selected.value = node
}

const selectOrg = async (code: string, node?: GbDeviceChannelTreeNode) => {
  treeSelectedCode.value = code
  treeSelectedNode.value = node ?? (code ? findTreeNode(treeRootNodes.value, code) : null)
  treeChannelPage.value = 1
  await loadTreeChannels()
}

const loadTreeChannels = async () => {
  if (!platformIdQ.value) {
    treeChannels.value = []
    treeChannelTotal.value = 0
    return
  }
  treeChannelLoading.value = true
  try {
    const res = await getGbChannelSubtreePageApi({
      page: treeChannelPage.value,
      pageSize: TREE_PAGE_SIZE,
      // 目录上报时 device_id 即平台编码（一个平台一个目录会话），树接口仍按 deviceId 查
      deviceId: platformIdQ.value,
      orgCode: treeSelectedCode.value || undefined,
      includeSubtree: includeSubtree.value,
      keyword: treeKeyword.value.trim() || undefined,
    })
    treeChannels.value = res.data?.rows ?? []
    treeChannelTotal.value = Number(res.data?.total ?? 0)
  } finally {
    treeChannelLoading.value = false
  }
}

const onToggleIncludeSubtree = (v: boolean) => {
  includeSubtree.value = v
  treeChannelPage.value = 1
  loadTreeChannels()
}

const gotoTreePage = (p: number) => {
  treeChannelPage.value = Math.min(Math.max(1, p), treeChannelPages.value)
  loadTreeChannels()
}

const onTreeChannelClick = (c: GbDeviceChannel) => {
  creating.value = false
  selected.value = c
}

let treeKeywordTimer: ReturnType<typeof setTimeout> | null = null
watch(treeKeyword, () => {
  if (treeKeywordTimer) clearTimeout(treeKeywordTimer)
  treeKeywordTimer = setTimeout(() => {
    treeChannelPage.value = 1
    loadTreeChannels()
  }, 300)
})

/** 手动刷新：重拉列表（保留当前筛选）+ 状态统计 */
const onRefresh = () =>
  activeKey.value === 'tree'
    ? Promise.all([loadRootTree(), fetchStatusCount()])
    : Promise.all([resource.load(true), fetchStatusCount()])

let debounceTimer: ReturnType<typeof setTimeout> | null = null
const triggerSearch = () => {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => {
    cursor.value = 0
    clearChecked()
    resource.search()
  }, 300)
}
watch([channelIdQ, nameQ], triggerSearch)
watch(platformIdQ, async () => {
  cursor.value = 0
  clearChecked()
  selected.value = null
  // 首次由 onMounted 统一加载，避免自动选平台触发重复请求
  if (!booted) return
  if (activeKey.value === 'tree') {
    await Promise.all([loadRootTree(), fetchStatusCount()])
    return
  }
  await Promise.all([resource.search(), fetchStatusCount()])
})
watch(sourceFilter, () => {
  cursor.value = 0
  clearChecked()
  resource.search()
})
watch(ptzFilter, () => {
  cursor.value = 0
  clearChecked()
  resource.search()
})

const onTab = (key: TabKey) => {
  if (activeKey.value === key) return
  activeKey.value = key
  cursor.value = 0
  clearChecked()
  // 目录树走独立接口（tree/children + subtree/page），不参与分页查询
  if (key === 'tree') {
    loadRootTree()
    return
  }
  resource.search()
}

const onClear = () => {
  channelIdQ.value = ''
  nameQ.value = ''
  // 下级平台是页面第一维度，清除只针对搜索/筛选条件
  activeKey.value = 'all'
  sourceFilter.value = '__all__'
  ptzFilter.value = '__all__'
  cursor.value = 0
  clearChecked()
  resource.search()
}

/* ---------------- 选择（勾选） ---------------- */
const checkedIds = ref<Set<string>>(new Set())

const pageIds = computed(() => resource.items.value.map((c) => c.id))
const allChecked = computed(
  () => pageIds.value.length > 0 && pageIds.value.every((id) => checkedIds.value.has(id)),
)
const indeterminate = computed(
  () => !allChecked.value && pageIds.value.some((id) => checkedIds.value.has(id)),
)

const onToggleAll = (v: boolean | 'indeterminate') => {
  const next = new Set(checkedIds.value)
  if (v === true) {
    pageIds.value.forEach((id) => next.add(id))
  } else {
    pageIds.value.forEach((id) => next.delete(id))
  }
  checkedIds.value = next
}

const onToggleOne = (c: GbDeviceChannel, checked: boolean) => {
  const next = new Set(checkedIds.value)
  if (checked) next.add(c.id)
  else next.delete(c.id)
  checkedIds.value = next
}

const clearChecked = () => {
  checkedIds.value = new Set()
}

/* ---------------- 检视器 ---------------- */
const cursor = ref(0)
const selected = ref<GbDeviceChannel | null>(null)
const creating = ref(false)

const scrollerRef = ref<HTMLElement>()
const sentinelRef = ref<HTMLElement>()
const channelIdRef = ref<HTMLInputElement>()

const firstLoading = computed(
  () => resource.loading.value && resource.items.value.length === 0,
)

const scrollCursorIntoView = () => {
  scrollerRef.value
    ?.querySelector(`[data-row="${cursor.value}"]`)
    ?.scrollIntoView({ block: 'nearest' })
}

const onRowClick = (c: GbDeviceChannel, i: number) => {
  cursor.value = i
  selected.value = c
  // 选行必须退出新建态，否则 inspector 会停留在"新建通道"表单
  creating.value = false
}

const onCloseInspector = () => {
  creating.value = false
  selected.value = null
}

const onCreate = () => {
  selected.value = null
  creating.value = true
}

const onSaved = () => resource.search()
const onCreated = () => {
  creating.value = false
  resource.search()
  fetchStatusCount()
}
const onDeleted = (c: GbDeviceChannel) => {
  resource.removeWhere((item) => item.id === c.id)
  const next = new Set(checkedIds.value)
  next.delete(c.id)
  checkedIds.value = next
  selected.value = null
  creating.value = false
  fetchStatusCount()
}

/* ---------------- 批删 ---------------- */
const batchDeleting = ref(false)
const onBatchDelete = async () => {
  if (!checkedIds.value.size || batchDeleting.value) return
  const ok = await confirm({
    title: `确定要删除选中的 ${checkedIds.value.size} 条通道吗？`,
    description: '此操作不可恢复。',
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  batchDeleting.value = true
  try {
    await batchDeleteGbDeviceChannelApi([...checkedIds.value])
    toast.success('删除成功')
    clearChecked()
    resource.search()
    fetchStatusCount()
    selected.value = null
  } finally {
    batchDeleting.value = false
  }
}

/* ---------------- 展示辅助 ---------------- */
const displayName = (c: GbDeviceChannel) => c.name || c.reportName || c.channelId

const statusText = (c: GbDeviceChannel) => {
  if (c.parental === 1) return '—'
  if (c.status === 'ON') return '在线'
  if (c.status === 'OFF') return '离线'
  return '未注册'
}

const SOURCE_TEXT: Record<number, string> = {
  1: '系统新增',
  2: '表格导入',
  3: '设备注册',
  4: '级联平台(历史)',
}
const sourceText = (source?: number) =>
  source == null ? '—' : SOURCE_TEXT[source] ?? '—'

const PTZ_TEXT: Record<number, string> = {
  0: '未知',
  1: '球机',
  2: '半球',
  3: '固定枪机',
  4: '遥控枪机',
}
const ptzText = (c: GbDeviceChannel) => {
  if (c.parental === 1 || c.ptzType == null) return '—'
  return PTZ_TEXT[c.ptzType] ?? '未知'
}

/* ---------------- 键盘流 ---------------- */
const isTypingTarget = (el: EventTarget | null) =>
  el instanceof HTMLElement &&
  (el.tagName === 'INPUT' ||
    el.tagName === 'TEXTAREA' ||
    el.tagName === 'SELECT' ||
    el.isContentEditable)

const onGlobalKeydown = (e: KeyboardEvent) => {
  if (isTypingTarget(e.target)) {
    if (e.key === 'Escape') (e.target as HTMLElement).blur()
    return
  }
  const list = resource.items.value
  // 目录树模式只保留刷新/关闭，避免 Enter 命中上一页缓存行
  if (activeKey.value === 'tree') {
    if (e.key === 'r' || e.key === 'R') {
      e.preventDefault()
      onRefresh()
    } else if (e.key === 'Escape') {
      onCloseInspector()
    }
    return
  }
  switch (e.key) {
    case '/':
      e.preventDefault()
      channelIdRef.value?.focus()
      break
    case 'j':
    case 'J':
      e.preventDefault()
      if (list.length) {
        cursor.value = Math.min(list.length - 1, cursor.value + 1)
        scrollCursorIntoView()
      }
      break
    case 'k':
    case 'K':
      e.preventDefault()
      cursor.value = Math.max(0, cursor.value - 1)
      scrollCursorIntoView()
      break
    case 'Enter':
      e.preventDefault()
      if (list[cursor.value]) onRowClick(list[cursor.value], cursor.value)
      break
    case 'n':
    case 'N':
      e.preventDefault()
      onCreate()
      break
    case 'r':
    case 'R':
      e.preventDefault()
      onRefresh()
      break
    case 'Escape':
      onCloseInspector()
      break
  }
}

/* ---------------- 生命周期 / observer ---------------- */
let observer: IntersectionObserver | null = null

/** 进入页面自动选中第一个已登记下级平台；平台多时用户可手工切换 */
const ensurePlatformSelected = async () => {
  if (platformIdQ.value) return
  try {
    const rows = (await getGbPlatformPageApi({ page: 1, pageSize: 200 })).data.rows ?? []
    const pick = rows.find((p) => p.status === 1) ?? rows.find((p) => p.enabled === 1) ?? rows[0]
    if (pick) platformIdQ.value = pick.platformId
  } catch {
    // 拉不到列表就保持未选，页面空态里有选择入口
  }
}

onMounted(async () => {
  window.addEventListener('keydown', onGlobalKeydown)
  await ensurePlatformSelected()
  await Promise.all([resource.load(true), fetchStatusCount()])
  booted = true
  observer = new IntersectionObserver(
    (entries) => {
      if (activeKey.value === 'tree') return
      if (entries[0].isIntersecting) resource.load(false)
    },
    { root: scrollerRef.value, rootMargin: '80px' },
  )
  if (sentinelRef.value) observer.observe(sentinelRef.value)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onGlobalKeydown)
  observer?.disconnect()
  if (debounceTimer) clearTimeout(debounceTimer)
})

watch(
  () => resource.items.value,
  (list) => {
    if (selected.value) {
      const fresh = list.find((c) => c.id === selected.value!.id)
      if (fresh) selected.value = fresh
    }
    if (cursor.value > list.length - 1) cursor.value = Math.max(0, list.length - 1)
  },
)
</script>

<style scoped>
.inspector-enter-active,
.inspector-leave-active {
  transition:
    opacity 0.18s ease,
    transform 0.18s ease;
}
.inspector-enter-from,
.inspector-leave-to {
  opacity: 0;
  transform: translateX(12px);
}

kbd {
  display: inline-block;
  border: 1px solid var(--border);
  border-radius: 3px;
  background: var(--muted);
  padding: 0 4px;
  font-size: 10px;
  line-height: 16px;
  color: var(--muted-foreground);
  font-family: ui-monospace, monospace;
}
</style>
