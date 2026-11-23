<template>
  <div class="flex h-full gap-3 overflow-hidden p-3">
    <!-- 左：来源组织树 -->
    <ResOrgTreePanel
      title="来源组织"
      :rows="orgRows"
      :loading="orgLoading"
      :selected-id="selectedOrgId"
      :expanded-ids="expandedIds"
      :all-expanded="allExpanded"
      @select="onSelectOrg"
      @toggle="toggleOrg"
      @toggle-all="toggleAllOrgs"
      @refresh="loadOrgTree"
    >
      <template #footer>
        <div class="border-t border-border px-3 py-2 text-[11px] leading-relaxed text-muted-foreground">
          选中组织即按「该组织含子树」共享；右侧通道列表会随之过滤。
        </div>
      </template>
    </ResOrgTreePanel>

    <!-- 中：可选通道 -->
    <div class="flex min-w-0 flex-1 flex-col overflow-hidden rounded-xl border border-border bg-card">
      <div class="flex h-12 shrink-0 items-center gap-2 border-b border-border px-3">
        <Select v-model="platformId">
          <SelectTrigger size="sm" class="w-44 text-xs">
            <SelectValue placeholder="选择目标上级" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem v-for="t in targets" :key="t.id" :value="t.id">
              {{ t.name }}
            </SelectItem>
          </SelectContent>
        </Select>
        <Input
          v-model="keyword"
          class="h-8 w-44 text-xs"
          placeholder="编码 / 名称"
          @update:model-value="triggerSearch"
        />
        <Select :model-value="sourceType ?? 'ALL'" @update:model-value="onSourceChange">
          <SelectTrigger size="sm" class="w-28 text-xs">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">全部来源</SelectItem>
            <SelectItem value="NATIVE">本地自建</SelectItem>
            <SelectItem value="INBOUND">下级入站</SelectItem>
          </SelectContent>
        </Select>
        <RefreshButton :loading="resource.loading.value" :on-refresh="refreshChannels" />
        <div class="flex-1" />
        <Button
          v-access="PERM.SHARE_COMPOSE_EDIT"
          variant="ghost"
          size="sm"
          class="gap-1.5"
          :disabled="!platformId"
          @click="openGenerate"
        >
          <IconSitemap class="size-3.5" />
          一键生成组织树
        </Button>
        <Button size="sm" variant="ghost" class="gap-1.5" :disabled="!platformId" @click="openShared">
          <IconList class="size-3.5" />
          已共享清单
        </Button>
      </div>

      <div ref="scrollerRef" class="min-h-0 flex-1 overflow-auto">
        <table class="w-full table-fixed border-collapse text-left text-[13px]">
          <thead class="sticky top-0 z-10">
            <tr class="bg-card text-xs text-muted-foreground">
              <th class="w-10 px-3 py-2 font-normal">
                <Checkbox :checked="allChecked" @update:checked="toggleAllChannels" />
              </th>
              <th class="w-44 px-3 py-2 font-normal">通道编码</th>
              <th class="w-48 px-3 py-2 font-normal">名称</th>
              <th class="w-32 px-3 py-2 font-normal">所属组织</th>
              <th class="w-32 px-3 py-2 font-normal">所属设备</th>
              <th class="w-16 px-3 py-2 font-normal">来源</th>
              <th class="w-40 px-3 py-2 font-normal">暴露码 / 共享状态</th>
              <th class="w-20 px-3 py-2 font-normal">操作</th>
            </tr>
          </thead>
          <tbody v-if="!resource.loading.value || resource.items.value.length">
            <tr
              v-for="row in resource.items.value"
              :key="row.channel.id"
              class="border-b border-border/50 hover:bg-muted/20"
            >
              <td class="px-3 py-1.5">
                <Checkbox
                  :checked="selectedChannelIds.includes(row.channel.id)"
                  :disabled="row.excluded"
                  @update:checked="(v: boolean | 'indeterminate') => toggleChannel(row.channel.id, v === true)"
                />
              </td>
              <td class="truncate px-3 py-1.5 font-mono text-xs" :title="row.channel.code">
                {{ row.channel.code }}
              </td>
              <td class="truncate px-3 py-1.5">
                {{ row.channel.localName || row.channel.name }}
                <span v-if="row.excluded" class="ml-1 rounded bg-amber-500/10 px-1 text-[10px] text-amber-600">已剔除</span>
                <span v-else-if="row.shared" class="ml-1 rounded bg-emerald-500/10 px-1 text-[10px] text-emerald-600">已共享</span>
              </td>
              <td class="truncate px-3 py-1.5 text-xs text-muted-foreground" :title="row.orgName ?? ''">
                {{ row.orgName || '—' }}
              </td>
              <td class="truncate px-3 py-1.5 text-xs text-muted-foreground" :title="row.deviceName ?? ''">
                {{ row.deviceName || '—' }}
              </td>
              <td class="px-3 py-1.5">
                <span
                  class="rounded px-1.5 py-0.5 text-[10px]"
                  :class="row.channel.sourceType === 'INBOUND' ? 'bg-amber-500/10 text-amber-600' : 'bg-muted text-muted-foreground'"
                >
                  {{ row.channel.sourceType === 'INBOUND' ? '入站' : '本地' }}
                </span>
              </td>
              <td class="truncate px-3 py-1.5 font-mono text-[11px] text-muted-foreground" :title="row.exposedCode ?? ''">
                {{ row.exposedCode || '未分配' }}
              </td>
              <td class="px-3 py-1.5">
                <div class="flex items-center gap-1">
                  <button
                    v-if="row.shared || row.excluded"
                    v-access="PERM.SHARE_COMPOSE_EDIT"
                    class="flex size-6 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-amber-600"
                    :title="row.excluded ? '取消剔除' : '剔除（保留派生关系）'"
                    @click="row.excluded ? onRestore(row.channel.id) : onExclude(row.channel.id)"
                  >
                    <IconArrowBackUp v-if="row.excluded" class="size-3.5" />
                    <IconBan v-else class="size-3.5" />
                  </button>
                  <span v-else class="text-[11px] text-muted-foreground/60">—</span>
                </div>
              </td>
            </tr>
          </tbody>
        </table>

        <div v-if="firstLoading" class="grid place-items-center py-16">
          <IconLoader2 class="size-5 animate-spin text-muted-foreground" />
        </div>
        <div
          v-else-if="!resource.loading.value && !resource.items.value.length"
          class="flex flex-col items-center gap-2 py-16 text-muted-foreground"
        >
          <IconList class="size-8" />
          <div class="text-[13px]">{{ platformId ? '该范围内还没有通道' : '先选择目标上级平台' }}</div>
          <div class="text-[11px] text-muted-foreground/70">目标上级在「级联平台」里注册，这里只列出已登记的上级</div>
        </div>

        <div ref="sentinelRef" class="grid place-items-center py-4">
          <IconLoader2
            v-if="resource.loading.value && resource.items.value.length"
            class="size-4 animate-spin text-muted-foreground"
          />
          <span v-else-if="resource.finished.value && resource.items.value.length" class="text-xs text-muted-foreground">
            已全部加载
          </span>
        </div>
      </div>

      <div class="flex h-8 shrink-0 items-center gap-3 border-t border-border px-3 text-[11px] text-muted-foreground/70">
        <span>共 {{ resource.total.value }} 路通道</span>
        <span>已勾选 {{ selectedChannelIds.length }} 路</span>
        <div class="flex-1" />
        <span>入站通道会重新编号后暴露给上级；本地通道直接透传</span>
      </div>
    </div>

    <!-- 右：本次提交 -->
    <div class="flex w-72 shrink-0 flex-col overflow-hidden rounded-xl border border-border bg-card">
      <div class="flex h-12 shrink-0 items-center gap-2 border-b border-border px-3">
        <IconShare class="size-4 text-muted-foreground" />
        <span class="text-[13px] font-medium">本次提交</span>
      </div>

      <div class="space-y-3 overflow-auto p-3">
        <div class="space-y-1 text-xs">
          <div class="flex items-center justify-between">
            <span class="text-muted-foreground">目标上级</span>
            <span class="truncate pl-2" :title="targetName">{{ targetName }}</span>
          </div>
          <div class="flex items-center justify-between">
            <span class="text-muted-foreground">子树组织</span>
            <span class="truncate pl-2" :title="selectedOrgName">{{ selectedOrgName }}</span>
          </div>
          <div class="flex items-center justify-between">
            <span class="text-muted-foreground">显式通道</span>
            <span class="font-mono">{{ selectedChannelIds.length }}</span>
          </div>
        </div>

        <div class="space-y-1.5">
          <div class="text-[11px] text-muted-foreground">挂到虚拟组织（空 = 根）</div>
          <Select :model-value="groupId ?? 'ROOT'" @update:model-value="onGroupChange">
            <SelectTrigger class="h-8 w-full text-xs">
              <SelectValue placeholder="根" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ROOT">根</SelectItem>
              <SelectItem v-for="g in groups" :key="g.id" :value="g.id">
                {{ '　'.repeat(Math.max(0, (g.level ?? 1) - 1)) + g.name }}
              </SelectItem>
            </SelectContent>
          </Select>
          <div v-if="!groups.length" class="text-[11px] text-muted-foreground/70">
            还没有虚拟组织，可先「一键生成组织树」
          </div>
        </div>

        <div class="rounded-md border border-border">
          <div class="flex items-center gap-2 border-b border-border px-2 py-1.5">
            <span class="text-[11px] font-medium">提交预览</span>
            <div class="flex-1" />
            <IconLoader2 v-if="previewLoading" class="size-3 animate-spin text-muted-foreground" />
            <span v-else-if="preview" class="text-[10px] text-muted-foreground">共 {{ preview.total }} 路</span>
          </div>
          <div v-if="preview" class="grid grid-cols-2 gap-x-2 gap-y-1 px-2 py-2 text-[11px]">
            <div class="flex justify-between"><span class="text-muted-foreground">已共享</span><span class="font-mono">{{ preview.shared }}</span></div>
            <div class="flex justify-between"><span class="text-muted-foreground">新增</span><span class="font-mono text-emerald-600">{{ preview.added }}</span></div>
            <div class="flex justify-between"><span class="text-muted-foreground">恢复</span><span class="font-mono text-sky-600">{{ preview.restored }}</span></div>
            <div class="flex justify-between"><span class="text-muted-foreground">剔除中</span><span class="font-mono text-amber-600">{{ preview.excluded }}</span></div>
            <div class="col-span-2 flex justify-between border-t border-border/60 pt-1">
              <span class="text-muted-foreground">需新分配暴露码</span>
              <span class="font-mono">{{ preview.renumber }}</span>
            </div>
          </div>
          <div v-else class="px-2 py-3 text-center text-[11px] text-muted-foreground">
            选择上级 / 组织 / 通道后自动计算
          </div>
        </div>

        <div v-if="preview && preview.conflicts.length" class="rounded-md border border-amber-500/30 bg-amber-500/5 p-2">
          <div class="mb-1 text-[11px] font-medium text-amber-600">防环冲突（{{ preview.conflicts.length }} 条，不会提交）</div>
          <div class="max-h-32 space-y-1 overflow-auto">
            <div v-for="c in preview.conflicts" :key="c.refId" class="text-[11px] leading-snug text-amber-700">
              <span class="font-mono">{{ c.code || c.refId }}</span>
              <span v-if="c.name"> · {{ c.name }}</span>
              <div class="text-amber-600/80">{{ c.reason }}</div>
            </div>
          </div>
        </div>
      </div>

      <div class="mt-auto space-y-1.5 border-t border-border p-3">
        <Button
          v-access="PERM.SHARE_COMPOSE_EDIT"
          size="sm"
          class="w-full gap-1.5"
          :disabled="!canSubmit || submitting"
          @click="onSubmit"
        >
          <IconLoader2 v-if="submitting" class="size-3.5 animate-spin" />
          <IconCheck v-else class="size-3.5" />
          提交本次共享
        </Button>
        <div class="text-center text-[10px] text-muted-foreground/70">
          提交会对该上级立即派生一次，不自动推送；推送在「推送记录」页手动或定时执行
        </div>
      </div>
    </div>

    <!-- 一键生成组织树 -->
    <Dialog :open="genOpen" @update:open="(v) => (genOpen = v)">
      <DialogContent class="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>一键生成虚拟组织树</DialogTitle>
          <DialogDescription>
            按来源组织子树镜像出该上级的虚拟组织；重复执行只增量更新，不会产生重复分组。
          </DialogDescription>
        </DialogHeader>
        <div class="space-y-3">
          <div class="flex items-center gap-3">
            <Label class="w-24 shrink-0 text-xs text-muted-foreground">来源组织</Label>
            <div class="flex-1 text-xs">{{ selectedOrgName }}</div>
          </div>
          <div class="flex items-center gap-3">
            <Label class="w-24 shrink-0 text-xs text-muted-foreground">挂到</Label>
            <div class="flex-1">
              <Select :model-value="genMountGroupId ?? 'ROOT'" @update:model-value="(v) => (genMountGroupId = v === 'ROOT' ? null : String(v))">
                <SelectTrigger class="h-8 w-full text-xs"><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="ROOT">该上级根目录</SelectItem>
                  <SelectItem v-for="g in groups" :key="g.id" :value="g.id">{{ g.path }}</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>
          <div v-if="!selectedOrgId" class="text-[11px] text-amber-600">先在左侧选中一个来源组织</div>
        </div>
        <DialogFooter>
          <Button size="sm" variant="outline" @click="genOpen = false">取消</Button>
          <Button size="sm" :disabled="!selectedOrgId || genSubmitting" @click="doGenerate">
            <IconLoader2 v-if="genSubmitting" class="mr-1 size-3.5 animate-spin" />
            开始生成
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <!-- 已共享清单（编排视图，只做剔除/恢复） -->
    <Dialog :open="sharedOpen" @update:open="(v) => (sharedOpen = v)">
      <DialogContent class="sm:max-w-4xl">
        <DialogHeader>
          <DialogTitle>已共享清单</DialogTitle>
          <DialogDescription>
            {{ targetName }} · 撤销单条请到「共享总览」操作
          </DialogDescription>
        </DialogHeader>

        <div class="flex items-center gap-2">
          <Input v-model="sharedKeyword" class="h-8 w-56 text-xs" placeholder="编码 / 组织路径" @update:model-value="triggerSharedSearch" />
          <div class="flex-1" />
          <span class="text-[11px] text-muted-foreground">共 {{ shared.total.value }} 条</span>
        </div>

        <div class="max-h-[26rem] overflow-auto rounded-md border">
          <table class="w-full border-collapse text-left text-[12px]">
            <thead class="sticky top-0 bg-muted/60">
              <tr class="text-muted-foreground">
                <th class="w-20 px-2 py-1.5 font-normal">类型</th>
                <th class="w-44 px-2 py-1.5 font-normal">资源</th>
                <th class="w-40 px-2 py-1.5 font-normal">挂载组织</th>
                <th class="w-20 px-2 py-1.5 font-normal">来源</th>
                <th class="w-24 px-2 py-1.5 font-normal">状态</th>
                <th class="w-24 px-2 py-1.5 font-normal">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in shared.items.value" :key="r.id" class="border-t border-border/50">
                <td class="px-2 py-1 font-mono">{{ refTypeText(r.refType) }}</td>
                <td class="truncate px-2 py-1" :title="r.refName ?? r.refCode ?? ''">
                  {{ r.refName || r.refCode || r.refId }}
                  <span class="ml-1 font-mono text-[10px] text-muted-foreground/70">{{ r.refCode }}</span>
                </td>
                <td class="truncate px-2 py-1 text-muted-foreground" :title="r.groupName ?? ''">{{ r.groupName || '根' }}</td>
                <td class="px-2 py-1 text-muted-foreground">{{ r.derived === 1 ? '规则派生' : '手工' }}</td>
                <td class="px-2 py-1">
                  <span :class="enabledClass(r)">{{ enabledText(r) }}</span>
                </td>
                <td class="px-2 py-1">
                  <div class="flex items-center gap-1">
                    <button
                      v-if="r.refType === 'CHANNEL' && r.enabled === 1"
                      v-access="PERM.SHARE_COMPOSE_EDIT"
                      class="flex size-6 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-amber-600"
                      title="剔除"
                      @click="onExclude(Number(r.refId))"
                    >
                      <IconBan class="size-3.5" />
                    </button>
                    <button
                      v-else-if="r.refType === 'CHANNEL' && r.enabled === 0"
                      v-access="PERM.SHARE_COMPOSE_EDIT"
                      class="flex size-6 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-emerald-600"
                      title="取消剔除"
                      @click="onRestore(Number(r.refId))"
                    >
                      <IconArrowBackUp class="size-3.5" />
                    </button>
                    <button
                      v-else-if="r.refType === 'ORG' && r.scopeMode === 'SUBTREE'"
                      v-access="PERM.SHARE_COMPOSE_EDIT"
                      class="flex size-6 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-red-500"
                      title="撤销该子树规则"
                      @click="onRemoveRule(r)"
                    >
                      <IconTrash class="size-3.5" />
                    </button>
                  </div>
                </td>
              </tr>
              <tr v-if="!shared.items.value.length">
                <td colspan="6" class="px-2 py-10 text-center text-muted-foreground">
                  {{ sharedLoading ? '加载中…' : '还没有共享项' }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <DialogFooter>
          <Button size="sm" variant="outline" :disabled="shared.finished.value" @click="shared.load(false)">加载更多</Button>
          <Button size="sm" variant="outline" @click="sharedOpen = false">关闭</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  IconArrowBackUp,
  IconBan,
  IconCheck,
  IconList,
  IconLoader2,
  IconShare,
  IconSitemap,
  IconTrash,
} from '@tabler/icons-vue'
import Button from '@/components/ui/button/Button.vue'
import Checkbox from '@/components/ui/checkbox/Checkbox.vue'
import Input from '@/components/ui/input/Input.vue'
import Label from '@/components/ui/label/Label.vue'
import RefreshButton from '@/components/RefreshButton.vue'
import ResOrgTreePanel from '@/features/res/ResOrgTreePanel.vue'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { useResource } from '@/shared/composables/useResource'
import { PERM } from '@/features/access/permissions'
import { confirm } from '@/lib/confirm'
import { toast } from '@/lib/toast'
import {
  addShareChannelsApi,
  addShareRuleApi,
  excludeShareChannelApi,
  generateShareGroupsApi,
  getShareGroupsApi,
  getShareOrgTreeApi,
  getShareTargetsApi,
  pageShareChannelsApi,
  pageShareItemsApi,
  previewShareApi,
  removeShareRuleApi,
  restoreShareChannelApi,
} from '@/api/share'
import type { FlatOrgRow } from '@/features/res/useResTree'
import type {
  OutGroup,
  OutShare,
  ResourceStatus,
  ShareChannelOption,
  ShareChannelQuery,
  ShareItemQuery,
  ShareOrgTreeNode,
  SharePreview,
  ShareTarget,
  SourceType,
} from '@/types/api'

