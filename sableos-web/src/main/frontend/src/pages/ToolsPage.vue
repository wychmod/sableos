<script setup lang="ts">
import { computed, onMounted, ref, shallowRef, watch } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import CopyButton from '../components/CopyButton.vue'
import EmptyState from '../components/EmptyState.vue'
import ErrorState from '../components/ErrorState.vue'
import PageHeader from '../components/PageHeader.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'
import { normalizeError, type ApiError } from '../api/client'
import { listTools } from '../api/endpoints'
import { refreshTick } from '../composables/useRefreshSignal'
import { openDiagnostics, pushToast, reportError } from '../composables/useUiFeedback'
import type { ToolView } from '../types/api'

interface SchemaView {
  ok: boolean
  text: string
  keys: number
}

const tools = ref<ToolView[]>([])
const loading = ref(false)
const refreshing = ref(false)
const error = shallowRef<ApiError | null>(null)
const query = ref('')
const expanded = ref<string[]>([])
const searchField = ref<HTMLInputElement | null>(null)

const filtered = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  if (!keyword) return tools.value
  return tools.value.filter((tool) => {
    const haystack = `${tool.name} ${tool.description ?? ''}`.toLowerCase()
    return haystack.includes(keyword)
  })
})

const hasQuery = computed(() => query.value.trim().length > 0)
const allExpanded = computed(
  () => filtered.value.length > 0 && filtered.value.every((tool) => expanded.value.includes(tool.name)),
)

async function load(force = false): Promise<void> {
  if (loading.value) return
  if (tools.value.length > 0 && !force && !error.value) {
    return
  }
  loading.value = true
  error.value = null
  try {
    tools.value = await listTools()
  } catch (caught) {
    error.value = normalizeError(caught)
    reportError(error.value, { toast: force })
  } finally {
    loading.value = false
  }
}

async function refresh(): Promise<void> {
  if (refreshing.value) return
  refreshing.value = true
  await load(true)
  if (!error.value) {
    pushToast('工具清单已刷新', 'success')
  }
  refreshing.value = false
}

function isExpanded(name: string): boolean {
  return expanded.value.includes(name)
}

function toggle(name: string): void {
  expanded.value = isExpanded(name)
    ? expanded.value.filter((value) => value !== name)
    : [...expanded.value, name]
}

function toggleAll(): void {
  expanded.value = allExpanded.value ? [] : filtered.value.map((tool) => tool.name)
}

function clearQuery(): void {
  query.value = ''
  searchField.value?.focus()
}

function schemaFor(tool: ToolView): SchemaView {
  const raw = tool.inputSchema ?? ''
  if (raw.trim().length === 0) {
    return { ok: true, text: '该工具未声明入参 Schema。', keys: 0 }
  }
  try {
    const parsed: unknown = JSON.parse(raw)
    const keys =
      parsed && typeof parsed === 'object' && !Array.isArray(parsed)
        ? Object.keys(parsed as Record<string, unknown>).length
        : 0
    return { ok: true, text: JSON.stringify(parsed, null, 2), keys }
  } catch {
    return { ok: false, text: raw, keys: 0 }
  }
}

