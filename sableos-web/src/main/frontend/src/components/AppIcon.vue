<script setup lang="ts">
import { computed } from 'vue'

export type IconName =
  | 'overview'
  | 'sessions'
  | 'agents'
  | 'tools'
  | 'memory'
  | 'refresh'
  | 'menu'
  | 'close'
  | 'copy'
  | 'check'
  | 'alert'
  | 'chevron-down'
  | 'chevron-right'
  | 'arrow-right'
  | 'plus'
  | 'play'
  | 'pulse'
  | 'clock'
  | 'info'
  | 'search'
  | 'archive'
  | 'terminal'
  | 'send'
  | 'external'

const props = withDefaults(
  defineProps<{
    name: IconName
    size?: string | number
    label?: string
  }>(),
  {
    size: '1em',
    label: '',
  },
)

const paths: Record<IconName, string[]> = {
  overview: ['M4 4h6v6H4z', 'M14 4h6v6h-6z', 'M4 14h6v6H4z', 'M14 14h6v6h-6z'],
  sessions: ['M4 5h16v12H8l-4 4V5Z', 'M8 9h8', 'M8 13h5'],
  agents: [
    'M8 8h8a3 3 0 0 1 3 3v4a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3v-4a3 3 0 0 1 3-3Z',
    'M12 4v4',
    'M9 13h.01',
    'M15 13h.01',
    'M9 18v2',
    'M15 18v2',
  ],
  tools: ['M14 6a4 4 0 0 0-4 4c0 .6.1 1.1.3 1.6L4 18l2 2 6.4-6.3c.5.2 1 .3 1.6.3a4 4 0 0 0 4-4l-2.3 2.3-2-2L14 6Z'],
  memory: [
    'M4 7c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3Z',
    'M4 7v5c0 1.7 3.6 3 8 3s8-1.3 8-3V7',
    'M4 12v5c0 1.7 3.6 3 8 3s8-1.3 8-3v-5',
  ],
  refresh: ['M20 11a8 8 0 1 0-2.3 6.7', 'M20 4v7h-7'],
  menu: ['M4 7h16', 'M4 12h16', 'M4 17h16'],
  close: ['M6 6l12 12', 'M18 6 6 18'],
  copy: ['M9 9h10v10H9z', 'M5 15H4V4h11v1'],
  check: ['M5 12l4 4L19 6'],
  alert: ['M12 3l9 17H3L12 3Z', 'M12 9v5', 'M12 17h.01'],
  'chevron-down': ['M6 9l6 6 6-6'],
  'chevron-right': ['M9 6l6 6-6 6'],
  'arrow-right': ['M5 12h14', 'M13 6l6 6-6 6'],
  plus: ['M12 5v14', 'M5 12h14'],
  play: ['M8 5v14l11-7L8 5Z'],
  pulse: ['M3 12h4l2-5 4 10 2-5h6'],
  clock: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z', 'M12 7v5l3 2'],
  info: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z', 'M12 11v5', 'M12 8h.01'],
  search: ['M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14Z', 'M16 16l4 4'],
  archive: ['M4 7h16v13H4z', 'M3 4h18v3H3z', 'M9 11h6'],
  terminal: ['M4 5h16v14H4z', 'M8 9l2 2-2 2', 'M13 14h3'],
  send: ['M21 3 10 14', 'M21 3l-7 18-4-7-7-4 18-7Z'],
  external: ['M14 4h6v6', 'M20 4l-9 9', 'M19 13v6H5V5h6'],
}

const style = computed(() => ({
  '--od-icon-size': typeof props.size === 'number' ? `${props.size}px` : props.size,
}))
</script>

<template>
  <svg
    class="od-icon"
    viewBox="0 0 24 24"
    :style="style"
    :role="label ? 'img' : undefined"
    :aria-label="label || undefined"
    :aria-hidden="label ? undefined : 'true'"
  >
    <path v-for="path in paths[name]" :key="path" :d="path" />
  </svg>
</template>
