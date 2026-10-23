<script setup lang="ts">
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { useAuthStore } from './stores/auth'
const auth = useAuthStore()
</script>

<template>
  <ElConfigProvider :locale="zhCn">
    <div class="site-shell">
      <a class="skip-link" href="#main-content">跳转到正文</a>
      <header class="site-header">
        <RouterLink class="brand" to="/" aria-label="票务交易平台首页">
          <span class="brand-icon" aria-hidden="true">票</span>
          <span class="brand-copy">票务交易平台<small>让热爱，在现场相遇</small></span>
        </RouterLink>
        <nav class="primary-nav" aria-label="主导航">
          <RouterLink
            to="/"
            active-class="nav-active"
            exact-active-class="nav-active"
            >发现活动</RouterLink
          >
          <RouterLink to="/about" active-class="nav-active"
            >平台介绍</RouterLink
          >
        </nav>
        <div class="header-account">
          <RouterLink v-if="auth.user" class="account-pill" to="/profile">
            <span class="account-avatar" aria-hidden="true">{{ auth.user.username.slice(0, 1).toUpperCase() }}</span>
            <span class="account-name">{{ auth.user.username }}</span>
            <span class="account-arrow" aria-hidden="true">↗</span>
          </RouterLink>
          <template v-else
            ><RouterLink to="/login">登录</RouterLink
            ><RouterLink class="register-link" to="/register">加入我们 <span aria-hidden="true">↗</span></RouterLink></template
          >
        </div>
      </header>
      <nav v-if="auth.user" class="workspace-nav" aria-label="个人与运营导航">
        <RouterLink to="/profile">个人中心</RouterLink>
        <RouterLink to="/orders">我的订单</RouterLink>
        <RouterLink to="/purchase-requests">抢票排队</RouterLink>
        <RouterLink to="/waitlist">我的候补</RouterLink>
        <RouterLink to="/tickets">我的电子票</RouterLink>
        <RouterLink v-if="auth.isAdmin" to="/admin/activities">运营管理</RouterLink>
        <RouterLink v-if="auth.isAdmin" to="/admin/orders">订单运营</RouterLink>
        <RouterLink v-if="auth.isAdmin" to="/admin/checkins">电子票核销</RouterLink>
        <RouterLink v-if="auth.isStaff" to="/staff/checkin">场次核销</RouterLink>
        <RouterLink v-if="auth.isAdmin" to="/admin/staff">工作人员管理</RouterLink>
        <RouterLink v-if="auth.isAdmin" to="/admin/audit-logs">操作审计</RouterLink>
      </nav>
      <main id="main-content">
        <div v-if="auth.sessionError" class="session-error" role="alert">
          {{ auth.sessionError }}
          <ElButton @click="auth.restore(true)">重新获取登录状态</ElButton>
        </div>
        <RouterView />
      </main>
      <footer class="site-footer">
        <RouterLink class="footer-brand" to="/">票务交易平台</RouterLink>
        <span>让精彩发生，让热爱在场。</span>
        <span class="footer-stage">发现精彩 · 安排现场 · 凭票入场</span>
        <RouterLink v-if="auth.isAdmin" to="/admin/activities"
          >运营管理</RouterLink
        >
      </footer>
    </div>
  </ElConfigProvider>
</template>
