<script setup lang="ts">
import { computed, nextTick, onMounted, ref, shallowRef, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import EmptyState from '../components/EmptyState.vue'
import ErrorState from '../components/ErrorState.vue'
import PageHeader from '../components/PageHeader.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'
import { normalizeError, type ApiError } from '../api/client'
import { createAgent, deleteAgent } from '../api/endpoints'
import { loadProfiles, profiles, profilesError, profilesLoading } from '../composables/useProfiles'
import { refreshTick } from '../composables/useRefreshSignal'
import { openDiagnostics, pushToast, reportError } from '../composables/useUiFeedback'
import type { AgentView } from '../types/api'

const router = useRouter()

const MAX_DESCRIPTION_CHARS = 2000
const NAME_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/

const refreshing = ref(false)

const agentCount = computed(() => profiles.value.length)
const toolCount = computed(() => new Set(profiles.value.flatMap((agent) => agent.tools)).size)
const scheduleCount = computed(() =>
  profiles.value.reduce((total, agent) => total + agent.schedules.length, 0),
)

// ---------------------------------------------------------------- 新建

const createOpen = ref(false)
const createName = ref('')
const createDescription = ref('')
const createPending = ref(false)
const createError = shallowRef<ApiError | null>(null)
const createFieldError = ref('')
const createDialog = ref<HTMLDialogElement | null>(null)
const nameField = ref<HTMLInputElement | null>(null)

const canCreate = computed(
  () =>
    !createPending.value &&
    createName.value.trim().length > 0 &&
    createDescription.value.length <= MAX_DESCRIPTION_CHARS,
)

function openCreate(): void {
  createName.value = ''
  createDescription.value = ''
  createError.value = null
  createFieldError.value = ''
  createOpen.value = true
}

function closeCreate(): void {
  if (createPending.value) return
  createOpen.value = false
}

async function submitCreate(): Promise<void> {
  if (!canCreate.value) return
  const name = createName.value.trim()
  if (!NAME_PATTERN.test(name)) {
    createFieldError.value = '名字只允许字母、数字、_ . -；首字符须为字母或数字，最多 64 字符。'
    nameField.value?.focus()
    return
  }
  createFieldError.value = ''
  createError.value = null
  createPending.value = true
  try {
    const created = await createAgent(name, createDescription.value.trim())
    await loadProfiles(true)
    pushToast(`Agent ${created.agent.name} 已创建`, 'success')
    createOpen.value = false
    void router.push(`/agents/${encodeURIComponent(created.agent.name)}`)
  } catch (error) {
    const apiError = normalizeError(error)
    createError.value = apiError
    reportError(apiError, { toast: false })
  } finally {
    createPending.value = false
  }
}

// ---------------------------------------------------------------- 删除

const deleteTarget = shallowRef<AgentView | null>(null)
const deletePending = ref(false)

function openDelete(agent: AgentView): void {
  deleteTarget.value = agent
}

function closeDelete(): void {
  if (deletePending.value) return
  deleteTarget.value = null
}

async function confirmDelete(): Promise<void> {
  const target = deleteTarget.value
  if (!target || deletePending.value) return
  deletePending.value = true
  try {
    await deleteAgent(target.name)
    await loadProfiles(true)
    pushToast(`Agent ${target.name} 已删除并归档`, 'success')
    deleteTarget.value = null
  } catch (error) {
    reportError(normalizeError(error), { toast: true })
  } finally {
    deletePending.value = false
  }
}

// ---------------------------------------------------------------- 通用

async function refresh(): Promise<void> {
  if (refreshing.value) return
  refreshing.value = true
  await loadProfiles(true)
  if (profilesError.value) {
    reportError(profilesError.value)
  } else {
    pushToast('Agent 清单已刷新', 'success')
  }
  refreshing.value = false
}

watch(createOpen, async (open) => {
  await nextTick()
  if (!createDialog.value) return
  if (open && !createDialog.value.open) {
    createDialog.value.showModal()
    await nextTick()
    nameField.value?.focus()
  } else if (!open && createDialog.value.open) {
    createDialog.value.close()
  }
})

watch(refreshTick, () => {
  void loadProfiles(true)
})

onMounted(() => {
  void loadProfiles()
})
</script>

<template>
  <div class="page-shell page-stack agents-page">
    <PageHeader
      eyebrow="Agent registry / 03"
      title="Agents"
      description="丢一个目录、填一段描述，Agent 就上线——不需要重启进程。点卡片进入详情可看文件、聊天、查记忆。"
    >
      <template #actions>
        <span class="registry-caption mono">{{ agentCount }} agents</span>
        <button type="button" class="btn btn--secondary btn--sm" :disabled="refreshing" @click="refresh">
          <AppIcon name="refresh" size="14" :class="{ spinning: refreshing || profilesLoading }" />
          {{ refreshing ? '刷新中' : '刷新' }}
        </button>
        <button type="button" class="btn btn--primary btn--sm" @click="openCreate">
          <AppIcon name="plus" size="14" />
          新建 Agent
        </button>
      </template>
    </PageHeader>

    <section v-if="agentCount > 0" class="registry-strip" aria-label="Agent 注册摘要">
      <div class="registry-cell">
        <span class="registry-cell__label">已注册 Agent</span>
        <strong class="registry-cell__value mono">{{ agentCount }}</strong>
      </div>
      <div class="registry-cell">
        <span class="registry-cell__label">关联工具</span>
        <strong class="registry-cell__value mono">{{ toolCount }}</strong>
      </div>
      <div class="registry-cell">
        <span class="registry-cell__label">定时声明</span>
        <strong class="registry-cell__value mono">{{ scheduleCount }}</strong>
      </div>
      <div class="registry-cell registry-cell--note">
        <span class="registry-cell__label">录入方式</span>
        <span class="registry-cell__text">API 创建 / 往 agents/ 丢目录 / 页面上改，三条路径殊途同归</span>
      </div>
    </section>

    <div v-if="profilesLoading && agentCount === 0" class="profile-grid" aria-label="Agent 加载中">
      <article v-for="index in 3" :key="index" class="profile-card profile-card--skeleton">
        <div class="profile-card__head">
          <SkeletonBlock width="46%" height="20px" />
          <SkeletonBlock width="12%" height="12px" />
        </div>
        <div class="profile-skeleton__body">
          <SkeletonBlock width="88%" height="12px" />
          <SkeletonBlock width="64%" height="12px" />
          <SkeletonBlock width="100%" height="52px" />
        </div>
      </article>
    </div>

    <ErrorState
      v-else-if="profilesError && agentCount === 0"
      title="Agent 清单加载失败"
      :error="profilesError"
      @retry="refresh"
      @inspect="openDiagnostics(profilesError)"
    />

    <EmptyState
      v-else-if="agentCount === 0"
      title="还没有 Agent"
      description="点「新建 Agent」填一个名字和一句描述，系统会按模板脚手架出完整目录。"
      action-label="新建 Agent"
      icon="agents"
      @action="openCreate"
    />

    <template v-else>
      <div v-if="profilesError" class="inline-alert inline-alert--danger">
        <AppIcon name="alert" size="16" />
        <span>
          {{ profilesError.message }}
          <span class="inline-alert__trace">traceId: {{ profilesError.traceId || '—' }}</span>
        </span>
        <button type="button" class="inline-alert__action" @click="openDiagnostics(profilesError)">
          诊断
        </button>
      </div>

      <div class="profile-grid">
        <article v-for="(agent, index) in profiles" :key="agent.name" class="profile-card">
          <header class="profile-card__head">
            <div class="profile-identity">
              <span class="profile-index mono">#{{ String(index + 1).padStart(2, '0') }}</span>
              <h2 class="profile-name mono">
                <RouterLink :to="`/agents/${encodeURIComponent(agent.name)}`">{{ agent.name }}</RouterLink>
              </h2>
            </div>
            <span class="profile-state">
              <span class="status-dot" aria-hidden="true" />
              已注册
            </span>
          </header>

          <p class="profile-description" :class="{ 'profile-description--empty': !agent.description }">
            {{ agent.description || '该 Agent 未提供描述。' }}
          </p>

          <dl class="profile-meta">
            <div class="profile-meta__item">
              <dt>Provider</dt>
              <dd class="mono">{{ agent.provider || '—' }}</dd>
            </div>
            <div class="profile-meta__item">
              <dt>Model</dt>
              <dd class="mono">{{ agent.model || '—' }}</dd>
            </div>
            <div class="profile-meta__item">
              <dt>定时</dt>
              <dd class="mono">{{ agent.schedules.length }}</dd>
            </div>
          </dl>

          <div class="profile-section">
            <span class="profile-section__label">
              Tools
              <span class="mono">{{ agent.tools.length }}</span>
            </span>
            <div v-if="agent.tools.length > 0" class="chip-row">
              <span v-for="tool in agent.tools" :key="tool" class="chip">{{ tool }}</span>
            </div>
            <p v-else class="profile-empty-line">未挂载工具</p>
          </div>

          <footer class="profile-card__foot">
            <button type="button" class="btn btn--danger-ghost btn--sm" @click="openDelete(agent)">
              <AppIcon name="close" size="12" />
              删除
            </button>
            <RouterLink
              class="btn btn--accent-ghost btn--sm"
              :to="`/agents/${encodeURIComponent(agent.name)}`"
            >
              <AppIcon name="play" size="12" />
              打开
            </RouterLink>
          </footer>
        </article>
      </div>
    </template>

    <dialog
      ref="createDialog"
      class="dialog"
      aria-labelledby="create-dialog-title"
      @cancel.prevent="closeCreate"
      @click.self="closeCreate"
    >
      <div class="dialog__head">
        <div>
          <div class="eyebrow">New agent</div>
          <h2 id="create-dialog-title" class="dialog__title">新建 Agent</h2>
        </div>
        <button type="button" class="icon-button" aria-label="关闭" :disabled="createPending" @click="closeCreate">
          <AppIcon name="close" size="16" />
        </button>
      </div>

      <div class="dialog__body">
        <p class="invoke-note">
          <AppIcon name="info" size="14" />
          只需名字与描述；系统按模板生成 AGENT.md、REFERENCE.md 与示例脚本，立即注册、不用重启。
        </p>

        <div class="form-field">
          <label class="form-label" for="create-name">名字</label>
          <input
            id="create-name"
            ref="nameField"
            v-model="createName"
            class="input mono"
            placeholder="例如 weather-daily"
            :disabled="createPending"
            :aria-invalid="Boolean(createFieldError)"
            @input="createFieldError = ''"
          />
          <p class="field-hint">字母、数字、_ . -；首字符须为字母或数字，最多 64 字符。</p>
        </div>

        <div class="form-field">
          <label class="form-label" for="create-description">
            描述
            <span class="char-count mono">
              {{ createDescription.length.toLocaleString('zh-CN') }} /
              {{ MAX_DESCRIPTION_CHARS.toLocaleString('zh-CN') }}
            </span>
          </label>
          <textarea
            id="create-description"
            v-model="createDescription"
            class="textarea"
            placeholder="例如：每天早上九点查北京天气，把穿搭建议发到团队群"
            :disabled="createPending"
          />
        </div>

        <p v-if="createFieldError" class="field-error">{{ createFieldError }}</p>

        <div v-if="createError" class="inline-alert inline-alert--danger">
          <AppIcon name="alert" size="15" />
          <span>{{ createError.message }}</span>
        </div>
      </div>

      <div class="dialog__footer">
        <button type="button" class="btn btn--secondary" :disabled="createPending" @click="closeCreate">取消</button>
        <button type="button" class="btn btn--primary" :disabled="!canCreate" @click="submitCreate">
          {{ createPending ? '创建中…' : '创建' }}
        </button>
      </div>
    </dialog>

    <dialog v-if="deleteTarget" class="dialog" aria-labelledby="delete-dialog-title" open @cancel.prevent="closeDelete">
      <div class="dialog__head">
        <div>
          <div class="eyebrow">Delete agent</div>
          <h2 id="delete-dialog-title" class="dialog__title">
            删除 <span class="mono">{{ deleteTarget.name }}</span>？
          </h2>
        </div>
      </div>
      <div class="dialog__body">
        <p>删除会先停掉它的定时、再移出注册表，最后把整个目录移到 <code>archive/</code>（不物理删）。</p>
      </div>
      <div class="dialog__footer">
        <button type="button" class="btn btn--secondary" :disabled="deletePending" @click="closeDelete">取消</button>
        <button type="button" class="btn btn--danger" :disabled="deletePending" @click="confirmDelete">
          {{ deletePending ? '删除中…' : '确认删除' }}
        </button>
      </div>
    </dialog>
  </div>
</template>

<style scoped>
.agents-page {
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

.registry-caption {
  color: var(--text-faint);
  font-size: 10px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  white-space: nowrap;
}

.registry-strip {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 150px)) minmax(0, 1fr);
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #0a0a0a;
}

