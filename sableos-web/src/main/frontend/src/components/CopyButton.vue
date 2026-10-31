<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import AppIcon from './AppIcon.vue'
import { copyToClipboard } from '../utils/clipboard'
import { pushToast } from '../composables/useUiFeedback'

const props = withDefaults(
  defineProps<{
    value: string
    label?: string
    showLabel?: boolean
  }>(),
  {
    label: '复制',
    showLabel: false,
  },
)

const copied = ref(false)
let resetTimer: number | null = null

async function copy(): Promise<void> {
  const success = await copyToClipboard(props.value)
  if (!success) {
    pushToast('复制失败，请手动选择文本', 'error')
    return
  }
  copied.value = true
  if (resetTimer !== null) {
    window.clearTimeout(resetTimer)
  }
  resetTimer = window.setTimeout(() => {
    copied.value = false
    resetTimer = null
  }, 1600)
}

onBeforeUnmount(() => {
  if (resetTimer !== null) {
    window.clearTimeout(resetTimer)
  }
})
</script>

<template>
  <button
    type="button"
    class="copy-button"
    :class="{ 'btn': showLabel, 'btn--ghost': showLabel, 'btn--sm': showLabel }"
    :aria-label="copied ? '已复制' : label"
    :title="copied ? '已复制' : label"
    @click="copy"
  >
    <AppIcon :name="copied ? 'check' : 'copy'" size="14" />
    <span v-if="showLabel">{{ copied ? '已复制' : label }}</span>
    <span v-else class="sr-only">{{ copied ? '已复制' : label }}</span>
  </button>
</template>

<style scoped>
.copy-button:not(.btn) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex: 0 0 auto;
  padding: 0;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  transition:
    background-color var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out);
}

.copy-button:not(.btn):hover {
  background: var(--hover);
  color: var(--text);
}

.copy-button.btn {
  color: var(--text-secondary);
}
</style>
