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
      <div class="profile-identity">
        <span class="profile-avatar" aria-hidden="true">{{ auth.user.username.slice(0, 1).toUpperCase() }}</span>
        <div>
          <span class="section-kicker">欢迎回到你的现场</span>
          <h2>你好，{{ auth.user.username }}</h2>
          <p>每一次期待，都可以从这里继续。</p>
        </div>
      </div>
      <dl>
        <div>
          <dt>用户编号</dt>
          <dd>{{ auth.user.id }}</dd>
        </div>
        <div>
          <dt>账号身份</dt>
          <dd>{{ auth.isAdmin ? '管理员' : auth.isStaff ? '核销工作人员' : '普通用户' }}</dd>
        </div>
      </dl>
      <p v-if="$route.query.denied" class="inline-error" role="alert">
        当前账号没有运营管理权限
      </p>
      <p>
        查看订单安排、关注候补进度，出发前记得准备好你的电子票。
      </p>
      <nav class="profile-shortcuts" aria-label="我的现场功能">
        <RouterLink to="/orders">
          <span class="shortcut-icon" aria-hidden="true">▤</span>
          <strong>我的订单</strong>
          <small>查看预订与支付状态</small>
          <span class="shortcut-arrow" aria-hidden="true">↗</span>
        </RouterLink>
        <RouterLink to="/waitlist">
          <span class="shortcut-icon" aria-hidden="true">◷</span>
          <strong>我的候补</strong>
          <small>关注每一份等待</small>
          <span class="shortcut-arrow" aria-hidden="true">↗</span>
        </RouterLink>
        <RouterLink to="/tickets">
          <span class="shortcut-icon" aria-hidden="true">▱</span>
          <strong>我的电子票</strong>
          <small>查看票券与入场凭证</small>
          <span class="shortcut-arrow" aria-hidden="true">↗</span>
        </RouterLink>
      </nav>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <div class="auth-links">
        <RouterLink v-if="auth.canCheckin" :to="auth.isAdmin ? '/admin/checkins' : '/staff/checkin'">进入电子票核销</RouterLink>
        <RouterLink to="/">继续发现活动</RouterLink
        ><RouterLink v-if="auth.isAdmin" to="/admin/checkins"
          >电子票核销</RouterLink
        ><RouterLink v-if="auth.isAdmin" to="/admin/activities"
          >进入运营管理</RouterLink
        ><ElButton :loading="busy" @click="logout">退出登录</ElButton>
      </div>
    </section>
  </div>
</template>
