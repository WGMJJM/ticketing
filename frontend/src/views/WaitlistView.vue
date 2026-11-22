<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
import {
  waitlistApi,
  waitlistStatusText,
  type WaitlistEntry,
} from '../api/waitlist'
import { orderStatusText } from '../api/orders'
import { formatTime } from '../utils/date'
import { formatCents } from '../utils/money'
import { useAuthStore } from '../stores/auth'
const props = defineProps<{ id?: string }>()
const auth = useAuthStore()
const rows = ref<WaitlistEntry[]>([])
const page = ref(1)
const total = ref(0)
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')
let generation = 0
let timer: ReturnType<typeof setTimeout> | undefined

function hasActiveEntry() {
  return rows.value.some(entry => ['WAITING', 'PROCESSING', 'ALLOCATED'].includes(entry.status))
}

function refreshWhenVisible() {
  clearTimeout(timer)
  if (!document.hidden && hasActiveEntry()) void load(true)
}

async function load(quiet = false) {
  clearTimeout(timer)
  if (!auth.authenticated || (quiet && document.hidden)) return
  const active = ++generation
  if (!quiet) {
    loading.value = true
    error.value = ''
  }
  try {
    const result = props.id
      ? { items: [await waitlistApi.detail(props.id)], total: 1 }
      : await waitlistApi.list(page.value)
    if (active === generation) {
      rows.value = result.items
      total.value = result.total
      error.value = ''
    }
  } catch (failure) {
    if (active === generation && !quiet)
      error.value =
        failure instanceof Error ? failure.message : '候补记录加载失败'
  } finally {
    if (active === generation) {
      loading.value = false
      if (!document.hidden && hasActiveEntry()) timer = setTimeout(() => void load(true), 5000)
    }
  }
}
document.addEventListener('visibilitychange', refreshWhenVisible)
onBeforeUnmount(() => {
  generation++
  clearTimeout(timer)
  document.removeEventListener('visibilitychange', refreshWhenVisible)
})
watch(
  [() => props.id, () => auth.user?.id],
  () => {
    generation++
    loading.value = false
    rows.value = []
    page.value = 1
    total.value = 0
    notice.value = ''
    if (auth.authenticated) void load()
    else clearTimeout(timer)
  },
  { immediate: true },
)
async function cancel(entry: WaitlistEntry) {
  busy.value = true
  try {
    try {
      await ElMessageBox.confirm(
        '取消等待中的候补后将退出队列，确定继续吗？',
        '取消候补',
        {
          confirmButtonText: '确认取消候补',
          cancelButtonText: '继续等待',
          type: 'warning',
        },
      )
    } catch {
      return
    }
    await waitlistApi.cancel(entry.id)
    notice.value = '候补已取消'
    await load()
  } catch (failure) {
    const message = failure instanceof Error ? failure.message : '取消失败'
    await load()
    error.value = message
  } finally {
    busy.value = false
  }
}
function changePage(value: number) {
  page.value = value
  void load()
}

function waitingForPayment(entry: WaitlistEntry) {
  return entry.status === 'ALLOCATED' && entry.orderStatus === 'PENDING' &&
    !!entry.orderExpiresAt && Date.parse(entry.orderExpiresAt) > Date.now()
}
</script>
<template>
  <div class="orders-page">
    <div class="order-top">
      <div>
        <span class="eyebrow"><span></span>等待下一次相遇</span>
        <h1>{{ id ? '候补详情' : '候补记录' }}</h1>
      </div>
      <ElButton :loading="loading" :disabled="busy" @click="load()"
        >刷新候补</ElButton
      >
    </div>
    <RouterLink v-if="id" to="/waitlist" class="back-link"
      >← 返回我的候补</RouterLink
    >
    <p>
      候补不保证成功。等待期间不占库存或购票额度，分配时重新校验。页面会自动更新，请在订单截止前完成支付。
    </p>
    <p v-if="notice" class="success-notice" role="status">{{ notice }}</p>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <div v-if="loading" class="content-state">正在加载候补…</div>
    <div v-else-if="!error && !rows.length" class="content-state personal-empty">
      <span class="empty-illustration" aria-hidden="true">◷</span>
      <h2>你的等待，会在这里记录</h2>
      <p>目前没有候补申请。喜欢的票档库存不足时，可以申请候补。</p>
      <RouterLink class="empty-action" to="/">发现活动 <span aria-hidden="true">↗</span></RouterLink>
    </div>
    <template v-else>
      <article v-for="entry in rows" :key="entry.id" class="order-card" :data-status="entry.status">
        <div class="order-top">
          <div>
            <span class="section-kicker">候补编号 {{ entry.id }}</span>
            <h2>{{ entry.activityTitle }}</h2>
          </div>
          <span class="ticket-sale-label">{{
            waitlistStatusText[entry.status]
          }}</span>
        </div>
        <p>
          {{ entry.sessionTitle }} · {{ entry.skuName }} ×
          {{ entry.quantity }} 张
        </p>
        <dl>
          <div>
            <dt>当前票价</dt>
            <dd>
              {{ formatCents(entry.priceCents) }} / 张，最终金额以分配订单为准
            </dd>
          </div>
          <div>
            <dt>申请时间</dt>
            <dd>{{ formatTime(entry.createdAt) }}（北京时间）</dd>
          </div>
          <div>
            <dt>候补申请截止</dt>
            <dd>{{ formatTime(entry.expiresAt) }}（北京时间）</dd>
          </div>
        </dl>
        <p v-if="entry.status === 'PROCESSING'" class="order-note">正在为你分配订单，请等待结果后操作；此时暂不能取消候补。</p>
        <p v-if="entry.status === 'ALLOCATED' && entry.orderExpiresAt" class="order-note">
          {{ waitingForPayment(entry) ? '候补成功，请前往订单支付。截止' : '此候补订单的支付截止时间为' }}
          {{ formatTime(entry.orderExpiresAt) }}（北京时间）。
        </p>
        <p v-if="entry.status === 'COMPLETED'" class="order-note">
          候补订单已支付，可前往「我的票夹」查看电子票。
        </p>
        <p v-if="entry.status === 'EXPIRED'">
          候补已过期、不再符合条件，或对应订单已取消/过期。可查看订单了解当前状态。
        </p>
        <div class="auth-links">
          <RouterLink v-if="!id" :to="'/waitlist/' + entry.id"
            >查看候补详情</RouterLink
          ><RouterLink
            v-if="entry.reservedOrderId"
            :to="'/orders/' + entry.reservedOrderId"
            >{{ waitingForPayment(entry) ? '前往支付' : '查看候补订单' }}<span v-if="entry.orderStatus && !waitingForPayment(entry)"
              >（{{ orderStatusText[entry.orderStatus] }}）</span
            ></RouterLink
          ><RouterLink :to="'/activities/' + entry.activityId"
            >查看活动与库存</RouterLink
          ><ElButton
            v-if="entry.canCancel"
            :disabled="busy"
            @click="cancel(entry)"
            >取消候补</ElButton
          >
        </div>
      </article>
    </template>
    <div v-if="!id && total > 10" class="pagination">
      <ElButton
        :disabled="page === 1 || loading || busy"
        @click="changePage(page - 1)"
        >上一页</ElButton
      ><span>第 {{ page }} 页 · 共 {{ total }} 条</span
      ><ElButton
        :disabled="page * 10 >= total || loading || busy"
        @click="changePage(page + 1)"
        >下一页</ElButton
      >
    </div>
  </div>
</template>
