<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
import { adminApi } from '../api/admin'
import {
  activityStatusText,
  categories,
  categoryInfo,
  sessionStatusText,
  type Activity,
  type ActivityInput,
  type ActivityQuery,
  type ActivitySession,
  type Category,
  type SessionInput,
} from '../api/activities'
import { useAdminStore } from '../stores/admin'
import { formatTime, fromBeijingInput, toBeijingInput } from '../utils/date'
import { chineseValidation, clearValidation } from '../utils/validation'
import TicketManager from '../components/TicketManager.vue'
const ticketSession = ref<ActivitySession>()
const ticketsDialog = ref(false)
function openTickets(session: ActivitySession) {
  ticketSession.value = session
  ticketsDialog.value = true
}
const admin = useAdminStore()
const router = useRouter()
const query = reactive<ActivityQuery>({
  keyword: '',
  city: '',
  category: '',
  status: '',
  page: 1,
  size: 10,
})
const rows = ref<Activity[]>([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const activityDialog = ref(false)
const editing = ref<Activity>()
const form = reactive<ActivityInput>({
  title: '',
  category: 'CONCERT',
  city: '',
  venue: '',
  summary: '',
  description: '',
  coverUrl: '',
})
const sessionsDialog = ref(false)
const parent = ref<Activity>()
const sessions = ref<ActivitySession[]>([])
const sessionsLoading = ref(false)
const sessionEditing = ref<ActivitySession>()
const sessionFormOpen = ref(false)
const sessionForm = reactive({
  title: '',
  startsAt: '',
  endsAt: '',
  saleStartsAt: '',
})
watch(
  () => admin.authenticated,
  (value) => {
    if (!value) void router.replace('/admin/login')
  },
)
function report(failure: unknown) {
  error.value =
    failure instanceof Error ? failure.message : '操作失败，请稍后重试'
}
async function load(reset = false) {
  if (reset) query.page = 1
  loading.value = true
  error.value = ''
  try {
    const page = await adminApi.list(query)
    rows.value = page.items
    total.value = page.total
  } catch (failure) {
    report(failure)
  } finally {
    loading.value = false
  }
}
async function edit(activity?: Activity) {
  error.value = ''
  editing.value = activity
  Object.assign(
    form,
    {
      title: '',
      category: 'CONCERT' as Category,
      city: '',
      venue: '',
      summary: '',
      description: '',
      coverUrl: '',
      version: undefined,
    },
    activity ? { ...activity, coverUrl: activity.coverUrl ?? '' } : {},
  )
  activityDialog.value = true
}
async function saveActivity() {
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const payload: ActivityInput = {
      title: form.title,
      category: form.category,
      city: form.city,
      venue: form.venue,
      summary: form.summary,
      description: form.description,
      coverUrl: form.coverUrl,
      ...(editing.value ? { version: editing.value.version } : {}),
    }
    if (editing.value) await adminApi.update(editing.value.id, payload)
    else await adminApi.create(payload)
    activityDialog.value = false
    notice.value = editing.value ? '活动已更新' : '活动草稿已创建'
    await load()
  } catch (failure) {
    report(failure)
  } finally {
    saving.value = false
  }
}
async function transition(activity: Activity) {
  const operation = activity.status === 'PUBLISHED' ? 'offline' : 'publish'
  const label = operation === 'publish' ? '发布' : '下架'
  try {
    await ElMessageBox.confirm(
      `确定${label}“${activity.title}”吗？${operation === 'offline' ? '下架后用户将无法查看该活动。' : '发布后用户可以查看活动和场次。'}`,
      `${label}活动`,
      {
        confirmButtonText: `确认${label}`,
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return
  }
  saving.value = true
  error.value = ''
  try {
    await adminApi.transition(activity.id, operation, activity.version)
    notice.value = `活动已${label}`
    await load()
  } catch (failure) {
    report(failure)
  } finally {
    saving.value = false
  }
}
async function openSessions(activity: Activity) {
  parent.value = activity
  sessions.value = []
  sessionFormOpen.value = false
  sessionsDialog.value = true
  error.value = ''
  await loadSessions()
}
async function loadSessions() {
  if (!parent.value) return
  sessionsLoading.value = true
  try {
    sessions.value = await adminApi.sessions(parent.value.id)
  } catch (failure) {
    report(failure)
  } finally {
    sessionsLoading.value = false
  }
}
function editSession(session?: ActivitySession) {
  sessionEditing.value = session
  Object.assign(sessionForm, {
    title: session?.title ?? '',
    startsAt: session ? toBeijingInput(session.startsAt) : '',
    endsAt: session ? toBeijingInput(session.endsAt) : '',
    saleStartsAt: session ? toBeijingInput(session.saleStartsAt) : '',
  })
  sessionFormOpen.value = true
  error.value = ''
}
async function saveSession() {
  if (!parent.value) return
  if (
    sessionForm.endsAt <= sessionForm.startsAt ||
    sessionForm.saleStartsAt >= sessionForm.startsAt
  ) {
    error.value = '结束时间必须晚于开始时间，开售时间必须早于开始时间'
    return
  }
  saving.value = true
  error.value = ''
  const payload: SessionInput = {
    title: sessionForm.title,
    startsAt: fromBeijingInput(sessionForm.startsAt),
    endsAt: fromBeijingInput(sessionForm.endsAt),
    saleStartsAt: fromBeijingInput(sessionForm.saleStartsAt),
    ...(sessionEditing.value ? { version: sessionEditing.value.version } : {}),
  }
  try {
    if (sessionEditing.value)
      await adminApi.updateSession(sessionEditing.value.id, payload)
    else await adminApi.createSession(parent.value.id, payload)
    sessionFormOpen.value = false
    notice.value = '场次已保存'
    await loadSessions()
  } catch (failure) {
    report(failure)
  } finally {
    saving.value = false
  }
}
async function cancelSession(session: ActivitySession) {
  try {
    await ElMessageBox.confirm(
      `确定取消“${session.title}”吗？取消后不可恢复，用户将无法选择此场次。`,
      '取消场次',
      {
        confirmButtonText: '确认取消场次',
        cancelButtonText: '保留场次',
        type: 'warning',
      },
    )
  } catch {
    return
  }
  saving.value = true
  error.value = ''
  try {
    await adminApi.cancelSession(session.id, session.version)
    notice.value = '场次已取消'
    await loadSessions()
  } catch (failure) {
    report(failure)
  } finally {
    saving.value = false
  }
}
function page(next: number) {
  query.page = next
  void load()
}
function logout() {
  admin.clear()
  void router.replace('/admin/login')
}
onMounted(() => load())
</script>
<template>
  <div class="admin-page">
    <div class="admin-heading">
      <div>
        <span class="section-kicker">运营工作台</span>
        <h1>活动与场次管理</h1>
        <p>管理活动内容、发布状态和场次安排。时间统一按北京时间输入与展示。</p>
      </div>
      <div class="admin-user">
        <span>{{ admin.username }}</span
        ><ElButton @click="logout">退出认证</ElButton>
      </div>
    </div>
    <p
      v-if="error && !activityDialog && !sessionsDialog"
      class="inline-error"
      role="alert"
    >
      {{ error }}
    </p>
    <p v-if="notice" class="success-notice" role="status">{{ notice }}</p>
    <form class="activity-filters admin-filters" @submit.prevent="load(true)">
      <label class="search-field"
        ><span>活动名称</span
        ><input
          v-model="query.keyword"
          placeholder="搜索活动名称"
          maxlength="80" /></label
      ><label
        ><span>城市</span
        ><input
          v-model="query.city"
          placeholder="全部城市"
          maxlength="64" /></label
      ><label
        ><span>类别</span
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
      ><label
        ><span>状态</span
        ><select v-model="query.status">
          <option value="">全部状态</option>
          <option value="DRAFT">草稿</option>
          <option value="PUBLISHED">已发布</option>
          <option value="OFFLINE">已下架</option>
        </select></label
      ><ElButton native-type="submit" :loading="loading">筛选</ElButton
      ><ElButton type="primary" @click="edit()">创建活动</ElButton>
    </form>
    <div v-if="loading" class="content-state" role="status">正在加载活动…</div>
    <div v-else-if="rows.length === 0" class="content-state">
      暂无活动，请创建活动或调整筛选条件。
    </div>
    <div v-else class="table-wrap">
      <table class="admin-table">
        <thead>
          <tr>
            <th>活动</th>
            <th>类别与城市</th>
            <th>状态</th>
            <th>更新时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="activity in rows" :key="activity.id">
            <td>
              <strong>{{ activity.title }}</strong
              ><small>{{ activity.venue }}</small>
            </td>
            <td>
              {{ categoryInfo(activity.category).label }} · {{ activity.city }}
            </td>
            <td>
              <span class="state-pill" :class="activity.status.toLowerCase()">{{
                activityStatusText[activity.status]
              }}</span>
            </td>
            <td>{{ formatTime(activity.updatedAt) }}</td>
            <td class="table-actions">
              <ElButton
                size="small"
                :disabled="saving || activity.status === 'PUBLISHED'"
                @click="edit(activity)"
                >编辑</ElButton
              ><ElButton
                size="small"
                :disabled="saving"
                @click="openSessions(activity)"
                >场次</ElButton
              ><ElButton
                size="small"
                :disabled="saving"
                @click="transition(activity)"
                >{{
                  activity.status === 'PUBLISHED' ? '下架' : '发布'
                }}</ElButton
              >
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-if="total > query.size" class="pagination">
      <ElButton
        :disabled="loading || query.page <= 1"
        @click="page(query.page - 1)"
        >上一页</ElButton
      ><span
        >第 {{ query.page }} / {{ Math.ceil(total / query.size) }} 页，共
        {{ total }} 条</span
      ><ElButton
        :disabled="loading || query.page >= Math.ceil(total / query.size)"
        @click="page(query.page + 1)"
        >下一页</ElButton
      >
    </div>
    <p class="admin-footnote">
      已发布活动须先下架再编辑。场次取消后不可恢复。可从场次进入票档管理；购票与支付功能尚未开放。
    </p>

    <ElDialog
      v-model="activityDialog"
      :title="editing ? '编辑活动' : '创建活动草稿'"
      width="min(720px, 94vw)"
      :close-on-click-modal="false"
      :close-on-press-escape="!saving"
      :show-close="!saving"
      destroy-on-close
    >
      <form
        class="edit-form"
        @submit.prevent="saveActivity"
        @invalid.capture="chineseValidation"
        @input.capture="clearValidation"
        @change.capture="clearValidation"
      >
        <label
          >活动名称<input v-model="form.title" required maxlength="200"
        /></label>
        <div class="form-pair">
          <label
            >活动类别<select v-model="form.category" required>
              <option
                v-for="category in categories"
                :key="category.value"
                :value="category.value"
              >
                {{ category.label }}
              </option>
            </select></label
          ><label
            >举办城市<input v-model="form.city" required maxlength="64"
          /></label>
        </div>
        <label
          >场馆与详细地点<input
            v-model="form.venue"
            required
            maxlength="200" /></label
        ><label
          >活动简介<textarea
            v-model="form.summary"
            required
            maxlength="500"
            rows="2"
          ></textarea></label
        ><label
          >活动详情<textarea
            v-model="form.description"
            required
            maxlength="20000"
            rows="5"
          ></textarea></label
        ><label
          >封面图片地址（可选）<input
            v-model="form.coverUrl"
            type="url"
            pattern="https?://.*"
            maxlength="2048"
            placeholder="https://… 留空时使用平台默认封面"
        /></label>
        <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
        <div class="form-actions">
          <ElButton :disabled="saving" @click="activityDialog = false"
            >取消</ElButton
          ><ElButton type="primary" native-type="submit" :loading="saving"
            >保存活动</ElButton
          >
        </div>
      </form>
    </ElDialog>

    <ElDialog
      v-model="sessionsDialog"
      :title="`${parent?.title ?? ''} · 场次管理`"
      width="min(900px, 94vw)"
      :close-on-click-modal="false"
      :close-on-press-escape="!saving"
      :show-close="!saving"
      destroy-on-close
    >
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <p class="form-hint">
        所有时间均为北京时间，每个活动最多200个场次；取消场次仍保留在用户列表中并明确标记。
      </p>
      <ElButton
        v-if="!sessionFormOpen"
        type="primary"
        :disabled="sessionsLoading"
        @click="editSession()"
        >新增场次</ElButton
      >
      <p v-if="sessionsLoading" role="status">正在加载场次…</p>
      <form
        v-if="sessionFormOpen"
        class="edit-form session-edit"
        @submit.prevent="saveSession"
        @invalid.capture="chineseValidation"
        @input.capture="clearValidation"
        @change.capture="clearValidation"
      >
        <h3>{{ sessionEditing ? '编辑场次' : '新增场次' }}</h3>
        <label
          >场次名称<input v-model="sessionForm.title" required maxlength="100"
        /></label>
        <div class="form-pair">
          <label
            >开始时间（北京时间）<input
              v-model="sessionForm.startsAt"
              type="datetime-local"
              required /></label
          ><label
            >结束时间（北京时间）<input
              v-model="sessionForm.endsAt"
              type="datetime-local"
              required
          /></label>
        </div>
        <label
          >开售时间（北京时间）<input
            v-model="sessionForm.saleStartsAt"
            type="datetime-local"
            required
        /></label>
        <div class="form-actions">
          <ElButton :disabled="saving" @click="sessionFormOpen = false"
            >取消编辑</ElButton
          ><ElButton type="primary" native-type="submit" :loading="saving"
            >保存场次</ElButton
          >
        </div>
      </form>
      <div
        v-else-if="!sessionsLoading && sessions.length === 0"
        class="content-state"
      >
        该活动尚无场次，请新增场次。
      </div>
      <div v-if="sessions.length" class="table-wrap">
        <table class="admin-table">
          <thead>
            <tr>
              <th>场次</th>
              <th>开始 / 结束时间</th>
              <th>开售时间</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="session in sessions" :key="session.id">
              <td>{{ session.title }}</td>
              <td>
                {{ formatTime(session.startsAt)
                }}<small>{{ formatTime(session.endsAt) }}</small>
              </td>
              <td>{{ formatTime(session.saleStartsAt) }}</td>
              <td>{{ sessionStatusText[session.status] }}</td>
              <td class="table-actions">
                <ElButton
                  size="small"
                  :disabled="saving || session.status !== 'SCHEDULED'"
                  @click="editSession(session)"
                  >编辑</ElButton
                ><ElButton
                  size="small"
                  :disabled="saving || session.status !== 'SCHEDULED'"
                  @click="cancelSession(session)"
                  >取消场次</ElButton
                >
                <ElButton
                  size="small"
                  :disabled="saving"
                  @click="openTickets(session)"
                  >票档管理</ElButton
                >
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </ElDialog>
    <TicketManager
      v-if="ticketSession"
      v-model="ticketsDialog"
      :session="ticketSession"
    />
  </div>
</template>
