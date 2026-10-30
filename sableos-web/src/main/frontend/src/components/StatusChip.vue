<script setup lang="ts">
import { computed } from 'vue'

type StatusTone = 'success' | 'failure' | 'warning' | 'neutral' | 'accent'

const props = withDefaults(
  defineProps<{
    label: string
    tone?: StatusTone
  }>(),
  {
    tone: 'neutral',
  },
)

const toneClass = computed(() => `status-chip--${props.tone}`)
</script>

<template>
  <span class="status-chip" :class="toneClass" role="status">
    <span class="status-dot" aria-hidden="true" />
    <span>{{ label }}</span>
  </span>
</template>

<style scoped>
.status-chip {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  gap: 6px;
  padding: 2px 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #090909;
  color: var(--text-secondary);
  font-size: var(--fs-11);
  font-weight: 550;
  line-height: var(--lh-18);
  white-space: nowrap;
}

.status-chip--success {
  border-color: rgba(34, 197, 94, 0.32);
  background: var(--success-soft);
  color: #86efac;
}

.status-chip--failure {
  border-color: rgba(239, 68, 68, 0.34);
  background: var(--failure-soft);
  color: #fca5a5;
}

.status-chip--warning {
  border-color: rgba(245, 158, 11, 0.34);
  background: var(--warning-soft);
  color: #fcd34d;
}

.status-chip--accent {
  border-color: var(--accent-border);
  background: var(--accent-soft);
  color: var(--accent);
}

.status-chip--neutral {
  color: var(--text-secondary);
}
</style>
