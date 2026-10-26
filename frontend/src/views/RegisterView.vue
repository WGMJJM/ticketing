<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import axios from 'axios'
import { useRouter } from 'vue-router'
import { registerAccount, sendRegistrationCode } from '../api/auth'
import { chineseValidation, clearValidation } from '../utils/validation'
import AuthLayout from '../components/AuthLayout.vue'
const username = ref('')
const email = ref('')
const code = ref('')
const emailInput = ref<HTMLInputElement>()
const password = ref('')
const confirmation = ref('')
const error = ref('')
const busy = ref(false)
const sending = ref(false)
const cooldown = ref(0)
const notice = ref('')
let timer: ReturnType<typeof setInterval> | undefined
const router = useRouter()
function startCooldown(seconds: number) {
  if (timer) clearInterval(timer)
  const deadline = Date.now() + seconds * 1000
  cooldown.value = seconds
  timer = setInterval(() => {
    cooldown.value = Math.max(0, Math.ceil((deadline - Date.now()) / 1000))
    if (!cooldown.value && timer) clearInterval(timer)
  }, 1000)
}
onUnmounted(() => { if (timer) clearInterval(timer) })
async function sendCode() {
  if (sending.value || busy.value || cooldown.value > 0) return
  email.value = email.value.trim().toLowerCase()
  if (!emailInput.value?.reportValidity()) return
  if (!/^[\x21-\x7E]+$/.test(email.value)) {
    error.value = '邮箱须使用英文字母、数字和英文符号'
    return
  }
  error.value = ''
  notice.value = ''
  sending.value = true
  try {
    await sendRegistrationCode(email.value)
    notice.value = '验证码已发送，5分钟内有效，请检查收件箱或垃圾邮件。重新发送后旧验证码失效。'
    code.value = ''
    startCooldown(60)
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '验证码发送失败，请稍后重试'
    if (axios.isAxiosError(failure) && failure.response?.status === 429) {
      const retry = Number(failure.response.headers['retry-after'])
      if (Number.isInteger(retry) && retry > 0 && retry <= 86400) startCooldown(retry)
    }
  } finally {
    sending.value = false
  }
}
async function submit() {
  if (busy.value || sending.value) return
  error.value = ''
  if (password.value !== confirmation.value) {
    error.value = '两次输入的密码不一致'
    return
  }
  if (new TextEncoder().encode(password.value).length > 72) {
    error.value = '密码的UTF-8编码不能超过72字节，请减少字符'
    return
  }
  busy.value = true
  try {
    await registerAccount({
      username: username.value.trim().toLowerCase(),
      email: email.value.trim().toLowerCase(),
      password: password.value,
      code: code.value,
    })
    await router.replace({ path: '/login', query: { registered: '1' } })
  } catch (failure) {
    error.value =
      failure instanceof Error ? failure.message : '注册失败，请重试'
  } finally {
    password.value = ''
    confirmation.value = ''
    code.value = ''
    busy.value = false
  }
}
</script>
<template>
  <AuthLayout>
    <span class="eyebrow"><span></span>每份热爱，都值得抵达</span>
    <h1>注册账号</h1>
    <p>
      用户名为3至32位字母、数字或下划线，以字母开头，不区分大小写。密码为8至72个字符，UTF-8编码不超过72字节。
    </p>
    <form
      @submit.prevent="submit"
      @invalid.capture="chineseValidation"
      @input.capture="clearValidation"
      @change.capture="clearValidation"
    >
      <label
        >用户名<input
          v-model="username"
          required
          pattern="[A-Za-z][A-Za-z0-9_]{2,31}"
          maxlength="32"
          autocomplete="username"
          placeholder="以字母开头，3至32位"
      /></label>
      <label
        >邮箱<input
          ref="emailInput"
          v-model="email"
          @input="code = ''; notice = ''"
          required
          type="email"
          maxlength="254"
          autocomplete="email"
          :disabled="sending || busy"
          placeholder="用于接收注册验证码"
      /></label>
      <ElButton native-type="button" :loading="sending" :disabled="busy || cooldown > 0" @click="sendCode">
        {{ cooldown > 0 ? `${cooldown}秒后重新发送` : '发送邮箱验证码' }}
      </ElButton>
      <label
        >邮箱验证码<input
          v-model="code"
          required
          pattern="[0-9]{6}"
          minlength="6"
          maxlength="6"
          inputmode="numeric"
          autocomplete="one-time-code"
          placeholder="请输入6位验证码"
      /></label>
      <p v-if="notice" class="code-notice" role="status">{{ notice }}</p>
      <label
        >密码<input
          v-model="password"
          required
          type="password"
          minlength="8"
          maxlength="72"
          autocomplete="new-password"
          placeholder="设置8至72位密码"
      /></label>
      <label
        >确认密码<input
          v-model="confirmation"
          required
          type="password"
          minlength="8"
          maxlength="72"
          autocomplete="new-password"
          placeholder="请再次输入密码"
      /></label>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <ElButton type="primary" native-type="submit" :loading="busy" :disabled="sending"
        >注册账号</ElButton
      >
    </form>
    <RouterLink to="/login">已有账号？前往登录</RouterLink>
  </AuthLayout>
</template>
<style scoped>
.code-notice {
  margin: 0;
  color: var(--text-secondary, #57677c);
  font-size: 13px;
  line-height: 1.6;
}
</style>
