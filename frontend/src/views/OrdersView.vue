<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
import { orderApi, type Order } from '../api/orders'
import OrderCard from '../components/OrderCard.vue'
import { formatCents } from '../utils/money'
const props = defineProps<{ id?: string }>()
const rows = ref<Order[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')
let generation = 0
async function load() {
  const active = ++generation
  loading.value = true
  error.value = ''
  try {
    const result = props.id
      ? { items: [await orderApi.detail(props.id)], total: 1 }
      : await orderApi.list(page.value)
    if (active === generation) {
      rows.value = result.items
      total.value = result.total
    }
  } catch (failure) {
    if (active === generation)
      error.value =
        failure instanceof Error ? failure.message : '订单加载失败，请重试'
  } finally {
    if (active === generation) loading.value = false
  }
}
watch(
  () => props.id,
  () => {
    rows.value = []
    page.value = 1
    notice.value = ''
    void load()
  },
  { immediate: true },
)
async function cancel(order: Order) {
  busy.value = true
  try {
    try {
      await ElMessageBox.confirm(
        '取消后将释放该订单的库存与场次限购额度，确定取消吗？',
        '取消购票订单',
        {
          confirmButtonText: '确认取消订单',
          cancelButtonText: '保留订单',
          type: 'warning',
        },
      )
    } catch {
      return
    }
    await orderApi.cancel(order.id)
    notice.value = '订单已取消，库存与限购额度已释放'
    await load()
  } catch (failure) {
    const message =
      failure instanceof Error ? failure.message : '取消失败，请刷新后重试'
    await load()
    error.value = message
  } finally {
    busy.value = false
  }
}
async function pay(order: Order) {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const current = await orderApi.detail(order.id)
    if (!current.canPay) throw new Error('订单当前不可支付，请查看最新状态')
    try {
      await ElMessageBox.confirm(
        `${current.activityTitle}，${current.sessionTitle}，${current.skuName} ${current.quantity}张，应付${formatCents(current.totalAmountCents)}。这是模拟支付，不会产生实际扣款。确认继续吗？`,
        '确认模拟支付',
        {
          confirmButtonText: '确认模拟支付',
          cancelButtonText: '暂不支付',
          type: 'warning',
        },
      )
    } catch {
      return
    }
    const payment = await orderApi.pay(order.id)
    notice.value = '模拟支付成功，未产生实际扣款。流水号 ' + payment.paymentNo
    await load()
  } catch (failure) {
    const message =
      failure instanceof Error ? failure.message : '模拟支付失败，请刷新后重试'
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
</script>
<template>
  <div class="orders-page">
    <div class="order-top">
      <div>
        <span class="eyebrow"><span></span>我的现场安排</span>
        <h1>{{ id ? '订单详情' : '我的订单' }}</h1>
      </div>
      <ElButton :loading="loading" :disabled="busy" @click="load"
        >刷新订单</ElButton
      >
    </div>
    <RouterLink v-if="id" to="/orders" class="back-link"
      >← 返回我的订单</RouterLink
    >
    <p>
      模拟支付仅用于功能体验，不产生实际扣款。超时订单将自动关闭，可刷新查看最新状态。
    </p>
    <p v-if="notice" class="success-notice" role="status">{{ notice }}</p>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <div v-if="loading" class="content-state" role="status">正在加载订单…</div>
    <div v-else-if="!error && rows.length === 0" class="content-state">
      暂无订单，去发现喜欢的活动吧。<RouterLink to="/">发现活动</RouterLink>
    </div>
    <OrderCard
      v-for="order in rows"
      v-else
      :key="order.id"
      :order="order"
      :detail="!!id"
      :busy="busy || loading"
      @cancel="cancel"
      @pay="pay"
    />
    <div v-if="!id && total > 10" class="pagination">
      <ElButton
        :disabled="page === 1 || loading || busy"
        @click="changePage(page - 1)"
        >上一页</ElButton
      ><span>第 {{ page }} 页 · 共 {{ total }} 笔</span
      ><ElButton
        :disabled="page * 10 >= total || loading || busy"
        @click="changePage(page + 1)"
        >下一页</ElButton
      >
    </div>
  </div>
</template>
