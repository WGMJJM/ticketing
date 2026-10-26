<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import SiteIcon from '../components/SiteIcon.vue'
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
    <span class="eyebrow"><span></span>个人中心</span>
    <h1>我的演出安排</h1>
    <section v-if="auth.user" class="profile-card">
      <div class="profile-identity">
        <span class="profile-avatar">{{ auth.user.username.slice(0, 1).toUpperCase() }}</span>
        <div><span class="section-kicker">欢迎回来</span><h2>{{ auth.user.username }}</h2><p>订单、票券和关注的演出，都在这里。</p></div>
      </div>
      <dl>
        <div><dt>账号身份</dt><dd>{{ auth.isAdmin ? '管理员' : auth.isStaff ? '核销工作人员' : '普通用户' }}</dd></div>
        <div><dt>用户编号</dt><dd>{{ auth.user.id }}</dd></div>
      </dl>
      <p v-if="$route.query.denied" class="inline-error" role="alert">当前账号没有运营管理权限</p>
      <div class="profile-section-heading"><h3>常用功能</h3><RouterLink class="text-link" to="/purchase-requests">查看购票进度<SiteIcon name="arrow" /></RouterLink></div>
      <nav class="profile-shortcuts" aria-label="个人常用功能">
        <RouterLink to="/orders"><span class="shortcut-icon"><SiteIcon name="calendar" /></span><strong>我的订单</strong><small>查看订单与支付状态</small><span class="shortcut-arrow"><SiteIcon name="arrow" /></span></RouterLink>
        <RouterLink to="/tickets"><span class="shortcut-icon"><SiteIcon name="ticket" /></span><strong>我的票夹</strong><small>电子票与入场二维码</small><span class="shortcut-arrow"><SiteIcon name="arrow" /></span></RouterLink>
        <RouterLink to="/followed-activities"><span class="shortcut-icon"><SiteIcon name="bookmark" /></span><strong>关注演出</strong><small>回看场次与开售时间</small><span class="shortcut-arrow"><SiteIcon name="arrow" /></span></RouterLink>
        <RouterLink to="/waitlist"><span class="shortcut-icon"><SiteIcon name="clock" /></span><strong>候补记录</strong><small>查看候补分配结果</small><span class="shortcut-arrow"><SiteIcon name="arrow" /></span></RouterLink>
      </nav>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <div class="auth-links">
        <RouterLink to="/">继续发现演出</RouterLink>
        <RouterLink v-if="auth.canCheckin" :to="auth.isAdmin ? '/admin/checkins' : '/staff/checkin'">入场核销</RouterLink>
        <RouterLink v-if="auth.isAdmin" to="/admin/activities">演出管理</RouterLink>
        <ElButton :loading="busy" @click="logout">退出登录</ElButton>
      </div>
    </section>
  </div>
</template>
