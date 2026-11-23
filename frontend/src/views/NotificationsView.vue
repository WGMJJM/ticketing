<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { notificationApi, type TicketNotification } from '../api/notifications'
import SiteIcon from '../components/SiteIcon.vue'
import { formatTime } from '../utils/date'

const rows = ref<TicketNotification[]>([])
const page = ref(1)
const total = ref(0)
const unreadCount = ref(0)
const unreadOnly = ref(false)
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const controller = new AbortController()

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [result, count] = await Promise.all([
      notificationApi.list(page.value, unreadOnly.value, controller.signal),
      notificationApi.unreadCount(controller.signal),
    ])
    total.value = result.total
    unreadCount.value = count
    const lastPage = Math.max(1, Math.ceil(result.total / 10))
    if (page.value > lastPage) {
      page.value = lastPage
      await load()
      return
    }
    rows.value = result.items
  } catch (failure) {
    if (!controller.signal.aborted)
      error.value = failure instanceof Error ? failure.message : '消息暂时无法加载'
  } finally {
    loading.value = false
  }
}

function setFilter(unread: boolean) {
  unreadOnly.value = unread
  goToPage(1)
}

function goToPage(next: number) {
  page.value = next
  void load()
}

async function markRead(id?: string) {
  saving.value = true
  error.value = ''
  try {
    if (id) await notificationApi.read(id)
    else await notificationApi.readAll()
    if (!controller.signal.aborted) await load()
  } catch (failure) {
    if (!controller.signal.aborted)
      error.value = failure instanceof Error ? failure.message : '标记已读失败，请重试'
  } finally {
    saving.value = false
  }
}

onMounted(load)
onBeforeUnmount(() => controller.abort())
</script>

<template>
  <section class="orders-page">
    <div class="order-top">
      <div>
        <h1>消息中心</h1>
        <p>{{ unreadCount }} 条未读消息</p>
      </div>
      <div class="notification-actions">
        <ElButton :disabled="loading || saving" @click="load">刷新</ElButton>
        <ElButton :disabled="loading || saving || unreadCount === 0" @click="markRead()">全部标为已读</ElButton>
      </div>
    </div>

    <div class="notification-filters" role="group" aria-label="筛选消息">
      <ElButton :type="unreadOnly ? 'default' : 'primary'" :aria-pressed="!unreadOnly"
        :disabled="loading || saving" @click="setFilter(false)">全部</ElButton>
      <ElButton :type="unreadOnly ? 'primary' : 'default'" :aria-pressed="unreadOnly"
        :disabled="loading || saving" @click="setFilter(true)">未读</ElButton>
    </div>

    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <div v-if="loading" class="content-state" role="status">正在加载消息…</div>
    <div v-else-if="!rows.length && !error" class="content-state">
      {{ unreadOnly ? '没有未读消息' : '暂无票务消息' }}
    </div>
    <ul v-else-if="rows.length" class="notification-list">
      <li v-for="item in rows" :key="item.id" :class="{ unread: !item.readAt }">
        <span class="notification-icon" aria-hidden="true">
          <SiteIcon :name="item.kind === 'WAITLIST_ALLOCATED' ? 'ticket' : 'calendar'" />
        </span>
        <div class="notification-copy">
          <div class="notification-heading">
            <strong>{{ item.kind === 'WAITLIST_ALLOCATED' ? '候补成功' : '场次取消' }}</strong>
            <small v-if="!item.readAt">未读</small>
          </div>
          <p>{{ item.activityTitle }} · {{ item.sessionTitle }}</p>
          <p v-if="item.paymentAvailable && item.paymentDeadline">请在 {{ formatTime(item.paymentDeadline) }} 前完成支付。</p>
          <p v-else-if="item.kind === 'WAITLIST_ALLOCATED'">请查看当前订单状态。</p>
          <p v-else>场次已取消，请打开订单查看退款或关闭进度。</p>
          <time :datetime="item.createdAt">{{ formatTime(item.createdAt) }}</time>
        </div>
        <div class="notification-links">
          <RouterLink :to="'/orders/' + item.orderId">{{ item.paymentAvailable ? '前往支付' : '查看订单' }} →</RouterLink>
          <ElButton v-if="!item.readAt" link :disabled="saving" @click="markRead(item.id)">标为已读</ElButton>
        </div>
      </li>
    </ul>
    <div v-if="total > 10" class="pagination">
      <ElButton :disabled="page === 1 || loading || saving" @click="goToPage(page - 1)">上一页</ElButton>
      <span>第 {{ page }} 页 · 共 {{ total }} 条</span>
      <ElButton :disabled="page * 10 >= total || loading || saving" @click="goToPage(page + 1)">下一页</ElButton>
    </div>
  </section>
</template>

<style scoped>
.notification-actions, .notification-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.notification-filters { margin: 20px 0; }
.notification-actions :deep(.el-button + .el-button),
.notification-filters :deep(.el-button + .el-button) { margin-left: 0; }
.notification-list {
  display: grid;
  gap: 12px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.notification-list li {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) auto;
  align-items: start;
  gap: 14px;
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: 12px;
}
.notification-list li.unread { border-left: 3px solid var(--primary); }
.notification-icon {
  display: grid;
  width: 40px;
  height: 40px;
  place-items: center;
  border: 1px solid var(--border);
  border-radius: 10px;
  color: var(--primary);
}
.notification-heading {
  display: flex;
  align-items: center;
  gap: 8px;
}
.notification-heading small, .notification-links a { color: var(--primary); }
.notification-copy p { margin: 4px 0; }
.notification-copy time { color: var(--muted); font-size: 13px; }
.notification-links {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}
@media (max-width: 600px) {
  .notification-list li { grid-template-columns: 42px minmax(0, 1fr); }
  .notification-links { grid-column: 2; }
}
</style>
