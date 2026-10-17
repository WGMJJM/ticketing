<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
const auth = useAuthStore()
const router = useRouter()
const error = ref('')
const busy = ref(false)
watch(
  () => auth.authenticated,
  (value) => {
    if (!value) void router.replace('/login')
  },
)
async function logout() {
  busy.value = true
  error.value = ''
  try {
    await auth.logout()
    await router.replace('/')
  } catch (failure) {
    error.value =
      failure instanceof Error ? failure.message : '退出失败，请重试'
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <div class="profile-page">
    <span class="eyebrow"><span></span>我的账号</span>
    <h1>个人中心</h1>
    <section v-if="auth.user" class="profile-card">
      <h2>你好，{{ auth.user.username }}</h2>
      <dl>
        <div>
          <dt>用户编号</dt>
          <dd>{{ auth.user.id }}</dd>
        </div>
        <div>
          <dt>账号身份</dt>
          <dd>{{ auth.isAdmin ? '管理员' : '普通用户' }}</dd>
        </div>
      </dl>
      <p v-if="$route.query.denied" class="inline-error" role="alert">
        当前账号没有运营管理权限
      </p>
      <p>
        账号身份由服务端会话确认。可提交购票订单并主动取消，支付功能尚未开放。
      </p>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <div class="auth-links">
        <RouterLink to="/">继续发现活动</RouterLink
        ><RouterLink to="/orders">我的订单</RouterLink
        ><RouterLink v-if="auth.isAdmin" to="/admin/activities"
          >进入运营管理</RouterLink
        ><ElButton :loading="busy" @click="logout">退出登录</ElButton>
      </div>
    </section>
  </div>
</template>