/* ---------------- 目标上级 ---------------- */
const targets = ref<ShareTarget[]>([])
const platformId = ref<string | null>(null)

const targetName = computed(() => targets.value.find((t) => t.id === platformId.value)?.name ?? '未选择')

/* ---------------- 虚拟组织 ---------------- */
const groups = ref<OutGroup[]>([])
const groupId = ref<string | null>(null)

const loadGroups = async () => {
  if (!platformId.value) {
    groups.value = []
    return
  }
  try {
    groups.value = (await getShareGroupsApi(platformId.value)).data ?? []
  } catch {
    groups.value = []
  }
}

const onGroupChange = (v: unknown) => {
  groupId.value = v === 'ROOT' ? null : String(v)
}

/* ---------------- 来源组织树 ---------------- */
const orgTree = ref<ShareOrgTreeNode[]>([])
const orgLoading = ref(false)
const expandedIds = ref<Set<string>>(new Set())
const selectedOrgId = ref<string | null>(null)
let orgInitialized = false

const collectIds = (list: ShareOrgTreeNode[], acc: string[] = []): string[] => {
  list.forEach((n) => {
    acc.push(n.id)
    if (n.children?.length) collectIds(n.children, acc)
  })
  return acc
}

const orgRows = computed<FlatOrgRow[]>(() => {
  const acc: FlatOrgRow[] = []
  const walk = (list: ShareOrgTreeNode[], depth: number) => {
    for (const n of list) {
      acc.push({ node: n, depth })
      if (n.children?.length && expandedIds.value.has(n.id)) walk(n.children, depth + 1)
    }
  }
  walk(orgTree.value, 0)
  return acc
})

