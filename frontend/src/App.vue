<script setup lang="ts">
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { useAuthStore } from './stores/auth'
import { useRoute } from 'vue-router'
import { siteName, siteTagline } from './config/site'
import SiteIcon from './components/SiteIcon.vue'
const auth = useAuthStore()
const route = useRoute()
function closeOperations(event: MouseEvent) {
  const target = event.target as Element
  if (target.closest('a')) target.closest('details')?.removeAttribute('open')
}
</script>

<template>
  <ElConfigProvider :locale="zhCn">
    <div class="site-shell">
      <a class="skip-link" href="#main-content">跳转到正文</a>
      <header class="site-header">
        <RouterLink class="brand" to="/" :aria-label="siteName + '首页'">
          <span class="brand-icon"><SiteIcon name="ticket" /></span>
          <span class="brand-copy">{{ siteName }}<small>{{ siteTagline }}</small></span>
        </RouterLink>
        <nav class="primary-nav" aria-label="主导航">
          <RouterLink to="/" :class="{ 'nav-active': route.path === '/' && route.hash !== '#popular' }">演出发现</RouterLink>
          <RouterLink to="/#popular" :class="{ 'nav-active': route.path === '/' && route.hash === '#popular' }">人气榜单</RouterLink>
          <RouterLink to="/about" active-class="nav-active">购票指南</RouterLink>
        </nav>
        <div class="header-account">
          <template v-if="auth.user">
            <RouterLink class="header-tickets" to="/tickets"><SiteIcon name="ticket" />我的票夹</RouterLink>
            <RouterLink class="account-pill" to="/profile" aria-label="进入个人中心">
              <span class="account-avatar">{{ auth.user.username.slice(0, 1).toUpperCase() }}</span>
              <span class="account-name">{{ auth.user.username }}</span>
            </RouterLink>
          </template>
          <template v-else>
            <RouterLink class="login-link" to="/login">登录</RouterLink>
            <RouterLink class="register-link" to="/register">注册账号<SiteIcon name="arrow" /></RouterLink>
          </template>
        </div>
      </header>
      <nav v-if="auth.user" class="workspace-nav" aria-label="个人功能">
        <div class="workspace-links">
          <RouterLink to="/profile">个人中心</RouterLink>
          <RouterLink to="/orders">我的订单</RouterLink>
          <RouterLink to="/purchase-requests">购票进度</RouterLink>
          <RouterLink to="/followed-activities">关注演出</RouterLink>
          <RouterLink to="/waitlist">候补记录</RouterLink>
          <RouterLink to="/tickets">我的票夹</RouterLink>
        </div>
        <details v-if="auth.canCheckin" class="operations-menu">
          <summary>管理工作台<SiteIcon name="chevron" /></summary>
          <div class="operations-links" @click="closeOperations">
            <RouterLink v-if="auth.isAdmin" to="/admin/activities">演出管理</RouterLink>
            <RouterLink v-if="auth.isAdmin" to="/admin/orders">订单管理</RouterLink>
            <RouterLink :to="auth.isAdmin ? '/admin/checkins' : '/staff/checkin'">入场核销</RouterLink>
            <RouterLink v-if="auth.isAdmin" to="/admin/staff">工作人员</RouterLink>
            <RouterLink v-if="auth.isAdmin" to="/admin/audit-logs">操作记录</RouterLink>
          </div>
        </details>
      </nav>
      <main id="main-content">
        <div v-if="auth.sessionError" class="session-error" role="alert">
          {{ auth.sessionError }}
          <ElButton @click="auth.restore(true)">重新获取登录状态</ElButton>
        </div>
        <RouterView />
      </main>
      <footer class="site-footer">
        <div><RouterLink class="footer-brand" to="/">{{ siteName }}</RouterLink><p>从一场演出，开始一段回忆。</p></div>
        <nav aria-label="页脚导航"><RouterLink to="/">演出发现</RouterLink><RouterLink to="/about">购票指南</RouterLink><RouterLink to="/profile">个人中心</RouterLink></nav>
        <span class="footer-note">演示体验 · 模拟支付不产生实际扣款</span>
      </footer>
    </div>
  </ElConfigProvider>
</template>
