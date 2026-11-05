<script setup lang="ts">
import type { FileNode } from '../types/api'

defineProps<{ node: FileNode; selected: string }>()
const emit = defineEmits<{ open: [node: FileNode] }>()

function onFileClick(node: FileNode): void {
  if (!node.directory) emit('open', node)
}
</script>

<template>
  <details v-if="node.directory" class="file-branch" :open="node.path.split('/').length <= 3">
    <summary class="file-node file-node--dir">{{ node.name }}</summary>
    <div class="file-children">
      <FileTreeNode
        v-for="child in node.children"
        :key="child.path"
        :node="child"
        :selected="selected"
        @open="emit('open', $event)"
      />
    </div>
  </details>
  <button
    v-else
    type="button"
    class="file-node"
    :class="{ 'file-node--active': selected === node.path }"
    @click="onFileClick(node)"
  >
    {{ node.name }}
  </button>
</template>

<style scoped>
.file-node {
  display: block;
  width: 100%;
  padding: 4px 8px;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-secondary);
  font-family: var(--font-mono);
  font-size: var(--fs-12);
  text-align: left;
  cursor: pointer;
}

.file-node--dir {
  color: var(--text-faint);
  cursor: default;
}

.file-node--active,
button.file-node:hover {
  background: var(--panel);
  color: var(--text);
}

.file-children {
  padding-left: 12px;
}
</style>
