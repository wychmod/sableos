<script setup lang="ts">
import { computed, onMounted, ref, shallowRef, watch } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import EmptyState from '../components/EmptyState.vue'
import ErrorState from '../components/ErrorState.vue'
import PageHeader from '../components/PageHeader.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'
import { normalizeError, type ApiError } from '../api/client'
import { getAgentMemory } from '../api/endpoints'
import { loadProfiles, profiles, profilesError, profilesLoading } from '../composables/useProfiles'
import { refreshTick } from '../composables/useRefreshSignal'
import { openDiagnostics, pushToast, reportError } from '../composables/useUiFeedback'
import type { MemoryView } from '../types/api'
import { formatClock } from '../utils/format'

interface MemorySection {
  level: number
  title: string
  bullets: string[]
  paragraphs: string[]
  raw: string
}

const profileInput = ref('')
const activeProfile = ref('')
const memory = shallowRef<MemoryView | null>(null)
const loading = ref(false)
const refreshing = ref(false)
const error = shallowRef<ApiError | null>(null)
const fieldError = ref('')
const lastLoadedAt = ref<Date | null>(null)
const profileField = ref<HTMLInputElement | null>(null)

const content = computed(() => memory.value?.content ?? '')
const hasContent = computed(() => content.value.trim().length > 0)
const charCount = computed(() => content.value.length)

