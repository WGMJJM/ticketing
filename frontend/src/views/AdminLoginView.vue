<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAdminStore } from '../stores/admin'
import { chineseValidation, clearValidation } from '../utils/validation'
const username = ref('')
const password = ref('')
const error = ref('')
const busy = ref(false)
const admin = useAdminStore()
const router = useRouter()
async function submit() {
  busy.value = true
  error.value = ''
  try {
    await admin.login(username.value.trim(), password.value)
    password.value = ''
    await router.replace('/admin/activities')
  } catch (failure) {
    error.value =
      failure instanceof Error ? failure.message : '认证失败，请重试'
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <div class="admin-login">
    <span class="eyebrow"><span></span>运营工作台</span>
    <h1>管理员认证</h1>
    <p>
      仅限已配置凭据的开发管理员使用。账号信息仅在当前页面会话内保存，刷新后需要重新认证。
    </p>
    <form
      @submit.prevent="submit"
      @invalid.capture="chineseValidation"
      @input.capture="clearValidation"
      @change.capture="clearValidation"
    >
      <label
        >管理员账号<input
          v-model="username"
          required
          maxlength="64"
          autocomplete="username" /></label
      ><label
        >管理员密码<input
          v-model="password"
          required
          type="password"
          maxlength="200"
          autocomplete="current-password"
      /></label>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <ElButton type="primary" native-type="submit" :loading="busy"
        >进入运营工作台</ElButton
      >
    </form>
    <RouterLink to="/" class="back-link">← 返回发现活动</RouterLink>
  </div>
</template>
