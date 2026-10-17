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
          <span>票务交易平台</span>
        </RouterLink>
        <nav aria-label="主导航">
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
          <template v-if="auth.user"
            ><span class="account-name">{{ auth.user.username }}</span
            ><RouterLink to="/profile">个人中心</RouterLink
            ><RouterLink to="/orders">我的订单</RouterLink
            ><RouterLink v-if="auth.isAdmin" to="/admin/activities"
              >运营管理</RouterLink
            ></template
          >
          <template v-else
            ><RouterLink to="/login">登录</RouterLink
            ><RouterLink to="/register">注册</RouterLink></template
          >
        </div>
      </header>
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
        <span class="footer-stage">购票订单与模拟支付 · 超时自动关闭</span>
        <RouterLink v-if="auth.isAdmin" to="/admin/activities"
          >运营管理</RouterLink
        >
      </footer>
    </div>
  </ElConfigProvider>
</template>
