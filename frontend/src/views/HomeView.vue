<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { getSystemStatus } from '../api/system'
import ActivityList from '../components/ActivityList.vue'

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
  <section class="hero" aria-labelledby="hero-title">
    <div class="hero-copy">
      <span class="eyebrow"><span></span> 精彩现场，正在酝酿</span>
      <h1 id="hero-title">
        发现下一场<br />精彩<span class="title-highlight">现场<i></i></span
        ><span class="title-dot">。</span>
      </h1>
      <p>
        演唱会、音乐节、展览与精彩赛事。<br />走出日常，把喜欢变成一次亲临现场。
      </p>
      <div class="hero-actions">
        <a class="primary-link" href="#discover">探索现场灵感 <span aria-hidden="true">↗</span></a>
        <RouterLink class="hero-secondary" to="/tickets">我的入场凭证 <span aria-hidden="true">→</span></RouterLink>
      </div>
      <div class="hero-footnote">
        <span class="tiny-ticket" aria-hidden="true">✦</span>
        为下一次心动，留一个位置
      </div>
    </div>
    <div
      class="hero-art"
      role="img"
      aria-label="抽象舞台与票券插画，展示现场氛围，无可售活动"
    >
      <div class="art-grid"></div>
      <span class="orbit orbit-one"></span><span class="orbit orbit-two"></span>
      <span class="art-spark spark-one">✦</span
      ><span class="art-spark spark-two">✳</span>
      <div class="stage-light light-one"></div>
      <div class="stage-light light-two"></div>
      <div class="scene-ticket">
        <div class="ticket-top"><span>下一站 · 现场</span><span>✦</span></div>
        <div class="ticket-main">热爱<br /><span>即将开场</span></div>
        <div class="sound-bars" aria-hidden="true">
          <i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i
          ><i></i><i></i><i></i>
        </div>
        <div class="ticket-stub">
          <span>精彩，不止于想象</span><span>↗</span>
        </div>
      </div>
      <div class="floating-label"><span>◉</span> 让每一份热爱抵达现场</div>
      <span class="art-caption">视觉概念 · 非售票信息</span>
    </div>
  </section>

  <section id="discover" class="discovery" aria-labelledby="discover-title">
    <div class="section-heading">
      <div>
        <span class="section-kicker">跟随你的热爱</span>
        <h2 id="discover-title">总有一种现场，让你心动</h2>
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
        <span class="category-caption">发现现场灵感</span>
      </article>
    </div>
  </section>

  <section class="upcoming" aria-labelledby="upcoming-title">
    <div class="section-heading">
      <div>
        <span class="section-kicker">好现场，值得等待</span>
        <h2 id="upcoming-title">近期精彩现场</h2>
      </div>
      <span class="coming-label">活动与场次展示</span>
    </div>
    <ActivityList />
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
</template>