const allExpanded = computed(
  () => orgRows.value.length > 0 && collectIds(orgTree.value).every((id) => expandedIds.value.has(id)),
)

const findNode = (list: ShareOrgTreeNode[], id: string): ShareOrgTreeNode | undefined => {
  for (const n of list) {
    if (n.id === id) return n
    if (n.children?.length) {
      const hit = findNode(n.children, id)
      if (hit) return hit
    }
  }
}

const selectedOrgName = computed(() =>
  selectedOrgId.value ? (findNode(orgTree.value, selectedOrgId.value)?.name ?? `#${selectedOrgId.value}`) : '未选择',
)

const loadOrgTree = async () => {
  orgLoading.value = true
  try {
    orgTree.value = (await getShareOrgTreeApi()).data ?? []
    if (!orgInitialized) {
      expandedIds.value = new Set(collectIds(orgTree.value))
      orgInitialized = true
    }
  } finally {
    orgLoading.value = false
  }
}

const toggleOrg = (id: string) => {
  const next = new Set(expandedIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expandedIds.value = next
}

const toggleAllOrgs = () => {
  expandedIds.value = allExpanded.value ? new Set() : new Set(collectIds(orgTree.value))
}

const onSelectOrg = (id: string | null) => {
  selectedOrgId.value = id
  resource.search()
}

/* ---------------- 通道列表 ---------------- */
const keyword = ref('')
const sourceType = ref<SourceType | null>(null)

const resource = useResource<ShareChannelQuery, ShareChannelOption>({
  fetcher: (q) =>
    pageShareChannelsApi({
      ...q,
      platformId: platformId.value ?? undefined,
      orgId: selectedOrgId.value ?? undefined,
      includeSubtree: true,
    }),
  initial: { page: 1, pageSize: 30, keyword: '', status: 'ACTIVE' as ResourceStatus },
})

const firstLoading = computed(() => resource.loading.value && resource.items.value.length === 0)
const refreshChannels = () => resource.load(true)

let searchTimer: ReturnType<typeof setTimeout> | undefined
const triggerSearch = () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    resource.query.keyword = keyword.value
    resource.search()
  }, 300)
}

