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
const props = defineProps<{ ticket: TicketSku; limit?: PurchaseLimit }>()
const emit = defineEmits<{ changed: [] }>()
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const quantity = ref(1)
const busy = ref(false)
const error = ref('')
const attempt = ref<PurchaseAttempt>()
const selling = computed(() => props.ticket.saleStatus === 'SELLING')
let generation = 0
onBeforeUnmount(() => { generation++ })
const maximum = computed(() => Math.min(props.ticket.availableStock, props.ticket.maxQuantityPerOrder,
  props.limit?.remainingQuantity ?? props.ticket.maxQuantityPerOrder))
const estimate = computed(() => formatCents((BigInt(props.ticket.priceCents) *
  BigInt(Number.isInteger(quantity.value) ? quantity.value : 0)).toString()))
watch([() => auth.user?.id, () => props.ticket.id], () => {
  generation++
  busy.value = false
  error.value = ''
  attempt.value = auth.user ? readAttempt(auth.user.id, props.ticket.id) : undefined
  quantity.value = attempt.value?.quantity ?? 1
}, { immediate: true })
async function purchase() {
  if (busy.value) return
  const userId = auth.user?.id
  if (!userId) { await router.push({ path: '/login', query: { redirect: route.path } }); return }
  if (attempt.value?.requestId) { await router.push('/purchase-requests/' + attempt.value.requestId); return }
  if (!attempt.value && !selling.value) return
  if (!attempt.value && !props.limit) return
  if (!attempt.value && (!Number.isInteger(quantity.value) || quantity.value < 1 || quantity.value > maximum.value)) {
    error.value = '请按库存与限购范围选择数量'; return
  }
  const active = generation
  const skuId = props.ticket.id
  let current = attempt.value
  const freshAttempt = !current
  busy.value = true
  error.value = ''
  try {
    if (!current) {
      try {
        await ElMessageBox.confirm(
          `确认申请“${props.ticket.name}”${quantity.value}张？预计${estimate.value}，金额以正式订单为准。提交受理时会暂时预扣库存用于排队，但不代表购票成功；申请失败或超时会释放预扣。只有正式订单创建成功后，才能按订单截止时间完成支付。`,
          '确认购票申请', { confirmButtonText: '提交购票申请', cancelButtonText: '再想想', closeOnClickModal: false, type: 'warning' },
        )
      } catch { return }
      if (active !== generation) return
      if (!selling.value || !props.limit || quantity.value > maximum.value) {
        error.value = '票档库存或限购已变化，请重新确认购买数量'
        return
      }
      current = { quantity: quantity.value, key: crypto.randomUUID() }
      saveAttempt(userId, skuId, current)
      attempt.value = current
    }
    if (active !== generation) return
    let result: PurchaseRequest
    let existing: PurchaseRequest | undefined
    if (!freshAttempt) {
      try {
        existing = await purchaseApi.lookup(current.key)
      } catch (failure) {
        if (!isAxiosError(failure) || failure.response?.data?.code !== 'PURCHASE_REQUEST_NOT_FOUND') throw failure
      }
      if (!existing && (!selling.value || current.quantity > maximum.value)) {
        if (active === generation) error.value = '暂未查到原申请。当前票档或余票无法提交原数量，可稍后再查询。'
        return
      }
    }
    if (active !== generation) return
    if (existing) result = existing
    else try {
      result = await orderApi.create(skuId, current.quantity, current.key)
    } catch (writeFailure) {
      if (isAxiosError(writeFailure) && !definitivelyRejected(writeFailure) &&
          (!writeFailure.response || writeFailure.response.status >= 500)) {
        try { result = await purchaseApi.lookup(current.key) } catch { throw writeFailure }
      } else throw writeFailure
    }
    if (active !== generation) {
      if (readAttempt(userId, skuId)?.key === current.key)
        saveAttempt(userId, skuId, { ...current, requestId: result.requestId })
      return
    }
    if (result.status === 'QUEUED') saveAttempt(userId, skuId, { ...current, requestId: result.requestId })
    else clearAttempt(userId, skuId, current.key)
    attempt.value = result.status === 'QUEUED' ? { ...current, requestId: result.requestId } : undefined
    if (result.status === 'SUCCEEDED' && result.orderId) {
      await router.push('/orders/' + result.orderId)
    } else await router.push('/purchase-requests/' + result.requestId)
  } catch (failure) {
    const priority = isAxiosError(failure) && failure.response?.data?.code === 'WAITLIST_PRIORITY'
    const rejected = freshAttempt && definitivelyRejected(failure)
    if (rejected && current) clearAttempt(userId, skuId, current.key)
    if (active === generation) {
      if (rejected) {
        attempt.value = undefined
        emit('changed')
      }
      error.value = (failure instanceof Error ? failure.message : '购票申请提交失败') +
        (rejected ? (priority ? '。可在下方申请候补。' : '。可调整数量后重新提交。')
          : attempt.value ? '。原申请已保留，请先查询结果。' : '')
    }
  } finally { if (active === generation) busy.value = false }
}
function definitivelyRejected(failure: unknown) {
  return isAxiosError(failure) && [
    'INVALID_SKU_ID', 'TICKET_NOT_FOUND', 'TICKET_UNAVAILABLE', 'WAITLIST_PRIORITY',
    'PURCHASE_LIMIT', 'PURCHASE_QUEUE_FULL', 'PURCHASE_PUBLISH_UNAVAILABLE', 'TOO_MANY_REQUESTS', 'RATE_LIMIT_UNAVAILABLE',
  ].includes(String(failure.response?.data?.code))
}
</script>
<template>
  <form class="purchase-panel" @submit.prevent="purchase" @invalid.capture="chineseValidation"
    @input.capture="clearValidation" @change.capture="clearValidation">
    <template v-if="selling">
      <label :for="'quantity-' + ticket.id">购买数量</label>
      <input :id="'quantity-' + ticket.id" v-model.number="quantity" type="number" min="1"
        :max="attempt ? undefined : maximum" step="1" required :disabled="busy || !!attempt || maximum < 1"
        :aria-label="ticket.name + '购买数量'" />
      <span class="purchase-estimate"><small>预计金额</small><strong>{{ estimate }}</strong></span>
      <p>申请受理后会暂时预扣库存，但不代表购票成功；只有正式订单创建成功后才能支付。</p>
    </template>
    <p v-if="attempt" role="status">已保留原购票申请（{{ attempt.quantity }} 张），可查询受理结果。</p>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <ElButton v-if="selling || attempt" type="primary" native-type="submit" :loading="busy"
      :disabled="!attempt && (maximum < 1 || (auth.authenticated && !limit))">
      {{ !auth.authenticated ? '登录后购票' : attempt?.requestId ? '查看购票进度' : attempt ? (selling ? '查询或重试原申请' : '查询原购票申请') : '提交购票申请' }}
    </ElButton>
  </form>
</template>
