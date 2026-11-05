<script setup lang="ts">
import type { ApiError } from '../api/client'
import AppIcon from './AppIcon.vue'
import { t } from '../i18n'

withDefaults(
  defineProps<{
    title?: string
    description?: string
    error?: ApiError | null
    retryLabel?: string
  }>(),
  {
    // 默认文案置空，模板里回落 t()；否则模块初始化时算定，切语言不生效。
    title: '',
    description: '',
    error: null,
    retryLabel: '',
  },
)

defineEmits<{
  retry: []
  inspect: []
}>()
</script>

<template>
  <div class="error-state" role="alert">
    <span class="empty-state__mark">
      <AppIcon name="alert" size="18" />
    </span>
    <h2 class="error-state__title">{{ title || t('加载失败') }}</h2>
    <p class="error-state__description">{{ error?.message || description || t('请求未能完成。') }}</p>
    <p class="error-state__trace">traceId: {{ error?.traceId || '—' }}</p>
    <div class="button-row">
      <button type="button" class="btn btn--secondary" @click="$emit('retry')">
        <AppIcon name="refresh" size="14" />
        {{ retryLabel || t('重试') }}
      </button>
      <button type="button" class="btn btn--ghost" @click="$emit('inspect')">
        {{ t('查看诊断') }}
      </button>
    </div>
  </div>
</template>