watch(refreshTick, () => {
  void load(true)
})

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page-shell page-stack tools-page">
    <PageHeader
      eyebrow="Tool registry / 04"
      title="工具"
      description="工作区注册的工具能力与入参 Schema。筛选仅作用于当前已加载清单，不发起额外请求。"
    >
      <template #actions>
        <span class="tools-caption mono">{{ filtered.length }} / {{ tools.length }}</span>
        <button
          type="button"
          class="btn btn--secondary btn--sm"
          :disabled="refreshing || loading"
          @click="refresh"
        >
          <AppIcon name="refresh" size="14" :class="{ spinning: refreshing || loading }" />
          {{ refreshing ? '刷新中' : '刷新' }}
        </button>
      </template>
    </PageHeader>

    <div class="tool-toolbar">
      <div class="tool-search">
        <AppIcon name="search" size="14" class="tool-search__icon" />
        <input
          ref="searchField"
          v-model="query"
          type="search"
          class="input tool-search__input"
          placeholder="按名称或描述筛选工具"
          aria-label="按名称或描述筛选工具"
          autocomplete="off"
        />
      </div>
      <div class="tool-toolbar__actions">
        <button
          v-if="filtered.length > 0"
          type="button"
          class="btn btn--ghost btn--sm"
          :aria-pressed="allExpanded"
          @click="toggleAll"
        >
          {{ allExpanded ? '全部收起' : '展开全部' }}
        </button>
        <button v-if="hasQuery" type="button" class="btn btn--ghost btn--sm" @click="clearQuery">
          清除筛选
        </button>
      </div>
    </div>

    <div v-if="loading && tools.length === 0" class="tool-list" aria-label="工具加载中">
      <div v-for="index in 3" :key="index" class="tool-skeleton">
        <div class="tool-skeleton__main">
          <SkeletonBlock width="160px" height="16px" />
          <SkeletonBlock width="72%" height="12px" />
        </div>
        <SkeletonBlock width="18px" height="18px" />
      </div>
    </div>

    <ErrorState
      v-else-if="error && tools.length === 0"
      title="工具清单加载失败"
      :error="error"
      @retry="refresh"
      @inspect="openDiagnostics(error)"
    />

    <EmptyState
      v-else-if="tools.length === 0"
      title="尚未注册任何工具"
      description="工作区当前没有可用的工具。工具由后端注册表提供，控制台不提供新增入口。"
      action-label="重新加载"
      icon="tools"
      @action="refresh"
    />

    <template v-else>
      <div v-if="error" class="inline-alert inline-alert--danger">
        <AppIcon name="alert" size="16" />
        <span>
          {{ error.message }}
          <span class="inline-alert__trace">traceId: {{ error.traceId || '—' }}</span>
        </span>
        <button type="button" class="inline-alert__action" @click="openDiagnostics(error)">诊断</button>
      </div>

      <div v-if="filtered.length === 0" class="panel tool-no-match">
        <span class="empty-state__mark"><AppIcon name="search" size="18" /></span>
        <h2 class="empty-state__title">没有匹配的工具</h2>
        <p class="empty-state__description">
          当前关键字“{{ query.trim() }}”没有命中任何工具名称或描述。
        </p>
        <button type="button" class="btn btn--secondary" @click="clearQuery">清除筛选</button>
      </div>

      <div v-else class="tool-list" role="list">
        <article
          v-for="tool in filtered"
          :key="tool.name"
          class="tool-row"
          :class="{ 'tool-row--open': isExpanded(tool.name) }"
          role="listitem"
        >
          <button
            type="button"
            class="tool-row__trigger"
            :aria-expanded="isExpanded(tool.name)"
            :aria-controls="`schema-${tool.name}`"
            @click="toggle(tool.name)"
          >
            <span class="tool-row__main">
              <span class="tool-row__name mono">{{ tool.name }}</span>
              <span class="tool-row__description">
                {{ tool.description || '该工具未提供描述。' }}
              </span>
            </span>
            <span class="tool-row__meta">
              <span class="chip">{{ schemaFor(tool).ok ? 'JSON' : 'RAW' }}</span>
              <AppIcon
                name="chevron-down"
                size="16"
                class="tool-row__chevron"
                :class="{ 'tool-row__chevron--open': isExpanded(tool.name) }"
              />
            </span>
          </button>

          <div
            v-if="isExpanded(tool.name)"
            :id="`schema-${tool.name}`"
            class="tool-schema"
          >
            <div class="tool-schema__head">
              <span class="eyebrow">Input schema</span>
              <div class="tool-schema__actions">
                <span v-if="!schemaFor(tool).ok" class="schema-warning">
                  <AppIcon name="alert" size="13" />
                  Schema 不是合法 JSON
                </span>
                <CopyButton :value="schemaFor(tool).text" label="复制 Schema" show-label />
              </div>
            </div>
            <pre class="tool-schema__code">{{ schemaFor(tool).text }}</pre>
          </div>
        </article>
      </div>
    </template>
  </div>
