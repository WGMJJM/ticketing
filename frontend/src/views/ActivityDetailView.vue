<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  categoryInfo,
  getActivity,
  listSessions,
  sessionStatusText,
  type Activity,
  type ActivitySession,
} from '../api/activities'
import ActivityCover from '../components/ActivityCover.vue'
import TicketList from '../components/TicketList.vue'
import { formatTime } from '../utils/date'
const route = useRoute()
const activity = ref<Activity>()
const sessions = ref<ActivitySession[]>([])
const selected = ref('')
const loading = ref(false)
const error = ref('')
let controller: AbortController | undefined
async function load() {
  controller?.abort()
  const active = new AbortController()
  controller = active
  loading.value = true
  error.value = ''
  selected.value = ''
  try {
    const id = String(route.params.id)
    const [detail, rows] = await Promise.all([
      getActivity(id, active.signal),
      listSessions(id, active.signal),
    ])
    activity.value = detail
    sessions.value = rows
  } catch (failure) {
    if (!active.signal.aborted)
      error.value = failure instanceof Error ? failure.message : '活动加载失败'
  } finally {
    if (!active.signal.aborted) loading.value = false
  }
}
watch(() => route.params.id, load, { immediate: true })
onBeforeUnmount(() => controller?.abort())
</script>
<template>
  <div class="detail-page">
    <RouterLink to="/" class="back-link">← 返回发现活动</RouterLink>
    <div v-if="loading" class="content-state" role="status">
      正在加载活动信息…
    </div>
    <div v-else-if="error" class="content-state error-state" role="alert">
      <h1>暂时无法查看此活动</h1>
      <p>{{ error }}</p>
      <ElButton @click="load">重新加载</ElButton>
    </div>
    <template v-else-if="activity">
      <section class="detail-hero">
        <ActivityCover
          :url="activity.coverUrl"
          :category="activity.category"
          :title="activity.title"
        />
        <div>
          <span class="eyebrow"
            ><span></span>{{ categoryInfo(activity.category).label }} ·
            {{ activity.city }}</span
          >
          <h1>{{ activity.title }}</h1>
          <p>{{ activity.summary }}</p>
          <div class="venue-note">
            举办地点 <strong>{{ activity.city }} · {{ activity.venue }}</strong>
          </div>
          <span class="coming-label">选择场次 · 查看票档 · 预订现场</span>
        </div>
      </section>
      <div class="detail-columns">
        <section class="activity-description">
          <span class="section-kicker">关于这场相遇</span>
          <h2>活动介绍</h2>
          <p>{{ activity.description }}</p>
        </section>
        <section class="session-section">
          <span class="section-kicker">选择你的现场</span>
          <h2>活动场次 <small>以下均为北京时间</small></h2>
          <p v-if="sessions.length === 0" class="content-state">
            场次正在筹备中，请稍后关注。
          </p>
          <div v-else class="session-options">
            <button
              v-for="session in sessions"
              :key="session.id"
              class="session-option"
              :class="{ selected: selected === session.id }"
              :disabled="session.status !== 'SCHEDULED'"
              :aria-pressed="selected === session.id"
              @click="selected = session.id"
            >
              <span
                ><strong>{{ session.title }}</strong
                ><span class="state-pill">{{
                  sessionStatusText[session.status]
                }}</span></span
              ><span
                >{{ formatTime(session.startsAt) }} —
                {{ formatTime(session.endsAt) }}</span
              ><small>开售时间 {{ formatTime(session.saleStartsAt) }}</small>
            </button>
          </div>
          <div class="stage-notice" role="status">
            {{
              selected ? '已选择场次。' : '可选择正常场次查看安排。'
            }}可选择正在售票的票档提交订单并体验模拟支付，不产生实际扣款。
          </div>
        </section>
      </div>
      <TicketList
        v-if="selected"
        :key="selected"
        :activity-id="activity.id"
        :session-id="selected"
      />
    </template>
  </div>
</template>
