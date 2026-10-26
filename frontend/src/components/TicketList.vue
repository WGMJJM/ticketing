<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { listTickets, saleStatusText, type TicketSku } from '../api/tickets'
import { formatCents } from '../utils/money'
import { formatTime } from '../utils/date'
import PurchasePanel from './PurchasePanel.vue'
import WaitlistPanel from './WaitlistPanel.vue'
const props = defineProps<{ activityId: string; sessionId: string }>()
const rows = ref<TicketSku[]>([])
const loading = ref(false)
const error = ref('')
let controller: AbortController | undefined
async function load() {
  controller?.abort()
  const active = new AbortController()
  controller = active
  loading.value = true
  error.value = ''
  rows.value = []
  try {
    rows.value = await listTickets(
      props.activityId,
      props.sessionId,
      active.signal,
    )
  } catch (failure) {
    if (!active.signal.aborted)
      error.value = failure instanceof Error ? failure.message : '票档加载失败'
  } finally {
    if (!active.signal.aborted) loading.value = false
  }
}
watch(() => [props.activityId, props.sessionId], load, { immediate: true })
onBeforeUnmount(() => controller?.abort())
</script>
<template>
  <section class="ticket-section" aria-labelledby="ticket-heading">
    <div class="ticket-heading">
      <div>
        <span class="section-kicker">按预算与余票，选择适合的票档</span>
        <h2 id="ticket-heading">场次票档</h2>
      </div>
      <ElButton :loading="loading" @click="load">刷新票档</ElButton>
    </div>
    <div v-if="loading" class="content-state" role="status">
      正在加载场次票档…
    </div>
    <div v-else-if="error" class="content-state error-state" role="alert">
      <h3>票档暂时无法加载</h3>
      <p>{{ error }}</p>
      <ElButton @click="load">重新加载票档</ElButton>
    </div>
    <div v-else-if="rows.length === 0" class="content-state">
      该场次暂无已公开票档，请稍后关注。
    </div>
    <div v-else class="ticket-grid">
      <article
        v-for="ticket in rows"
        :key="ticket.id"
        class="ticket-card"
        :class="ticket.saleStatus.toLowerCase()"
      >
        <div class="ticket-card-top">
          <h3>{{ ticket.name }}</h3>
          <span class="ticket-sale-label">{{
            saleStatusText[ticket.saleStatus]
          }}</span>
        </div>
        <p class="ticket-price">
          {{ formatCents(ticket.priceCents) }}<small> / 张</small>
        </p>
        <div class="ticket-stock">
          剩余 <strong>{{ ticket.availableStock }}</strong> 张<span
            >单笔限购 {{ ticket.maxQuantityPerOrder }} 张</span
          >
        </div>
        <dl>
          <div>
            <dt>开售时间</dt>
            <dd>{{ formatTime(ticket.saleStartsAt) }}</dd>
          </div>
          <div>
            <dt>停售时间</dt>
            <dd>{{ formatTime(ticket.saleEndsAt) }}</dd>
          </div>
        </dl>
        <PurchasePanel
          v-if="ticket.saleStatus === 'SELLING'"
          :ticket="ticket"
          @changed="load"
        />
        <WaitlistPanel
          v-if="
            ticket.saleStatus === 'SELLING' || ticket.saleStatus === 'SOLD_OUT'
          "
          :ticket="ticket"
        />
      </article>
    </div>
    <p class="ticket-query-note">
      时间均为北京时间。提交申请后，可在「购票进度」查看结果。请在订单截止时间前支付，超时将自动关闭。
    </p>
  </section>
</template>
