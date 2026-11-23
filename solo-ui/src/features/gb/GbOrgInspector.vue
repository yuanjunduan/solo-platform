<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { IconArrowUpRight, IconBuildingCommunity, IconPlus, IconTrash, IconX } from '@tabler/icons-vue'
import Button from '@/components/ui/button/Button.vue'
import type { GbOrgSaveRequest, GbOrgTreeNode } from '@/types/api'
import { saveGbOrgApi, updateGbOrgApi } from '@/api/gb28181'
import { PERM } from '@/features/access/permissions'
import { toast } from '@/lib/toast'

const props = defineProps<{
  /** 编辑时传入；新建为 undefined */
  org?: GbOrgTreeNode
  /** 新建时的预置父组织（"新增下级"） */
  presetParentId?: string
  /** 组织树，供父组织选择 */
  tree: GbOrgTreeNode[]
  /** 新建态 */
  creating?: boolean
}>()

const emit = defineEmits<{
  close: []
  saved: []
  created: []
  deleted: [org: GbOrgTreeNode]
  addChild: [org: GbOrgTreeNode]
  /** 下钻到设备页/通道页看该组织直接归属的资源 */
  drill: [kind: 'device' | 'channel', org: GbOrgTreeNode]
}>()

interface OrgOption {
  id: string
  name: string
  depth: number
}

/** 扁平化树供父组织下拉；排除自身与自身子树（换父到自己下面会成环） */
const parentOptions = computed<OrgOption[]>(() => {
  const self = props.org
  const acc: OrgOption[] = []
  const walk = (list: GbOrgTreeNode[], depth: number) => {
    for (const n of list) {
      const inSelfSubtree =
        !!self && (n.id === self.id || (!!self.path && !!n.path && n.path.startsWith(self.path)))
      if (!inSelfSubtree) acc.push({ id: n.id, name: n.name, depth })
      if (n.children?.length) walk(n.children, depth + 1)
    }
  }
  walk(props.tree, 0)
  return acc
})

const form = reactive({
  parentId: '0',
  name: '',
  code: '',
  sort: 0,
  remark: '',
})
const errors = reactive({ name: '', code: '' })
const saving = ref(false)

/** 新建时默认挂在根组织下（parentId=0 是"根之外"，正常业务不这么挂） */
const defaultParentId = () => props.presetParentId ?? props.tree[0]?.id ?? '0'

const resetForm = () => {
  if (props.org) {
    Object.assign(form, {
      parentId: props.org.parentId || '0',
      name: props.org.name,
      code: props.org.code ?? '',
      sort: props.org.sort ?? 0,
      remark: props.org.remark ?? '',
    })
  } else {
    Object.assign(form, {
      parentId: defaultParentId(),
      name: '',
      code: '',
      sort: 0,
      remark: '',
    })
  }
  errors.name = ''
  errors.code = ''
}

watch(() => [props.org, props.creating, props.presetParentId], resetForm, { immediate: true })

const validate = () => {
  const name = form.name.trim()
  errors.name = name ? (name.length > 64 ? '不能超过 64 个字符' : '') : '请输入组织名称'
  const code = form.code.trim()
  errors.code = !code || /^\d{20}$/.test(code) ? '' : '国标编码必须是 20 位数字'
  return !errors.name && !errors.code
}

const onSave = async () => {
  if (!validate()) return
  const payload: GbOrgSaveRequest = {
    parentId: form.parentId || '0',
    name: form.name.trim(),
    code: form.code.trim() || null,
    sort: form.sort ?? 0,
    remark: form.remark.trim() || null,
  }
  saving.value = true
  try {
    if (props.org) {
      await updateGbOrgApi(props.org.id, payload)
      toast.success('保存成功')
      emit('saved')
    } else {
      await saveGbOrgApi(payload)
      toast.success('创建成功')
      emit('created')
    }
  } catch {
    // 具体原因（同名、编码被占用、移到自己下级）由请求拦截器统一提示
  } finally {
    saving.value = false
  }
}

const isDefaultRoot = computed(() => props.org?.id === '1')
</script>

