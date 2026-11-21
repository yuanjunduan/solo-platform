<template>
  <div class="flex h-full flex-col overflow-hidden">
    <!-- 工具栏 -->
    <div class="flex h-12 shrink-0 items-center gap-2 border-b border-border px-3">
      <span class="text-[13px] font-medium">本平台国标配置</span>
      <span
        class="rounded-full px-1.5 py-px text-[10px]"
        :class="
          form.source === 'yaml'
            ? 'bg-muted text-muted-foreground'
            : 'bg-primary/10 text-primary'
        "
      >
        {{ form.source === 'yaml' ? '来源：配置文件' : '来源：数据库' }}
      </span>
      <div class="flex-1" />
      <RefreshButton :loading="loading" :on-refresh="load" />
      <Button
        v-access="PERM.GB_PARENT_PLATFORM_EDIT"
        size="sm"
        class="ml-1"
        :loading="saving"
        @click="onSubmit"
      >
        保存
      </Button>
    </div>

    <div class="min-h-0 flex-1 overflow-y-auto p-4">
      <div class="mx-auto max-w-[780px] space-y-4 pb-6">
        <!-- 待重启提示 -->
        <div
          v-if="form.restartRequired"
          class="rounded-lg border border-amber-500/30 bg-amber-500/5 p-3 text-xs leading-relaxed"
        >
          <p class="flex items-center gap-1.5 font-medium text-amber-600">
            <IconAlertTriangle class="size-3.5 shrink-0" />
            身份项已保存，重启本服务后生效
          </p>
          <p class="mt-1 text-muted-foreground">
            当前实际生效：
            <span class="font-mono text-foreground/90">
              {{ form.runningSipId || '—' }} / {{ form.runningSipDomain || '—' }} @
              {{ form.runningSipIp || '自动' }}:{{ form.runningSipPort ?? '—' }}
            </span>
          </p>
          <p class="mt-1 text-muted-foreground">
            重启后本平台以新身份监听；下级设备与上级平台需要重新注册（对方登记的身份也要同步改）。
          </p>
        </div>

        <div
          v-else
          class="flex items-start gap-1.5 rounded-lg border border-border bg-muted/30 p-3 text-[11px] leading-relaxed text-muted-foreground"
        >
          <IconInfoCircle class="mt-px size-3.5 shrink-0" />
          <span>
            配置写入数据库后优先于 application yaml。身份项（ID / 域 / 监听 IP / 端口）重启生效，
            其余接入参数保存即热更，无需重启。
          </span>
        </div>

        <!-- 平台身份 -->
        <section class="rounded-xl border border-border bg-card p-4">
          <header class="flex items-center gap-2">
            <h4 class="text-[13px] font-medium">平台身份</h4>
            <span class="rounded-full bg-amber-500/10 px-1.5 py-px text-[10px] text-amber-600">
              修改后需重启
            </span>
          </header>
          <p class="mt-1 text-[11px] text-muted-foreground">
            SIP 监听点与 Via/Contact 均按运行值构造，运行期只落库不重建，变更需重启进程。
          </p>

          <div class="mt-3 space-y-3">
            <div class="flex items-center gap-3">
              <Label class="w-24 shrink-0 whitespace-nowrap text-xs text-muted-foreground">
                SIP 国标 ID<span class="ml-0.5 text-destructive">*</span>
              </Label>
              <div class="flex-1">
                <Input
                  v-model="form.sipId"
                  class="h-8 font-mono text-xs"
                  placeholder="20 位国标编码"
                />
                <p v-if="errors.sipId" class="mt-1 text-[11px] text-destructive">
                  {{ errors.sipId }}
                </p>
              </div>
            </div>

            <div class="flex items-center gap-3">
              <Label class="w-24 shrink-0 whitespace-nowrap text-xs text-muted-foreground">
                SIP 域<span class="ml-0.5 text-destructive">*</span>
              </Label>
              <div class="flex-1">
                <Input
                  v-model="form.sipDomain"
                  class="h-8 font-mono text-xs"
                  placeholder="国标 ID 前 10 位"
                />
                <p v-if="errors.sipDomain" class="mt-1 text-[11px] text-destructive">
                  {{ errors.sipDomain }}
                </p>
              </div>
            </div>

            <div class="flex items-center gap-3">
              <Label class="w-24 shrink-0 whitespace-nowrap text-xs text-muted-foreground">
                监听 IP
              </Label>
              <div class="flex-1">
                <Input
                  v-model="form.sipIp"
                  class="h-8 font-mono text-xs"
                  placeholder="留空监听所有网卡"
                />
                <p v-if="errors.sipIp" class="mt-1 text-[11px] text-destructive">
                  {{ errors.sipIp }}
                </p>
              </div>
            </div>

            <div class="flex items-center gap-3">
              <Label class="w-24 shrink-0 whitespace-nowrap text-xs text-muted-foreground">
                监听端口<span class="ml-0.5 text-destructive">*</span>
              </Label>
              <div class="flex-1">
                <Input
                  v-model.number="form.sipPort"
                  type="number"
                  class="h-8 font-mono text-xs"
                  placeholder="5060"
                />
                <p v-if="errors.sipPort" class="mt-1 text-[11px] text-destructive">
                  {{ errors.sipPort }}
                </p>
              </div>
            </div>

            <div class="flex items-center gap-3">
              <Label class="w-24 shrink-0 whitespace-nowrap text-xs text-muted-foreground">
                公网 IP
              </Label>
              <div class="flex-1">
                <Input
                  v-model="form.sipPublicIp"
                  class="h-8 font-mono text-xs"
                  placeholder="NAT 部署时填写，留空用监听 IP"
                />
                <p v-if="errors.sipPublicIp" class="mt-1 text-[11px] text-destructive">
                  {{ errors.sipPublicIp }}
                </p>
              </div>
            </div>
          </div>
        </section>

        <!-- 设备接入 -->
        <section class="rounded-xl border border-border bg-card p-4">
          <header class="flex items-center gap-2">
            <h4 class="text-[13px] font-medium">设备接入</h4>
            <span class="rounded-full bg-primary/10 px-1.5 py-px text-[10px] text-primary">
              保存即生效
            </span>
          </header>

          <div class="mt-3 space-y-3">
            <div class="flex items-center gap-3">
              <Label class="w-24 shrink-0 whitespace-nowrap text-xs text-muted-foreground">
                鉴权密码
              </Label>
              <div class="flex-1">
                <Input
                  v-model="passwordInput"
                  type="password"
                  class="h-8 text-xs"
                  autocomplete="new-password"
                  :placeholder="
                    form.passwordConfigured ? '已配置，留空则不修改' : '当前无鉴权，输入即设置'
                  "
                />
                <p class="mt-1 text-[11px] text-muted-foreground">
                  下级设备 REGISTER 的 Digest 鉴权密码；页面不回显明文。
                </p>
              </div>
            </div>

            <div class="flex items-center gap-3">
              <Label class="w-24 shrink-0 whitespace-nowrap text-xs text-muted-foreground">
                清空密码
              </Label>
              <div class="flex flex-1 items-center gap-2">
                <Switch
                  :model-value="resetPassword"
                  @update:model-value="(v) => (resetPassword = !!v)"
                />
                <span class="text-[11px] text-muted-foreground">
                  打开后保存即改为开放模式（不鉴权），仅隔离测试环境使用
                </span>
              </div>
            </div>

            <div class="flex items-center gap-3">
              <Label class="w-24 shrink-0 whitespace-nowrap text-xs text-muted-foreground">
                注册有效期
              </Label>
              <div class="flex flex-1 items-center gap-2">
                <Input
                  v-model.number="form.registerExpires"
                  type="number"
                  class="h-8 w-32 font-mono text-xs"
                  placeholder="3600"
                />
                <span class="text-[11px] text-muted-foreground">秒（60-86400）</span>
              </div>
            </div>

            <div class="flex items-center gap-3">
              <Label class="w-24 shrink-0 whitespace-nowrap text-xs text-muted-foreground">
                心跳超时倍数
              </Label>
              <div class="flex flex-1 items-center gap-2">
                <Input
                  v-model.number="form.keepaliveTimeoutMultiplier"
                  type="number"
                  class="h-8 w-32 font-mono text-xs"
                  placeholder="3"
                />
                <span class="text-[11px] text-muted-foreground">
                  超过 N 个心跳周期未收心跳判离线（2-10）
                </span>
              </div>
            </div>

            <div class="flex items-center gap-3">
              <Label class="w-24 shrink-0 whitespace-nowrap text-xs text-muted-foreground">
                探测超时
              </Label>
              <div class="flex flex-1 items-center gap-2">
                <Input
                  v-model.number="form.detectTimeoutMs"
                  type="number"
                  class="h-8 w-32 font-mono text-xs"
                  placeholder="10000"
                />
                <span class="text-[11px] text-muted-foreground">毫秒（1000-60000）</span>
              </div>
            </div>

            <div class="flex items-center gap-3">
              <Label class="w-24 shrink-0 whitespace-nowrap text-xs text-muted-foreground">
                源地址优先
              </Label>
              <div class="flex flex-1 items-center gap-2">
                <Switch
                  :model-value="form.useSourceIpAsRemoteAddress"
                  @update:model-value="(v) => (form.useSourceIpAsRemoteAddress = !!v)"
                />
                <span class="text-[11px] text-muted-foreground">
                  开启后用报文源 IP/端口作为设备地址，NAT 后设备建议开启
                </span>
              </div>
            </div>
          </div>
        </section>

        <!-- 级联推送 -->
        <section class="rounded-xl border border-border bg-card p-4">
          <header class="flex items-center gap-2">
            <h4 class="text-[13px] font-medium">级联推送</h4>
            <span class="rounded-full bg-primary/10 px-1.5 py-px text-[10px] text-primary">
              保存即生效
            </span>
          </header>
          <p class="mt-1 text-[11px] text-muted-foreground">
            本平台作为下级向上级响应 Catalog、推流时使用的分片与端口段。
          </p>

          <div class="mt-3 space-y-3">
            <div class="flex items-center gap-3">
              <Label class="w-24 shrink-0 whitespace-nowrap text-xs text-muted-foreground">
                目录分片
              </Label>
              <div class="flex flex-1 items-center gap-2">
                <Select v-model="catalogPageSizeModel">
                  <SelectTrigger class="h-8 w-40 text-xs">
                    <SelectValue placeholder="默认（走配置）" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="__default__">默认（走配置）</SelectItem>
                    <SelectItem value="1">1 条/包</SelectItem>
                    <SelectItem value="2">2 条/包</SelectItem>
                    <SelectItem value="4">4 条/包</SelectItem>
                    <SelectItem value="8">8 条/包</SelectItem>
                  </SelectContent>
                </Select>
                <span class="text-[11px] text-muted-foreground">
                  上级品牌兼容性差时逐档下调
                </span>
              </div>
            </div>

            <div class="flex items-center gap-3">
              <Label class="w-24 shrink-0 whitespace-nowrap text-xs text-muted-foreground">
                分片 ACK 超时
              </Label>
              <div class="flex flex-1 items-center gap-2">
                <Input
                  v-model.number="form.catalogAckTimeoutMs"
                  type="number"
                  class="h-8 w-32 font-mono text-xs"
                  placeholder="3000"
                />
                <span class="text-[11px] text-muted-foreground">毫秒（500-60000）</span>
              </div>
            </div>

            <div class="flex items-center gap-3">
              <Label class="w-24 shrink-0 whitespace-nowrap text-xs text-muted-foreground">
                兜底发送间隔
              </Label>
              <div class="flex flex-1 items-center gap-2">
                <Input
                  v-model.number="form.catalogPageIntervalMs"
                  type="number"
                  class="h-8 w-32 font-mono text-xs"
                  placeholder="200"
                />
                <span class="text-[11px] text-muted-foreground">
                  毫秒（0-10000，ACK 超时才用）
                </span>
              </div>
            </div>

            <div class="flex items-center gap-3">
              <Label class="w-24 shrink-0 whitespace-nowrap text-xs text-muted-foreground">
                推流端口段
              </Label>
              <div class="flex flex-1 items-center gap-2">
                <Input
                  v-model.number="form.sendRtpPortStart"
                  type="number"
                  class="h-8 w-28 font-mono text-xs"
                  placeholder="50000"
                />
                <span class="text-xs text-muted-foreground">—</span>
                <Input
                  v-model.number="form.sendRtpPortEnd"
                  type="number"
                  class="h-8 w-28 font-mono text-xs"
                  placeholder="60000"
                />
                <span class="text-[11px] text-muted-foreground">需与 ZLM 收流端口段错开</span>
              </div>
            </div>
            <p v-if="errors.rtp" class="text-[11px] text-destructive">{{ errors.rtp }}</p>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { IconAlertTriangle, IconInfoCircle } from '@tabler/icons-vue'
