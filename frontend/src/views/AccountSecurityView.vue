<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
import type { AccountSession } from '../api/auth'
import { useAuthStore } from '../stores/auth'
import { formatTime } from '../utils/date'
import { chineseValidation, clearValidation, passwordValidationMessage } from '../utils/validation'

const auth = useAuthStore()
const router = useRouter()
const currentPassword = ref('')
const newPassword = ref('')
const confirmation = ref('')
const changeError = ref('')
const changing = ref(false)
const passwordChanged = ref(false)
const sessions = ref<AccountSession[]>([])
const otherCount = computed(() => sessions.value.filter(session => !session.current).length)
const loading = ref(false)
const revoking = ref('')
const sessionError = ref('')
const sessionNotice = ref('')

watch(() => auth.authenticated, value => {
  if (!value && !passwordChanged.value) void router.replace({ path: '/login', query: { redirect: '/profile/security' } })
})
onMounted(loadSessions)

async function changePassword() {
  if (changing.value) return
  changeError.value = passwordValidationMessage(newPassword.value, confirmation.value)
  if (changeError.value) return
  changing.value = true
  try {
    await auth.request<void>({
      method: 'POST',
      url: '/auth/password/change',
      data: { currentPassword: currentPassword.value, newPassword: newPassword.value },
    })
    passwordChanged.value = true
    auth.forgetSession()
    await router.replace({ path: '/login', query: { passwordChanged: '1' } })
  } catch (failure) {
    changeError.value = failure instanceof Error ? failure.message : '修改密码失败，请稍后重试'
  } finally {
    currentPassword.value = ''
    newPassword.value = ''
    confirmation.value = ''
    changing.value = false
  }
}

async function loadSessions() {
  if (loading.value) return
  loading.value = true
  sessionError.value = ''
  try {
    sessions.value = await auth.request<AccountSession[]>({ url: '/auth/sessions' })
  } catch (failure) {
    sessionError.value = failure instanceof Error ? failure.message : '暂时无法查看登录会话'
  } finally {
    loading.value = false
  }
}

async function revoke(id = 'others') {
  if (revoking.value) return
  if (id === 'others') {
    try {
      await ElMessageBox.confirm('其他设备将退出此账号，当前登录会保留。', '退出其他会话',
        { confirmButtonText: '确认退出', cancelButtonText: '取消', type: 'warning' })
    } catch { return }
  }
  revoking.value = id
  sessionError.value = ''
  sessionNotice.value = ''
  try {
    await auth.request<void>({ method: 'DELETE', url: '/auth/sessions/' + id })
    sessions.value = sessions.value.filter(session => session.current || (id !== 'others' && session.id !== id))
    sessionNotice.value = id === 'others' ? '其他会话已退出' : '该会话已退出'
  } catch (failure) {
    sessionError.value = failure instanceof Error ? failure.message : '暂时无法退出该会话'
  } finally {
    revoking.value = ''
  }
}
</script>

<template>
  <div class="profile-page account-security-page">
    <RouterLink to="/profile" class="back-link">返回个人中心</RouterLink>
    <h1>账号与安全</h1>
    <div class="account-security-grid">
      <section class="profile-card" aria-labelledby="password-heading">
        <h2 id="password-heading">修改密码</h2>
        <p>请输入当前密码。修改成功后，所有会话都会退出，请用新密码重新登录。</p>
        <form class="account-password-form" @submit.prevent="changePassword" @invalid.capture="chineseValidation"
          @input.capture="clearValidation" @change.capture="clearValidation">
          <label>当前密码
            <input v-model="currentPassword" type="password" required maxlength="72" autocomplete="current-password" />
          </label>
          <label>新密码
            <input v-model="newPassword" type="password" required minlength="8" maxlength="72" autocomplete="new-password" />
          </label>
          <label>确认新密码
            <input v-model="confirmation" type="password" required minlength="8" maxlength="72" autocomplete="new-password" />
          </label>
          <p v-if="changeError" class="inline-error" role="alert">{{ changeError }}</p>
          <ElButton type="primary" native-type="submit" :loading="changing">保存新密码</ElButton>
        </form>
      </section>
      <section class="profile-card" aria-labelledby="sessions-heading">
        <div class="account-session-heading">
          <h2 id="sessions-heading">登录会话</h2>
          <ElButton :loading="loading" :disabled="!!revoking" @click="loadSessions">刷新</ElButton>
        </div>
        <p v-if="sessionNotice" class="success-notice" role="status">{{ sessionNotice }}</p>
        <p v-if="sessionError" class="inline-error" role="alert">{{ sessionError }}</p>
        <ul v-if="sessions.length" class="account-session-list">
          <li v-for="session in sessions" :key="session.id">
            <div>
              <strong>{{ session.current ? '当前会话' : '其他会话' }}</strong>
              <p>最近活动 <time :datetime="session.lastActiveAt">{{ formatTime(session.lastActiveAt) }}</time></p>
            </div>
            <ElButton v-if="!session.current" size="small" :loading="revoking === session.id"
              :disabled="loading || !!revoking" @click="revoke(session.id)">退出此会话</ElButton>
          </li>
        </ul>
        <p v-if="sessions.length && !otherCount">只有当前会话仍在登录。</p>
        <ElButton v-if="otherCount" :loading="revoking === 'others'"
          :disabled="loading || !!revoking" @click="revoke()">退出所有其他会话</ElButton>
      </section>
    </div>
  </div>
</template>
