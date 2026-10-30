<script setup lang="ts">
import AppIcon, { type IconName } from './AppIcon.vue'

withDefaults(
  defineProps<{
    title: string
    description?: string
    actionLabel?: string
    icon?: IconName
  }>(),
  {
    description: '',
    actionLabel: '',
    icon: 'terminal',
  },
)

defineEmits<{
  action: []
}>()
</script>

<template>
  <div class="empty-state">
    <span class="empty-state__mark">
      <AppIcon :name="icon" size="18" />
    </span>
    <h2 class="empty-state__title">{{ title }}</h2>
    <p v-if="description" class="empty-state__description">{{ description }}</p>
    <button v-if="actionLabel" type="button" class="btn btn--secondary" @click="$emit('action')">
      {{ actionLabel }}
    </button>
  </div>
</template>
