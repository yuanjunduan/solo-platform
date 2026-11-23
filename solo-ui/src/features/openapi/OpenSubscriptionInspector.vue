<template>
  <div class="flex h-full flex-col">
    <!-- 头部 -->
    <div class="flex items-center gap-3">
      <div
        class="grid h-11 w-11 shrink-0 place-items-center rounded-full bg-primary/10 text-primary"
      >
        <IconBroadcast class="h-5 w-5" />
      </div>
      <div class="min-w-0 flex-1">
        <div class="truncate text-base font-semibold">
          {{ sub?.appName || sub?.appKey }}
        </div>
        <div class="truncate font-mono text-xs text-muted-foreground">
          {{ sub?.appKey }} · {{ sub?.topic }}
        </div>
      </div>
      <Button variant="ghost" size="icon-sm" @click="emit('close')">
        <IconX class="h-4 w-4" />
      </Button>
    </div>

    <div class="my-4 h-px bg-border" />

    <!-- 推送范围 -->
    <section>
      <h4 class="mb-3.5 text-[13px] font-medium">推送范围</h4>
      <div class="space-y-3">
        <div class="flex gap-3">
          <Label class="w-20 shrink-0 pt-1.5 text-xs text-muted-foreground">来源平台</Label>
          <div class="min-w-0 flex-1">
            <Select :model-value="platformValue" @update:model-value="onPlatformChange">
              <SelectTrigger class="h-8 text-xs">
                <SelectValue placeholder="全部平台" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="__all__">全部平台</SelectItem>
                <SelectItem v-for="p in platformOptions" :key="p.id" :value="p.id">
                  {{ p.name }}
                </SelectItem>
              </SelectContent>
            </Select>
            <p class="mt-1 text-[11px] text-muted-foreground">
              只推送这个平台同步进来的资源变更；选「全部平台」则不按来源裁剪
            </p>
          </div>
        </div>

        <div class="flex gap-3">
          <Label class="w-20 shrink-0 pt-1.5 text-xs text-muted-foreground">组织范围</Label>
          <div class="min-w-0 flex-1">
            <Input
              v-model="orgPathText"
              class="h-8 font-mono text-xs"
              placeholder="/1/5/ 前缀，留空为整租户"
            />
            <p class="mt-1 text-[11px] text-muted-foreground">
              填组织物化路径前缀（首尾斜杠），如 <span class="font-mono">/1/5/</span>；
              留空表示整个租户。变更事件按该前缀二次过滤后才下发
            </p>
            <p v-if="orgPathError" class="mt-1 text-[11px] text-destructive">{{ orgPathError }}</p>
          </div>
        </div>

        <div class="flex items-center gap-3">
          <Label class="w-20 shrink-0 text-xs text-muted-foreground">订阅状态</Label>
          <Switch :model-value="form.status === 1" @update:model-value="onStatusChange" />
          <span class="text-xs" :class="form.status === 1 ? 'text-emerald-500' : 'text-muted-foreground'">
            {{ form.status === 1 ? '启用中，可正常推送' : '已停用，连接会被拒绝' }}
          </span>
        </div>

        <div class="flex gap-3">
          <Label class="w-20 shrink-0 pt-1.5 text-xs text-muted-foreground">备注</Label>
          <Input
            v-model="remarkText"
            class="h-8 flex-1 text-xs"
            placeholder="为什么给这么宽的授权（可选）"
          />
        </div>
      </div>
    </section>

    <div class="mt-4 flex justify-end gap-2">
      <Button size="sm" variant="outline" @click="emit('close')">取消</Button>
      <Button v-access="PERM.OPENAPI_SUB_EDIT" size="sm" :loading="saving" @click="onSave">
        保存
      </Button>
    </div>

    <!-- 运行状态 -->
    <template v-if="sub">
      <div class="my-4 h-px bg-border" />

      <section class="space-y-2 text-xs">
        <h4 class="mb-1 text-[13px] font-medium">运行状态</h4>
        <div class="flex items-center justify-between">
          <span class="text-muted-foreground">服务端游标</span>
          <span class="font-mono">{{ sub.lastEventId || '0' }}</span>
        </div>
        <div class="flex items-center justify-between">
          <span class="text-muted-foreground">来源平台</span>
          <span>{{ sub.platformName || '全部平台' }}</span>
        </div>
        <div class="flex items-center justify-between">
          <span class="text-muted-foreground">组织范围</span>
          <span class="font-mono">{{ sub.orgPath || '整租户' }}</span>
        </div>
        <div class="flex items-center justify-between">
          <span class="text-muted-foreground">最近更新</span>
          <span class="font-mono">{{ sub.updateTime || '—' }}</span>
        </div>

        <div class="pt-2">
          <Button
            v-access="PERM.OPENAPI_SUB_EDIT"
            variant="outline"
            size="sm"
            class="w-full"
            :loading="resetting"
            @click="onReset"
          >
            <IconRefresh class="h-3.5 w-3.5" />
            重置游标（跳到最新，不回放历史）
          </Button>
          <p class="mt-1 text-[11px] text-muted-foreground">
            数据修复后让订户从当前位置重新开始；要补历史请让订户重拉
            <span class="font-mono">/open-api/v1/snapshot</span>
          </p>
        </div>
      </section>

      <div v-if="widened" class="mt-4 rounded-md border border-amber-500/40 bg-amber-500/10 p-2.5">
        <div class="flex items-start gap-1.5 text-[11px] text-amber-600 dark:text-amber-400">
          <IconAlertTriangle class="mt-px h-3.5 w-3.5 shrink-0" />
          <span>
            这次改动放宽了可见范围：订户重连后只会收到此后的变更，
            期间漏掉的资源需要它自己重拉快照
          </span>
        </div>
      </div>

      <div class="mt-3 rounded-md border border-border bg-muted/40 p-2.5 text-[11px] text-muted-foreground">
        scope 即授权：推送前会按平台与组织前缀再过滤一次，越界的变更不会下发
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import type { AcceptableValue } from 'reka-ui'
import { IconBroadcast, IconX, IconRefresh, IconAlertTriangle } from '@tabler/icons-vue'
import type { EventSubscription, EventSubscriptionSaveRequest } from '@/types/api'
import { updateSubscriptionApi, resetSubscriptionCursorApi } from '@/api/openapi'
import { getPlatformListApi } from '@/api/platform'
import { PERM } from '@/features/access/permissions'
import { toast } from '@/lib/toast'
import { confirm } from '@/lib/confirm'
import Button from '@/components/ui/button/Button.vue'
import Input from '@/components/ui/input/Input.vue'
import Label from '@/components/ui/label/Label.vue'
import Switch from '@/components/ui/switch/Switch.vue'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'

