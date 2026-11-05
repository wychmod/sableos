<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import AppIcon from './AppIcon.vue'
import { copyToClipboard } from '../utils/clipboard'
import { pushToast } from '../composables/useUiFeedback'
import { t } from '../i18n'

const props = withDefaults(
  defineProps<{
    value: string
    label?: string
    showLabel?: boolean
  }>(),
  {
    // 置空而不是写死 '复制'：默认值只在模块初始化算一次，切语言不会跟着变。
    label: '',
    showLabel: false,
  },
)

const copied = ref(false)
let resetTimer: number | null = null

/** 已复制优先，其次是调用处传入的标签，最后才是通用「复制」。 */
function displayLabel(): string {
  return copied.value ? t('已复制') : props.label || t('复制')
}

async function copy(): Promise<void> {
  const success = await copyToClipboard(props.value)
  if (!success) {
    pushToast(t('复制失败，请手动选择文本'), 'error')
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
    :aria-label="displayLabel()"
    :title="displayLabel()"
    @click="copy"
  >
    <AppIcon :name="copied ? 'check' : 'copy'" size="14" />
    <span v-if="showLabel">{{ displayLabel() }}</span>
    <span v-else class="sr-only">{{ displayLabel() }}</span>
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
