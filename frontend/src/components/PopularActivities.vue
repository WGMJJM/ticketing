<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { listPopularActivities, categoryInfo, type PopularActivity } from '../api/activities'
import ActivityCover from './ActivityCover.vue'
import SiteIcon from './SiteIcon.vue'

const items = ref<PopularActivity[]>([])
const loading = ref(true)
const error = ref('')
const controller = new AbortController()

async function load() {
  loading.value = true
  error.value = ''
  try {
    items.value = await listPopularActivities(10, controller.signal)
  } catch (failure) {
    if (!controller.signal.aborted)
      error.value = failure instanceof Error ? failure.message : '热门演出暂时无法加载'
  } finally {
    if (!controller.signal.aborted) loading.value = false
  }
}

onMounted(load)
onBeforeUnmount(() => controller.abort())
</script>

<template>
  <section id="popular" class="popular-section" aria-labelledby="popular-title">
    <div class="section-heading">
      <div><span class="section-kicker">每一份喜欢，都算数</span><h2 id="popular-title">人气榜单<span class="heading-dot">.</span></h2></div>
      <span class="section-aside">按点赞数排序 · 最多 10 场</span>
    </div>
    <div v-if="loading" class="content-state" role="status">正在加载人气榜单…</div>
    <div v-else-if="error" class="content-state error-state" role="alert">
      <p>{{ error }}</p><ElButton @click="load">重新加载</ElButton>
    </div>
    <div v-else-if="items.length" class="popular-list">
      <RouterLink v-for="item in items" :key="item.activity.id" :to="'/activities/' + item.activity.id" class="popular-item">
        <span class="popular-rank" :class="{ leading: item.rank <= 3 }">{{ String(item.rank).padStart(2, '0') }}</span>
        <ActivityCover :url="item.activity.coverUrl" :category="item.activity.category" :title="item.activity.title" />
        <div class="popular-copy"><h3>{{ item.activity.title }}</h3><p>{{ categoryInfo(item.activity.category).label }} · {{ item.activity.city }}</p></div>
        <span class="popular-likes"><SiteIcon name="heart" />{{ item.likeCount }}<small>人喜欢</small></span>
      </RouterLink>
    </div>
    <div v-else class="content-state">还没有已发布的演出，稍后再来看看。</div>
  </section>
</template>
