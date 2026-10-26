<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import axios from 'axios'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
import { useAuthStore } from '../stores/auth'
import { orderApi, type PurchaseLimit } from '../api/orders'
import { waitlistApi } from '../api/waitlist'
import type { TicketSku } from '../api/tickets'
import { chineseValidation, clearValidation } from '../utils/validation'
const props = defineProps<{ ticket: TicketSku }>()
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const limit = ref<PurchaseLimit>()
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const minimum = computed(() => Math.max(1, props.ticket.availableStock + 1))
const quantity = ref(minimum.value)
const maximum = computed(() =>
  Math.min(
    props.ticket.maxQuantityPerOrder,
    limit.value?.remainingQuantity ?? props.ticket.maxQuantityPerOrder,
  ),
)
const show = computed(() => minimum.value <= maximum.value)
async function loadLimit() {
  limit.value = undefined
  if (!auth.authenticated) return
  loading.value = true
  error.value = ''
  try {
    limit.value = await orderApi.limits(props.ticket.sessionId)
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '限购查询失败'
  } finally {
    loading.value = false
  }
}
watch(() => auth.user?.id, loadLimit, { immediate: true })
async function join() {
  if (!auth.authenticated) {
    await router.push({ path: '/login', query: { redirect: route.path } })
    return
  }
  if (
    !Number.isInteger(quantity.value) ||
    quantity.value < minimum.value ||
    quantity.value > maximum.value
  ) {
    error.value = '请选择库存不足且符合限购的候补数量'
    return
  }
  busy.value = true
  error.value = ''
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
    const entry = await waitlistApi.join(props.ticket.id, quantity.value)
    await router.push('/waitlist/' + entry.id)
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
      库存不足时可申请同票档候补，按候补记录顺序处理；库存不足以满足队首时不会跳过。
    </p>
    <p v-if="limit">
      本场还可购买 {{ limit.remainingQuantity }} 张，分配时将再次检查限购。
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
      :disabled="loading || (auth.authenticated && !limit)"
      >{{ auth.authenticated ? '申请候补' : '登录后申请候补' }}</ElButton
    >
    <ElButton v-if="auth.authenticated && !limit && !loading" @click="loadLimit"
      >重试限购查询</ElButton
    >
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <RouterLink v-if="auth.authenticated" to="/waitlist"
      >查看我的候补</RouterLink
    >
  </form>
</template>
