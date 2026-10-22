<script setup lang="ts">
import { onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
import { adminApi } from '../api/admin'
import { sessionStatusText, type Activity, type ActivitySession, type SessionInput } from '../api/activities'
import { formatTime, fromBeijingInput, toBeijingInput } from '../utils/date'
import { chineseValidation, clearValidation } from '../utils/validation'
import TicketManager from './TicketManager.vue'
const props = defineProps<{ activity: Activity }>()
const open = defineModel<boolean>({ default: false })
const error = ref('')
const notice = ref('')
const saving = ref(false)
let generation = 0
const ticketSession = ref<ActivitySession>()
const ticketsDialog = ref(false)
function openTickets(session: ActivitySession) {
  ticketSession.value = session
  ticketsDialog.value = true
}
const sessions = ref<ActivitySession[]>([])
const sessionsLoading = ref(false)
const sessionEditing = ref<ActivitySession>()
const sessionFormOpen = ref(false)
const sessionForm = reactive({
  title: '',
  startsAt: '',
  endsAt: '',
  saleStartsAt: '',
})
function report(failure: unknown) {
  error.value = failure instanceof Error ? failure.message : '操作失败，请稍后重试'
}
async function loadSessions() {
  const active = ++generation
  sessionsLoading.value = true
  try {
    const result = await adminApi.sessions(props.activity.id)
    if (active === generation) sessions.value = result
  } catch (failure) {
    if (active === generation) report(failure)
  } finally {
    if (active === generation) sessionsLoading.value = false
  }
}
function editSession(session?: ActivitySession) {
  sessionEditing.value = session
  Object.assign(sessionForm, {
    title: session?.title ?? '',
    startsAt: session ? toBeijingInput(session.startsAt) : '',
    endsAt: session ? toBeijingInput(session.endsAt) : '',
    saleStartsAt: session ? toBeijingInput(session.saleStartsAt) : '',
  })
  sessionFormOpen.value = true
  error.value = ''
}
async function saveSession() {
  if (
    sessionForm.endsAt <= sessionForm.startsAt ||
    sessionForm.saleStartsAt >= sessionForm.startsAt
  ) {
    error.value = '结束时间必须晚于开始时间，开售时间必须早于开始时间'
    return
  }
  saving.value = true
  error.value = ''
  const payload: SessionInput = {
    title: sessionForm.title,
    startsAt: fromBeijingInput(sessionForm.startsAt),
    endsAt: fromBeijingInput(sessionForm.endsAt),
    saleStartsAt: fromBeijingInput(sessionForm.saleStartsAt),
    ...(sessionEditing.value ? { version: sessionEditing.value.version } : {}),
  }
  try {
    if (sessionEditing.value)
      await adminApi.updateSession(sessionEditing.value.id, payload)
    else await adminApi.createSession(props.activity.id, payload)
    sessionFormOpen.value = false
    notice.value = '场次已保存'
    await loadSessions()
  } catch (failure) {
    report(failure)
  } finally {
    saving.value = false
  }
}
async function cancelSession(session: ActivitySession) {
  try {
    await ElMessageBox.confirm(
      `确定取消“${session.title}”吗？取消后不可恢复。系统将逐单关闭待支付订单，并对未核销的已付订单整单模拟退款；已核销或异常订单需人工处理，不发生实际资金退回。`,
      '取消场次',
      {
        confirmButtonText: '确认取消场次',
        cancelButtonText: '保留场次',
        type: 'warning',
      },
    )
  } catch {
    return
  }
  saving.value = true
  error.value = ''
  try {
    await adminApi.cancelSession(session.id, session.version)
    notice.value = '场次已取消，存量订单将由后台逐单处理。请在订单运营中查询善后结果。'
    await loadSessions()
  } catch (failure) {
    report(failure)
  } finally {
    saving.value = false
  }
}
watch(() => [open.value, props.activity.id], () => {
  generation++
  sessionsLoading.value = false
  ticketsDialog.value = false
  ticketSession.value = undefined
  if (!open.value) return
  sessions.value = []
  sessionFormOpen.value = false
  error.value = ''
  notice.value = ''
  void loadSessions()
}, { immediate: true })
onBeforeUnmount(() => { generation++ })
</script>
<template>
    <ElDialog
      v-model="open"
      :title="`${activity.title} · 场次管理`"
      width="min(900px, 94vw)"
      :close-on-click-modal="false"
      :close-on-press-escape="!saving"
      :show-close="!saving"
      destroy-on-close
    >
      <p v-if="notice" class="success-notice" role="status">{{ notice }}</p>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <p class="form-hint">
        所有时间均为北京时间，每个活动最多200个场次；取消场次仍保留在用户列表中并明确标记。
      </p>
      <ElButton
        v-if="!sessionFormOpen"
        type="primary"
        :disabled="sessionsLoading"
        @click="editSession()"
        >新增场次</ElButton
      >
      <p v-if="sessionsLoading" role="status">正在加载场次…</p>
      <form
        v-if="sessionFormOpen"
        class="edit-form session-edit"
        @submit.prevent="saveSession"
        @invalid.capture="chineseValidation"
        @input.capture="clearValidation"
        @change.capture="clearValidation"
      >
        <h3>{{ sessionEditing ? '编辑场次' : '新增场次' }}</h3>
        <label
          >场次名称<input v-model="sessionForm.title" required maxlength="100"
        /></label>
        <div class="form-pair">
          <label
            >开始时间（北京时间）<input
              v-model="sessionForm.startsAt"
              type="datetime-local"
              required /></label
          ><label
            >结束时间（北京时间）<input
              v-model="sessionForm.endsAt"
              type="datetime-local"
              required
          /></label>
        </div>
        <label
          >开售时间（北京时间）<input
            v-model="sessionForm.saleStartsAt"
            type="datetime-local"
            required
        /></label>
        <div class="form-actions">
          <ElButton :disabled="saving" @click="sessionFormOpen = false"
            >取消编辑</ElButton
          ><ElButton type="primary" native-type="submit" :loading="saving"
            >保存场次</ElButton
          >
        </div>
      </form>
      <div
        v-else-if="!sessionsLoading && sessions.length === 0"
        class="content-state"
      >
        该活动尚无场次，请新增场次。
      </div>
      <div v-if="sessions.length" class="table-wrap">
        <table class="admin-table">
          <thead>
            <tr>
              <th>场次</th>
              <th>开始 / 结束时间</th>
              <th>开售时间</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="session in sessions" :key="session.id">
              <td>{{ session.title }}</td>
              <td>
                {{ formatTime(session.startsAt)
                }}<small>{{ formatTime(session.endsAt) }}</small>
              </td>
              <td>{{ formatTime(session.saleStartsAt) }}</td>
              <td>{{ sessionStatusText[session.status] }}</td>
              <td class="table-actions">
                <ElButton
                  size="small"
                  :disabled="saving || session.status !== 'SCHEDULED'"
                  @click="editSession(session)"
                  >编辑</ElButton
                ><ElButton
                  size="small"
                  :disabled="saving || session.status !== 'SCHEDULED'"
                  @click="cancelSession(session)"
                  >取消场次</ElButton
                >
                <ElButton
                  size="small"
                  :disabled="saving"
                  @click="openTickets(session)"
                  >票档管理</ElButton
                >
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </ElDialog>
    <TicketManager
      v-if="ticketSession"
      v-model="ticketsDialog"
      :session="ticketSession"
    />
</template>
