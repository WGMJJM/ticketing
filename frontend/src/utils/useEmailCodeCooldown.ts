import { onUnmounted, ref } from 'vue'
import { isAxiosError } from 'axios'

export function useEmailCodeCooldown() {
  const cooldown = ref(0)
  let timer: ReturnType<typeof setInterval> | undefined
  function start(seconds = 60) {
    clearInterval(timer)
    const deadline = Date.now() + seconds * 1000
    cooldown.value = seconds
    timer = setInterval(() => {
      cooldown.value = Math.max(0, Math.ceil((deadline - Date.now()) / 1000))
      if (!cooldown.value) clearInterval(timer)
    }, 1000)
  }
  function useRetryAfter(failure: unknown) {
    if (!isAxiosError(failure) || failure.response?.status !== 429) return
    const retry = Number(failure.response.headers['retry-after'])
    if (Number.isInteger(retry) && retry > 0 && retry <= 86400) start(retry)
  }
  onUnmounted(() => clearInterval(timer))
  return { cooldown, start, useRetryAfter }
}
