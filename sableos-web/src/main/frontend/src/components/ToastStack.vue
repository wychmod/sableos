<script setup lang="ts">
import AppIcon from './AppIcon.vue'
import { dismissToast, toasts } from '../composables/useUiFeedback'

function iconFor(tone: string): 'check' | 'alert' | 'info' {
  if (tone === 'success') return 'check'
  if (tone === 'error') return 'alert'
  return 'info'
}
</script>

<template>
  <div class="toast-stack" aria-live="polite" aria-atomic="false">
    <TransitionGroup name="toast">
      <div
        v-for="toast in toasts"
        :key="toast.id"
        class="toast"
        :class="`toast--${toast.tone}`"
        :role="toast.tone === 'error' ? 'alert' : 'status'"
      >
        <span class="toast__icon">
          <AppIcon :name="iconFor(toast.tone)" size="15" />
        </span>
        <span class="toast__message">{{ toast.message }}</span>
        <button
          type="button"
          class="toast__close"
          aria-label="关闭提示"
          @click="dismissToast(toast.id)"
        >
          <AppIcon name="close" size="13" />
        </button>
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.toast-stack {
  position: fixed;
  z-index: 90;
  right: var(--space-6);
  bottom: var(--space-6);
  display: flex;
  width: min(360px, calc(100vw - 32px));
  flex-direction: column;
  gap: var(--space-2);
  pointer-events: none;
}

.toast {
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
  padding: 11px 12px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  background: #111111;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.38);
  color: var(--text-secondary);
  font-size: var(--fs-12);
  line-height: var(--lh-18);
  pointer-events: auto;
}

.toast--success {
  border-color: rgba(34, 197, 94, 0.38);
}

.toast--error {
  border-color: rgba(239, 68, 68, 0.48);
}

.toast__icon {
  color: var(--text-muted);
}

.toast--success .toast__icon {
  color: var(--success);
}

.toast--error .toast__icon {
  color: var(--failure);
}

.toast--info .toast__icon {
  color: var(--accent);
}

.toast__message {
  min-width: 0;
  flex: 1;
  overflow-wrap: anywhere;
}

.toast__close {
  display: inline-flex;
  width: 22px;
  height: 22px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  padding: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
}

.toast__close:hover {
  background: var(--hover);
  color: var(--text);
}

.toast-enter-active,
.toast-leave-active {
  transition:
    opacity var(--duration-base) var(--ease-out),
    transform var(--duration-base) var(--ease-out);
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(6px);
}

@media (max-width: 767px) {
  .toast-stack {
    right: var(--space-4);
    bottom: var(--space-4);
  }
}
</style>
