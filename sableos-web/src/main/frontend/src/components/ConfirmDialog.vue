<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import AppIcon from './AppIcon.vue'
import { t } from '../i18n'

const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    description: string
    confirmLabel?: string
    cancelLabel?: string
    pending?: boolean
    danger?: boolean
  }>(),
  {
    // 默认文案置空、在使用处回落 t()：withDefaults 里的字面量只在模块初始化算一次，
    // 写死中文会让切语言后按钮不跟着变。
    confirmLabel: '',
    cancelLabel: '',
    pending: false,
    danger: false,
  },
)

const emit = defineEmits<{
  confirm: []
  cancel: []
}>()

const dialog = ref<HTMLDialogElement | null>(null)
const cancelButton = ref<HTMLButtonElement | null>(null)

watch(
  () => props.open,
  async (open) => {
    await nextTick()
    if (!dialog.value) return
    if (open && !dialog.value.open) {
      dialog.value.showModal()
      await nextTick()
      cancelButton.value?.focus()
    } else if (!open && dialog.value.open) {
      dialog.value.close()
    }
  },
  { immediate: true, flush: 'post' },
)

function requestCancel(): void {
  if (!props.pending) {
    emit('cancel')
  }
}

function onBackdrop(event: MouseEvent): void {
  if (event.target === dialog.value) {
    requestCancel()
  }
}
</script>

<template>
  <dialog
    ref="dialog"
    class="dialog confirm-dialog"
    aria-labelledby="confirm-dialog-title"
    @cancel.prevent="requestCancel"
    @click="onBackdrop"
  >
    <div class="dialog__head">
      <div>
        <div class="eyebrow">Confirmation required</div>
        <h2 id="confirm-dialog-title" class="dialog__title">{{ title }}</h2>
      </div>
      <button
        type="button"
        class="icon-button"
        :aria-label="t('关闭对话框')"
        :disabled="pending"
        @click="requestCancel"
      >
        <AppIcon name="close" size="16" />
      </button>
    </div>
    <div class="dialog__body">
      <p class="confirm-dialog__description">{{ description }}</p>
      <slot />
    </div>
    <div class="dialog__footer">
      <button
        ref="cancelButton"
        type="button"
        class="btn btn--secondary"
        :disabled="pending"
        @click="requestCancel"
      >
        {{ cancelLabel || t('取消') }}
      </button>
      <button
        type="button"
        class="btn"
        :class="danger ? 'btn--danger' : 'btn--primary'"
        :disabled="pending"
        @click="emit('confirm')"
      >
        {{ pending ? t('处理中…') : confirmLabel || t('确认') }}
      </button>
    </div>
  </dialog>
</template>

<style scoped>
.confirm-dialog {
  width: min(440px, calc(100vw - 32px));
}

.confirm-dialog__description {
  color: var(--text-secondary);
  font-size: var(--fs-13);
  line-height: var(--lh-20);
}
</style>
