<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import {
  categories,
  categoryInfo,
  listActivities,
  type Activity,
  type ActivityQuery,
} from '../api/activities'
import ActivityCover from './ActivityCover.vue'
const query = reactive<ActivityQuery>({
  keyword: '',
  category: '',
  city: '',
  page: 1,
  size: 6,
})
const items = ref<Activity[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
let controller: AbortController | undefined
async function load(reset = false) {
  if (reset) query.page = 1
  controller?.abort()
  const active = new AbortController()
  controller = active
  loading.value = true
  error.value = ''
  try {
    const page = await listActivities(query, active.signal)
    items.value = page.items
    total.value = page.total
  } catch (failure) {
    if (!active.signal.aborted) {
      error.value = failure instanceof Error ? failure.message : '活动加载失败'
      items.value = []
    }
  } finally {
    if (!active.signal.aborted) loading.value = false
  }
}
function changePage(next: number) {
  query.page = next
  void load()
}
onMounted(() => load())
onBeforeUnmount(() => controller?.abort())
</script>
<template>
  <form class="activity-filters" @submit.prevent="load(true)">
    <label class="search-field"
      ><span>搜索活动</span
      ><input
        v-model="query.keyword"
        maxlength="80"
        placeholder="输入活动名称，寻找你的热爱"
    /></label>
    <label
      ><span>活动类别</span
      ><select v-model="query.category">
        <option value="">全部类别</option>
        <option
          v-for="category in categories"
          :key="category.value"
          :value="category.value"
        >
          {{ category.label }}
        </option>
      </select></label
    >
    <label
      ><span>举办城市</span
      ><input v-model="query.city" maxlength="64" placeholder="全部城市"
    /></label>
    <ElButton native-type="submit" type="primary" :loading="loading"
      >查找活动</ElButton
    >
  </form>
  <div v-if="loading" class="content-state" role="status">
    正在寻找精彩现场…
  </div>
  <div v-else-if="error" class="content-state error-state" role="alert">
    <h3>活动暂时无法加载</h3>
    <p>{{ error }}</p>
    <ElButton @click="load()">重新加载</ElButton>
  </div>
  <div v-else-if="items.length === 0" class="empty-events">
    <div class="empty-ticket" aria-hidden="true">✦</div>
    <div>
      <h3>暂时没有符合条件的活动</h3>
      <p>试试其他关键词、类别或城市。活动正在陆续准备中。</p>
    </div>
  </div>
  <div v-else class="activity-grid">
    <RouterLink
      v-for="item in items"
      :key="item.id"
      :to="`/activities/${item.id}`"
      class="event-card"
    >
      <ActivityCover
        :url="item.coverUrl"
        :category="item.category"
        :title="item.title"
      />
      <div class="event-card-body">
        <span class="event-meta"
          >{{ categoryInfo(item.category).label }} <span>·</span>
          {{ item.city }}</span
        >
        <h3>{{ item.title }}</h3>
        <p>{{ item.summary }}</p>
        <div class="event-card-bottom">
          <span>{{ item.venue }}</span
          ><span>查看场次 ↗</span>
        </div>
      </div>
    </RouterLink>
  </div>
  <div
    v-if="!error && total > query.size"
    class="pagination"
    aria-label="活动分页"
  >
    <ElButton
      :disabled="loading || query.page <= 1"
      @click="changePage(query.page - 1)"
      >上一页</ElButton
    ><span
      >第 {{ query.page }} / {{ Math.ceil(total / query.size) }} 页，共
      {{ total }} 场活动</span
    ><ElButton
      :disabled="loading || query.page >= Math.ceil(total / query.size)"
      @click="changePage(query.page + 1)"
      >下一页</ElButton
    >
  </div>
</template>
