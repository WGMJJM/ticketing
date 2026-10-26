<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import axios from 'axios'
import { useAuthStore } from '../stores/auth'
import { purchaseApi, purchaseStatusText, type PurchaseRequest } from '../api/orders'
import { readAttempt, clearAttempt } from '../utils/purchaseAttempts'
import { formatTime } from '../utils/date'
const props = defineProps<{ id?: string }>()
const router = useRouter()
const auth = useAuthStore()
const rows = ref<PurchaseRequest[]>([])
const loading = ref(false)
const error = ref('')
const paused = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined
let controller: AbortController | undefined
let generation = 0
let polls = 0
let disposed = false
function stop() { generation++; clearTimeout(timer); controller?.abort(); loading.value = false }
async function load(restart = false) {
  if (disposed || !auth.user || document.hidden) return
  if (restart) { stop(); polls = 0; paused.value = false }
  if (loading.value) return
  const active = generation
  const userId = auth.user.id
  controller = new AbortController()
  loading.value = true
  error.value = ''
  try {
    const result = props.id ? [await purchaseApi.detail(props.id, controller.signal)] : await purchaseApi.active(controller.signal)
    if (active !== generation || disposed) return
    rows.value = result
    for (const request of result) {
      if (['SUCCEEDED', 'FAILED', 'EXPIRED'].includes(request.status)) {
        const attempt = readAttempt(userId, request.skuId)
        if (attempt?.requestId === request.requestId) clearAttempt(userId, request.skuId, attempt.key)
      }
    }
    const success = props.id ? result.find(request => request.status === 'SUCCEEDED' && request.orderId) : undefined
    if (success) { await router.replace('/orders/' + success.orderId); return }
  } catch (failure) {
    if (active !== generation || axios.isCancel(failure)) return
    error.value = failure instanceof Error ? failure.message : '排队状态读取失败，请稍后刷新'
    if (axios.isAxiosError(failure) && [400, 401, 403, 404].includes(failure.response?.status ?? 0)) polls = 60
  } finally {
    if (active === generation && !disposed) {
      loading.value = false
      polls++
      const pending = rows.value.some(request => request.status === 'QUEUED' || request.status === 'PROCESSING')
      if ((pending || error.value) && polls < 60 && !document.hidden) timer = setTimeout(() => void load(), Math.min(5000, 2000 + polls * 100))
      else if (pending || error.value) paused.value = true
    }
  }
}
watch(() => [props.id, auth.user?.id], () => { rows.value = []; stop(); void load(true) }, { immediate: true })
function visibility() { if (document.hidden) stop(); else void load(true) }
document.addEventListener('visibilitychange', visibility)
onBeforeUnmount(() => { disposed = true; stop(); document.removeEventListener('visibilitychange', visibility) })
</script>
<template>
  <div class="orders-page">
    <div class="order-top"><div><span class="eyebrow"><span></span>我的购票申请</span><h1>购票进度</h1></div>
      <ElButton :loading="loading" @click="load(true)">刷新进度</ElButton></div>
    <p>申请受理后会暂时预扣库存，不代表购票成功。订单创建后，请在截止时间前完成支付；失败或超时会释放预扣。</p>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <p v-if="!auth.authenticated" class="stage-notice" role="status">登录状态已失效，请重新登录后查看原申请。<RouterLink :to="{path:'/login',query:{redirect:id ? '/purchase-requests/'+id : '/purchase-requests'}}">重新登录 →</RouterLink></p>
    <p v-if="paused" class="stage-notice" role="status">已暂停自动查询，页面不会因此改变服务端处理结果，可稍后手动刷新。</p>
    <div v-if="auth.authenticated && !loading && !error && !rows.length" class="content-state">当前没有待处理的购票申请。这里显示最近 50 笔进行中的申请。</div>
    <article v-for="request in rows" :key="request.requestId" class="order-card">
      <div class="order-card-heading"><h2>{{ purchaseStatusText[request.status] }}</h2><span class="state-pill">申请 {{ request.requestId }}</span></div>
      <p>票档 {{ request.skuId }} · {{ request.quantity }} 张</p>
      <p>受理时间 {{ formatTime(request.acceptedAt) }} · 排队截止 {{ formatTime(request.deadlineAt) }}</p>
      <p v-if="request.failureMessage" class="inline-error" role="status">{{ request.failureMessage }}</p>
      <RouterLink v-if="!id" :to="'/purchase-requests/' + request.requestId">查看申请进度 →</RouterLink>
      <RouterLink v-if="request.orderId" :to="'/orders/' + request.orderId">查看正式订单 →</RouterLink>
    </article>
    <div class="hero-actions"><RouterLink to="/orders">查看我的订单 →</RouterLink><RouterLink to="/waitlist">查看缺票候补 →</RouterLink></div>
  </div>
</template>
