import { ref, shallowRef } from 'vue'
import { ApiError, normalizeError } from '../api/client'

export type ToastTone = 'success' | 'error' | 'info'

export interface ToastItem {
  id: number
  message: string
  tone: ToastTone
}

export const toasts = ref<ToastItem[]>([])
export const diagnosticError = shallowRef<ApiError | null>(null)
export const diagnosticOpen = ref(false)

let toastSequence = 0

export function dismissToast(id: number): void {
  toasts.value = toasts.value.filter((toast) => toast.id !== id)
}

export function pushToast(message: string, tone: ToastTone = 'info'): number {
  const id = ++toastSequence
  toasts.value.push({ id, message, tone })
  window.setTimeout(() => dismissToast(id), 4200)
  return id
}

interface ReportOptions {
  toast?: boolean
  open?: boolean
}

export function reportError(error: unknown, options: ReportOptions = {}): ApiError {
  const apiError = normalizeError(error)
  diagnosticError.value = apiError
  if (options.open) {
    diagnosticOpen.value = true
  }
  if (options.toast !== false) {
    pushToast(apiError.message, 'error')
  }
  return apiError
}

export function openDiagnostics(error?: unknown): void {
  if (error !== undefined) {
    diagnosticError.value = normalizeError(error)
  }
  diagnosticOpen.value = true
}

export function closeDiagnostics(): void {
  diagnosticOpen.value = false
}
