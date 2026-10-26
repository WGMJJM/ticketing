<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import {
  listFollowedActivities,
  unfollowActivity,
  type FollowedActivity,
} from '../api/activities'
import ActivityCover from '../components/ActivityCover.vue'
import { useAuthStore } from '../stores/auth'
import { formatTime } from '../utils/date'

const auth = useAuthStore()
const page = ref(1)
const size = 12
const total = ref(0)
const items = ref<FollowedActivity[]>([])
const busyId = ref('')
const loading = ref(false)
const error = ref('')
let controller: AbortController | undefined

async function load() {
  controller?.abort()
  const active = new AbortController()
  controller = active
  loading.value = true
  error.value = ''
  try {
    const result = await listFollowedActivities(page.value, size, active.signal)
    items.value = result.items
    total.value = result.total
  } catch (failure) {
    if (!active.signal.aborted)
      error.value = failure instanceof Error ? failure.message : '关注列表暂时无法加载'
  } finally {
    if (!active.signal.aborted) loading.value = false
  }
}

async function unfollow(id: string) {
  if (busyId.value) return
  busyId.value = id
  error.value = ''
  try {
    await unfollowActivity(id)
    if (items.value.length === 1 && page.value > 1) page.value--
    await load()
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '取消关注失败，请重试'
    await auth.restore(true)
  } finally {
    busyId.value = ''
  }
}

function changePage(next: number) {
  page.value = next
  void load()
}

onMounted(load)
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <div class="followed-page">
    <RouterLink to="/profile" class="back-link">← 返回个人中心</RouterLink>
    <div class="section-heading">
      <div>
        <span class="section-kicker">留住下一次相遇</span>
        <h1>我的关注<span class="heading-dot" aria-hidden="true">.</span></h1>
      </div>
      <span class="section-aside">共 {{ total }} 场活动</span>
    </div>
    <div v-if="loading" class="content-state" role="status">正在读取你的关注…</div>
    <div v-else-if="error && items.length === 0" class="content-state error-state" role="alert">
      <p>{{ error }}</p>
      <ElButton @click="load">重新加载</ElButton>
    </div>
    <div v-else-if="items.length === 0" class="content-state">
      <h3>还没有关注活动</h3>
      <p>在活动详情页关注演出，之后可以从这里查看场次和开售时间。</p>
      <RouterLink to="/">去发现活动 ↗</RouterLink>
    </div>
    <template v-else>
    <p v-if="error && items.length" class="social-error" role="alert">{{ error }}</p>
    <div class="followed-list">
      <article v-for="item in items" :key="item.activity.id" class="followed-card">
        <RouterLink
          v-if="item.activity.status === 'PUBLISHED'"
          class="followed-card-main"
          :to="`/activities/${item.activity.id}`"
        >
          <ActivityCover :url="item.activity.coverUrl" :category="item.activity.category" :title="item.activity.title" />
          <div class="followed-copy">
            <span class="event-meta">{{ item.activity.city }} · {{ item.activity.venue }}</span>
            <h2>{{ item.activity.title }}</h2>
            <p>{{ item.activity.summary }}</p>
          </div>
        </RouterLink>
        <div v-else class="followed-card-main offline">
          <ActivityCover :url="item.activity.coverUrl" :category="item.activity.category" :title="item.activity.title" />
          <div class="followed-copy">
            <span class="offline-label">活动已下架</span>
            <h2>{{ item.activity.title }}</h2>
            <p>活动已下架，当前没有可售场次；你仍可取消关注。</p>
          </div>
        </div>
        <div v-if="item.sessions.length" class="followed-sessions">
          <div v-for="session in item.sessions" :key="session.id" class="followed-session">
            <strong>{{ session.title }}</strong>
            <span>{{ formatTime(session.startsAt) }}</span>
            <small>开售 {{ formatTime(session.saleStartsAt) }}</small>
          </div>
        </div>
        <div class="followed-card-actions">
          <ElButton :loading="busyId === item.activity.id" :disabled="Boolean(busyId)" @click="unfollow(item.activity.id)">
            取消关注
          </ElButton>
        </div>
      </article>
    </div>
    <div v-if="total > size" class="pagination" aria-label="关注列表分页">
      <ElButton :disabled="loading || page <= 1" @click="changePage(page - 1)">上一页</ElButton>
      <span>第 {{ page }} / {{ Math.ceil(total / size) }} 页</span>
      <ElButton :disabled="loading || page >= Math.ceil(total / size)" @click="changePage(page + 1)">下一页</ElButton>
    </div>
    </template>
  </div>
</template>
