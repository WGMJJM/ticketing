<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { staffApi, staffRoleText, type StaffAccount, type StaffPermission } from '../api/staff'
import { sessionStatusText } from '../api/activities'
import { formatTime } from '../utils/date'
import { chineseValidation, clearValidation } from '../utils/validation'
const rows = ref<StaffAccount[]>([])
const username = ref('')
const page = ref(1)
const total = ref(0)
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const selected = ref<StaffAccount>()
const permissions = ref<StaffPermission[]>([])
const permissionPage = ref(1)
const permissionTotal = ref(0)
const permissionLoading = ref(false)
const permissionError = ref('')
const sessionId = ref('')
let generation = 0
let permissionGeneration = 0
async function load(reset = false) {
  if (reset) page.value = 1
  const current = ++generation
  loading.value = true
  error.value = ''
  try {
    const data = await staffApi.list(page.value, username.value.trim())
    if (current === generation) { rows.value = data.items; total.value = data.total }
  } catch (failure) { if (current === generation) error.value = failure instanceof Error ? failure.message : '账号列表读取失败' }
  finally { if (current === generation) loading.value = false }
}
async function loadPermissions(account?: StaffAccount) {
  if (account) { selected.value = account; permissionPage.value = 1; sessionId.value = '' }
  if (!selected.value) return
  const current = ++permissionGeneration
  permissionLoading.value = true
  permissionError.value = ''
  try {
    const data = await staffApi.permissions(selected.value.id, permissionPage.value)
    if (current === permissionGeneration) { permissions.value = data.items; permissionTotal.value = data.total }
  } catch (failure) { if (current === permissionGeneration) { permissions.value = []; permissionError.value = failure instanceof Error ? failure.message : '授权记录读取失败' } }
  finally { if (current === permissionGeneration) permissionLoading.value = false }
}
async function change(account: StaffAccount, action: 'promote' | 'demote' | 'disable') {
  const label = { promote: '授权为核销工作人员', demote: '降回普通用户并撤销所有场次授权', disable: '停用账号并撤销所有场次授权' }[action]
  try { await ElMessageBox.confirm(`确认将“${account.username}”${label}？`, '确认工作人员变更', { confirmButtonText: '确认变更', cancelButtonText: '暂不处理', type: 'warning' }) }
  catch { return }
  busy.value = true
  error.value = ''
  try {
    const updated = await staffApi.change(account.id, action)
    if (selected.value?.id === updated.id) { selected.value = updated; await loadPermissions() }
    await load()
  } catch (failure) { error.value = failure instanceof Error ? failure.message : '工作人员变更失败' }
  finally { busy.value = false }
}
async function grant(action: 'grant' | 'revoke', id = sessionId.value) {
  if (!selected.value || busy.value) return
  const account = selected.value
  try { await ElMessageBox.confirm(`确认${action === 'grant' ? '授权' : '撤销'}“${account.username}”对场次 ${id} 的核销权限？`, '确认场次授权', { confirmButtonText: '确认操作', cancelButtonText: '取消', type: 'warning' }) }
  catch { return }
  busy.value = true
  permissionError.value = ''
  try { await staffApi.grant(account.id, id, action); await loadPermissions() }
  catch (failure) { permissionError.value = failure instanceof Error ? failure.message : '场次授权操作失败' }
  finally { busy.value = false }
}
onMounted(() => { void load() })
onBeforeUnmount(() => { generation++; permissionGeneration++ })
</script>
<template>
  <section class="admin-orders-page">
    <header class="order-top"><div><span class="eyebrow"><span></span>运营工作台</span><h1>工作人员管理</h1></div><RouterLink class="back-link" to="/admin/activities">活动与场次管理 ↗</RouterLink></header>
    <p class="admin-order-intro">从已有普通账号中授权工作人员，核销权限按场次分配。工作人员不获得订单、库存或退款管理权限。</p>
    <form class="admin-order-filters" @submit.prevent="load(true)"><label>用户名<input v-model.trim="username" maxlength="64" placeholder="完整用户名，留空查看全部" /></label><ElButton native-type="submit" type="primary" :loading="loading">查询账号</ElButton></form>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <p v-if="loading" role="status">正在加载账号…</p>
    <p v-else-if="!error && !rows.length">暂无符合条件的账号。</p>
    <div v-else-if="!error" class="admin-orders-table" tabindex="0" aria-label="工作人员账号列表，可横向滚动">
      <table><thead><tr><th>账号</th><th>角色</th><th>账号状态</th><th>操作</th></tr></thead><tbody>
        <tr v-for="account in rows" :key="account.id"><td><strong>{{ account.username }}</strong><small>用户编号 {{ account.id }}</small></td><td>{{ staffRoleText[account.role] }}</td><td>{{ account.status === 'ACTIVE' ? '正常' : '已停用' }}</td><td><div class="auth-links">
          <ElButton :disabled="busy" @click="loadPermissions(account)">场次授权</ElButton>
          <ElButton v-if="account.role === 'USER'" :disabled="busy || account.status !== 'ACTIVE'" @click="change(account, 'promote')">授权工作人员</ElButton>
          <template v-else><ElButton :disabled="busy || account.status !== 'ACTIVE'" @click="change(account, 'demote')">撤销工作人员角色</ElButton><ElButton :disabled="busy || account.status !== 'ACTIVE'" @click="change(account, 'disable')">停用工作人员</ElButton></template>
        </div></td></tr>
      </tbody></table>
    </div>
    <div class="pagination"><ElButton :disabled="page <= 1 || loading || busy" @click="page--; load()">上一页</ElButton><span>第 {{ page }} 页 · 共 {{ total }} 个账号</span><ElButton :disabled="page * 10 >= total || loading || busy" @click="page++; load()">下一页</ElButton></div>
    <section v-if="selected" class="order-card">
      <h2>{{ selected.username }} · 场次授权</h2><p>场次编号可在活动与场次管理中查看。撤权后旧会话不能继续核销该场次。</p>
      <form v-if="selected.role === 'CHECKIN_STAFF' && selected.status === 'ACTIVE'" class="admin-order-filters" @submit.prevent="grant('grant')" @invalid.capture="chineseValidation" @input.capture="clearValidation"><label>场次编号<input v-model="sessionId" required inputmode="numeric" pattern="[1-9][0-9]*" placeholder="请输入需要授权的场次编号" /></label><ElButton type="primary" native-type="submit" :loading="busy">分配核销场次</ElButton></form>
      <p v-if="permissionError" class="inline-error" role="alert">{{ permissionError }}</p><p v-if="permissionLoading" role="status">正在加载授权记录…</p>
      <p v-else-if="!permissionError && !permissions.length">尚无场次授权记录。</p>
      <ul v-else-if="!permissionError" class="permission-list"><li v-for="permission in permissions" :key="permission.sessionId"><div><strong>{{ permission.activityTitle }} · {{ permission.sessionTitle }}</strong><p>场次 {{ permission.sessionId }} · {{ sessionStatusText[permission.sessionStatus] }} · {{ formatTime(permission.startsAt) }}（北京时间）</p><p>{{ permission.revokedAt ? '已撤销' : '已授权' }} · 授权时间 {{ permission.grantedAt ? formatTime(permission.grantedAt) : '' }}<span v-if="permission.revokedAt"> · 撤销时间 {{ formatTime(permission.revokedAt) }}</span></p></div><ElButton v-if="!permission.revokedAt" :disabled="busy" @click="grant('revoke', permission.sessionId)">撤销场次授权</ElButton></li></ul>
      <div class="pagination"><ElButton :disabled="permissionPage <= 1 || permissionLoading || busy" @click="permissionPage--; loadPermissions()">上一页</ElButton><span>第 {{ permissionPage }} 页 · 共 {{ permissionTotal }} 条授权</span><ElButton :disabled="permissionPage * 10 >= permissionTotal || permissionLoading || busy" @click="permissionPage++; loadPermissions()">下一页</ElButton></div>
    </section>
  </section>
</template>
<style scoped>
.permission-list { list-style: none; padding: 0; }
.permission-list li { display: flex; align-items: center; justify-content: space-between; gap: 1rem; padding: 1rem 0; border-bottom: 1px solid var(--border-color, #e9e5e1); }
@media (max-width: 640px) { .permission-list li { align-items: flex-start; flex-direction: column; } }
</style>
