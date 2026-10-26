<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { isAxiosError } from 'axios'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
import { useAuthStore } from '../stores/auth'
import { orderApi, purchaseApi, type PurchaseLimit, type PurchaseRequest } from '../api/orders'
import { readAttempt, saveAttempt, clearAttempt, type PurchaseAttempt } from '../utils/purchaseAttempts'
import type { TicketSku } from '../api/tickets'
import { formatCents } from '../utils/money'
import { chineseValidation, clearValidation } from '../utils/validation'
const props = defineProps<{ ticket: TicketSku }>()
const emit = defineEmits<{ changed: [] }>()
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const quantity = ref(1)
const limit = ref<PurchaseLimit>()
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const attempt = ref<PurchaseAttempt>()
let generation = 0
onBeforeUnmount(() => { generation++ })
const maximum = computed(() => Math.min(props.ticket.availableStock, props.ticket.maxQuantityPerOrder,
  limit.value?.remainingQuantity ?? props.ticket.maxQuantityPerOrder))
const estimate = computed(() => formatCents((BigInt(props.ticket.priceCents) *
  BigInt(Number.isInteger(quantity.value) ? quantity.value : 0)).toString()))
async function loadLimit() {
  const active = generation
  if (!auth.authenticated) return
  loading.value = true
  limit.value = undefined
  try {
    const result = await orderApi.limits(props.ticket.sessionId)
    if (active === generation) limit.value = result
  } catch (failure) {
    if (active === generation) error.value = failure instanceof Error ? failure.message : '限购信息加载失败'
  } finally { if (active === generation) loading.value = false }
}
watch(() => [auth.user?.id, props.ticket.id], () => {
  generation++
  busy.value = false
  error.value = ''
  attempt.value = auth.user ? readAttempt(auth.user.id, props.ticket.id) : undefined
  quantity.value = attempt.value?.quantity ?? 1
  void loadLimit()
}, { immediate: true })
async function purchase() {
  if (busy.value) return
  const userId = auth.user?.id
  if (!userId) { await router.push({ path: '/login', query: { redirect: route.path } }); return }
  if (attempt.value?.requestId) { await router.push('/purchase-requests/' + attempt.value.requestId); return }
  if (!attempt.value && (!Number.isInteger(quantity.value) || quantity.value < 1 || quantity.value > maximum.value)) {
    error.value = '请按库存与限购范围选择数量'; return
  }
  const active = generation
  const skuId = props.ticket.id
  let freshAttempt = false
  busy.value = true
  error.value = ''
  try {
    if (!attempt.value) {
      try {
        await ElMessageBox.confirm(
          `确认申请“${props.ticket.name}”${quantity.value}张？预计${estimate.value}，金额以正式订单为准。提交受理时会暂时预扣库存用于排队，但不代表购票成功；申请失败或超时会释放预扣。只有正式订单创建成功后，才能按订单截止时间完成模拟支付。`,
          '确认购票申请', { confirmButtonText: '提交购票申请', cancelButtonText: '再想想', closeOnClickModal: false, type: 'warning' },
        )
      } catch { return }
      if (active !== generation) return
      const created = { quantity: quantity.value, key: crypto.randomUUID() }
      saveAttempt(userId, skuId, created)
      attempt.value = created
      freshAttempt = true
    }
    const current = attempt.value
    if (!current || active !== generation) return
    let result: PurchaseRequest
    let existing: PurchaseRequest | undefined
    if (!freshAttempt) {
      try {
        // 刷新或首次响应丢失后先只读找回，确定未受理才提交原键。
        existing = await purchaseApi.lookup(current.key)
      } catch (failure) {
        if (!isAxiosError(failure) || failure.response?.status !== 404) throw failure
      }
    }
    if (existing) result = existing
    else try {
      result = await orderApi.create(skuId, current.quantity, current.key)
    } catch (writeFailure) {
      if (isAxiosError(writeFailure) && (!writeFailure.response || writeFailure.response.status === 429 || writeFailure.response.status >= 500)) {
        // 只恢复已提交事实；原安全限流不变，不循环重发写入。
        try { result = await purchaseApi.lookup(current.key) } catch { throw writeFailure }
      } else throw writeFailure
    }
    saveAttempt(userId, skuId, { ...current, requestId: result.requestId })
    if (active !== generation) return
    attempt.value = { ...current, requestId: result.requestId }
    emit('changed')
    if (result.status === 'SUCCEEDED' && result.orderId) {
      clearAttempt(userId, skuId, current.key)
      await router.push('/orders/' + result.orderId)
    } else await router.push('/purchase-requests/' + result.requestId)
  } catch (failure) {
    const notAdmitted = isAxiosError(failure) && ['TICKET_NOT_FOUND', 'TICKET_UNAVAILABLE']
      .includes(String(failure.response?.data?.code))
    const rejected = isAxiosError(failure) && [429, 503].includes(failure.response?.status ?? 0) &&
      ['TOO_MANY_REQUESTS', 'RATE_LIMIT_UNAVAILABLE', 'PURCHASE_QUEUE_FULL', 'PURCHASE_GLOBAL_BUSY',
        'PURCHASE_TICKET_BUSY', 'PURCHASE_ADMISSION_UNAVAILABLE'].includes(String(failure.response?.data?.code))
    if (active === generation) error.value = (failure instanceof Error ? failure.message : '购票申请提交失败') +
      (attempt.value ? notAdmitted ? '。本次提交未获受理，原申请参数仍保留；票档恢复可售后可用原申请重试。' : rejected ? '。本次提交暂未通过，原申请仍保留，请稍后重试确认。' : '。结果尚未确认，请用同一申请重试；刷新页面后也会保留原申请。' : '。请确认浏览器允许本站会话存储后重试。')
  } finally { if (active === generation) busy.value = false }
}
</script>
<template>
  <form class="purchase-panel" @submit.prevent="purchase" @invalid.capture="chineseValidation"
    @input.capture="clearValidation" @change.capture="clearValidation">
    <template v-if="auth.authenticated">
      <p v-if="loading">正在读取限购信息…</p>
      <p v-else-if="limit">本场限购 {{ limit.maxPerSession }} 张，已占用 {{ limit.usedQuantity }} 张，还可购买 {{ limit.remainingQuantity }} 张</p>
      <ElButton v-else @click="loadLimit">重试限购查询</ElButton>
    </template>
    <label :for="'quantity-' + ticket.id">购买数量</label>
    <input :id="'quantity-' + ticket.id" v-model.number="quantity" type="number" min="1"
      :max="attempt ? undefined : maximum" step="1" required :disabled="busy || !!attempt || maximum < 1"
      :aria-label="ticket.name + '购买数量'" />
    <span class="purchase-estimate"><small>预计金额</small><strong>{{ estimate }}</strong></span>
    <p>申请受理后会暂时预扣库存，但不代表购票成功；只有正式订单创建成功后才能模拟支付。</p>
    <p v-if="attempt" role="status">已保留原购票申请，重复点击和刷新将查询同一结果。</p>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <ElButton type="primary" native-type="submit" :loading="busy"
      :disabled="!attempt && (maximum < 1 || (auth.authenticated && (!limit || loading)))">
      {{ !auth.authenticated ? '登录后购票' : attempt?.requestId ? '查看购票进度' : attempt ? '重试确认该申请' : '提交购票申请' }}
    </ElButton>
    <RouterLink v-if="auth.authenticated" to="/purchase-requests">查看购票进度</RouterLink>
  </form>
</template>