const onSourceChange = (v: unknown) => {
  sourceType.value = v === 'ALL' ? null : (v as SourceType)
  resource.query.sourceType = sourceType.value ?? undefined
  resource.search()
}

/* ---------------- 勾选 ---------------- */
const selectedChannelIds = ref<string[]>([])

const toggleChannel = (id: string, checked: boolean) => {
  if (checked) {
    if (!selectedChannelIds.value.includes(id)) selectedChannelIds.value = [...selectedChannelIds.value, id]
  } else {
    selectedChannelIds.value = selectedChannelIds.value.filter((x) => x !== id)
  }
}

const allChecked = computed(
  () =>
    resource.items.value.length > 0 &&
    resource.items.value.filter((r) => !r.excluded).every((r) => selectedChannelIds.value.includes(r.channel.id)),
)

const toggleAllChannels = (v: boolean | 'indeterminate') => {
  const ids = resource.items.value.filter((r) => !r.excluded).map((r) => r.channel.id)
  if (v === true) {
    selectedChannelIds.value = Array.from(new Set([...selectedChannelIds.value, ...ids]))
  } else {
    selectedChannelIds.value = selectedChannelIds.value.filter((id) => !ids.includes(id))
  }
}

/* ---------------- 预览 ---------------- */
const preview = ref<SharePreview | null>(null)
const previewLoading = ref(false)
let previewTimer: ReturnType<typeof setTimeout> | undefined

