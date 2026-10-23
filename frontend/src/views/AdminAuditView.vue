<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { auditActionText, auditResourceText, listAudit, type AuditRecord } from '../api/audit'
import { formatTime, fromBeijingInput } from '../utils/date'
import { formatCents } from '../utils/money'
import { chineseValidation, clearValidation } from '../utils/validation'
const query = reactive({ operatorId: '', action: '', resourceType: '', resourceId: '' })
const from = ref('')
const to = ref('')
const page = ref(1)
const total = ref(0)
const rows = ref<AuditRecord[]>([])
const loading = ref(false)
const error = ref('')
let generation = 0
async function load(reset = false) {
  if (reset) page.value = 1
  const current = ++generation
  loading.value = true
  error.value = ''
  try {
    if (from.value && to.value && from.value >= to.value) throw new Error('结束时间必须晚于开始时间')
    const params: Record<string, string | number> = Object.fromEntries(Object.entries(query).filter(([, value]) => value !== ''))
    params.page = page.value; params.size = 10
    if (from.value) params.from = fromBeijingInput(from.value)
    if (to.value) params.to = fromBeijingInput(to.value)
    const data = await listAudit(params)
    if (current === generation) { rows.value = data.items; total.value = data.total }
  } catch (failure) { if (current === generation) error.value = failure instanceof Error ? failure.message : '审计记录读取失败' }
  finally { if (current === generation) loading.value = false }
}
const fieldText: Record<string, string> = { status: '状态', title: '名称', summary: '简介', description: '活动详情', coverUrl: '封面地址', name: '票档名称', category: '类别', city: '城市', venue: '场馆', version: '版本', startsAt: '开始时间', endsAt: '结束时间', saleStartsAt: '开售时间', saleEndsAt: '停售时间', priceCents: '票价', totalStock: '总库存', maxQuantityPerOrder: '单笔限购', role: '账号角色', sessionId: '场次编号', active: '有效授权', revokedPermissions: '已撤销授权数量', orderStatus: '订单状态', result: '处理结果', reason: '处理原因' }
const valueText: Record<string, string> = { USER: '普通用户', ADMIN: '管理员', CHECKIN_STAFF: '核销工作人员', ACTIVE: '正常', DISABLED: '已停用', DRAFT: '草稿', PUBLISHED: '已发布', OFFLINE: '已下架', SCHEDULED: '正常', CANCELLED: '已取消', ENDED: '已结束', ON_SALE: '已启用销售', OFF_SALE: '已下架', PAID: '已支付', REFUNDED: '已模拟退款', SUCCEEDED: '处理成功', MANUAL_REQUIRED: '待人工核对', CUSTOMER_REQUEST: '用户诉求售后', SERVICE_ADJUSTMENT: '运营服务调整', SESSION_CANCELLED: '场次取消善后', CONCERT: '演唱会', FESTIVAL: '音乐节', STAGE: '舞台演出', EXHIBITION: '展览', ESPORTS: '电竞赛事' }
function snapshot(raw: string) {
  const values = JSON.parse(raw) as Record<string, unknown>
  return Object.entries(values).map(([key, value]) => {
    const text = value == null ? '未设置' : key === 'priceCents' ? formatCents(String(value)) : key.endsWith('At') ? `${formatTime(String(value))}（北京）` : typeof value === 'boolean' ? value ? '是' : '否' : valueText[String(value)] ?? String(value)
    return `${fieldText[key] ?? '业务字段'}：${text}`
  })
}
onMounted(() => { void load() })
onBeforeUnmount(() => { generation++ })
</script>
<template>
  <section class="admin-orders-page">
    <header class="order-top"><div><span class="eyebrow"><span></span>运营工作台</span><h1>管理操作审计</h1></div><RouterLink class="back-link" to="/admin/activities">活动与场次管理 ↗</RouterLink></header>
    <p class="admin-order-intro">记录已提交的管理操作及必要变更字段，只读查询。核销事实仍以电子票的真实工作人员与核销时间为准。</p>
    <form class="admin-order-filters" @submit.prevent="load(true)" @invalid.capture="chineseValidation" @input.capture="clearValidation">
      <label>操作人编号<input v-model="query.operatorId" inputmode="numeric" pattern="[1-9][0-9]*" placeholder="用户编号" /></label>
      <label>操作类型<select v-model="query.action"><option value="">全部操作</option><option v-for="(label, key) in auditActionText" :key="key" :value="key">{{ label }}</option></select></label>
      <label>资源类型<select v-model="query.resourceType"><option value="">全部资源</option><option v-for="(label, key) in auditResourceText" :key="key" :value="key">{{ label }}</option></select></label>
      <label>资源编号<input v-model="query.resourceId" inputmode="numeric" pattern="[1-9][0-9]*" /></label>
      <label>开始时间（北京）<input v-model="from" type="datetime-local" /></label><label>结束时间（北京）<input v-model="to" type="datetime-local" /></label>
      <ElButton native-type="submit" type="primary" :loading="loading">查询审计</ElButton>
    </form>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p><p v-if="loading" role="status">正在加载审计记录…</p><p v-else-if="!error && !rows.length">当前条件下暂无审计记录。</p>
    <div v-else-if="!error" class="admin-orders-table" tabindex="0" aria-label="管理审计记录，可横向滚动"><table><thead><tr><th>操作与资源</th><th>操作人及时间（北京）</th><th>变更前</th><th>变更后</th><th>请求关联编号</th></tr></thead><tbody><tr v-for="row in rows" :key="row.id">
      <td><strong>{{ auditActionText[row.action] ?? row.summary }}</strong><small>{{ auditResourceText[row.resourceType] }} · {{ row.resourceId }}</small></td><td>{{ row.operatorName }}<small>用户 {{ row.operatorId }}</small><small>{{ formatTime(row.occurredAt) }}</small></td>
      <td><details v-if="snapshot(row.beforeValues).length"><summary>查看变更前字段</summary><small v-for="item in snapshot(row.beforeValues)" :key="item">{{ item }}</small></details><span v-else>无历史值</span></td><td><details><summary>查看变更后字段</summary><small v-for="item in snapshot(row.afterValues)" :key="item">{{ item }}</small></details></td><td><small>{{ row.requestId }}</small></td>
    </tr></tbody></table></div>
    <div class="pagination"><ElButton :disabled="page <= 1 || loading" @click="page--; load()">上一页</ElButton><span>第 {{ page }} 页 · 共 {{ total }} 条</span><ElButton :disabled="page * 10 >= total || loading" @click="page++; load()">下一页</ElButton></div>
  </section>
</template>
<style scoped>
details { min-width: 10rem; max-width: 26rem; }
details small { white-space: pre-wrap; overflow-wrap: anywhere; }
summary { cursor: pointer; color: var(--primary-color, #7751a9); }
</style>
