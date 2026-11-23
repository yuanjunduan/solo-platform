<template>
  <div class="flex h-full gap-3 overflow-hidden p-3">
    <ResOrgTreePanel
      title="资源组织"
      :rows="flatRows"
      :loading="loading"
      :selected-id="selectedId"
      :expanded-ids="expandedIds"
      :all-expanded="allExpanded"
      @select="onSelect"
      @toggle="toggle"
      @toggle-all="toggleAll"
      @refresh="fetchTree"
    >
      <template #footer>
        <div
          class="flex h-8 shrink-0 items-center gap-2 border-t border-border px-3 text-[11px] text-muted-foreground/70"
        >
          <span>{{ totals.orgs }} 个组织</span>
          <span>·</span>
          <span>设备 {{ totals.devices }}</span>
          <span>·</span>
          <span>通道 {{ totals.channels }}</span>
        </div>
      </template>
    </ResOrgTreePanel>

    <div class="flex min-w-0 flex-1 flex-col overflow-hidden rounded-xl border border-border bg-card">
      <div class="flex h-12 shrink-0 items-center gap-2 border-b border-border px-3">
        <span class="text-[13px] font-medium">{{ creating ? '新建组织' : '组织详情' }}</span>
        <span v-if="editing?.sourceType === 'INBOUND'" class="rounded bg-amber-500/10 px-1.5 py-0.5 text-[10px] text-amber-600">
          入站镜像 · 只读
        </span>
        <div class="flex-1" />
        <Button v-access="PERM.RES_ORG_ADD" variant="ghost" size="sm" class="gap-1.5" @click="startCreate()">
          <IconPlus class="size-3.5" />
          新增
        </Button>
      </div>

      <div class="min-h-0 flex-1 overflow-y-auto p-5">
        <template v-if="creating || editing">
          <div class="max-w-[520px] space-y-3">
            <div class="flex items-center gap-3">
              <Label class="w-20 shrink-0 text-xs text-muted-foreground">组织编码</Label>
              <div class="flex-1">
                <Input
                  v-model="form.code"
                  class="h-8 font-mono text-xs"
                  :disabled="!creating"
                  placeholder="本系统内唯一，建立后不可修改"
                />
              </div>
            </div>

            <div class="flex items-center gap-3">
              <Label class="w-20 shrink-0 text-xs text-muted-foreground">组织名称</Label>
              <div class="flex-1">
                <Input v-model="form.name" class="h-8 text-xs" placeholder="展示名称" />
              </div>
            </div>

            <div class="flex items-center gap-3">
              <Label class="w-20 shrink-0 text-xs text-muted-foreground">上级组织</Label>
              <div class="flex-1">
                <Select v-model="form.parentId" :disabled="editing?.sourceType === 'INBOUND'">
                  <SelectTrigger class="h-8 w-full text-xs">
                    <SelectValue placeholder="（根组织）" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="__root__">（根组织）</SelectItem>
                    <SelectItem v-for="opt in parentOptions" :key="opt.id" :value="opt.id">
                      {{ '　'.repeat(opt.depth) }}{{ opt.name }}
                    </SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>

            <div class="flex items-center gap-3">
              <Label class="w-20 shrink-0 text-xs text-muted-foreground">本地别名</Label>
              <div class="flex-1">
                <Input v-model="form.localName" class="h-8 text-xs" placeholder="同步不覆盖，可留空" />
              </div>
            </div>

            <div class="flex items-center gap-3">
              <Label class="w-20 shrink-0 text-xs text-muted-foreground">同级排序</Label>
              <div class="flex-1">
                <Input v-model.number="form.sortNo" type="number" min="0" class="h-8 font-mono text-xs" />
              </div>
            </div>

            <div class="flex justify-end gap-2 pt-2">
              <Button size="sm" variant="outline" @click="onClose">
                {{ editable ? '取消' : '关闭' }}
              </Button>
              <Button
                v-if="editable"
                v-access="creating ? PERM.RES_ORG_ADD : PERM.RES_ORG_EDIT"
                size="sm"
                :loading="saving"
                @click="onSave"
              >
                保存
              </Button>
            </div>
          </div>

          <div v-if="editing" class="mt-6 max-w-[520px] space-y-2 rounded-md border bg-muted/20 p-3 text-xs">
            <div class="flex items-center justify-between gap-3">
              <span class="text-muted-foreground">层级 / 路径</span>
              <span class="truncate font-mono">L{{ editing.level }} · {{ editing.path || '—' }}</span>
            </div>
            <div class="flex items-center justify-between gap-3">
              <span class="text-muted-foreground">直接归属</span>
              <span class="font-mono">设备 {{ editing.deviceCount ?? 0 }} · 通道 {{ editing.channelCount ?? 0 }}</span>
            </div>
            <div class="flex items-center justify-between gap-3">
              <span class="text-muted-foreground">来源 / 状态</span>
              <span class="font-mono">{{ editing.sourceType || 'NATIVE' }} · {{ editing.status || 'ACTIVE' }}</span>
            </div>
            <div class="flex justify-end pt-1">
              <Button
                v-access="PERM.RES_ORG_DELETE"
                size="sm"
                variant="ghost"
                class="text-red-500 hover:text-red-600"
                :disabled="editing.sourceType === 'INBOUND'"
                :title="editing.sourceType === 'INBOUND' ? '入站镜像组织由同步链路维护，不能删除' : '删除须先清空子组织与资源归属'"
                @click="onDelete"
              >
                <IconTrash class="mr-1 size-3.5" />
                删除组织
              </Button>
            </div>
          </div>
        </template>

        <div v-else class="flex h-full flex-col items-center justify-center gap-2 text-muted-foreground">
          <IconSitemap class="size-8" />
          <div class="text-[13px]">在左侧选择一个组织，或新建组织</div>
          <div class="text-[11px] text-muted-foreground/70">
            入站镜像组织（下级平台同步而来）在这里只能查看，不能改名 / 换父 / 删除
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { IconPlus, IconSitemap, IconTrash } from '@tabler/icons-vue'
import Button from '@/components/ui/button/Button.vue'
import Input from '@/components/ui/input/Input.vue'
import Label from '@/components/ui/label/Label.vue'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import ResOrgTreePanel from '@/features/res/ResOrgTreePanel.vue'
import { useResTree } from '@/features/res/useResTree'
import { PERM } from '@/features/access/permissions'
import { confirm } from '@/lib/confirm'
import { toast } from '@/lib/toast'
import { createResOrgApi, deleteResOrgApi, updateResOrgApi } from '@/api/res'
import type { ResOrgTreeNode } from '@/types/api'

