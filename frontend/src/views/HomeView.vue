<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { getSystemStatus } from '../api/system'
import ActivityList from '../components/ActivityList.vue'
import StageArtwork from '../components/StageArtwork.vue'

const connection = ref<'loading' | 'online' | 'offline'>('loading')
const lastChecked = ref<Date | null>(null)
const controller = new AbortController()
const connectionText = computed(
  () =>
    ({
      loading: '正在连接服务',
      online: '服务运行正常',
      offline: '暂时无法连接后端服务',
    })[connection.value],
)
const checkedTime = computed(() =>
  lastChecked.value
    ? new Intl.DateTimeFormat('zh-CN', {
        timeZone: 'Asia/Shanghai',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hour12: false,
      }).format(lastChecked.value)
    : '',
)
const categories = [
  {
    name: '演唱会',
    description: '在万人合唱里，找到共鸣',
    icon: 'mic',
    accent: 'purple',
  },
  {
    name: '音乐节',
    description: '把自由，交给音乐与旷野',
    icon: 'wave',
    accent: 'green',
  },
  {
    name: '舞台演出',
    description: '灯光亮起，好故事正在发生',
    icon: 'stage',
    accent: 'orange',
  },
  {
    name: '展览赛事',
    description: '遇见灵感，也见证高光',
    icon: 'star',
    accent: 'blue',
  },
]

async function checkConnection() {
  connection.value = 'loading'
  try {
    await getSystemStatus(controller.signal)
    connection.value = 'online'
  } catch {
    if (!controller.signal.aborted) connection.value = 'offline'
  } finally {
    if (!controller.signal.aborted) lastChecked.value = new Date()
  }
}

onMounted(checkConnection)
onBeforeUnmount(() => controller.abort())
</script>

<template>
  <div class="home-page">
  <section class="hero" aria-labelledby="hero-title">
    <div class="hero-copy">
      <span class="eyebrow"><span></span> 下一站，现场</span>
      <h1 id="hero-title">
        为热爱，<br />奔赴<span class="hero-outline">现场</span><span class="hero-asterisk" aria-hidden="true">✳</span>
      </h1>
      <p>
        有些心动，只有在现场才能听见。<br />从一场演出、一段音乐，找到下一次出发的理由。
      </p>
      <div class="hero-actions">
        <a class="primary-link" href="#discover">浏览近期活动 <span aria-hidden="true">↗</span></a>
        <RouterLink class="hero-secondary" to="/tickets">我的入场凭证 <span aria-hidden="true">→</span></RouterLink>
      </div>
      <div class="hero-journey" aria-label="现场体验流程">
        <span><small>01</small> 发现心动</span><i aria-hidden="true">—</i>
        <span><small>02</small> 预订现场</span><i aria-hidden="true">—</i>
        <span><small>03</small> 凭票入场</span>
      </div>
    </div>
    <div
      class="hero-art"
      role="img"
      aria-label="音乐唱片与纸带构成的原创概念海报，不代表实际售票活动"
    >
      <StageArtwork />
      <span class="poster-kicker">献给每一个想要在场的你</span>
      <strong class="poster-title">热爱<br />自有回响。</strong>
      <div class="poster-footer">
        <span>让期待落地<br /><b>让热爱在场</b></span>
        <span aria-hidden="true">↗</span>
      </div>
      <span class="art-caption">原创视觉概念 · 非售票信息</span>
    </div>
  </section>

  <section id="discover" class="upcoming" aria-labelledby="upcoming-title">
    <div class="section-heading">
      <div>
        <span class="section-kicker">01 / 为喜欢，留出时间</span>
        <h2 id="upcoming-title">近期精彩现场<span class="heading-dot" aria-hidden="true">.</span></h2>
      </div>
      <a class="section-aside" href="#inspiration">寻找更多灵感 <span aria-hidden="true">↗</span></a>
    </div>
    <ActivityList />
  </section>

  <section id="inspiration" class="discovery" aria-labelledby="discover-title">
    <div class="section-heading">
      <div>
        <span class="section-kicker">02 / 跟随你的热爱</span>
        <h2 id="discover-title">找到你的同频现场<span class="heading-dot" aria-hidden="true">.</span></h2>
      </div>
      <span class="section-aside"
        >四种精彩，无限可能 <span aria-hidden="true">↘</span></span
      >
    </div>
    <div class="category-grid">
      <article
        v-for="(category, index) in categories"
        :key="category.name"
        class="category-card"
        :class="category.accent"
      >
        <div class="category-top">
          <span class="category-number">0{{ index + 1 }}</span
          ><span class="category-glyph" aria-hidden="true">{{
            ['♫', '≋', '◒', '✧'][index]
          }}</span>
        </div>
        <h3>{{ category.name }}</h3>
        <p>{{ category.description }}</p>
        <span class="category-caption">{{ ['听见共鸣', '奔向自由', '走进故事', '发现新意'][index] }}</span>
      </article>
    </div>
  </section>

  <section
    class="connection-panel"
    :class="connection"
    aria-label="后端连接状态"
  >
    <div class="connection-copy" role="status" aria-live="polite">
      <span class="status-dot" aria-hidden="true"></span>
      <div>
        <strong>{{ connectionText }}</strong>
        <p>
          {{
            connection === 'offline'
              ? '请确认后端已启动，您可以稍后重新连接。'
              : '平台基础服务连接检测，可选择活动体验购票、模拟支付与候补。'
          }}
        </p>
      </div>
    </div>
    <div class="connection-actions">
      <span v-if="checkedTime" class="checked-time"
        >北京时间 {{ checkedTime }} 检查</span
      ><ElButton
        :loading="connection === 'loading'"
        :disabled="connection === 'loading'"
        @click="checkConnection"
        >重新检测</ElButton
      >
    </div>
  </section>
  </div>
</template>
