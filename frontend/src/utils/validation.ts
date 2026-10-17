export function chineseValidation(event: Event) {
  const field = event.target
  if (
    field instanceof HTMLInputElement ||
    field instanceof HTMLTextAreaElement ||
    field instanceof HTMLSelectElement
  ) {
    field.setCustomValidity(
      field.validity.valueMissing
        ? '请填写此项'
        : field.validity.typeMismatch || field.validity.patternMismatch
          ? '请输入完整有效的 HTTP 或 HTTPS 图片地址'
          : '请检查输入内容是否符合要求',
    )
  }
}

export function clearValidation(event: Event) {
  const field = event.target
  if (
    field instanceof HTMLInputElement ||
    field instanceof HTMLTextAreaElement ||
    field instanceof HTMLSelectElement
  )
    field.setCustomValidity('')
}
