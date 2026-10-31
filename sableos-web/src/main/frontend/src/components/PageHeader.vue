<script setup lang="ts">
defineProps<{
  eyebrow: string
  title: string
  description?: string
}>()
</script>

<template>
  <header class="page-header">
    <div class="page-header__main">
      <div class="page-header__kicker">
        <span class="page-header__rail" aria-hidden="true" />
        <span class="eyebrow">{{ eyebrow }}</span>
      </div>
      <h1 class="page-header__title">{{ title }}</h1>
      <p v-if="description" class="page-header__description">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="page-header__actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--space-6);
  padding-bottom: var(--space-5);
  border-bottom: 1px solid var(--border);
}

.page-header__main {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: var(--space-2);
}

.page-header__kicker {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.page-header__rail {
  width: 18px;
  height: 1px;
  background: var(--accent);
}

.page-header__title {
  font-size: var(--fs-24);
  font-weight: 600;
  letter-spacing: -0.025em;
  line-height: var(--lh-32);
}

.page-header__description {
  max-width: 68ch;
  color: var(--text-muted);
  font-size: var(--fs-13);
  line-height: var(--lh-20);
}

.page-header__actions {
  display: flex;
  align-items: center;
  flex: 0 0 auto;
  flex-wrap: wrap;
  gap: var(--space-2);
}

@media (max-width: 767px) {
  .page-header {
    align-items: flex-start;
    flex-direction: column;
    gap: var(--space-4);
  }
}
</style>