import Button from '@/components/ui/button/Button.vue'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Switch } from '@/components/ui/switch'
import RefreshButton from '@/components/RefreshButton.vue'
import { getGbLocalConfigApi, saveGbLocalConfigApi } from '@/api/gb28181'
import { PERM } from '@/features/access/permissions'
import { toast } from '@/lib/toast'
import type { GbLocalConfig, GbLocalConfigSaveRequest } from '@/types/api'

type FormState = {
  source: 'db' | 'yaml'
  restartRequired: boolean
  sipId: string
  sipDomain: string
  sipIp: string
  sipPublicIp: string
  sipPort?: number
  passwordConfigured: boolean
  registerExpires?: number
  keepaliveTimeoutMultiplier?: number
  detectTimeoutMs?: number
  useSourceIpAsRemoteAddress: boolean
  catalogPageSize?: number
  catalogAckTimeoutMs?: number
  catalogPageIntervalMs?: number
  sendRtpPortStart?: number
  sendRtpPortEnd?: number
  runningSipId: string
  runningSipDomain: string
  runningSipIp: string
  runningSipPort?: number
}

const form = reactive<FormState>({
  source: 'yaml',
  restartRequired: false,
  sipId: '',
  sipDomain: '',
  sipIp: '',
  sipPublicIp: '',
  sipPort: undefined,
  passwordConfigured: false,
  registerExpires: undefined,
  keepaliveTimeoutMultiplier: undefined,
  detectTimeoutMs: undefined,
  useSourceIpAsRemoteAddress: true,
  catalogPageSize: undefined,
  catalogAckTimeoutMs: undefined,
  catalogPageIntervalMs: undefined,
  sendRtpPortStart: undefined,
  sendRtpPortEnd: undefined,
  runningSipId: '',
  runningSipDomain: '',
  runningSipIp: '',
  runningSipPort: undefined,
})