.registry-cell {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
  padding: var(--space-4) var(--space-5);
  border-right: 1px solid var(--border);
}

.registry-cell:last-child {
  border-right: 0;
}

.registry-cell__label {
  color: var(--text-faint);
  font-size: 10px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.registry-cell__value {
  color: var(--text);
  font-size: var(--fs-20);
  font-weight: 600;
  letter-spacing: -0.03em;
  line-height: var(--lh-28);
}

.registry-cell__text {
  color: var(--text-secondary);
  font-size: var(--fs-12);
  line-height: var(--lh-18);
}

.registry-cell--note {
  justify-content: center;
}

.profile-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: var(--space-4);
}

.profile-card {
  display: flex;
  min-width: 0;
  flex-direction: column;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  transition: border-color var(--duration-fast) var(--ease-out);
}

.profile-card:hover {
  border-color: var(--border-strong);
}

.profile-card--skeleton {
  min-height: 268px;
}

.profile-skeleton__body {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-4);
}

.profile-card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-4) var(--space-4) var(--space-3);
  border-bottom: 1px solid var(--border-subtle);
}

.profile-identity {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.profile-index {
  color: var(--text-faint);
  font-size: 10px;
  letter-spacing: 0.08em;
}

.profile-name {
  min-width: 0;
  color: var(--text);
  font-size: var(--fs-16);
  font-weight: 600;
  letter-spacing: -0.01em;
  line-height: var(--lh-24);
  overflow-wrap: anywhere;
}

.profile-state {
  display: inline-flex;
  align-items: center;
  flex: 0 0 auto;
  gap: 6px;
  color: #86efac;
  font-size: var(--fs-11);
  white-space: nowrap;
}

.profile-description {
  padding: var(--space-3) var(--space-4);
  color: var(--text-secondary);
  font-size: var(--fs-13);
  line-height: var(--lh-20);
  overflow-wrap: anywhere;
}

.profile-description--empty {
  color: var(--text-faint);
}

.profile-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-3);
  padding: 0 var(--space-4) var(--space-4);
}

