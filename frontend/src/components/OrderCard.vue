<script setup lang="ts">
import type { Order } from '../api/orders'
import { orderStatusText } from '../api/orders'
import { formatCents } from '../utils/money'
import { formatTime } from '../utils/date'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
const props = defineProps<{ order: Order; detail?: boolean; busy?: boolean }>()
defineEmits<{ cancel: [order: Order]; pay: [order: Order] }>()
const elapsed = ref(0)
let receivedAt = performance.now()
let timer: ReturnType<typeof setInterval> | undefined
watch(
  () => props.order,
  () => {
    receivedAt = performance.now()
    elapsed.value = 0
  },
)
onMounted(() => {
  timer = setInterval(() => {
    elapsed.value = performance.now() - receivedAt
  }, 1000)
})
onUnmounted(() => clearInterval(timer))
// 仅用服务端时间推进按钮的截止提示，后端仍在数据库事务中作最终判断。
const deadlinePassed = computed(
  () =>
    props.order.paymentDeadlinePassed ||
    Date.parse(props.order.serverTime) + elapsed.value >=
      Date.parse(props.order.expiresAt),
)
</script>
<template>
  <article class="order-card">
    <div class="order-top">
      <div>
        <span class="section-kicker">订单号 {{ order.orderNo }}</span>
        <h2>{{ order.activityTitle }}</h2>
      </div>
      <span class="ticket-sale-label">{{ orderStatusText[order.status] }}</span>
    </div>
    <p>
      {{ order.sessionTitle }} ·
      {{ formatTime(order.sessionStartsAt) }}（北京时间）
    </p>
    <div class="order-line">
      <strong>{{ order.skuName }} × {{ order.quantity }} 张</strong
      ><strong class="order-total">{{
        formatCents(order.totalAmountCents)
      }}</strong>
    </div>
    <dl>
      <div>
        <dt>下单单价</dt>
        <dd>{{ formatCents(order.unitPriceCents) }} / 张</dd>
      </div>
      <div>
        <dt>创建时间</dt>
        <dd>{{ formatTime(order.createdAt) }}</dd>
      </div>
      <div>
        <dt>支付截止</dt>
        <dd>{{ formatTime(order.expiresAt) }}</dd>
      </div>
    </dl>
    <p
      v-if="order.status === 'PENDING'"
      :class="deadlinePassed ? 'inline-error' : 'order-note'"
    >
      {{
        deadlinePassed
          ? '支付已截止，系统将自动关闭订单并释放库存，请刷新查看最新状态。'
          : order.canPay
            ? '库存已预占。请在截止前完成模拟支付，不产生实际扣款。'
            : '当前活动或场次不可支付，可主动取消释放库存。'
      }}
    </p>
    <section v-if="order.payment" class="payment-info">
      <h3>模拟支付流水</h3>
      <p>模拟支付成功，未产生实际扣款。</p>
      <dl>
        <div>
          <dt>支付流水号</dt>
          <dd>{{ order.payment.paymentNo }}</dd>
        </div>
        <div>
          <dt>支付金额</dt>
          <dd>{{ formatCents(order.payment.amountCents) }}</dd>
        </div>
        <div>
          <dt>支付时间</dt>
          <dd>{{ formatTime(order.payment.paidAt) }}（北京时间）</dd>
        </div>
      </dl>
    </section>
    <div class="auth-links">
      <RouterLink
        v-if="order.status === 'PAID'"
        :to="{ path: '/tickets', query: { orderId: order.id } }"
        >查看本单电子票</RouterLink
      >
      <RouterLink v-if="!detail" :to="'/orders/' + order.id"
        >查看订单详情</RouterLink
      >
      <RouterLink :to="'/activities/' + order.activityId"
        >查看活动与最新库存</RouterLink
      >
      <ElButton
        v-if="order.status === 'PENDING'"
        type="primary"
        :disabled="busy || !order.canPay || deadlinePassed"
        @click="$emit('pay', order)"
        >立即支付</ElButton
      >
      <ElButton
        v-if="order.canCancel"
        :disabled="busy"
        @click="$emit('cancel', order)"
        >取消订单</ElButton
      >
    </div>
  </article>
</template>
