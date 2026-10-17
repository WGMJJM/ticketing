<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import axios from 'axios'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
import { useAuthStore } from '../stores/auth'
import { orderApi, type PurchaseLimit } from '../api/orders'
import type { TicketSku } from '../api/tickets'
import { formatCents } from '../utils/money'
import { chineseValidation, clearValidation } from '../utils/validation'
const props = defineProps<{ ticket: TicketSku }>()
const emit = defineEmits<{ changed: [] }>()
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const quantity = ref(1)
const limit = ref<PurchaseLimit>()
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const uncertain = ref(false)
let attempt: { quantity: number; key: string } | undefined
const maximum = computed(() =>
  Math.min(
    props.ticket.availableStock,
    props.ticket.maxQuantityPerOrder,
    limit.value?.remainingQuantity ?? props.ticket.maxQuantityPerOrder,
  ),
)
const estimate = computed(() =>
  formatCents(
    (
      BigInt(props.ticket.priceCents) *
      BigInt(Number.isInteger(quantity.value) ? quantity.value : 0)
    ).toString(),
  ),
)
async function loadLimit() {
  if (!auth.authenticated) return
  loading.value = true
  error.value = ''
  limit.value = undefined
  try {
    limit.value = await orderApi.limits(props.ticket.sessionId)
  } catch (failure) {
    error.value =
      failure instanceof Error ? failure.message : '限购信息加载失败'
  } finally {
    loading.value = false
  }
}
watch(
  () => auth.user?.id,
  () => {
    attempt = undefined
    uncertain.value = false
    quantity.value = 1
    void loadLimit()
  },
  { immediate: true },
)
watch(quantity, () => {
  if (!uncertain.value) attempt = undefined
})
async function purchase() {
  if (!auth.authenticated) {
    await router.push({ path: '/login', query: { redirect: route.path } })
    return
  }
  if (
    !Number.isInteger(quantity.value) ||
    quantity.value < 1 ||
    quantity.value > maximum.value
  ) {
    error.value = '请按库存与限购范围选择数量'
    return
  }
  busy.value = true
  error.value = ''
  try {
    if (!uncertain.value) {
      try {
        await ElMessageBox.confirm(
          `确认预订“${props.ticket.name}”${quantity.value}张？预计${estimate.value}，金额以服务端订单为准。提交后将占用库存，支付功能尚未开放。`,
          '确认购票订单',
          {
            confirmButtonText: '确认提交订单',
            cancelButtonText: '再想想',
            type: 'warning',
          },
        )
      } catch {
        return
      }
    }
    if (!attempt)
      attempt = { quantity: quantity.value, key: crypto.randomUUID() }
    const order = await orderApi.create(
      props.ticket.id,
      attempt.quantity,
      attempt.key,
    )
    attempt = undefined
    uncertain.value = false
    emit('changed')
    await router.push('/orders/' + order.id)
  } catch (failure) {
    uncertain.value =
      axios.isAxiosError(failure) &&
      (!failure.response || failure.response.status >= 500)
    if (!uncertain.value) {
      attempt = undefined
      void loadLimit()
    }
    error.value =
      (failure instanceof Error ? failure.message : '提交订单失败') +
      (uncertain.value
        ? '。结果尚未确认，请保持数量并用同一请求重试，或先查看我的订单。'
        : '')
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <form
    class="purchase-panel"
    @submit.prevent="purchase"
    @invalid.capture="chineseValidation"
    @input.capture="clearValidation"
    @change.capture="clearValidation"
  >
    <template v-if="auth.authenticated">
      <p v-if="loading">正在读取限购信息…</p>
      <p v-else-if="limit">
        本场限购 {{ limit.maxPerSession }} 张，已占用
        {{ limit.usedQuantity }} 张，还可购买 {{ limit.remainingQuantity }} 张
      </p>
      <ElButton v-else @click="loadLimit">重试限购查询</ElButton>
    </template>
    <label :for="'quantity-' + ticket.id">购买数量</label>
    <input
      :id="'quantity-' + ticket.id"
      v-model.number="quantity"
      type="number"
      min="1"
      :max="maximum"
      step="1"
      required
      :disabled="busy || uncertain || maximum < 1"
      :aria-label="ticket.name + '购买数量'"
    />
    <span class="purchase-estimate">预计 {{ estimate }}</span>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <ElButton
      type="primary"
      native-type="submit"
      :loading="busy"
      :disabled="maximum < 1 || (auth.authenticated && (!limit || loading))"
    >
      {{
        !auth.authenticated
          ? '登录后购票'
          : uncertain
            ? '重试确认该订单'
            : '提交购票订单'
      }}
    </ElButton>
    <RouterLink v-if="auth.authenticated" to="/orders">查看我的订单</RouterLink>
  </form>
</template>