const errors = reactive<Record<string, string>>({})
const passwordInput = ref('')
const resetPassword = ref(false)
const loading = ref(false)
const saving = ref(false)

const fill = (data: GbLocalConfig) => {
  form.source = data.source === 'db' ? 'db' : 'yaml'
  form.restartRequired = !!data.restartRequired
  form.sipId = data.sipId ?? ''
  form.sipDomain = data.sipDomain ?? ''
  form.sipIp = data.sipIp ?? ''
  form.sipPublicIp = data.sipPublicIp ?? ''
  form.sipPort = data.sipPort
  form.passwordConfigured = !!data.passwordConfigured
  form.registerExpires = data.registerExpires
  form.keepaliveTimeoutMultiplier = data.keepaliveTimeoutMultiplier
  form.detectTimeoutMs = data.detectTimeoutMs
  form.useSourceIpAsRemoteAddress = data.useSourceIpAsRemoteAddress !== false
  form.catalogPageSize = data.catalogPageSize
  form.catalogAckTimeoutMs = data.catalogAckTimeoutMs
  form.catalogPageIntervalMs = data.catalogPageIntervalMs
  form.sendRtpPortStart = data.sendRtpPortStart
  form.sendRtpPortEnd = data.sendRtpPortEnd
  form.runningSipId = data.runningSipId ?? ''
  form.runningSipDomain = data.runningSipDomain ?? ''
  form.runningSipIp = data.runningSipIp ?? ''
  form.runningSipPort = data.runningSipPort
  // 掩码永不回填输入框，避免把 ****** 当密码提交
  passwordInput.value = ''
  resetPassword.value = false
}