const parsed = computed(() => {
  const lines = content.value.replace(/\r\n/g, '\n').split('\n')
  const intro: string[] = []
  const sections: MemorySection[] = []
  let current: MemorySection | null = null

  for (const line of lines) {
    const heading = /^(#{2,4})\s+(.*)$/.exec(line)
    if (heading) {
      if (current) sections.push(current)
      current = {
        level: heading[1]?.length ?? 2,
        title: (heading[2] ?? '').trim(),
        bullets: [],
        paragraphs: [],
        raw: '',
      }
      continue
    }

    if (current) {
      current.raw += `${line}\n`
      const bullet = /^\s*[-*]\s+(.*)$/.exec(line)
      if (bullet) {
        current.bullets.push((bullet[1] ?? '').trim())
      } else if (line.trim().length > 0) {
        current.paragraphs.push(line.trimEnd())
      }
    } else if (line.trim().length > 0) {
      intro.push(line.trimEnd())
    }
  }

  if (current) sections.push(current)
  return { intro: intro.join('\n'), sections }
})

const sectionCount = computed(() => parsed.value.sections.length)
const lastLoadedLabel = computed(() =>
  lastLoadedAt.value ? formatClock(lastLoadedAt.value) : '—',
)

function sectionMeta(section: MemorySection): string {
  if (section.bullets.length > 0 && section.paragraphs.length === 0) {
    return `${section.bullets.length} 条记录`
  }
  const lines = section.raw.split('\n').filter((line) => line.trim().length > 0).length
  return `${lines} 行`
}

function selectProfile(name: string): void {
  profileInput.value = name
  void loadMemory(name)
}

async function loadMemory(name: string, options: { silent?: boolean } = {}): Promise<void> {
  const profile = name.trim()
  if (!profile) {
    fieldError.value = '请输入 Agent 名称。'
    profileField.value?.focus()
    return
  }

  if (loading.value && activeProfile.value === profile) return

  fieldError.value = ''
  loading.value = true
  error.value = null
  activeProfile.value = profile
  try {
    memory.value = await getAgentMemory(profile)
    lastLoadedAt.value = new Date()
  } catch (caught) {
    const apiError = normalizeError(caught)
    error.value = apiError
    memory.value = null
    reportError(apiError, { toast: options.silent !== true })
  } finally {
    loading.value = false
  }
}

async function refresh(): Promise<void> {
  if (refreshing.value) return
  refreshing.value = true
  await loadProfiles(true)
  await loadMemory(activeProfile.value, { silent: true })
  if (!error.value) {
    pushToast('长期记忆已刷新', 'success')
  }
  refreshing.value = false
}

function loadCurrent(): void {
  void loadMemory(profileInput.value)
}

watch(refreshTick, () => {
  void loadProfiles(true)
  void loadMemory(activeProfile.value, { silent: true })
})

onMounted(async () => {
  await loadProfiles()
  // 第 30 节：记忆按 Agent 隔离，默认选第一个已注册 Agent（不再有全局 memory 端点）
  const first = profiles.value[0]?.name
  if (first) {
    profileInput.value = first
    void loadMemory(first, { silent: true })
  }
})

watch(
  () => profiles.value.length,
  (length) => {
    if (length > 0 && !activeProfile.value) {
      const first = profiles.value[0]?.name
      if (first) {
        profileInput.value = first
        void loadMemory(first, { silent: true })
      }
    }
  },
)
</script>

<template>
  <div class="page-shell page-stack memory-page">
    <PageHeader
      eyebrow="Long-term memory / 05"
      title="长期记忆"
      description="查看 Agent 已写入的长期记忆全文。记忆由 Agent 在运行期自行写入，控制台不提供任何修改入口。"
    >
      <template #actions>
        <span class="readonly-badge">
          <AppIcon name="info" size="12" />
          只读视图
        </span>
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

    <section class="panel memory-selector" aria-label="选择 Profile">
      <form class="memory-form" @submit.prevent="loadCurrent">
        <div class="form-field memory-form__field">
          <label class="form-label" for="memory-profile">Agent</label>
          <input
            id="memory-profile"
            ref="profileField"
            v-model="profileInput"
            class="input mono"
            list="memory-profile-options"
            placeholder="weather-daily"
            autocomplete="off"
            :aria-invalid="Boolean(fieldError)"
            @input="fieldError = ''"
          />
          <datalist id="memory-profile-options">
            <option v-for="profile in profiles" :key="profile.name" :value="profile.name" />
          </datalist>
        </div>
        <button type="submit" class="btn btn--primary memory-form__submit" :disabled="loading">
          <AppIcon name="memory" size="14" />
          {{ loading ? '读取中…' : '读取记忆' }}
        </button>
      </form>

      <div v-if="fieldError" class="field-error memory-field-error">{{ fieldError }}</div>

      <div v-if="profilesError" class="inline-alert inline-alert--danger">
        <AppIcon name="alert" size="15" />
        <span>
          Agent 清单加载失败：{{ profilesError.message }}
          <span class="inline-alert__trace">traceId: {{ profilesError.traceId || '—' }}</span>
        </span>
        <button type="button" class="inline-alert__action" @click="loadProfiles(true)">重试</button>
      </div>

      <div class="profile-shortcuts">
        <span class="profile-shortcuts__label">
          {{ profilesLoading ? '正在加载 Agent 清单…' : '已注册 Agent' }}
        </span>
        <div v-if="profiles.length > 0" class="chip-row">
          <button
            v-for="profile in profiles"
            :key="profile.name"
            type="button"
            class="chip chip--action"
            :class="{ 'chip--active': activeProfile === profile.name && hasContent }"
            @click="selectProfile(profile.name)"
          >
            {{ profile.name }}
          </button>
        </div>
        <span v-else-if="!profilesLoading" class="profile-shortcuts__empty">
          没有可选 Agent，可直接手动输入 profile 名。
        </span>
      </div>
    </section>

    <div class="memory-meta">
      <span class="memory-meta__item mono">profile: {{ memory?.profile || activeProfile }}</span>
      <span class="memory-meta__item mono">{{ charCount.toLocaleString('zh-CN') }} chars</span>
      <span class="memory-meta__item mono">{{ sectionCount }} sections</span>
      <span class="memory-meta__item">最后读取 {{ lastLoadedLabel }}</span>
      <span class="memory-meta__item memory-meta__hint">内容原样展示，不做改写</span>
    </div>

    <section class="panel memory-panel">
      <div v-if="loading" class="memory-loading" aria-label="记忆加载中">
        <SkeletonBlock width="28%" height="18px" />
        <SkeletonBlock width="86%" height="12px" />
        <SkeletonBlock width="64%" height="12px" />
        <SkeletonBlock width="100%" height="120px" />
      </div>

      <ErrorState
        v-else-if="error"
        title="长期记忆读取失败"
        :error="error"
        @retry="loadMemory(activeProfile)"
        @inspect="openDiagnostics(error)"
      />

      <EmptyState
        v-else-if="!hasContent"
        title="该 Agent 还没有写入长期记忆"
        description="后端返回了空内容，这是正常状态，不代表读取失败。Agent 在运行期写入记忆后会出现在这里。"
        action-label="重新读取"
        icon="memory"
        @action="loadMemory(activeProfile)"
      />

      <article v-else class="memory-doc">
        <header class="memory-doc__head">
          <div>
            <span class="eyebrow">Memory transcript</span>
            <h2 class="memory-doc__title mono">{{ memory?.profile }}</h2>
          </div>
          <span class="memory-doc__note">全文 · 只读</span>
        </header>

        <div v-if="parsed.intro" class="memory-intro">{{ parsed.intro }}</div>

        <section
          v-for="(section, index) in parsed.sections"
          :key="`${section.title}-${index}`"
          class="memory-section"
          :class="{ 'memory-section--primary': section.level <= 2 }"
        >
          <header class="memory-section__head">
            <div class="memory-section__title-group">
              <span class="memory-section__index mono">
                {{ String(index + 1).padStart(2, '0') }}
              </span>
              <h3 class="memory-section__title" :class="{ 'memory-section__title--top': section.level <= 2 }">
                {{ section.title }}
              </h3>
            </div>
            <span class="memory-section__meta mono">{{ sectionMeta(section) }}</span>
          </header>

          <ul v-if="section.bullets.length > 0 && section.paragraphs.length === 0" class="memory-list">
            <li v-for="(bullet, bulletIndex) in section.bullets" :key="bulletIndex" class="memory-list__item">
              <span class="memory-list__marker" aria-hidden="true" />
              <span>{{ bullet }}</span>
            </li>
          </ul>

          <pre v-else class="memory-pre">{{ section.raw.trimEnd() }}</pre>
        </section>

        <footer class="memory-doc__foot">
          <AppIcon name="memory" size="13" />
          记忆全文由后端返回，控制台不支持编辑、清空或检索。
        </footer>
      </article>
    </section>
  </div>
</template>

<style scoped>
.memory-page {
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

.readonly-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #090909;
  color: var(--text-muted);
  font-size: var(--fs-11);
  white-space: nowrap;
}

.memory-selector {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-4) var(--space-5);
}

