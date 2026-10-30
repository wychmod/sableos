<script setup lang="ts">
import type { ApiError } from '../api/client'
import AppIcon from './AppIcon.vue'

withDefaults(
  defineProps<{
    title?: string
    description?: string
    error?: ApiError | null
    retryLabel?: string
  }>(),
  {
    title: '加载失败',
    description: '',
    error: null,
    retryLabel: '重试',
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
    <h2 class="error-state__title">{{ title }}</h2>
    <p class="error-state__description">{{ error?.message || description || '请求未能完成。' }}</p>
    <p class="error-state__trace">traceId: {{ error?.traceId || '—' }}</p>
    <div class="button-row">
      <button v-if="retryLabel" type="button" class="btn btn--secondary" @click="$emit('retry')">
        <AppIcon name="refresh" size="14" />
        {{ retryLabel }}
      </button>
      <button type="button" class="btn btn--ghost" @click="$emit('inspect')">查看诊断</button>
    </div>
  </div>
</template>
