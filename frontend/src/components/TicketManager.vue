<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
import type { ActivitySession } from '../api/activities'
import {
  adminTickets,
  ticketStatusText,
  saleStatusText,
  type TicketSku,
  type TicketInput,
} from '../api/tickets'
import { formatCents, centsToYuan, yuanToCents } from '../utils/money'
import { formatTime, toBeijingInput, fromBeijingInput } from '../utils/date'
import { chineseValidation, clearValidation } from '../utils/validation'
const open = defineModel<boolean>({ required: true })
const props = defineProps<{ session: ActivitySession }>()
const rows = ref<TicketSku[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const formOpen = ref(false)
const editing = ref<TicketSku>()
const form = reactive({
  name: '',
  priceYuan: '0.00',
  totalStock: 0,
  saleStartsAt: '',
  saleEndsAt: '',
  maxQuantityPerOrder: 4,
})
async function load() {
  loading.value = true
  error.value = ''
  try {
    rows.value = await adminTickets.list(props.session.id)
  } catch (failure) {
    report(failure)
  } finally {
    loading.value = false
  }
}
function report(failure: unknown) {
  error.value =
    failure instanceof Error ? failure.message : '票档操作失败，请稍后重试'
}
watch(
  () => [open.value, props.session.id],
  () => {
    if (open.value) {
      rows.value = []
      formOpen.value = false
      notice.value = ''
      void load()
    }
  },
  { immediate: true },
)
function edit(ticket?: TicketSku) {
  editing.value = ticket
  error.value = ''
  notice.value = ''
  Object.assign(form, {
    name: ticket?.name ?? '',
    priceYuan: ticket ? centsToYuan(ticket.priceCents) : '0.00',
    totalStock: ticket?.totalStock ?? 0,
    maxQuantityPerOrder: ticket?.maxQuantityPerOrder ?? 4,
    saleStartsAt: toBeijingInput(
      ticket?.saleStartsAt ??
        new Date(
          Math.ceil(new Date(props.session.saleStartsAt).getTime() / 60000) *
            60000,
        ).toISOString(),
    ),
    saleEndsAt: toBeijingInput(ticket?.saleEndsAt ?? props.session.startsAt),
  })
  formOpen.value = true
}
async function save() {
  error.value = ''
  notice.value = ''
  try {
    if (
      editing.value &&
      form.totalStock < editing.value.lockedStock + editing.value.soldStock
    )
      throw new Error('总库存不能小于已锁定库存与已售库存之和')
    const enabled = editing.value?.status === 'ON_SALE'
    const payload: TicketInput =
      enabled && editing.value
        ? {
            name: editing.value.name,
            priceCents: editing.value.priceCents,
            totalStock: form.totalStock,
            saleStartsAt: editing.value.saleStartsAt,
            saleEndsAt: editing.value.saleEndsAt,
            maxQuantityPerOrder: editing.value.maxQuantityPerOrder,
            version: editing.value.version,
          }
        : {
            name: form.name.trim(),
            priceCents: yuanToCents(form.priceYuan),
            totalStock: form.totalStock,
            saleStartsAt:
              editing.value &&
              form.saleStartsAt === toBeijingInput(editing.value.saleStartsAt)
                ? editing.value.saleStartsAt
                : fromBeijingInput(form.saleStartsAt),
            saleEndsAt:
              editing.value &&
              form.saleEndsAt === toBeijingInput(editing.value.saleEndsAt)
                ? editing.value.saleEndsAt
                : fromBeijingInput(form.saleEndsAt),
            maxQuantityPerOrder: form.maxQuantityPerOrder,
            ...(editing.value ? { version: editing.value.version } : {}),
          }
    if (
      new Date(payload.saleEndsAt).getTime() <=
      new Date(payload.saleStartsAt).getTime()
    )
      throw new Error('停售时间必须晚于开售时间')
    if (
      new Date(payload.saleStartsAt).getTime() <
        new Date(props.session.saleStartsAt).getTime() ||
      new Date(payload.saleEndsAt).getTime() >
        new Date(props.session.startsAt).getTime()
    )
      throw new Error('票档开售不得早于场次开售，停售不得晚于场次开始')
    saving.value = true
    if (editing.value) await adminTickets.update(editing.value.id, payload)
    else await adminTickets.create(props.session.id, payload)
    formOpen.value = false
    notice.value = '票档已保存'
    await load()
  } catch (failure) {
    report(failure)
  } finally {
    saving.value = false
  }
}
async function transition(ticket: TicketSku) {
  const operation = ticket.status === 'ON_SALE' ? 'offline' : 'publish'
  const label = operation === 'publish' ? '启用销售' : '下架'
  try {
    await ElMessageBox.confirm(
      `确定${label}“${ticket.name}”吗？${operation === 'publish' ? '实际销售状态仍由活动、场次、时间和库存共同决定。' : '下架后该票档将不再公开展示。'}`,
      `${label}票档`,
      {
        confirmButtonText: `确认${label}`,
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return
  }
  saving.value = true
  error.value = ''
  try {
    await adminTickets.transition(ticket, operation)
    notice.value = `票档已${label}`
    await load()
  } catch (failure) {
    report(failure)
  } finally {
    saving.value = false
  }
}
</script>
<template>
  <ElDialog
    v-model="open"
    :title="`${session.title} · 票档与库存管理`"
    width="min(1080px, 96vw)"
    append-to-body
    :close-on-click-modal="false"
    :show-close="!saving"
    :close-on-press-escape="!saving"
    destroy-on-close
  >
    <p class="form-hint">
      场次最早开售 {{ formatTime(session.saleStartsAt) }}，场次开始
      {{
        formatTime(session.startsAt)
      }}。以下均为北京时间，每场次最多200个票档。
    </p>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="success-notice" role="status">{{ notice }}</p>
    <div class="ticket-manager-actions">
      <ElButton
        type="primary"
        :disabled="saving || loading || session.status !== 'SCHEDULED'"
        @click="edit()"
        >新增票档</ElButton
      ><ElButton :loading="loading" :disabled="saving" @click="load"
        >刷新列表</ElButton
      >
    </div>
    <form
      v-if="formOpen"
      class="edit-form session-edit"
      @submit.prevent="save"
      @invalid.capture="chineseValidation"
      @input.capture="clearValidation"
      @change.capture="clearValidation"
    >
      <h3>{{ editing ? '编辑票档与库存' : '新增票档草稿' }}</h3>
      <p v-if="editing?.status === 'ON_SALE'" class="form-hint">
        当前已启用销售，只能调整总库存；其他信息请先下架后修改。
      </p>
      <div class="form-pair">
        <label
          >票档名称<input
            v-model="form.name"
            required
            maxlength="100"
            :disabled="editing?.status === 'ON_SALE'" /></label
        ><label
          >票价（人民币元）<input
            v-model="form.priceYuan"
            required
            inputmode="decimal"
            pattern="(0|[1-9][0-9]*)([.][0-9]{1,2})?"
            :disabled="editing?.status === 'ON_SALE'"
        /></label>
      </div>
      <div class="form-pair">
        <label
          >总库存（张）<input
            v-model.number="form.totalStock"
            required
            type="number"
            min="0"
            max="2147483647"
            step="1" /></label
        ><label
          >单笔限购数量（张）<input
            v-model.number="form.maxQuantityPerOrder"
            required
            type="number"
            min="1"
            max="2147483647"
            step="1"
            :disabled="editing?.status === 'ON_SALE'"
        /></label>
      </div>
      <div class="form-pair">
        <label
          >票档开售时间（北京时间）<input
            v-model="form.saleStartsAt"
            required
            type="datetime-local"
            :disabled="editing?.status === 'ON_SALE'" /></label
        ><label
          >票档停售时间（北京时间）<input
            v-model="form.saleEndsAt"
            required
            type="datetime-local"
            :disabled="editing?.status === 'ON_SALE'"
        /></label>
      </div>
      <div class="form-actions">
        <ElButton :disabled="saving" @click="formOpen = false"
          >取消编辑</ElButton
        ><ElButton type="primary" native-type="submit" :loading="saving"
          >保存票档</ElButton
        >
      </div>
    </form>
    <div v-if="loading" class="content-state" role="status">正在加载票档…</div>
    <div v-else-if="!error && rows.length === 0" class="content-state">
      该场次尚无票档，请创建票档草稿。
    </div>
    <div v-else-if="rows.length" class="table-wrap">
      <table class="admin-table ticket-table">
        <thead>
          <tr>
            <th>票档与票价</th>
            <th>管理 / 销售状态</th>
            <th>库存（张）</th>
            <th>销售窗口与限购</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="ticket in rows" :key="ticket.id">
            <td>
              <strong>{{ ticket.name }}</strong
              ><small>{{ formatCents(ticket.priceCents) }}</small>
            </td>
            <td>
              {{ ticketStatusText[ticket.status]
              }}<small>{{ saleStatusText[ticket.saleStatus] }}</small>
            </td>
            <td>
              总量 {{ ticket.totalStock
              }}<small
                >锁定 {{ ticket.lockedStock }} · 已售
                {{ ticket.soldStock }}</small
              ><strong>可售 {{ ticket.availableStock }}</strong>
            </td>
            <td>
              {{ formatTime(ticket.saleStartsAt)
              }}<small>至 {{ formatTime(ticket.saleEndsAt) }}</small
              ><small>单笔限购 {{ ticket.maxQuantityPerOrder }} 张</small>
            </td>
            <td class="table-actions">
              <ElButton
                size="small"
                :disabled="saving || session.status !== 'SCHEDULED'"
                @click="edit(ticket)"
                >{{
                  ticket.status === 'ON_SALE' ? '调整库存' : '编辑票档'
                }}</ElButton
              ><ElButton
                size="small"
                :disabled="
                  saving ||
                  (ticket.status !== 'ON_SALE' &&
                    session.status !== 'SCHEDULED')
                "
                @click="transition(ticket)"
                >{{
                  ticket.status === 'ON_SALE' ? '下架' : '启用销售'
                }}</ElButton
              >
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p class="form-hint">
      可售库存＝总库存－锁定库存－已售库存；锁定和已售库存不可由管理员修改。下单、支付及关闭操作自动维护库存。
    </p>
  </ElDialog>
</template>
