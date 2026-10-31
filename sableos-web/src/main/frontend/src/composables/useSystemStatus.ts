import { computed, ref, shallowRef } from 'vue'
import { normalizeError, type ApiError } from '../api/client'
import { getHealth, getInfo } from '../api/endpoints'
import type { HealthView, SystemInfo } from '../types/api'

export const systemInfo = ref<SystemInfo | null>(null)
export const health = ref<HealthView | null>(null)
export const infoLoading = ref(false)
export const healthLoading = ref(false)
export const infoError = shallowRef<ApiError | null>(null)
export const healthError = shallowRef<ApiError | null>(null)
export const lastRefreshedAt = ref<Date | null>(null)

export const healthState = computed<'checking' | 'ok' | 'down' | 'unknown'>(() => {
  if (healthLoading.value) {
    return 'checking'
  }
  if (health.value?.status === 'ok') {
    return 'ok'
  }
  return healthError.value ? 'down' : 'unknown'
})

export interface SystemRefreshResult {
  infoError: ApiError | null
  healthError: ApiError | null
}

let inflight: Promise<SystemRefreshResult> | null = null

export async function refreshSystem(): Promise<SystemRefreshResult> {
  if (inflight) {
    return inflight
  }

  inflight = (async () => {
    infoLoading.value = true
    healthLoading.value = true
    infoError.value = null
    healthError.value = null

    const [infoResult, healthResult] = await Promise.allSettled([getInfo(), getHealth()])

    if (infoResult.status === 'fulfilled') {
      systemInfo.value = infoResult.value
    } else {
      infoError.value = normalizeError(infoResult.reason)
    }

    if (healthResult.status === 'fulfilled') {
      health.value = healthResult.value
    } else {
      healthError.value = normalizeError(healthResult.reason)
    }

    infoLoading.value = false
    healthLoading.value = false
    lastRefreshedAt.value = new Date()

    return {
      infoError: infoError.value,
      healthError: healthError.value,
    }
  })()

  try {
    return await inflight
  } finally {
    inflight = null
  }
}