</template>

<style scoped>
.tools-page {
  animation: page-in var(--duration-base) var(--ease-out);
}

@keyframes page-in {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.spinning {
  animation: spin 700ms linear infinite;
}

.tools-caption {
  color: var(--text-faint);
  font-size: 10px;
  letter-spacing: 0.06em;
  white-space: nowrap;
}

.tool-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  flex-wrap: wrap;
}

.tool-search {
  position: relative;
  min-width: 0;
  flex: 1 1 300px;
  max-width: 420px;
}

.tool-search__icon {
  position: absolute;
  top: 50%;
  left: 11px;
  color: var(--text-faint);
  pointer-events: none;
  transform: translateY(-50%);
}

.tool-search__input {
  padding-left: 32px;
}

.tool-search__input::-webkit-search-cancel-button {
  display: none;
}

.tool-toolbar__actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.tool-list {
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
}

.tool-row {
  border-bottom: 1px solid var(--border);
}

.tool-row:last-child {
  border-bottom: 0;
}

.tool-row__trigger {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  padding: var(--space-4) var(--space-5);
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  text-align: left;
  transition: background-color var(--duration-fast) var(--ease-out);
}

.tool-row__trigger:hover {
  background: #101010;
}

.tool-row__trigger:focus-visible {
  outline: 1px solid var(--accent);
  outline-offset: -2px;
  box-shadow: none;
}

.tool-row--open .tool-row__trigger {
  background: #0c0c0c;
}

.tool-row__main {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.tool-row__name {
  color: var(--text);
  font-size: var(--fs-13);
  font-weight: 600;
  line-height: var(--lh-20);
  overflow-wrap: anywhere;
}

.tool-row__description {
  color: var(--text-muted);
  font-size: var(--fs-12);
  line-height: var(--lh-18);
  overflow-wrap: anywhere;
}

.tool-row__meta {
  display: flex;
  align-items: center;
  flex: 0 0 auto;
  gap: var(--space-3);
}

.tool-row__chevron {
  color: var(--text-faint);
  transition: transform var(--duration-base) var(--ease-out);
}

.tool-row__chevron--open {
  transform: rotate(180deg);
}

.tool-schema {
  border-top: 1px solid var(--border-subtle);
  background: #080808;
}

.tool-schema__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: 7px var(--space-4);
  border-bottom: 1px solid var(--border-subtle);
}

.tool-schema__actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.schema-warning {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: #fcd34d;
  font-size: var(--fs-11);
  white-space: nowrap;
}

.tool-schema__code {
  max-height: 340px;
  margin: 0;
  overflow: auto;
  padding: var(--space-4);
  color: var(--text-secondary);
  font-family: var(--font-mono);
  font-size: var(--fs-12);
  line-height: var(--lh-18);
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.tool-skeleton {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  padding: var(--space-4) var(--space-5);
  border-bottom: 1px solid var(--border);
}

.tool-skeleton:last-child {
  border-bottom: 0;
}

.tool-skeleton__main {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  flex-direction: column;
  gap: var(--space-2);
}

.tool-no-match {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-12) var(--space-6);
  text-align: center;
}

.tool-no-match .empty-state__mark {
  margin-bottom: var(--space-1);
}

@media (max-width: 767px) {
  .tool-search {
    max-width: none;
    flex-basis: 100%;
  }

  .tool-row__trigger {
    padding: var(--space-3) var(--space-4);
  }

  .tool-row__meta .chip {
    display: none;
  }

  .tool-schema__head {
    flex-wrap: wrap;
  }

  .tool-skeleton {
    padding: var(--space-3) var(--space-4);
  }
}
</style>
