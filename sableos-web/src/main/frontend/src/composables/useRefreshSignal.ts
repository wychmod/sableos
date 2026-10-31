import { ref } from 'vue'

export const refreshTick = ref(0)

export function signalRefresh(): void {
  refreshTick.value += 1
}
