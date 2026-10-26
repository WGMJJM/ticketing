<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
import { adminOrdersApi, aftercareStatusText, type AdminOrderDetail } from '../api/adminOrders'
import { refundReasonText, type RefundReason } from '../api/orders'
import { formatTime } from '../utils/date'
import { formatCents } from '../utils/money'
import { waitlistStatusText } from '../api/waitlist'
import OrderCard from './OrderCard.vue'

const props = defineProps<{ id: string }>()
const detail = ref<AdminOrderDetail>()
const reason = ref<RefundReason>('CUSTOMER_REQUEST')
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')
let generation = 0
async function load() {
  const current = ++generation
  loading.value = true
  error.value = ''
  try {
    const result = await adminOrdersApi.detail(props.id)
    if (current === generation) detail.value = result
  } catch (failure) {
    if (current === generation) error.value = failure instanceof Error ? failure.message : '订单详情加载失败'
  } finally {
    if (current === generation) loading.value = false
  }
}
watch(() => props.id, () => { detail.value = undefined; notice.value = ''; void load() }, { immediate: true })
onBeforeUnmount(() => { generation++ })
async function refund() {
  if (!detail.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  const id = props.id
  try {
    try {
      await ElMessageBox.confirm(`对订单 ${detail.value.summary.order.orderNo} 整单退款 ${formatCents(detail.value.summary.order.totalAmountCents)}？仅全部电子票未核销时可完成，成功后票券作废。当前为模拟退款，不发生实际资金退回。`, '确认整单退款', {
        confirmButtonText: '确认退款', cancelButtonText: '暂不处理', type: 'warning',
      })
    } catch { return }
    const result = await adminOrdersApi.refund(id, reason.value)
    if (id !== props.id) return
    notice.value = result.status === 'SUCCEEDED' ? '整单退款已完成，票券已作废。' : result.aftercare?.message ?? '此订单需要人工处理'
    await load()
  } catch (failure) {
    if (id === props.id) {
      await load()
      error.value = failure instanceof Error ? failure.message : '退款未完成，请刷新订单核对'
    }
  } finally { busy.value = false }
}
</script>
<template>
  <div class="admin-order-detail">
    <div class="order-top"><RouterLink class="back-link" to="/admin/orders">← 返回订单运营</RouterLink><ElButton :loading="loading" :disabled="busy" @click="load">刷新详情</ElButton></div>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="success-notice" role="status">{{ notice }}</p>
    <p v-if="loading" role="status">正在读取订单最新状态…</p>
    <template v-if="detail">
      <div class="admin-buyer"><strong>{{ detail.summary.username }}</strong><span>用户编号 {{ detail.summary.userId }}</span><span v-if="detail.summary.paidAt">支付于 {{ formatTime(detail.summary.paidAt) }}</span></div>
      <OrderCard :order="detail.summary.order" detail admin />
      <section class="admin-aftercare-panel">
        <h2>票券与售后</h2>
        <div class="ticket-counts"><span>电子票共 <strong>{{ detail.tickets.total }}</strong> 张</span><span>未使用 {{ detail.tickets.unused }}</span><span>已核销 {{ detail.tickets.used }}</span><span>已作废 {{ detail.tickets.voided }}</span></div>
        <p v-if="detail.waitlist">候补编号 {{ detail.waitlist.id }} · {{ waitlistStatusText[detail.waitlist.status as keyof typeof waitlistStatusText] ?? '未知状态' }} · 关联预留订单 {{ detail.waitlist.reservedOrderId }}</p>
        <div v-if="detail.aftercare" class="aftercare-state" :data-status="detail.aftercare.status" role="status">
          <strong>{{ aftercareStatusText[detail.aftercare.status] }}</strong><p>{{ detail.aftercare.message }}</p>
          <small>最近尝试 {{ formatTime(detail.aftercare.lastAttemptAt) }} · 共 {{ detail.aftercare.attemptCount }} 次<span v-if="detail.aftercare.nextRetryAt"> · 下次重试 {{ formatTime(detail.aftercare.nextRetryAt) }}</span></small>
        </div>
        <form v-if="detail.summary.order.status === 'PAID'" class="refund-form" @submit.prevent="refund">
          <label>退款原因<select v-model="reason" :disabled="busy"><option v-for="(label, value) in refundReasonText" :key="value" :value="value">{{ label }}</option></select></label>
          <ElButton type="primary" native-type="submit" :loading="busy" :disabled="loading">整单退款</ElButton>
          <p>仅全部电子票未核销且关联完整时可自动处理。已核销及异常订单保留原票券和支付事实，记录待人工处理。</p>
        </form>
      </section>
    </template>
  </div>
</template>