const runPreview = async () => {
  if (!platformId.value) {
    preview.value = null
    return
  }
  previewLoading.value = true
  try {
    preview.value = (await previewShareApi({
      platformId: platformId.value,
      orgIds: selectedOrgId.value ? [selectedOrgId.value] : [],
      channelIds: selectedChannelIds.value,
    })).data
  } catch {
    preview.value = null
  } finally {
    previewLoading.value = false
  }
}

const schedulePreview = () => {
  clearTimeout(previewTimer)
  previewTimer = setTimeout(runPreview, 350)
}

watch([platformId, selectedOrgId, selectedChannelIds], schedulePreview)

/* ---------------- 提交 ---------------- */
const submitting = ref(false)
const canSubmit = computed(() => !!platformId.value && (!!selectedOrgId.value || selectedChannelIds.value.length > 0))

const onSubmit = async () => {
  if (!platformId.value) return
  const ok = await confirm({
    title: '提交本次共享',
    description: `将对该上级立即派生一次共享关系，涉及 ${selectedChannelIds.value.length} 路显式通道${selectedOrgId.value ? '与 1 条子树规则' : ''}。提交本身不推送，推送在「推送记录」页执行。`,
    confirmText: '提交',
  })
  if (!ok) return
  submitting.value = true
  try {
    let added = 0
    if (selectedOrgId.value) {
      const r = await addShareRuleApi({ platformId: platformId.value, orgId: selectedOrgId.value, groupId: groupId.value })
      added += r.data.added
    }
    if (selectedChannelIds.value.length) {
      const r = await addShareChannelsApi({ platformId: platformId.value, channelIds: selectedChannelIds.value, groupId: groupId.value })
      added += r.data.added
    }
    toast.success(`已提交，新增 ${added} 路共享通道`)
    selectedChannelIds.value = []
    await Promise.all([resource.load(true), loadGroups()])
    await runPreview()
  } finally {
    submitting.value = false
  }
}

