import { localeTag } from '../i18n'

/**
 * 日期时间格式化器。
 *
 * <p>按当前界面语言创建，而非固定 `zh-CN`：英文界面下 `Intl` 会输出 11/01/2026 这类
 * 本地习惯格式。用 getter 惰性重建，只在语言真正变化时换实例。
 */
let cachedTag = ''
let dateTimeFormatter = new Intl.DateTimeFormat('zh-CN')
let clockFormatter = new Intl.DateTimeFormat('zh-CN')

function formatters(): { dateTime: Intl.DateTimeFormat; clock: Intl.DateTimeFormat } {
  const tag = localeTag()
  if (tag !== cachedTag) {
    cachedTag = tag
    dateTimeFormatter = new Intl.DateTimeFormat(tag, {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    })
    clockFormatter = new Intl.DateTimeFormat(tag, {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    })
  }
  return { dateTime: dateTimeFormatter, clock: clockFormatter }
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return formatters().dateTime.format(date)
}

export function formatClock(value: Date | number | null | undefined): string {
  if (value === null || value === undefined) {
    return '—'
  }
  return formatters().clock.format(new Date(value))
}

export function formatLatency(value: number | null | undefined): string {
  return typeof value === 'number' ? `${value} ms` : '—'
}
