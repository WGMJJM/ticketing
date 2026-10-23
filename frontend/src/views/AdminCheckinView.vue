<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import CheckinScanner from '../components/CheckinScanner.vue'
import type { ElectronicTicket } from '../api/electronicTickets'
import { formatTime } from '../utils/date'
import { staffApi, type StaffPermission } from '../api/staff'
import { useAuthStore } from '../stores/auth'
const props = defineProps<{ staff?: boolean }>()
const auth = useAuthStore()
const result = ref<ElectronicTicket>()
const sessions = ref<StaffPermission[]>([])
const page = ref(1)
const total = ref(0)
const loading = ref(false)
const error = ref('')
let sessionGeneration = 0
async function loadSessions() {
  const current = ++sessionGeneration
  loading.value = true
  error.value = ''
  try {
    const data = await staffApi.available(page.value)
    if (current === sessionGeneration) { sessions.value = data.items; total.value = data.total }
  } catch (failure) {
    if (current === sessionGeneration) { sessions.value = []; error.value = failure instanceof Error ? failure.message : '场次授权读取失败' }
  } finally { if (current === sessionGeneration) loading.value = false }
}
watch(() => [props.staff, auth.canCheckin], () => {
  sessionGeneration++
  result.value = undefined
  sessions.value = []
  if (props.staff && auth.canCheckin) void loadSessions()
}, { immediate: true })
onBeforeUnmount(() => { sessionGeneration++ })
</script>
<template>
  <div class="orders-page">
    <span class="eyebrow"><span></span>{{ staff ? '工作人员入口' : '运营工作台' }}</span>
    <h1>电子票核销</h1>
    <RouterLink v-if="!staff" class="back-link" to="/admin/activities"
      >← 返回活动与场次管理</RouterLink
    >
    <p>
      仅工作人员可核销，默认从演出开始前60分钟开放至场次结束。每张票只能核销一次，成功后请核对场次。
    </p>
    <section v-if="staff" class="order-card">
      <h2>{{ auth.isAdmin ? '可核销场次' : '我的核销场次' }}</h2>
      <p>仅展示当前有效授权。每次核销由服务端再次校验账号与实际票券所属场次。</p>
      <ElButton :loading="loading" @click="loadSessions">刷新场次授权</ElButton>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <p v-else-if="!loading && !sessions.length">暂无可核销场次，请联系管理员分配。</p>
      <ul v-else><li v-for="session in sessions" :key="session.sessionId">
        {{ session.activityTitle }} · {{ session.sessionTitle }} · {{ formatTime(session.startsAt) }}（北京时间）
      </li></ul>
      <div class="auth-links" v-if="total > 10">
        <ElButton :disabled="page <= 1 || loading" @click="page--; loadSessions()">上一页</ElButton>
        <span>第 {{ page }} 页 · 共 {{ total }} 场</span>
        <ElButton :disabled="page * 10 >= total || loading" @click="page++; loadSessions()">下一页</ElButton>
      </div>
    </section>
    <CheckinScanner v-if="auth.canCheckin" :key="staff ? 'staff' : 'admin'" :staff="staff" @checked="result = $event" @reset="result = undefined" />
    <p v-if="result" class="success-notice" role="status">核销成功，可以入场。</p>
    <section v-if="result" class="order-card">
      <h2>核销成功 · {{ result.activityTitle }}</h2>
      <p>
        {{ result.sessionTitle }} · {{ result.skuName }} · 第
        {{ result.sequenceNo }} 张
      </p>
      <dl>
        <div>
          <dt>场馆</dt>
          <dd>{{ result.city }} · {{ result.venue }}</dd>
        </div>
        <div>
          <dt>持票人</dt>
          <dd>{{ result.ownerName }}</dd>
        </div>
        <div>
          <dt>票号</dt>
          <dd class="ticket-number">{{ result.ticketNo }}</dd>
        </div>
        <div>
          <dt>核销时间</dt>
          <dd>
            {{ result.usedAt ? formatTime(result.usedAt) : '' }}（北京时间）
          </dd>
        </div>
        <div>
          <dt>工作人员编号</dt>
          <dd>{{ result.checkedBy }}</dd>
        </div>
      </dl>
    </section>
  </div>
</template>
