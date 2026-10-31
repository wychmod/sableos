<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import AppIcon from './AppIcon.vue'
import CopyButton from './CopyButton.vue'
import { closeDiagnostics, diagnosticError, diagnosticOpen } from '../composables/useUiFeedback'

const dialog = ref<HTMLDialogElement | null>(null)

watch(diagnosticOpen, async (open) => {
  await nextTick()
  if (!dialog.value) return
  if (open && !dialog.value.open) {
    dialog.value.showModal()
  } else if (!open && dialog.value.open) {
    dialog.value.close()
  }
})

function close(): void {
  closeDiagnostics()
}
</script>

<template>
  <dialog
    ref="dialog"
    class="diagnostic-dialog"
    aria-labelledby="diagnostic-title"
    @cancel.prevent="close"
    @close="close"
  >
    <div class="diagnostic-dialog__head">
      <div>
        <div class="eyebrow">Request diagnostics</div>
        <h2 id="diagnostic-title" class="diagnostic-dialog__title">诊断信息</h2>
      </div>
      <button type="button" class="icon-button" aria-label="关闭诊断信息" @click="close">
        <AppIcon name="close" size="16" />
      </button>
    </div>

    <div class="diagnostic-dialog__body">
      <div class="diagnostic-grid">
        <span class="diagnostic-grid__label">HTTP 状态</span>
        <span class="diagnostic-grid__value mono">{{ diagnosticError?.httpStatus ?? '—' }}</span>
        <span class="diagnostic-grid__label">业务 code</span>
        <span class="diagnostic-grid__value mono">{{ diagnosticError?.code ?? '—' }}</span>
      </div>

      <div class="diagnostic-block">
        <span class="diagnostic-block__label">message</span>
        <p class="diagnostic-block__message">{{ diagnosticError?.message || '暂无错误信息' }}</p>
      </div>

      <div class="diagnostic-block">
        <span class="diagnostic-block__label">traceId</span>
        <div class="diagnostic-trace">
          <code>{{ diagnosticError?.traceId || '—' }}</code>
          <CopyButton
            v-if="diagnosticError?.traceId"
            :value="diagnosticError.traceId"
            label="复制 traceId"
          />
        </div>
      </div>

      <p class="diagnostic-note">
        错误文案来自后端响应，未做二次翻译。前端网络错误或超时不会有 traceId。
      </p>
    </div>
  </dialog>
</template>

<style scoped>
.diagnostic-dialog {
  width: min(420px, 100vw);
  max-width: 420px;
  height: 100vh;
  max-height: 100vh;
  margin: 0 0 0 auto;
  padding: 0;
  overflow-y: auto;
  border: 0;
  border-left: 1px solid var(--border-strong);
  border-radius: 0;
  background: #0b0b0b;
  color: var(--text);
  box-shadow: var(--shadow-overlay);
}

.diagnostic-dialog::backdrop {
  background: rgba(0, 0, 0, 0.62);
}

.diagnostic-dialog__head {
  position: sticky;
  z-index: 1;
  top: 0;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
  padding: var(--space-5);
  border-bottom: 1px solid var(--border);
  background: #0b0b0b;
}

.diagnostic-dialog__title {
  margin-top: 2px;
  font-size: var(--fs-20);
  font-weight: 600;
  letter-spacing: -0.02em;
  line-height: var(--lh-28);
}

.diagnostic-dialog__body {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
  padding: var(--space-5);
}

.diagnostic-grid {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr);
  gap: var(--space-3);
  padding: var(--space-4);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
}

.diagnostic-grid__label,
.diagnostic-block__label {
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: var(--fs-11);
  letter-spacing: 0.04em;
}

.diagnostic-grid__value {
  color: var(--text);
  font-size: var(--fs-13);
  text-align: right;
}

.diagnostic-block {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.diagnostic-block__message {
  padding: var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #080808;
  color: #fca5a5;
  font-size: var(--fs-13);
  line-height: var(--lh-20);
  overflow-wrap: anywhere;
}

.diagnostic-trace {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-height: 36px;
  padding: 6px 8px 6px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #080808;
}

.diagnostic-trace code {
  min-width: 0;
  flex: 1;
  overflow-wrap: anywhere;
  color: var(--text-secondary);
  font-family: var(--font-mono);
  font-size: var(--fs-11);
  line-height: var(--lh-18);
}

.diagnostic-note {
  color: var(--text-muted);
  font-size: var(--fs-12);
  line-height: var(--lh-18);
}

@media (max-width: 480px) {
  .diagnostic-dialog {
    width: 100vw;
    max-width: none;
  }
}
</style>
