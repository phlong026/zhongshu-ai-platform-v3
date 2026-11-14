export type Int64Value = string | number

export const integerText = (value: Int64Value) => {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) return ''
  const text = String(value)
  return /^-?\d+$/.test(text) ? text : ''
}

export const formatInteger = (value: Int64Value) => {
  const text = integerText(value)
  if (!text) return '数据精度异常'
  const negative = text.startsWith('-')
  const digits = negative ? text.slice(1) : text
  const grouped = digits.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return `${negative ? '-' : ''}${grouped}`
}

export const formatMicros = (value: Int64Value) => {
  const text = integerText(value)
  if (!text) return '数据精度异常'
  const micros = BigInt(text)
  const negative = micros < 0n
  const absolute = negative ? -micros : micros
  const whole = absolute / 1_000_000n
  const fraction = (absolute % 1_000_000n).toString().padStart(6, '0').replace(/0+$/, '')
  return `${negative ? '-' : ''}${whole}${fraction ? `.${fraction}` : ''}`
}
