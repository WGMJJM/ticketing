<script setup lang="ts">
import { onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { adminOrdersApi, type AdminOrder, type AdminOrderQuery } from '../api/adminOrders'
import { orderStatusText } from '../api/orders'
import { formatCents } from '../utils/money'
import { formatTime, fromBeijingInput } from '../utils/date'
import { chineseValidation, clearValidation } from '../utils/validation'
import AdminOrderDetail from '../components/AdminOrderDetail.vue'

const props = defineProps<{ id?: string }>()
const route = useRoute()
const query = reactive<AdminOrderQuery>({ orderNo: '', userId: '', username: '', activityId: '', sessionId: '', status: '', page: 1, size: 10 })
const createdFrom = ref('')
const createdTo = ref('')
const rows = ref<AdminOrder[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
let generation = 0
async function load(reset = false) {
  if (reset) query.page = 1
  const current = ++generation
  loading.value = true
  error.value = ''
  try {
    if (createdFrom.value && createdTo.value && createdFrom.value >= createdTo.value) throw new Error('创建时间范围的结束必须晚于开始')
    const params = Object.fromEntries(Object.entries(query).filter(([, value]) => value !== '')) as unknown as AdminOrderQuery
    params.createdFrom = createdFrom.value ? fromBeijingInput(createdFrom.value) : undefined
    params.createdTo = createdTo.value ? fromBeijingInput(createdTo.value) : undefined
    const result = await adminOrdersApi.list(params)
    if (current === generation) { rows.value = result.items; total.value = result.total }
  } catch (failure) {
    if (current === generation) error.value = failure instanceof Error ? failure.message : '订单加载失败'
  } finally { if (current === generation) loading.value = false }
}
watch(() => props.id, () => {
  generation++
  if (!props.id) { query.sessionId = typeof route.query.sessionId === 'string' ? route.query.sessionId : ''; void load(true) }
}, { immediate: true })
onBeforeUnmount(() => { generation++ })
function changePage(value: number) { query.page = value; void load() }
</script>
<template>
  <section class="admin-orders-page">
    <header class="order-top"><div><span class="eyebrow"><span></span>运营工作台</span><h1>{{ id ? '运营订单详情' : '订单运营' }}</h1></div><RouterLink class="back-link" to="/admin/activities">活动与场次管理 ↗</RouterLink></header>
    <p class="admin-order-intro">从订单到票券，核对每一笔现场安排。售后仅为模拟退款，不产生实际资金退回。</p>
    <AdminOrderDetail v-if="id" :id="id" />
    <template v-else>
      <form class="admin-order-filters" @submit.prevent="load(true)" @invalid.capture="chineseValidation" @input.capture="clearValidation">
        <label>订单号<input v-model.trim="query.orderNo" maxlength="64" placeholder="完整订单号" /></label>
        <label>用户名<input v-model.trim="query.username" maxlength="64" placeholder="完整用户名" /></label>
        <label>用户编号<input v-model="query.userId" inputmode="numeric" pattern="[1-9][0-9]*" placeholder="用户编号" /></label>
        <label>活动编号<input v-model="query.activityId" inputmode="numeric" pattern="[1-9][0-9]*" placeholder="活动编号" /></label>
        <label>场次编号<input v-model="query.sessionId" inputmode="numeric" pattern="[1-9][0-9]*" placeholder="场次编号" /></label>
        <label>订单状态<select v-model="query.status"><option value="">全部状态</option><option v-for="(label, value) in orderStatusText" :key="value" :value="value">{{ label }}</option></select></label>
        <label>创建时间起（北京）<input v-model="createdFrom" type="datetime-local" /></label>
        <label>创建时间止（北京）<input v-model="createdTo" type="datetime-local" /></label>
        <ElButton type="primary" native-type="submit" :loading="loading">查询订单</ElButton>
      </form>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <p v-if="loading" class="content-state" role="status">正在加载订单…</p>
      <p v-else-if="!error && !rows.length" class="content-state">当前条件下暂无订单，试试调整筛选条件。</p>
      <div v-else-if="!error" class="admin-orders-table" tabindex="0" aria-label="订单列表，可横向滚动">
        <table><thead><tr><th>订单与用户</th><th>活动与场次</th><th>票档快照</th><th>金额与状态</th><th>时间（北京）</th><th>操作</th></tr></thead>
          <tbody><tr v-for="row in rows" :key="row.order.id"><td><strong>{{ row.order.orderNo }}</strong><small>{{ row.username }} · {{ row.userId }}</small></td><td>{{ row.order.activityTitle }}<small>{{ row.order.sessionTitle }} · {{ formatTime(row.order.sessionStartsAt) }}</small></td><td>{{ row.order.skuName ?? '明细缺失，待核对' }}<template v-if="row.order.quantity != null"> × {{ row.order.quantity }}</template><small>单价 {{ row.order.unitPriceCents == null ? '待核对' : formatCents(row.order.unitPriceCents) }}</small></td><td><strong>{{ formatCents(row.order.totalAmountCents) }}</strong><small class="order-state" :data-status="row.order.status">{{ orderStatusText[row.order.status] }}</small></td><td><small>创建 {{ formatTime(row.order.createdAt) }}</small><small>截止 {{ formatTime(row.order.expiresAt) }}</small><small v-if="row.paidAt">支付 {{ formatTime(row.paidAt) }}</small></td><td><RouterLink :to="'/admin/orders/' + row.order.id">查看详情 ↗</RouterLink></td></tr></tbody>
        </table>
      </div>
      <div class="pagination"><ElButton :disabled="query.page === 1 || loading" @click="changePage(query.page - 1)">上一页</ElButton><span>第 {{ query.page }} 页 · 共 {{ total }} 笔</span><ElButton :disabled="query.page * query.size >= total || loading" @click="changePage(query.page + 1)">下一页</ElButton></div>
    </template>
  </section>
</template>
