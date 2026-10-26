<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import TicketCredential from '../components/TicketCredential.vue'
import {
  electronicTicketApi,
  electronicTicketStatus,
  type ElectronicTicket,
} from '../api/electronicTickets'
import { formatTime } from '../utils/date'
const props = defineProps<{ id?: string }>()
const route = useRoute()
const rows = ref<ElectronicTicket[]>([])
const page = ref(1)
const total = ref(0)
const status = ref('')
const loading = ref(false)
const error = ref('')
let generation = 0
async function load() {
  const active = ++generation
  loading.value = true
  error.value = ''
  try {
    const orderId =
      typeof route.query.orderId === 'string' ? route.query.orderId : undefined
    const result = props.id
      ? { items: [await electronicTicketApi.detail(props.id)], total: 1 }
      : await electronicTicketApi.list(page.value, orderId, status.value)
    if (active === generation) {
      rows.value = result.items
      total.value = result.total
    }
  } catch (failure) {
    if (active === generation)
      error.value =
        failure instanceof Error ? failure.message : '电子票加载失败，请重试'
  } finally {
    if (active === generation) loading.value = false
  }
}
watch(
  () => [props.id, route.query.orderId],
  () => {
    rows.value = []
    page.value = 1
    void load()
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  generation++
  rows.value = []
})
function changePage(value: number) {
  page.value = value
  void load()
}
function filterTickets() {
  page.value = 1
  void load()
}
</script>
<template>
  <div class="orders-page">
    <div class="order-top">
      <div>
        <span class="eyebrow"><span></span>你的入场凭证</span>
        <h1>{{ id ? '电子票详情' : '我的票夹' }}</h1>
      </div>
      <ElButton :loading="loading" @click="load">刷新电子票</ElButton>
    </div>
    <RouterLink v-if="id" class="back-link" to="/tickets"
      >← 返回我的票夹</RouterLink
    >
    <p>
      每张电子票独立核销一次。已核销或已作废的票券不能再次入场，二维码请妥善保管。
    </p>
    <form v-if="!id" class="ticket-filters" @submit.prevent="filterTickets">
      <label
        >票券状态<select v-model="status">
          <option value="">全部状态</option>
          <option value="UNUSED">未使用</option>
          <option value="USED">已核销</option>
          <option value="VOID">已作废</option>
        </select></label
      ><ElButton native-type="submit" :disabled="loading">筛选电子票</ElButton
      ><RouterLink v-if="$route.query.orderId" to="/tickets"
        >查看全部订单的电子票</RouterLink
      >
    </form>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <div v-if="loading" class="content-state">正在加载电子票…</div>
    <div v-else-if="!error && !rows.length" class="content-state personal-empty">
      <span class="empty-illustration" aria-hidden="true">▱</span>
      <h2>等待属于你的入场时刻</h2>
      <p>当前条件下暂无电子票。订单支付成功后，票券将自动出现在这里。</p>
      <RouterLink class="empty-action" to="/orders">查看我的订单 <span aria-hidden="true">↗</span></RouterLink>
    </div>
    <template v-else>
      <article v-for="ticket in rows" :key="ticket.id" class="order-card electronic-ticket-card" :data-status="ticket.status">
        <div class="order-top">
          <div>
            <span class="section-kicker"
              >独立电子票 · 第 {{ ticket.sequenceNo }} 张</span
            >
            <h2>{{ ticket.activityTitle }}</h2>
          </div>
          <span class="ticket-sale-label">{{
            electronicTicketStatus[ticket.status]
          }}</span>
        </div>
        <p>{{ ticket.sessionTitle }} · {{ ticket.skuName }}</p>
        <dl>
          <div>
            <dt>演出时间</dt>
            <dd>
              {{ formatTime(ticket.sessionStartsAt) }} —
              {{ formatTime(ticket.sessionEndsAt) }}（北京时间）
            </dd>
          </div>
          <div>
            <dt>场馆</dt>
            <dd>{{ ticket.city }} · {{ ticket.venue }}</dd>
          </div>
          <div>
            <dt>持票人</dt>
            <dd>{{ ticket.ownerName }}</dd>
          </div>
          <div>
            <dt>电子票编号</dt>
            <dd class="ticket-number">{{ ticket.ticketNo }}</dd>
          </div>
          <div v-if="ticket.usedAt">
            <dt>核销时间</dt>
            <dd>{{ formatTime(ticket.usedAt) }}（北京时间）</dd>
          </div>
        </dl>
        <TicketCredential v-if="id && ticket.status === 'UNUSED'" :key="ticket.id + ':' + generation" :ticket-id="ticket.id" />
        <p v-if="ticket.status !== 'UNUSED'" class="order-note">
          {{
            ticket.status === 'USED'
              ? '此票已核销，不能再次作为入场凭证。'
              : '此票已作废，不能作为入场凭证。'
          }}
        </p>
        <div class="auth-links">
          <RouterLink v-if="!id" :to="'/tickets/' + ticket.id"
            >{{ ticket.status === 'UNUSED' ? '查看电子票与二维码' : '查看电子票详情' }}</RouterLink
          ><RouterLink :to="'/orders/' + ticket.orderId"
            >查看所属订单</RouterLink
          >
        </div>
      </article>
    </template>
    <div v-if="!id && total > 10" class="pagination">
      <ElButton :disabled="page === 1 || loading" @click="changePage(page - 1)"
        >上一页</ElButton
      ><span>第 {{ page }} 页 · 共 {{ total }} 张</span
      ><ElButton
        :disabled="page * 10 >= total || loading"
        @click="changePage(page + 1)"
        >下一页</ElButton
      >
    </div>
  </div>
</template>