.profile-meta__item {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  background: #080808;
}

.profile-meta__item dt {
  color: var(--text-faint);
  font-size: 10px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.profile-meta__item dd {
  color: var(--text);
  font-size: var(--fs-12);
  overflow-wrap: anywhere;
}

.profile-section {
  padding: var(--space-3) var(--space-4);
  border-top: 1px solid var(--border-subtle);
}

.profile-section__label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
  color: var(--text-faint);
  font-size: 10px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.profile-empty-line {
  color: var(--text-faint);
  font-size: var(--fs-11);
}

.profile-card__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  margin-top: auto;
  padding: var(--space-3) var(--space-4);
  border-top: 1px solid var(--border);
}

.profile-foot-note {
  color: var(--text-faint);
  font-size: var(--fs-11);
}

.invoke-dialog {
  width: min(560px, calc(100vw - 32px));
}

.invoke-body {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.invoke-note {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #090909;
  color: var(--text-secondary);
  font-size: var(--fs-12);
  line-height: var(--lh-18);
}

.invoke-context {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-3);
}

.invoke-context dt {
  color: var(--text-faint);
  font-size: 10px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.invoke-context dd {
  color: var(--text);
  font-size: var(--fs-12);
  overflow-wrap: anywhere;
}

.invoke-input {
  min-height: 104px;
}

.invoke-progress {
  display: flex;
  flex-direction: column;
  gap: 6px;
  color: var(--text-muted);
  font-size: var(--fs-12);
}

.invoke-error__text {
  min-width: 0;
  flex: 1 1 auto;
  overflow-wrap: anywhere;
}

.invoke-reply {
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #080808;
}

.invoke-reply__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: 6px 10px;
  border-bottom: 1px solid var(--border-subtle);
}

