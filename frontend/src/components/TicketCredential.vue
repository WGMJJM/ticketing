<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import QRCode from 'qrcode'
import { electronicTicketApi } from '../api/electronicTickets'
const props = defineProps<{ ticketId: string }>()
const qr = ref('')
const qrLoading = ref(false)
const error = ref('')
let qrGeneration = 0
function clearQr() {
  qrGeneration++
  qr.value = ''
  qrLoading.value = false
}
async function showQr() {
  if (!props.ticketId) return
  const active = ++qrGeneration
  qrLoading.value = true
  error.value = ''
  try {
    // 完整凭证只作为局部变量传入本地编码器，不放入Store、URL或浏览器存储。
    const response = await electronicTicketApi.credential(props.ticketId)
    if (active !== qrGeneration) return
    const image = await QRCode.toDataURL(response.credential, {
      width: 300,
      margin: 4,
      errorCorrectionLevel: 'M',
    })
    if (active === qrGeneration) qr.value = image
  } catch (failure) {
    if (active === qrGeneration)
      error.value =
        failure instanceof Error ? failure.message : '入场二维码加载失败'
  } finally {
    if (active === qrGeneration) qrLoading.value = false
  }
}
watch(() => props.ticketId, clearQr)
onBeforeUnmount(clearQr)
</script>
<template>
  <section class="ticket-qr">
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <ElButton v-if="!qr" type="primary" :loading="qrLoading" @click="showQr">展示入场二维码</ElButton>
    <template v-else>
      <img :src="qr" width="300" height="300" alt="该张电子票的独立入场二维码" />
      <p>请在工作人员处出示此二维码，最终入场资格由服务端核验。</p>
      <ElButton @click="clearQr">隐藏二维码</ElButton>
    </template>
  </section>
</template>
