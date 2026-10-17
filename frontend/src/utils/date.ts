const formatter = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})
export function formatTime(value: string) {
  return formatter.format(new Date(value))
}
export function toBeijingInput(value: string) {
  const shifted = new Date(new Date(value).getTime() + 8 * 60 * 60 * 1000)
  return shifted.toISOString().slice(0, 16)
}
export function fromBeijingInput(value: string) {
  return `${value}:00+08:00`
}
