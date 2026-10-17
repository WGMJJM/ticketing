<script setup lang="ts">
import type { Order } from '../api/orders'
import { orderStatusText } from '../api/orders'
import { formatCents } from '../utils/money'
import { formatTime } from '../utils/date'
defineProps<{ order: Order; detail?: boolean; busy?: boolean }>()
defineEmits<{ cancel: [order: Order] }>()
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
      :class="order.paymentDeadlinePassed ? 'inline-error' : 'order-note'"
    >
      {{
        order.paymentDeadlinePassed
          ? '支付已截止，订单仍待支付且库存尚未释放，请主动取消。自动超时回收尚未实现。'
          : '库存已预占。支付功能尚未开放，不需要的订单请主动取消释放库存与限购额度。'
      }}
    </p>
    <div class="auth-links">
      <RouterLink v-if="!detail" :to="'/orders/' + order.id"
        >查看订单详情</RouterLink
      >
      <RouterLink :to="'/activities/' + order.activityId"
        >查看活动与最新库存</RouterLink
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
