import { ref, shallowRef } from 'vue'
import { normalizeError, type ApiError } from '../api/client'
import { listAgents } from '../api/endpoints'
import type { AgentView } from '../types/api'

export const profiles = ref<AgentView[]>([])
export const profilesLoading = ref(false)
export const profilesError = shallowRef<ApiError | null>(null)
export const profilesLoaded = ref(false)

let inflight: Promise<void> | null = null

export async function loadProfiles(force = false): Promise<void> {
  if (inflight) {
    return inflight
  }
  if (profilesLoaded.value && !force) {
    return
  }

  inflight = (async () => {
    profilesLoading.value = true
    profilesError.value = null
    try {
      profiles.value = await listAgents()
      profilesLoaded.value = true
    } catch (error) {
      profilesError.value = normalizeError(error)
    } finally {
      profilesLoading.value = false
      inflight = null
    }
  })()

  return inflight
}
