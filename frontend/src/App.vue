<script setup lang="ts">
import zhCn from 'element-plus/es/locale/lang/zh-cn'
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
        <span class="header-note"
          ><i aria-hidden="true"></i> 每份热爱，都值得抵达</span
        >
      </header>
      <main id="main-content"><RouterView /></main>
      <footer class="site-footer">
        <RouterLink class="footer-brand" to="/">票务交易平台</RouterLink>
        <span>让精彩发生，让热爱在场。</span>
        <span class="footer-stage">活动与票档展示 · 购票功能筹备中</span>
        <RouterLink to="/admin/activities">运营管理</RouterLink>
      </footer>
    </div>
  </ElConfigProvider>
</template>
