<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { listPopularActivities, categoryInfo, type PopularActivity } from '../api/activities'
import ActivityCover from './ActivityCover.vue'

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
  <section class="popular-section" aria-labelledby="popular-title">
    <div class="section-heading">
      <div>
        <span class="section-kicker">人气现场 · 由真实点赞排序</span>
        <h2 id="popular-title">热门演出<span class="heading-dot" aria-hidden="true">.</span></h2>
      </div>
      <a class="section-aside" href="#discover">发现更多活动 <span aria-hidden="true">↘</span></a>
    </div>
    <div v-if="loading" class="content-state" role="status">正在整理大家喜欢的演出…</div>
    <div v-else-if="error" class="content-state error-state" role="alert">
      <p>{{ error }}</p>
      <ElButton @click="load">重新加载</ElButton>
    </div>
    <div v-else-if="items.length" class="activity-grid popular-grid">
      <RouterLink
        v-for="item in items"
        :key="item.activity.id"
        :to="`/activities/${item.activity.id}`"
        class="event-card popular-card"
      >
        <div class="popular-cover">
          <ActivityCover
            :url="item.activity.coverUrl"
            :category="item.activity.category"
            :title="item.activity.title"
          />
          <span class="popular-rank">TOP {{ item.rank }}</span>
        </div>
        <div class="event-card-body">
          <span class="event-meta">{{ categoryInfo(item.activity.category).label }} <span>·</span> {{ item.activity.city }}</span>
          <h3>{{ item.activity.title }}</h3>
          <p>{{ item.activity.summary }}</p>
          <div class="event-card-bottom">
            <span>{{ item.activity.venue }}</span>
            <span class="popular-likes">♥ {{ item.likeCount }} 人喜欢</span>
          </div>
        </div>
      </RouterLink>
    </div>
    <div v-else class="content-state">热门榜单正在等待第一份喜欢，先去发现近期现场吧。</div>
  </section>
</template>
