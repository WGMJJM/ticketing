<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { waitlistApi, type WaitlistEntry } from '../api/waitlist'
import { useAuthStore } from '../stores/auth'
import { formatTime } from '../utils/date'

const auth = useAuthStore()
const route = useRoute()
const payment = ref<WaitlistEntry | null>(null)

watch(() => auth.user?.id, (userId, _, onCleanup) => {
  payment.value = null
  if (!userId) return

  const controller = new AbortController()
  let timer: ReturnType<typeof setTimeout> | undefined
  let loading = false
  async function refresh() {
    clearTimeout(timer)
    if (document.hidden || loading) return
    loading = true
    try {
      const result = await waitlistApi.nextPayment(controller.signal)
      if (!controller.signal.aborted) payment.value = result
    } catch {
      if (!controller.signal.aborted) payment.value = null
    } finally {
      loading = false
      if (!controller.signal.aborted && !document.hidden) timer = setTimeout(refresh, 10000)
    }
  }

  document.addEventListener('visibilitychange', refresh)
  onCleanup(() => {
    controller.abort()
    clearTimeout(timer)
    document.removeEventListener('visibilitychange', refresh)
  })
  void refresh()
}, { immediate: true })
</script>

<template>
  <aside
    v-if="payment?.reservedOrderId && payment.orderExpiresAt && route.path !== '/orders/' + payment.reservedOrderId"
    class="waitlist-reminder"
    aria-label="候补支付提醒"
  >
    <div role="status">
      <strong>候补成功，请及时支付</strong>
      <span>{{ payment.activityTitle }} · {{ payment.skuName }} × {{ payment.quantity }} 张 · 截止 {{ formatTime(payment.orderExpiresAt) }}</span>
    </div>
    <RouterLink :to="'/orders/' + payment.reservedOrderId">前往支付 <span aria-hidden="true">↗</span></RouterLink>
  </aside>
</template>

<style scoped>
.waitlist-reminder {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px 24px;
  margin: 16px 0;
  padding: 14px 18px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--warning-soft);
  color: var(--ink);
}
.waitlist-reminder strong { display: block; margin-bottom: 4px; }
.waitlist-reminder span { font-size: 13px; }
.waitlist-reminder a { font-weight: 600; }
</style>
