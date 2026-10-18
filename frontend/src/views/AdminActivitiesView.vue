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
  type Activity,
  type ActivityInput,
  type ActivityQuery,
  type Category,
} from '../api/activities'
import { useAuthStore } from '../stores/auth'
import { formatTime } from '../utils/date'
import { chineseValidation, clearValidation } from '../utils/validation'
import AdminSessionManager from '../components/AdminSessionManager.vue'
const admin = useAuthStore()
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
watch(
  () => admin.isAdmin,
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
function openSessions(activity: Activity) {
  parent.value = activity
  sessionsDialog.value = true
}
function page(next: number) {
  query.page = next
  void load()
}
async function logout() {
  try {
    await admin.logout()
    await router.replace('/login')
  } catch (failure) {
    report(failure)
  }
}
onMounted(() => load())
</script>
<template>
  <div class="admin-page">
    <div class="admin-heading">
      <div>
        <span class="section-kicker">运营工作台</span>
        <h1>活动与场次管理</h1>
        <RouterLink to="/admin/checkins">进入电子票核销</RouterLink>
        <p>管理活动内容、发布状态和场次安排。时间统一按北京时间输入与展示。</p>
      </div>
      <div class="admin-user">
        <span>{{ admin.user?.username }}</span
        ><ElButton @click="logout">退出登录</ElButton>
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
      已发布活动须先下架再编辑。场次取消后不可恢复。可从场次进入票档管理，锁定库存由订单事务维护。
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

    <AdminSessionManager v-if="parent" v-model="sessionsDialog" :activity="parent" />
  </div>
</template>
