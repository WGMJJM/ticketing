<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { listTickets, saleStatusText, type TicketSku } from '../api/tickets'
import { orderApi, type PurchaseLimit } from '../api/orders'
import { formatCents } from '../utils/money'
import { formatTime } from '../utils/date'
import { useAuthStore } from '../stores/auth'
import { readAttempt } from '../utils/purchaseAttempts'
import PurchasePanel from './PurchasePanel.vue'
import WaitlistPanel from './WaitlistPanel.vue'
const props = defineProps<{ activityId: string; sessionId: string }>()
const auth = useAuthStore()
const rows = ref<TicketSku[]>([])
const selectedId = ref('')
const loading = ref(false)
const error = ref('')
const limit = ref<PurchaseLimit>()
const limitLoading = ref(false)
const limitError = ref('')
let controller: AbortController | undefined
let limitController: AbortController | undefined
const selected = computed(() => rows.value.find(ticket => ticket.id === selectedId.value))
const mayPurchase = computed(() => {
  const ticket = selected.value
  return ticket && (ticket.saleStatus === 'SELLING' ||
    (auth.user && readAttempt(auth.user.id, ticket.id)))
})
const mayWaitlist = computed(() => selected.value?.waitlistSupported &&
  ['SELLING', 'SOLD_OUT'].includes(selected.value.saleStatus))

async function load() {
  controller?.abort()
  const active = new AbortController()
  controller = active
  loading.value = true
  error.value = ''
  try {
    const result = await listTickets(
      props.activityId,
      props.sessionId,
      active.signal,
    )
    if (active.signal.aborted) return
    rows.value = result
    if (!result.some(ticket => ticket.id === selectedId.value)) {
      selectedId.value = (result.find(ticket => ticket.saleStatus === 'SELLING') ?? result[0])?.id ?? ''
    }
  } catch (failure) {
    if (!active.signal.aborted)
      error.value = failure instanceof Error ? failure.message : '票档加载失败'
  } finally {
    if (!active.signal.aborted) loading.value = false
  }
}
async function loadLimit() {
  limitController?.abort()
  limit.value = undefined
  limitError.value = ''
  limitLoading.value = false
  if (!auth.user) return
  const active = new AbortController()
  limitController = active
  limitLoading.value = true
  try {
    const result = await orderApi.limits(props.sessionId, active.signal)
    if (!active.signal.aborted) limit.value = result
  } catch (failure) {
    if (!active.signal.aborted)
      limitError.value = failure instanceof Error ? failure.message : '限购信息加载失败'
  } finally {
    if (!active.signal.aborted) limitLoading.value = false
  }
}
function refresh() {
  void load()
  void loadLimit()
}
watch(() => [props.activityId, props.sessionId], () => {
  rows.value = []
  selectedId.value = ''
  void load()
}, { immediate: true })
watch([() => props.sessionId, () => auth.user?.id], loadLimit, { immediate: true })
onBeforeUnmount(() => {
  controller?.abort()
  limitController?.abort()
})
</script>
<template>
  <section class="ticket-section" aria-labelledby="ticket-heading">
    <div class="ticket-heading">
      <div>
        <span class="section-kicker">按预算与余票，选择适合的票档</span>
        <h2 id="ticket-heading">场次票档</h2>
      </div>
      <ElButton :loading="loading" @click="refresh">刷新票档</ElButton>
    </div>
    <div v-if="loading && !rows.length" class="content-state" role="status">
      正在加载场次票档…
    </div>
    <div v-else-if="error && !rows.length" class="content-state error-state" role="alert">
      <h3>票档暂时无法加载</h3>
      <p>{{ error }}</p>
      <ElButton @click="load">重新加载票档</ElButton>
    </div>
    <div v-else-if="rows.length === 0" class="content-state">
      该场次暂无已公开票档，请稍后关注。
    </div>
    <template v-else>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <div class="ticket-grid" role="group" aria-label="选择票档">
      <button
        v-for="ticket in rows"
        :key="ticket.id"
        type="button"
        class="ticket-card"
        :class="[ticket.saleStatus.toLowerCase(), { selected: selectedId === ticket.id }]"
        :aria-pressed="selectedId === ticket.id"
        @click="selectedId = ticket.id"
      >
        <span class="ticket-card-top">
          <strong class="ticket-name">{{ ticket.name }}</strong>
          <span class="ticket-sale-label">{{
            saleStatusText[ticket.saleStatus]
          }}</span>
        </span>
        <span class="ticket-price">
          {{ formatCents(ticket.priceCents) }}<small> / 张</small>
        </span>
        <span class="ticket-stock">
          剩余 <strong>{{ ticket.availableStock }}</strong> 张
          <span>单笔限购 {{ ticket.maxQuantityPerOrder }} 张</span>
        </span>
      </button>
      </div>
      <section v-if="selected" class="selected-ticket" aria-labelledby="selected-ticket-heading">
        <h3 id="selected-ticket-heading">{{ selected.name }}</h3>
        <p class="form-hint">开售 {{ formatTime(selected.saleStartsAt) }} · 停售 {{ formatTime(selected.saleEndsAt) }}</p>
        <template v-if="auth.authenticated && (mayPurchase || mayWaitlist)">
          <p v-if="limitLoading" role="status">正在读取场次限购信息…</p>
          <p v-else-if="limit" class="form-hint">
            本场限购 {{ limit.maxPerSession }} 张，已占用 {{ limit.usedQuantity }} 张，还可购买 {{ limit.remainingQuantity }} 张
          </p>
          <div v-else-if="limitError" class="inline-error" role="alert">
            {{ limitError }} <ElButton size="small" @click="loadLimit">重试限购查询</ElButton>
          </div>
        </template>
        <PurchasePanel
          v-if="mayPurchase"
          :key="'purchase-' + selected.id"
          :ticket="selected"
          :limit="limit"
          @changed="refresh"
        />
        <WaitlistPanel
          v-if="mayWaitlist"
          :key="'waitlist-' + selected.id"
          :ticket="selected"
          :limit="limit"
        />
        <p v-if="!mayPurchase && !mayWaitlist" role="status">{{ saleStatusText[selected.saleStatus] }}</p>
      </section>
    </template>
    <p class="ticket-query-note">
      时间均为北京时间。<RouterLink v-if="auth.authenticated" to="/purchase-requests">查看购票进度</RouterLink>
      请在订单截止时间前支付。
    </p>
  </section>
</template>
