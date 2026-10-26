<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  categoryInfo,
  followActivity,
  getActivityEngagement,
  getActivity,
  likeActivity,
  listSessions,
  sessionStatusText,
  unlikeActivity,
  unfollowActivity,
  type Activity,
  type ActivityEngagement,
  type ActivitySession,
} from '../api/activities'
import ActivityCover from '../components/ActivityCover.vue'
import SiteIcon from '../components/SiteIcon.vue'
import TicketList from '../components/TicketList.vue'
import { useAuthStore } from '../stores/auth'
import { formatTime } from '../utils/date'
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const activity = ref<Activity>()
const sessions = ref<ActivitySession[]>([])
const engagement = ref<ActivityEngagement>()
const selected = ref('')
const loading = ref(false)
const error = ref('')
const engagementError = ref('')
const likeBusy = ref(false)
const followBusy = ref(false)
let controller: AbortController | undefined
let engagementController: AbortController | undefined

async function refreshEngagement(id = activity.value?.id) {
  if (!id) return
  engagementController?.abort()
  const active = new AbortController()
  engagementController = active
  engagementError.value = ''
  try {
    engagement.value = await getActivityEngagement(id, active.signal)
  } catch (failure) {
    if (!active.signal.aborted)
      engagementError.value = failure instanceof Error ? failure.message : '互动信息暂时无法加载'
  }
}

async function load() {
  controller?.abort()
  engagementController?.abort()
  const active = new AbortController()
  controller = active
  loading.value = true
  error.value = ''
  engagement.value = undefined
  engagementError.value = ''
  selected.value = ''
  try {
    const id = String(route.params.id)
    const [detail, rows] = await Promise.all([
      getActivity(id, active.signal),
      listSessions(id, active.signal),
    ])
    activity.value = detail
    sessions.value = rows
    void refreshEngagement(id)
  } catch (failure) {
    if (!active.signal.aborted)
      error.value = failure instanceof Error ? failure.message : '活动加载失败'
  } finally {
    if (!active.signal.aborted) loading.value = false
  }
}

function guideLogin() {
  return router.push({ path: '/login', query: { redirect: route.fullPath } })
}

async function toggleLike() {
  if (!engagement.value || likeBusy.value) return
  if (!auth.authenticated) {
    await guideLogin()
    return
  }
  likeBusy.value = true
  engagementError.value = ''
  try {
    engagement.value = engagement.value.liked
      ? await unlikeActivity(activity.value!.id)
      : await likeActivity(activity.value!.id)
  } catch (failure) {
    engagementError.value = failure instanceof Error ? failure.message : '点赞操作失败，请重试'
    await auth.restore(true)
  } finally {
    likeBusy.value = false
  }
}

async function toggleFollow() {
  if (!engagement.value || followBusy.value) return
  if (!auth.authenticated) {
    await guideLogin()
    return
  }
  followBusy.value = true
  engagementError.value = ''
  try {
    engagement.value = engagement.value.following
      ? await unfollowActivity(activity.value!.id)
      : await followActivity(activity.value!.id)
  } catch (failure) {
    engagementError.value = failure instanceof Error ? failure.message : '关注操作失败，请重试'
    await auth.restore(true)
  } finally {
    followBusy.value = false
  }
}

watch(() => route.params.id, load, { immediate: true })
watch(() => auth.authenticated, () => void refreshEngagement())
onBeforeUnmount(() => {
  controller?.abort()
  engagementController?.abort()
})
</script>
<template>
  <div class="detail-page">
    <RouterLink to="/" class="back-link">← 返回演出列表</RouterLink>
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
          <div v-if="engagement" class="activity-social-actions" aria-label="活动互动">
            <button class="social-action" :class="{ active: engagement.liked }" :disabled="likeBusy || followBusy" :aria-pressed="engagement.liked" @click="toggleLike">
              <SiteIcon name="heart" />{{ engagement.liked ? '已点赞' : '点赞' }}<span>{{ engagement.likeCount }}</span>
            </button>
            <button class="social-action" :class="{ active: engagement.following }" :disabled="likeBusy || followBusy" :aria-pressed="engagement.following" @click="toggleFollow">
              <SiteIcon name="bookmark" />{{ engagement.following ? '已关注' : '关注演出' }}<span>{{ engagement.followCount }}</span>
            </button>
          </div>
          <div v-if="engagementError" class="social-error" role="status">{{ engagementError }} <ElButton size="small" @click="refreshEngagement()">重试</ElButton></div>
          <span class="coming-label">点赞表达喜欢，关注方便下次回看场次。</span>
        </div>
      </section>
      <div class="detail-columns">
        <section class="activity-description">
          <span class="section-kicker">关于这场相遇</span>
          <h2>演出介绍</h2>
          <p>{{ activity.description }}</p>
        </section>
        <section class="session-section">
          <span class="section-kicker">选择你的现场</span>
          <h2>选择场次 <small>以下时间均为北京时间</small></h2>
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
            }}选择后可查看票档与余票。
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