/* ---------------- 剔除 / 恢复 ---------------- */
const onExclude = async (channelId?: string | number | null) => {
  const id = channelId === null || channelId === undefined ? '' : String(channelId)
  if (!platformId.value || !id) return
  const ok = await confirm({
    title: '剔除该通道',
    description: '剔除后上级不再看到该通道，但派生关系保留；可随时取消剔除。',
    confirmText: '剔除',
  })
  if (!ok) return
  try {
    await excludeShareChannelApi({ platformId: platformId.value, channelId: id })
    toast.success('已剔除')
    await resource.load(true)
    if (sharedOpen.value) await shared.search()
    schedulePreview()
  } catch {
    // 拦截器已提示
  }
}

const onRestore = async (channelId?: string | number | null) => {
  const id = channelId === null || channelId === undefined ? '' : String(channelId)
  if (!platformId.value || !id) return
  try {
    await restoreShareChannelApi({ platformId: platformId.value, channelId: id })
    toast.success('已取消剔除')
    await resource.load(true)
    if (sharedOpen.value) await shared.search()
    schedulePreview()
  } catch {
    // 拦截器已提示
  }
}

const onRemoveRule = async (r: OutShare) => {
  if (!platformId.value || !r.refId) return
  const ok = await confirm({
    title: '撤销子树规则',
    description: `将撤销该组织的子树共享规则，由它派生的通道会随之撤销（人工勾选的通道保留）。`,
    confirmText: '撤销规则',
  })
  if (!ok) return
  try {
    const res = await removeShareRuleApi(platformId.value, r.refId)
    toast.success(`已撤销规则，移除派生 ${res.data.removed} 路`)
    await shared.search()
    await resource.load(true)
    schedulePreview()
  } catch {
    // 拦截器已提示
  }
}