const props = defineProps<{ sub?: EventSubscription }>()
const emit = defineEmits<{
  close: []
  changed: []
}>()

interface PlatformOption {
  id: string
  name: string
}

const platforms = ref<PlatformOption[]>([])
const saving = ref(false)
const resetting = ref(false)
const orgPathError = ref('')

/**
 * Input / Select 的 v-model 都不吃 null，所以文本类字段单独用 string ref，
 * 提交时再规范成 null（null = 不裁剪，这个语义不能丢）。
 */
const form = reactive<{ platformId: string | null; status: number }>({
  platformId: props.sub?.platformId ?? null,
  status: props.sub?.status ?? 1,
})
const orgPathText = ref(props.sub?.orgPath ?? '')
const remarkText = ref(props.sub?.remark ?? '')

watch(
  () => props.sub?.id,
  () => {
    form.platformId = props.sub?.platformId ?? null
    form.status = props.sub?.status ?? 1
    orgPathText.value = props.sub?.orgPath ?? ''
    remarkText.value = props.sub?.remark ?? ''
    orgPathError.value = ''
  },
)

/**
 * 选项目录优先用平台接口；没有 platform:list 权限时会 403，
 * 这时退回"订阅里记的平台名"，保证选择框不会显示成一个裸 id。
 */
const platformOptions = computed<PlatformOption[]>(() => {
  const list = [...platforms.value]
  const current = props.sub?.platformId
  if (current && !list.some((p) => p.id === current)) {
    list.unshift({ id: current, name: props.sub?.platformName || current })
  }
  return list
})

onMounted(async () => {
  try {
    const res = await getPlatformListApi({ direction: 'INBOUND' })
    platforms.value = (res.data ?? []).map((it) => ({
      id: it.platform.id,
      name: it.platform.name,
    }))
  } catch {
    // 权限不足或平台模块不可用：不影响编辑，只是下拉里只有当前平台
  }
})

const platformValue = computed(() => form.platformId ?? '__all__')

const onPlatformChange = (v: AcceptableValue) => {
  const value = typeof v === 'string' ? v : ''
  form.platformId = value && value !== '__all__' ? value : null
}

const onStatusChange = (v: boolean) => {
  form.status = v ? 1 : 0
}

/** 宽 Scope 是本次编辑最危险的动作，保存前先让人看一眼 */
const widened = computed(() => {
  if (!props.sub) return false
  if (props.sub.platformId && !form.platformId) return true
  if (!props.sub.orgPath) return false
  const next = normalizePath(orgPathText.value)
  return next === null || !next.startsWith(props.sub.orgPath)
})

const normalizePath = (raw?: string | null): string | null => {
  const trimmed = (raw ?? '').trim()
  if (!trimmed || trimmed === '/') return null
  let path = trimmed.replace(/\/{2,}/g, '/')
  if (!path.startsWith('/')) path = `/${path}`
  if (!path.endsWith('/')) path = `${path}/`
  return path === '/' ? null : path
}

const onSave = async () => {
  orgPathError.value = ''
  const raw = orgPathText.value.trim()
  // 只挡明显写错的形态：路径必须由数字 id 段组成，否则 LIKE 前缀会永远匹配不到
  if (raw && !/^\/?\d+(\/\d+)*\/?$/.test(raw)) {
    orgPathError.value = '组织路径只能由数字 id 段组成，如 /1/5/'
    return
  }
  if (!props.sub) return

  if (widened.value) {
    const ok = await confirm({
      title: '放宽推送范围',
      description:
        '放宽后订户会多看到范围内的新变更，但放宽之前发生的变更不会补发，需要订户重拉快照。确认保存？',
      confirmText: '放宽并保存',
    })
    if (!ok) return
  }

  const payload: EventSubscriptionSaveRequest = {
    id: props.sub.id,
    platformId: form.platformId ?? null,
    orgPath: normalizePath(orgPathText.value),
    status: form.status,
    remark: remarkText.value.trim() || null,
  }

  saving.value = true
  try {
    await updateSubscriptionApi(payload)
    toast.success('已保存')
    emit('changed')
  } finally {
    saving.value = false
  }
}

const onReset = async () => {
  if (!props.sub) return
  const ok = await confirm({
    title: '重置订阅游标',
    description: `游标将从 ${props.sub.lastEventId || '0'} 跳到当前最新位置，期间的事件不会补发。确认重置？`,
    confirmText: '重置',
    danger: true,
  })
  if (!ok) return
  resetting.value = true
  try {
    await resetSubscriptionCursorApi(props.sub.id)
    toast.success('游标已重置')
    emit('changed')
  } finally {
    resetting.value = false
  }
}
</script>