const load = async () => {
  loading.value = true
  try {
    fill((await getGbLocalConfigApi()).data)
  } finally {
    loading.value = false
  }
}

/** reka 的 Select 只认字符串，未选=沿用 yaml 配置 */
const catalogPageSizeModel = computed({
  get: () => (form.catalogPageSize == null ? '__default__' : String(form.catalogPageSize)),
  set: (v: string) => {
    form.catalogPageSize = v === '__default__' ? undefined : Number(v)
  },
})

const toNum = (v: unknown): number | undefined => {
  if (v === '' || v === null || v === undefined) return undefined
  const n = Number(v)
  return Number.isFinite(n) ? n : undefined
}

const validate = () => {
  Object.keys(errors).forEach((k) => delete errors[k])
  const sipId = form.sipId.trim()
  const sipDomain = form.sipDomain.trim()
  if (!/^\d{20}$/.test(sipId)) {
    errors.sipId = '国标 ID 必须是 20 位数字'
  }
  if (!/^\d{10}$/.test(sipDomain)) {
    errors.sipDomain = 'SIP 域必须是 10 位数字'
  } else if (/^\d{20}$/.test(sipId) && !sipId.startsWith(sipDomain)) {
    errors.sipDomain = 'SIP 域必须是国标 ID 的前 10 位'
  }
  const sipPort = toNum(form.sipPort)
  if (sipPort == null || sipPort < 1 || sipPort > 65535) {
    errors.sipPort = '端口需在 1-65535 之间'
  }
  if (form.sipIp.trim() && !/^(\d{1,3}\.){3}\d{1,3}$/.test(form.sipIp.trim())) {
    errors.sipIp = '监听 IP 格式不正确'
  }
  if (form.sipPublicIp.trim() && !/^(\d{1,3}\.){3}\d{1,3}$/.test(form.sipPublicIp.trim())) {
    errors.sipPublicIp = '公网 IP 格式不正确'
  }
  const rtpStart = toNum(form.sendRtpPortStart)
  const rtpEnd = toNum(form.sendRtpPortEnd)
  if (rtpStart != null && rtpEnd != null && rtpStart >= rtpEnd) {
    errors.rtp = '推流端口段起始必须小于结束端口'
  } else if ((rtpStart != null && rtpStart < 1) || (rtpEnd != null && rtpEnd < 1)) {
    errors.rtp = '推流端口需大于 0'
  }
  return Object.keys(errors).length === 0
}

