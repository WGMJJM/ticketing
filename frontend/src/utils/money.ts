const integerFormatter = new Intl.NumberFormat('zh-CN', {
  maximumFractionDigits: 0,
})
export function formatCents(value: string) {
  const cents = BigInt(value)
  return `¥${integerFormatter.format(cents / 100n)}.${(cents % 100n).toString().padStart(2, '0')}`
}
export function centsToYuan(value: string) {
  const cents = BigInt(value)
  return `${cents / 100n}.${(cents % 100n).toString().padStart(2, '0')}`
}
export function yuanToCents(value: string) {
  const match = /^(0|[1-9]\d*)(?:\.(\d{1,2}))?$/.exec(value.trim())
  if (!match) throw new Error('票价应为非负金额，最多保留两位小数')
  const cents =
    BigInt(match[1]!) * 100n + BigInt((match[2] ?? '').padEnd(2, '0'))
  if (cents > 9223372036854775807n) throw new Error('票价超出允许范围')
  return cents.toString()
}