const {
  tree,
  flatRows,
  loading,
  selectedId,
  selectedNode,
  totals,
  expandedIds,
  allExpanded,
  fetchTree,
  toggle,
  toggleAll,
  select,
} = useResTree()

const creating = ref(false)
const saving = ref(false)
const editing = computed(() => (creating.value ? null : selectedNode.value))
const editable = computed(() => creating.value || editing.value?.writable !== false)

const form = reactive({
  code: '',
  name: '',
  parentId: '__root__',
  localName: '',
  sortNo: 0,
})

/** 上级候选：排除自己与自己的整棵子树，避免把自己挂到自己下面 */
const parentOptions = computed(() => {
  const excluded = new Set<string>()
  const markSubtree = (node: ResOrgTreeNode) => {
    excluded.add(node.id)
    node.children?.forEach(markSubtree)
  }
  if (editing.value) markSubtree(editing.value)

  const acc: Array<{ id: string; name: string; depth: number }> = []
  const walk = (list: ResOrgTreeNode[], depth: number) => {
    list.forEach((n) => {
      if (!excluded.has(n.id)) acc.push({ id: n.id, name: n.name, depth })
      if (n.children?.length) walk(n.children, depth + 1)
    })
  }
  walk(tree.value, 0)
  return acc
})

const resetForm = () => {
  form.code = ''
  form.name = ''
  form.parentId = '__root__'
  form.localName = ''
  form.sortNo = 0
}

const startCreate = (parent?: ResOrgTreeNode) => {
  resetForm()
  form.parentId = parent?.id ?? selectedId.value ?? '__root__'
  creating.value = true
}

const onSelect = (id: string | null) => {
  creating.value = false
  select(id)
  const node = selectedNode.value
  if (!node) {
    resetForm()
    return
  }
  form.code = node.code
  form.name = node.name
  form.parentId = node.parentId ?? '__root__'
  form.localName = node.localName ?? ''
  form.sortNo = node.sortNo ?? 0
}

const onClose = () => {
  creating.value = false
  select(null)
}

const onSave = async () => {
  if (!form.code.trim() || !form.name.trim()) {
    toast.warning('组织编码与名称不能为空')
    return
  }
  saving.value = true
  try {
    const payload = {
      code: form.code.trim(),
      name: form.name.trim(),
      parentId: form.parentId === '__root__' ? undefined : form.parentId,
      localName: form.localName.trim() || undefined,
      sortNo: form.sortNo,
    }
    if (creating.value) {
      const created = await createResOrgApi(payload)
      toast.success('新建成功')
      creating.value = false
      await fetchTree()
      select(created.data?.id ?? null)
    } else if (editing.value) {
      await updateResOrgApi(editing.value.id, payload)
      toast.success('保存成功')
      await fetchTree()
    }
  } catch {
    // 拦截器已提示具体原因（编码重复 / 入站只读等）
  } finally {
    saving.value = false
  }
}

const onDelete = async () => {
  const node = editing.value
  if (!node) return
  const ok = await confirm({
    title: '删除组织',
    description: `确定删除组织「${node.name}」吗？组织下还有子组织或资源时会被拒绝。`,
    danger: true,
    confirmText: '删除',
  })
  if (!ok) return
  try {
    await deleteResOrgApi(node.id)
    toast.success('删除成功')
    select(null)
    creating.value = false
    await fetchTree()
  } catch {
    // 拦截器已提示（子组织 / 设备 / 通道未清空）
  }
}

onMounted(() => {
  void fetchTree()
})
</script>