<template>
  <div class="flex h-full flex-col">
    <!-- 头部 -->
    <div class="flex items-center gap-3">
      <div class="grid h-11 w-11 shrink-0 place-items-center rounded-full bg-primary/10 text-primary">
        <IconBuildingCommunity class="h-5 w-5" />
      </div>
      <div class="min-w-0 flex-1">
        <div class="truncate text-base font-semibold">
          {{ org ? org.name : '新建组织' }}
        </div>
        <div class="truncate text-xs text-muted-foreground">
          {{ org ? (org.code || '未设置国标编码') : '本地域资源的归属节点' }}
        </div>
      </div>
      <Button
        v-if="org"
        v-access="PERM.GB_ORG_ADD"
        variant="ghost"
        size="icon-sm"
        title="新增下级组织"
        @click="emit('addChild', org)"
      >
        <IconPlus class="h-4 w-4" />
      </Button>
      <Button variant="ghost" size="icon-sm" @click="emit('close')">
        <IconX class="h-4 w-4" />
      </Button>
    </div>

    <div class="my-4 h-px bg-border" />

    <!-- 只读概况（编辑态） -->
    <div v-if="org" class="mb-4 grid grid-cols-2 gap-2 rounded-lg bg-muted/40 p-3 text-xs">
      <div class="flex items-center justify-between">
        <span class="text-muted-foreground">层级</span>
        <span>L{{ org.level }}</span>
      </div>
      <div class="flex items-center justify-between">
        <span class="text-muted-foreground">直接设备</span>
        <button
          v-if="org.directDeviceCount"
          type="button"
          class="inline-flex items-center gap-0.5 text-primary hover:underline"
          title="到设备页查看该组织直接归属的设备"
          @click="emit('drill', 'device', org)"
        >
          {{ org.directDeviceCount }} 台
          <IconArrowUpRight class="size-3" />
        </button>
        <span v-else class="text-muted-foreground/60">0 台</span>
      </div>
      <div class="flex items-center justify-between">
        <span class="text-muted-foreground">直接通道</span>
        <button
          v-if="org.directChannelCount"
          type="button"
          class="inline-flex items-center gap-0.5 text-primary hover:underline"
          title="到通道页查看该组织直接归属的通道"
          @click="emit('drill', 'channel', org)"
        >
          {{ org.directChannelCount }} 路
          <IconArrowUpRight class="size-3" />
        </button>
        <span v-else class="text-muted-foreground/60">0 路</span>
      </div>
      <div class="flex items-center justify-between">
        <span class="text-muted-foreground">排序</span>
        <span>{{ org.sort }}</span>
      </div>
      <div class="col-span-2 truncate font-mono text-[11px] text-muted-foreground" :title="org.path">
        {{ org.path }}
      </div>
    </div>

    <!-- 表单 -->
    <div class="space-y-3">
      <div class="grid gap-1.5">
        <label class="text-xs text-muted-foreground"><span class="text-destructive">*</span> 组织名称</label>
        <input
          v-model="form.name"
          class="h-8 rounded-md border border-input bg-transparent px-2 text-xs outline-none focus:border-ring"
          :class="errors.name ? 'border-destructive' : ''"
          placeholder="如：一号楼"
        />
        <span v-if="errors.name" class="text-[10px] text-destructive">{{ errors.name }}</span>
      </div>

      <div class="grid gap-1.5">
        <label class="text-xs text-muted-foreground">父组织</label>
        <select
          v-model="form.parentId"
          class="h-8 rounded-md border border-input bg-transparent px-1.5 text-xs outline-none focus:border-ring"
        >
          <option value="0">（根级，不推荐）</option>
          <option v-for="o in parentOptions" :key="o.id" :value="o.id">
            {{ '　'.repeat(o.depth) }}{{ o.name }}
          </option>
        </select>
      </div>

      <div class="grid gap-1.5">
        <label class="text-xs text-muted-foreground">国标编码</label>
        <input
          v-model="form.code"
          class="h-8 rounded-md border border-input bg-transparent px-2 font-mono text-xs outline-none focus:border-ring"
          :class="errors.code ? 'border-destructive' : ''"
          placeholder="20 位数字，可留空"
          maxlength="20"
        />
        <span v-if="errors.code" class="text-[10px] text-destructive">{{ errors.code }}</span>
        <span v-else class="text-[10px] text-muted-foreground/70">
          用于向上级平台推送时作为组织目录的国标编码，平台内唯一
        </span>
      </div>

      <div class="grid gap-1.5">
        <label class="text-xs text-muted-foreground">同级排序</label>
        <input
          v-model.number="form.sort"
          type="number"
          min="0"
          class="h-8 rounded-md border border-input bg-transparent px-2 text-xs outline-none focus:border-ring"
        />
      </div>

      <div class="grid gap-1.5">
        <label class="text-xs text-muted-foreground">备注</label>
        <textarea
          v-model="form.remark"
          rows="3"
          maxlength="255"
          class="resize-none rounded-md border border-input bg-transparent px-2 py-1.5 text-xs outline-none focus:border-ring"
          placeholder="可选"
        />
      </div>
    </div>

    <!-- 操作 -->
    <div class="mt-5 flex gap-2">
      <Button variant="outline" size="sm" class="flex-1" @click="emit('close')">取消</Button>
      <Button size="sm" class="flex-1" :disabled="saving" @click="onSave">
        {{ org ? '保存' : '创建' }}
      </Button>
    </div>

    <!-- 危险操作 -->
    <template v-if="org">
      <div class="my-4 h-px bg-border" />
      <section>
        <h4 class="mb-2.5 text-[13px] font-medium text-destructive">危险操作</h4>
        <Button
          v-access="PERM.GB_ORG_DELETE"
          variant="destructive"
          size="sm"
          class="w-full"
          :disabled="isDefaultRoot"
          :title="isDefaultRoot ? '默认根组织不可删除' : undefined"
          @click="emit('deleted', org)"
        >
          <IconTrash class="h-3.5 w-3.5" />
          {{ isDefaultRoot ? '默认根组织不可删除' : '删除该组织' }}
        </Button>
        <p class="mt-2 text-[11px] leading-relaxed text-muted-foreground/70">
          组织下还有子组织、设备或单独归属的通道时会被拒绝，需要先把资源移出。
        </p>
      </section>
    </template>
  </div>
</template>
