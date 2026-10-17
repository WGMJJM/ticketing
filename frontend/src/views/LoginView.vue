<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { loginAccount, refreshCsrf } from '../api/auth'
import { chineseValidation, clearValidation } from '../utils/validation'
const props = defineProps<{ adminOnly?: boolean }>()
const username = ref('')
const password = ref('')
const error = ref('')
const busy = ref(false)
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
async function submit() {
  busy.value = true
  error.value = ''
  try {
    auth.acceptLogin(await loginAccount(username.value.trim(), password.value))
    await refreshCsrf()
    if (props.adminOnly && !auth.isAdmin) {
      error.value = '当前账号没有运营管理权限，可进入个人中心'
      return
    }
    const redirect =
      typeof route.query.redirect === 'string' ? route.query.redirect : ''
    const allowed =
      redirect === '/profile' ||
      (auth.isAdmin && redirect === '/admin/activities')
    await router.replace(
      allowed ? redirect : props.adminOnly ? '/admin/activities' : '/profile',
    )
  } catch (failure) {
    error.value =
      failure instanceof Error ? failure.message : '登录失败，请重试'
  } finally {
    password.value = ''
    busy.value = false
  }
}
</script>
<template>
  <div class="admin-login">
    <span class="eyebrow"
      ><span></span>{{ adminOnly ? '运营工作台' : '欢迎回来' }}</span
    >
    <h1>{{ adminOnly ? '管理员登录' : '用户登录' }}</h1>
    <p>
      {{
        adminOnly
          ? '使用具有管理员权限的平台账号登录。'
          : '登录后可查看个人中心，继续发现你喜爱的现场。'
      }}
      刷新页面会通过服务端会话恢复登录状态。
    </p>
    <p v-if="route.query.registered" class="success-notice">
      注册成功，请使用新账号登录
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
          maxlength="32"
          autocomplete="username"
      /></label>
      <label
        >密码<input
          v-model="password"
          required
          type="password"
          maxlength="72"
          autocomplete="current-password"
      /></label>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <ElButton type="primary" native-type="submit" :loading="busy">{{
        adminOnly ? '登录运营工作台' : '登录'
      }}</ElButton>
    </form>
    <div class="auth-links">
      <RouterLink to="/register">没有账号？立即注册</RouterLink
      ><RouterLink to="/profile" v-if="auth.authenticated"
        >进入个人中心</RouterLink
      >
    </div>
    <RouterLink to="/" class="back-link">← 返回发现活动</RouterLink>
  </div>
</template>
