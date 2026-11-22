<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { resetPassword, sendPasswordResetCode } from '../api/auth'
import AuthLayout from '../components/AuthLayout.vue'
import { useAuthStore } from '../stores/auth'
import { useEmailCodeCooldown } from '../utils/useEmailCodeCooldown'
import { chineseValidation, clearValidation, passwordValidationMessage } from '../utils/validation'

const router = useRouter()
const auth = useAuthStore()
const email = ref('')
const emailInput = ref<HTMLInputElement>()
const code = ref('')
const password = ref('')
const confirmation = ref('')
const notice = ref('')
const error = ref('')
const sending = ref(false)
const saving = ref(false)
const { cooldown, start, useRetryAfter } = useEmailCodeCooldown()

async function sendCode() {
  if (sending.value || saving.value || cooldown.value) return
  email.value = email.value.trim()
  if (!emailInput.value?.reportValidity()) return
  notice.value = ''
  error.value = ''
  sending.value = true
  try {
    await sendPasswordResetCode(email.value)
    code.value = ''
    notice.value = '如果该邮箱已验证且账号可用，验证码会发送到收件箱，5 分钟内有效。'
    start()
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '暂时无法发送验证码，请稍后重试'
    useRetryAfter(failure)
  } finally {
    sending.value = false
  }
}

async function submit() {
  if (saving.value || sending.value) return
  error.value = passwordValidationMessage(password.value, confirmation.value)
  if (error.value) return
  saving.value = true
  try {
    await resetPassword(email.value.trim(), code.value, password.value)
    if (auth.authenticated) await auth.restore(true)
    await router.replace({ path: '/login', query: { passwordChanged: '1' } })
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '找回密码失败，请稍后重试'
  } finally {
    password.value = ''
    confirmation.value = ''
    saving.value = false
  }
}
</script>

<template>
  <AuthLayout>
    <h1>找回密码</h1>
    <p>使用账号已验证的邮箱设置新密码。成功后，该账号的现有登录会话会失效。</p>
    <form @submit.prevent="submit" @invalid.capture="chineseValidation"
      @input.capture="clearValidation" @change.capture="clearValidation">
      <label>已验证邮箱
        <input ref="emailInput" v-model="email" type="email" required maxlength="254" autocomplete="email"
          :disabled="sending || saving" @input="code = ''; notice = ''" />
      </label>
      <ElButton native-type="button" :loading="sending" :disabled="saving || cooldown > 0" @click="sendCode">
        {{ cooldown > 0 ? `${cooldown} 秒后重新发送` : '发送找回验证码' }}
      </ElButton>
      <p v-if="notice" class="success-notice" role="status">{{ notice }}</p>
      <label>邮箱验证码
        <input v-model="code" required pattern="[0-9]{6}" maxlength="6" inputmode="numeric" autocomplete="one-time-code" />
      </label>
      <label>新密码
        <input v-model="password" type="password" required minlength="8" maxlength="72" autocomplete="new-password" />
      </label>
      <label>确认新密码
        <input v-model="confirmation" type="password" required minlength="8" maxlength="72" autocomplete="new-password" />
      </label>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <ElButton type="primary" native-type="submit" :loading="saving" :disabled="sending">重置密码</ElButton>
    </form>
    <RouterLink to="/login" class="back-link">返回登录</RouterLink>
  </AuthLayout>
</template>
