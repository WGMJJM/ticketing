<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { registerAccount } from '../api/auth'
import { chineseValidation, clearValidation } from '../utils/validation'
const username = ref('')
const password = ref('')
const confirmation = ref('')
const error = ref('')
const busy = ref(false)
const router = useRouter()
async function submit() {
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
    await registerAccount(username.value.trim(), password.value)
    await router.replace({ path: '/login', query: { registered: '1' } })
  } catch (failure) {
    error.value =
      failure instanceof Error ? failure.message : '注册失败，请重试'
  } finally {
    password.value = ''
    confirmation.value = ''
    busy.value = false
  }
}
</script>
<template>
  <div class="admin-login">
    <span class="eyebrow"><span></span>每份热爱，都值得抵达</span>
    <h1>创建平台账号</h1>
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
      /></label>
      <label
        >密码<input
          v-model="password"
          required
          type="password"
          minlength="8"
          maxlength="72"
          autocomplete="new-password"
      /></label>
      <label
        >确认密码<input
          v-model="confirmation"
          required
          type="password"
          minlength="8"
          maxlength="72"
          autocomplete="new-password"
      /></label>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <ElButton type="primary" native-type="submit" :loading="busy"
        >注册账号</ElButton
      >
    </form>
    <RouterLink to="/login">已有账号？前往登录</RouterLink>
  </div>
</template>
