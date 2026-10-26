<script setup lang="ts">
import SiteIcon from '../components/SiteIcon.vue'
import { siteName } from '../config/site'
</script>
<template>
  <div class="about-page">
    <span class="eyebrow"><span></span>购票指南</span>
    <h1>从心动，到<span>在场。</span></h1>
    <p class="about-intro">{{ siteName }}支持演唱会、音乐节、舞台演出、展览与电竞赛事。选一场喜欢的演出，按下面的步骤安排你的行程。</p>
    <div class="about-grid">
      <section class="about-card"><SiteIcon name="calendar" /><span class="section-kicker">01 · 选择演出</span><h2>先选场次，再选票档</h2><p>在演出详情页查看场馆、演出时间与开售安排，选择正常开放的场次，再查看可售票档及余票。</p></section>
      <section class="about-card"><SiteIcon name="clock" /><span class="section-kicker">02 · 确认结果</span><h2>购票进度，一处查看</h2><p>提交申请后，在「购票进度」查看处理结果。受理成功不等于购票成功；订单创建后，请在截止时间前完成支付。</p></section>
      <section class="about-card"><SiteIcon name="ticket" /><span class="section-kicker">03 · 准备入场</span><h2>电子票，收在我的票夹</h2><p>支付成功后生成独立电子票。入场时出示对应场次的二维码；已核销或已作废的票券不能再次使用。</p></section>
      <section class="about-card"><SiteIcon name="bookmark" /><span class="section-kicker">也可以，先留一份期待</span><h2>关注演出，或申请候补</h2><p>点赞参与人气排名，关注方便回看场次和开售安排。缺票时可申请候补，等待期间不占库存或购票额度，也不保证分配成功。</p></section>
    </div>
    <RouterLink class="primary-link" to="/">去发现演出<SiteIcon name="arrow" /></RouterLink>
  </div>
</template>
