<script setup lang="ts">
import { ref } from 'vue'
import ActivityList from '../components/ActivityList.vue'
import PopularActivities from '../components/PopularActivities.vue'
import StageArtwork from '../components/StageArtwork.vue'
import SiteIcon from '../components/SiteIcon.vue'

const selectedCategory = ref('')
const categories = [
  { value: '', label: '全部演出', icon: 'grid' },
  { value: 'CONCERT', label: '演唱会', icon: 'music' },
  { value: 'FESTIVAL', label: '音乐节', icon: 'heart' },
  { value: 'STAGE', label: '舞台演出', icon: 'stage' },
  { value: 'EXHIBITION', label: '展览', icon: 'art' },
  { value: 'ESPORTS', label: '电竞赛事', icon: 'game' },
] as const
</script>

<template>
  <div class="home-page">
    <section class="hero" aria-labelledby="hero-title">
      <div class="hero-copy">
        <span class="eyebrow"><span></span>为下一次相遇，留个位置</span>
        <h1 id="hero-title">发现<span>好演出</span>，<br />现在就出发。</h1>
        <p>从演唱会到舞台，从音乐节到展览。<br />找到你想去的现场，让期待有个日期。</p>
        <div class="hero-actions">
          <a class="primary-link" href="#discover">浏览演出<SiteIcon name="arrow" /></a>
          <a class="hero-secondary" href="#popular">看看大家喜欢什么<SiteIcon name="arrow" /></a>
        </div>
        <div class="hero-footnote"><SiteIcon name="ticket" /><span>发现演出</span><i></i><span>在线购票</span><i></i><span>电子票入场</span></div>
      </div>
      <div class="hero-art" role="img" aria-label="抽象舞台与唱片组成的视觉概念海报">
        <StageArtwork />
        <span class="poster-kicker">把期待，留给现场</span>
        <span class="art-caption">视觉概念海报</span>
      </div>
    </section>

    <nav class="category-nav" aria-label="按类别发现演出">
      <a v-for="category in categories" :key="category.value" href="#discover" :class="{ selected: selectedCategory === category.value }" :aria-current="selectedCategory === category.value ? 'true' : undefined" @click="selectedCategory = category.value">
        <SiteIcon :name="category.icon" /><span>{{ category.label }}</span>
      </a>
    </nav>

    <section id="discover" class="upcoming" aria-labelledby="upcoming-title">
      <div class="section-heading">
        <div><span class="section-kicker">为喜欢，安排一个日期</span><h2 id="upcoming-title">发现演出<span class="heading-dot">.</span></h2></div>
        <span class="section-aside">你的下一场精彩，从这里开始</span>
      </div>
      <ActivityList v-model:category="selectedCategory" />
    </section>

    <PopularActivities />

    <section class="home-guide" aria-label="购票流程">
      <div><span class="section-kicker">从发现到入场</span><h2>离现场，更近一步。</h2><RouterLink class="text-link" to="/about">查看购票指南<SiteIcon name="arrow" /></RouterLink></div>
      <ol>
        <li><span>01</span><div><h3>选择心仪演出</h3><p>查看场次、开售时间与票档。</p></div></li>
        <li><span>02</span><div><h3>确认购票结果</h3><p>在购票进度中查看订单结果。</p></div></li>
        <li><span>03</span><div><h3>带上电子票</h3><p>支付完成后，前往我的票夹。</p></div></li>
      </ol>
    </section>
  </div>
</template>