.memory-form {
  display: flex;
  align-items: flex-end;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.memory-form__field {
  min-width: 200px;
  flex: 1 1 240px;
  max-width: 360px;
}

.memory-form__submit {
  height: 36px;
  flex: 0 0 auto;
}

.memory-field-error {
  margin-top: calc(var(--space-1) * -1);
}

.profile-shortcuts {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
  padding-top: var(--space-3);
  border-top: 1px solid var(--border-subtle);
}

.profile-shortcuts__label {
  color: var(--text-faint);
  font-size: 10px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  white-space: nowrap;
}

.profile-shortcuts__empty {
  color: var(--text-faint);
  font-size: var(--fs-11);
}

.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.chip--action {
  cursor: pointer;
  transition:
    background-color var(--duration-fast) var(--ease-out),
    border-color var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out);
}

.chip--action:hover {
  border-color: var(--border-strong);
  background: var(--hover);
  color: var(--text);
}

.chip--active {
  border-color: var(--accent-border);
  background: var(--accent-soft);
  color: var(--accent);
}

.memory-meta {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  flex-wrap: wrap;
  padding: 0 var(--space-1);
  color: var(--text-faint);
  font-size: var(--fs-11);
}

.memory-meta__item {
  white-space: nowrap;
}

.memory-meta__hint {
  margin-left: auto;
  color: var(--text-faint);
}

.memory-panel {
  min-width: 0;
}

.memory-loading {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-5);
}

.memory-doc {
  display: flex;
  flex-direction: column;
}

.memory-doc__head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--space-4);
  padding: var(--space-5);
  border-bottom: 1px solid var(--border);
}

.memory-doc__title {
  margin-top: 2px;
  color: var(--text);
  font-size: var(--fs-16);
  font-weight: 600;
  line-height: var(--lh-24);
  overflow-wrap: anywhere;
}

.memory-doc__note {
  color: var(--text-faint);
  font-size: var(--fs-11);
  white-space: nowrap;
}

.memory-intro {
  padding: var(--space-4) var(--space-5);
  border-bottom: 1px solid var(--border-subtle);
  color: var(--text-secondary);
  font-size: var(--fs-13);
  line-height: var(--lh-20);
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.memory-section {
  padding: var(--space-4) var(--space-5) var(--space-5);
  border-bottom: 1px solid var(--border-subtle);
}

.memory-section:last-of-type {
  border-bottom: 0;
}

.memory-section__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
}

.memory-section__title-group {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: var(--space-3);
}

.memory-section__index {
  color: var(--text-faint);
  font-size: 10px;
  letter-spacing: 0.08em;
}

.memory-section--primary .memory-section__index {
  color: var(--accent);
}

.memory-section__title {
  color: var(--text-secondary);
  font-size: var(--fs-13);
  font-weight: 600;
  line-height: var(--lh-20);
  overflow-wrap: anywhere;
}

.memory-section__title--top {
  color: var(--text);
  font-size: var(--fs-14);
  line-height: var(--lh-22);
}

.memory-section__meta {
  flex: 0 0 auto;
  color: var(--text-faint);
  font-size: 10px;
  white-space: nowrap;
}

.memory-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.memory-list__item {
  display: grid;
  align-items: start;
  grid-template-columns: 8px minmax(0, 1fr);
  gap: var(--space-3);
  color: var(--text);
  font-size: var(--fs-13);
  line-height: var(--lh-20);
  overflow-wrap: anywhere;
}

.memory-list__marker {
  width: 4px;
  height: 4px;
  margin-top: 8px;
  border-radius: 1px;
  background: var(--text-faint);
}

.memory-section--primary .memory-list__marker {
  background: var(--accent);
}

.memory-pre {
  margin: 0;
  overflow-x: auto;
  padding: var(--space-3);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  background: #080808;
  color: var(--text-secondary);
  font-family: var(--font-mono);
  font-size: var(--fs-12);
  line-height: var(--lh-18);
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.memory-doc__foot {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-5);
  border-top: 1px solid var(--border);
  background: #0a0a0a;
  color: var(--text-faint);
  font-size: var(--fs-11);
}

@media (max-width: 767px) {
  .memory-selector {
    padding: var(--space-4);
  }

  .memory-form__field {
    max-width: none;
    flex-basis: 100%;
  }

  .memory-form__submit {
    width: 100%;
  }

  .memory-doc__head,
  .memory-section,
  .memory-intro,
  .memory-doc__foot {
    padding-right: var(--space-4);
    padding-left: var(--space-4);
  }

  .memory-meta__hint {
    margin-left: 0;
  }
}
</style>
