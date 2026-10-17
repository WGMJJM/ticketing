<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import type { IScannerControls } from '@zxing/browser'
import {
  electronicTicketApi,
  type ElectronicTicket,
} from '../api/electronicTickets'
import { formatTime } from '../utils/date'
const credential = ref('')
const busy = ref(false)
const scanning = ref(false)
const readingImage = ref(false)
const error = ref('')
const cameraError = ref('')
const notice = ref('')
const result = ref<ElectronicTicket>()
const video = ref<HTMLVideoElement>()
let controls: IScannerControls | undefined
let cameraGeneration = 0
let operationGeneration = 0
function stopCamera() {
  cameraGeneration++
  controls?.stop()
  controls = undefined
  if (typeof MediaStream !== 'undefined' && video.value?.srcObject instanceof MediaStream)
    video.value.srcObject.getTracks().forEach((track) => track.stop())
  if (video.value) video.value.srcObject = null
  scanning.value = false
}
async function startCamera() {
  stopCamera()
  const active = cameraGeneration
  cameraError.value = ''
  notice.value = ''
  credential.value = ''
  error.value = ''
  result.value = undefined
  if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
    cameraError.value =
      '当前浏览器不支持摄像头，或未处于安全上下文。请使用二维码图片或手动凭证核销。'
    return
  }
  scanning.value = true
  try {
    const { BrowserQRCodeReader } = await import('@zxing/browser')
    if (active !== cameraGeneration) return
    const reader = new BrowserQRCodeReader()
    const opened = await reader.decodeFromConstraints(
      { video: { facingMode: 'environment' }, audio: false },
      video.value,
      (decoded, _failure, current) => {
        if (!decoded || active !== cameraGeneration) return
        current.stop()
        stopCamera()
        credential.value = decoded.getText()
        notice.value = '已识别二维码，请点击确认核销入场。'
      },
    )
    if (active !== cameraGeneration) opened.stop()
    else controls = opened
  } catch {
    if (active === cameraGeneration) {
      stopCamera()
      cameraError.value =
        '无法启用摄像头，请检查设备和浏览器授权。仍可使用二维码图片或手动凭证核销。'
    }
  }
}
async function readImage(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  stopCamera()
  const active = ++operationGeneration
  readingImage.value = true
  error.value = ''
  notice.value = ''
  credential.value = ''
  result.value = undefined
  const url = URL.createObjectURL(file)
  try {
    const { BrowserQRCodeReader } = await import('@zxing/browser')
    const decoded = await new BrowserQRCodeReader().decodeFromImageUrl(url)
    if (active === operationGeneration) {
      credential.value = decoded.getText()
      notice.value = '已识别二维码，请点击确认核销入场。'
    }
  } catch {
    if (active === operationGeneration)
      error.value =
        '未能识别二维码，请选择清晰的电子票二维码图片，或粘贴完整凭证。'
  } finally {
    URL.revokeObjectURL(url)
    if (active === operationGeneration) readingImage.value = false
  }
}
async function checkIn() {
  if (busy.value || readingImage.value) return
  stopCamera()
  const value = credential.value.trim()
  credential.value = ''
  error.value = ''
  notice.value = ''
  result.value = undefined
  if (!value) {
    error.value = '请先扫描或输入完整电子票凭证'
    return
  }
  busy.value = true
  const active = ++operationGeneration
  try {
    const checked = await electronicTicketApi.checkIn(value)
    if (active === operationGeneration) {
      result.value = checked
      notice.value = '核销成功，可以入场。'
    }
  } catch (failure) {
    if (active === operationGeneration)
      error.value =
        failure instanceof Error ? failure.message : '核销失败，请重试'
  } finally {
    if (active === operationGeneration) busy.value = false
  }
}
onBeforeUnmount(() => {
  stopCamera()
  operationGeneration++
  credential.value = ''
  result.value = undefined
})
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
    <section class="order-card">
      <h2>扫描电子票二维码</h2>
      <p>
        启动摄像头需要安全上下文和浏览器授权。识别后自动停止扫描，确认后才提交核销。
      </p>
      <video
        ref="video"
        v-show="scanning"
        class="checkin-video"
        muted
        playsinline
        aria-label="电子票扫码摄像头预览"
      ></video>
      <div class="auth-links">
        <ElButton
          :disabled="busy || readingImage || scanning"
          @click="startCamera"
          >启动摄像头扫码</ElButton
        ><ElButton v-if="scanning" @click="stopCamera">停止扫码</ElButton
        ><label
          >选择二维码图片<input
            type="file"
            accept="image/*"
            :disabled="busy || readingImage || scanning"
            @change="readImage"
        /></label>
      </div>
      <p v-if="cameraError" class="inline-error" role="alert">
        {{ cameraError }}
      </p>
      <p v-if="readingImage">正在识别二维码…</p>
      <form @submit.prevent="checkIn">
        <label for="checkin-credential">完整电子票凭证</label
        ><textarea
          id="checkin-credential"
          v-model="credential"
          rows="3"
          maxlength="256"
          autocomplete="off"
          spellcheck="false"
          :disabled="busy || scanning || readingImage"
          placeholder="可粘贴完整凭证，凭证不会写入地址栏或浏览器存储"
        ></textarea
        ><ElButton
          native-type="submit"
          type="primary"
          :loading="busy"
          :disabled="scanning || readingImage || !credential.trim()"
          >确认核销入场</ElButton
        >
      </form>
    </section>
    <p v-if="notice" class="success-notice" role="status">{{ notice }}</p>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
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