.invoke-reply__hint {
  color: var(--text-faint);
  font-size: var(--fs-11);
}

.invoke-reply__text {
  max-height: 280px;
  overflow-y: auto;
  padding: var(--space-3) 10px;
  color: var(--text);
  font-size: var(--fs-13);
  line-height: var(--lh-20);
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.invoke-footer-note {
  margin-right: auto;
  color: var(--text-faint);
  font-size: var(--fs-11);
}

@media (max-width: 900px) {
  .registry-strip {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .registry-cell--note {
    display: none;
  }
}

@media (max-width: 767px) {
  .profile-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .registry-cell {
    padding: var(--space-3) var(--space-4);
  }

  .invoke-footer-note {
    display: none;
  }
}
</style>

.profile-meta {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.profile-name a {
  color: inherit;
  text-decoration: none;
}

.profile-name a:hover {
  text-decoration: underline;
}

.field-hint {
  color: var(--text-faint);
  font-size: var(--fs-11);
  line-height: var(--lh-16);
}

.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.chip {
  padding: 2px 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  font-size: var(--fs-11);
  overflow-wrap: anywhere;
}

.profile-empty-line {
  color: var(--text-faint);
  font-size: var(--fs-12);
}

.profile-section {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 0 var(--space-4) var(--space-4);
}

.profile-section__label {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  color: var(--text-faint);
  font-size: 10px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.profile-card__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  border-top: 1px solid var(--border-subtle);
}

.profile-card__foot a.btn {
  text-decoration: none;
}

.invoke-note {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  color: var(--text-secondary);
  font-size: var(--fs-12);
  line-height: var(--lh-18);
}
