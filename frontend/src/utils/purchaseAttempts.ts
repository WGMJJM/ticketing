// 只保存可重放的购票参数和幂等键，不保存身份凭据、CSRF或票券；服务端始终校验真实归属。
export interface PurchaseAttempt {
  quantity: number
  key: string
  requestId?: string
}
function slot(userId: string, skuId: string) { return `ticketing.purchase.${userId}.${skuId}` }
export function readAttempt(userId: string, skuId: string): PurchaseAttempt | undefined {
  try {
    const value = JSON.parse(sessionStorage.getItem(slot(userId, skuId)) ?? 'null')
    if (value && Number.isInteger(value.quantity) && value.quantity > 0 &&
        typeof value.key === 'string' && /^[!-~]{1,128}$/.test(value.key) &&
        (value.requestId === undefined || /^[1-9][0-9]{0,18}$/.test(value.requestId))) return value
  } catch { /* 禁止存储的浏览器仍可在当前页面使用原键重试。 */ }
}
export function saveAttempt(userId: string, skuId: string, attempt: PurchaseAttempt) {
  // 存储失败时须在提交前明确停止，避免刷新后换键重复购票。
  sessionStorage.setItem(slot(userId, skuId), JSON.stringify(attempt))
}
export function clearAttempt(userId: string, skuId: string, key: string) {
  if (readAttempt(userId, skuId)?.key === key) sessionStorage.removeItem(slot(userId, skuId))
}