const onSubmit = async () => {
  if (saving.value || !validate()) return
  saving.value = true
  try {
    const payload: GbLocalConfigSaveRequest = {
      sipId: form.sipId.trim(),
      sipDomain: form.sipDomain.trim(),
      sipIp: form.sipIp.trim(),
      sipPublicIp: form.sipPublicIp.trim(),
      sipPort: toNum(form.sipPort),
      password: passwordInput.value ? passwordInput.value : undefined,
      resetPassword: resetPassword.value || undefined,
      registerExpires: toNum(form.registerExpires),
      keepaliveTimeoutMultiplier: toNum(form.keepaliveTimeoutMultiplier),
      detectTimeoutMs: toNum(form.detectTimeoutMs),
      useSourceIpAsRemoteAddress: form.useSourceIpAsRemoteAddress,
      catalogPageSize: toNum(form.catalogPageSize),
      catalogAckTimeoutMs: toNum(form.catalogAckTimeoutMs),
      catalogPageIntervalMs: toNum(form.catalogPageIntervalMs),
      sendRtpPortStart: toNum(form.sendRtpPortStart),
      sendRtpPortEnd: toNum(form.sendRtpPortEnd),
    }
    const data = (await saveGbLocalConfigApi(payload)).data
    fill(data)
    toast.success(data.restartRequired ? '已保存，身份项需重启后生效' : '已保存')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>
