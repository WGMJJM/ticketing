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
        : field instanceof HTMLInputElement && field.type === 'url'
          ? '请输入完整有效的 HTTP 或 HTTPS 图片地址'
          : field.validity.rangeUnderflow || field.validity.rangeOverflow
            ? '输入数值超出允许范围'
            : field.validity.typeMismatch ||
                field.validity.patternMismatch ||
                field.validity.stepMismatch
              ? '请按提示填写有效格式和数值'
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