/* ---------------- 一键生成组织树 ---------------- */
const genOpen = ref(false)
const genMountGroupId = ref<string | null>(null)
const genSubmitting = ref(false)

const openGenerate = () => {
  if (!selectedOrgId.value) {
    toast.error('请先在左侧选中一个来源组织')
    return
  }
  genMountGroupId.value = groupId.value
  genOpen.value = true
}

const doGenerate = async () => {
  if (!platformId.value || !selectedOrgId.value) return
  genSubmitting.value = true
  try {
    const r = await generateShareGroupsApi({
      platformId: platformId.value,
      sourceOrgId: selectedOrgId.value,
      mountGroupId: genMountGroupId.value,
    })
    toast.success(`已生成：新建 ${r.data.created}、更新 ${r.data.updated}、移动 ${r.data.moved}`)
    genOpen.value = false
    await loadGroups()
  } finally {
    genSubmitting.value = false
  }
}

/* ---------------- 已共享清单 ---------------- */
const sharedOpen = ref(false)
const sharedKeyword = ref('')
const sharedLoading = computed(() => shared.loading.value)

const shared = useResource<ShareItemQuery, OutShare>({
  fetcher: (q) =>
    pageShareItemsApi({
      ...q,
      platformId: platformId.value ?? undefined,
      refAlive: 1,
      enabled: undefined,
      status: undefined,
      refType: undefined,
    }),
  initial: { page: 1, pageSize: 30, keyword: '' },
})

