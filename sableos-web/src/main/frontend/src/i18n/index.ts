import { ref, watchEffect } from 'vue'
import { en } from './messages/en'

/** 管理台支持的界面语言。 */
export type Locale = 'zh' | 'en'

const STORAGE_KEY = 'sableos.admin.locale'

function readSavedLocale(): Locale | null {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    return raw === 'zh' || raw === 'en' ? raw : null
  } catch {
    // localStorage 被策略或隐私模式禁用时按默认中文处理，不影响其余功能
    return null
  }
}

/**
 * 当前界面语言。
 *
 * <p>默认中文：管理台的使用者与文案评审都以中文稿为准，跟随浏览器语言会让首次访问的界面与文档对不上。
 */
export const locale = ref<Locale>(readSavedLocale() ?? 'zh')

export function setLocale(next: Locale): void {
  locale.value = next
}

export function toggleLocale(): void {
  setLocale(locale.value === 'zh' ? 'en' : 'zh')
}

/**
 * 文案字典。
 *
 * <p>中文以**源文本身**作键，所以 zh 不需要额外维护一份映射——少一份迟早会写漏的表；
 * 英文查 en 表，缺条目时自动回落中文，保证新加文案不会显示成空白或键名。
 */
const dictionaries: Record<Locale, Record<string, string>> = { zh: {}, en }

function interpolate(template: string, params: Record<string, string | number>): string {
  return template.replace(/\{(\w+)\}/g, (whole, name: string) =>
    name in params ? String(params[name]) : whole,
  )
}

/**
 * 取当前语言的文案。
 *
 * <p>读的是 {@link locale}，因此在模板 / computed 里调用会建立依赖，切换语言时自动重渲染。
 * 参数用 `{name}` 占位，例如 `t('已载入 {count} 条', { count: 3 })`。
 *
 * @param key 中文源文（同时也是 zh 的键）
 * @param params 占位符取值
 * @return 目标语言文案；缺英文条目时返回中文原文
 */
export function t(key: string, params?: Record<string, string | number>): string {
  const template = dictionaries[locale.value][key] ?? key
  return params ? interpolate(template, params) : template
}

/** 取当前语言对应的 BCP 47 标签，供 `Intl.DateTimeFormat` 等原生 API 使用。 */
export function localeTag(): string {
  return locale.value === 'zh' ? 'zh-CN' : 'en-US'
}

watchEffect(() => {
  document.documentElement.lang = localeTag()
  try {
    window.localStorage.setItem(STORAGE_KEY, locale.value)
  } catch {
    // 写不进存储时只在本次会话生效，切换本身仍然可用
  }
})
