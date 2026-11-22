<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import axios from 'axios'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
import { useAuthStore } from '../stores/auth'
import type { PurchaseLimit } from '../api/orders'
import { waitlistApi } from '../api/waitlist'
import type { TicketSku } from '../api/tickets'
import { chineseValidation, clearValidation } from '../utils/validation'
const props = defineProps<{ ticket: TicketSku; limit?: PurchaseLimit }>()
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const busy = ref(false)
const error = ref('')
const minimum = 1
const quantity = ref(1)
const maximum = computed(() =>
  Math.min(
    props.ticket.maxQuantityPerOrder,
    props.limit?.remainingQuantity ?? props.ticket.maxQuantityPerOrder,
  ),
)
const show = computed(() => minimum <= maximum.value)
let mounted = true
onBeforeUnmount(() => { mounted = false })
async function join() {
  if (busy.value) return
  const userId = auth.user?.id
  if (!userId) {
    await router.push({ path: '/login', query: { redirect: route.path } })
    return
  }
  if (
    !props.limit || !Number.isInteger(quantity.value) ||
    quantity.value < minimum ||
    quantity.value > maximum.value
  ) {
    error.value = '请选择库存不足且符合限购的候补数量'
    return
  }
  busy.value = true
  error.value = ''
  const skuId = props.ticket.id
  const requestedQuantity = quantity.value
  try {
    try {
      await ElMessageBox.confirm(
        `申请“${props.ticket.name}”${quantity.value}张候补？等待期间不占库存或购票额度，不保证分配。分配后需在对应订单截止前完成支付，金额以订单为准。`,
        '确认候补申请',
        {
          confirmButtonText: '确认申请候补',
          cancelButtonText: '再想想',
          type: 'warning',
        },
      )
    } catch {
      return
    }
    if (!mounted || auth.user?.id !== userId) return
    if (!props.limit || requestedQuantity > maximum.value ||
        !['SELLING', 'SOLD_OUT'].includes(props.ticket.saleStatus)) {
      error.value = '票档或限购已变化，请重新确认候补数量'
      return
    }
    const entry = await waitlistApi.join(skuId, requestedQuantity)
    if (mounted && auth.user?.id === userId) await router.push('/waitlist/' + entry.id)
  } catch (failure) {
    const rejected = axios.isAxiosError(failure) &&
      (failure.response?.status === 429 || failure.response?.data?.code === 'RATE_LIMIT_UNAVAILABLE')
    error.value =
      (failure instanceof Error ? failure.message : '候补申请失败') +
      (rejected ? '' : '。结果不明时可先查看我的候补，重复同数量申请会返回原有效记录。')
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <form
    v-if="show"
    class="purchase-panel waitlist-purchase"
    @submit.prevent="join"
    @invalid.capture="chineseValidation"
    @input.capture="clearValidation"
    @change.capture="clearValidation"
  >
    <p>
      库存不足或已有候补排队时可申请，按记录顺序优先分配；不足以满足队首时会继续等待。
    </p>
    <label :for="'wait-quantity-' + ticket.id">候补数量</label>
    <input
      :id="'wait-quantity-' + ticket.id"
      v-model.number="quantity"
      type="number"
      :min="minimum"
      :max="maximum"
      step="1"
      required
      :disabled="busy"
      :aria-label="ticket.name + '候补数量'"
    />
    <ElButton
      native-type="submit"
      :loading="busy"
      :disabled="auth.authenticated && !limit"
      >{{ auth.authenticated ? '申请候补' : '登录后申请候补' }}</ElButton
    >
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <RouterLink v-if="auth.authenticated" to="/waitlist"
      >查看我的候补</RouterLink
    >
  </form>
</template>