let sharedTimer: ReturnType<typeof setTimeout> | undefined
const triggerSharedSearch = () => {
  clearTimeout(sharedTimer)
  sharedTimer = setTimeout(() => {
    shared.query.keyword = sharedKeyword.value
    shared.search()
  }, 300)
}

const openShared = async () => {
  if (!platformId.value) return
  sharedOpen.value = true
  sharedKeyword.value = ''
  shared.query.keyword = ''
  await shared.search()
}

/* ---------------- 平台切换 ---------------- */
watch(platformId, async (v) => {
  groupId.value = null
  selectedChannelIds.value = []
  selectedOrgId.value = null
  preview.value = null
  if (!v) {
    groups.value = []
    return
  }
  await Promise.all([loadGroups(), resource.search()])
})

const refTypeText = (t: string) => ({ ORG: '组织', DEVICE: '设备', CHANNEL: '通道' })[t] ?? t
const enabledText = (r: OutShare) => (r.enabled === 0 ? '已剔除' : '可见')
const enabledClass = (r: OutShare) => (r.enabled === 0 ? 'text-amber-600' : 'text-emerald-600')

/* ---------------- 无限滚动 ---------------- */
const scrollerRef = ref<HTMLElement>()
const sentinelRef = ref<HTMLElement>()
let observer: IntersectionObserver | null = null

onMounted(async () => {
  try {
    targets.value = (await getShareTargetsApi()).data ?? []
  } catch {
    targets.value = []
  }
  await loadOrgTree()
  await resource.load(true)
  observer = new IntersectionObserver(
    (entries) => {
      if (entries[0].isIntersecting) resource.load(false)
    },
    { root: scrollerRef.value, rootMargin: '80px' },
  )
  if (sentinelRef.value) observer.observe(sentinelRef.value)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  clearTimeout(searchTimer)
  clearTimeout(sharedTimer)
  clearTimeout(previewTimer)
})
</script>
