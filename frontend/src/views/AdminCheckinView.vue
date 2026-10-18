<script setup lang="ts">
import { ref } from 'vue'
import CheckinScanner from '../components/CheckinScanner.vue'
import type { ElectronicTicket } from '../api/electronicTickets'
import { formatTime } from '../utils/date'
const result = ref<ElectronicTicket>()
</script>
<template>
  <div class="orders-page">
    <span class="eyebrow"><span></span>运营工作台</span>
    <h1>电子票核销</h1>
    <RouterLink class="back-link" to="/admin/activities"
      >← 返回活动与场次管理</RouterLink
    >
    <p>
      仅工作人员可核销，默认从演出开始前60分钟开放至场次结束。每张票只能核销一次，成功后请核对场次。
    </p>
    <CheckinScanner @checked="result = $event" @reset="result = undefined" />
    <p v-if="result" class="success-notice" role="status">核销成功，可以入场。</p>
    <section v-if="result" class="order-card">
      <h2>核销成功 · {{ result.activityTitle }}</h2>
      <p>
        {{ result.sessionTitle }} · {{ result.skuName }} · 第
        {{ result.sequenceNo }} 张
      </p>
      <dl>
        <div>
          <dt>场馆</dt>
          <dd>{{ result.city }} · {{ result.venue }}</dd>
        </div>
        <div>
          <dt>持票人</dt>
          <dd>{{ result.ownerName }}</dd>
        </div>
        <div>
          <dt>票号</dt>
          <dd class="ticket-number">{{ result.ticketNo }}</dd>
        </div>
        <div>
          <dt>核销时间</dt>
          <dd>
            {{ result.usedAt ? formatTime(result.usedAt) : '' }}（北京时间）
          </dd>
        </div>
        <div>
          <dt>工作人员编号</dt>
          <dd>{{ result.checkedBy }}</dd>
        </div>
      </dl>
    </section>
  </div>
</template>
