<script setup lang="ts">
import { computed, nextTick, onMounted, ref, shallowRef, watch } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import EmptyState from '../components/EmptyState.vue'
import ErrorState from '../components/ErrorState.vue'
import PageHeader from '../components/PageHeader.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'
import { normalizeError, type ApiError } from '../api/client'
import { invokeAgent } from '../api/endpoints'
import { loadProfiles, profiles, profilesError, profilesLoading } from '../composables/useProfiles'
import { refreshTick } from '../composables/useRefreshSignal'
import { openDiagnostics, pushToast, reportError } from '../composables/useUiFeedback'
import type { ProfileView } from '../types/api'

const MAX_MESSAGE_CHARS = 32_768

const refreshing = ref(false)
const invokeTarget = shallowRef<ProfileView | null>(null)
const invokeMessage = ref('')
const invokePending = ref(false)
const invokeError = shallowRef<ApiError | null>(null)
const invokeReply = ref('')
const invokeFieldError = ref('')

const dialog = ref<HTMLDialogElement | null>(null)
const messageField = ref<HTMLTextAreaElement | null>(null)
const closeButton = ref<HTMLButtonElement | null>(null)

const agentCount = computed(() => profiles.value.length)
const toolCount = computed(
  () => new Set(profiles.value.flatMap((profile) => profile.tools)).size,
)
const channelCount = computed(
  () => new Set(profiles.value.flatMap((profile) => profile.channels)).size,
)

const charCount = computed(() => invokeMessage.value.length)
const overLimit = computed(() => charCount.value > MAX_MESSAGE_CHARS)
const canInvoke = computed(
  () =>
    Boolean(invokeTarget.value) &&
    !invokePending.value &&
    invokeMessage.value.trim().length > 0 &&
    !overLimit.value,
)

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

function openInvoke(profile: ProfileView): void {
  invokeTarget.value = profile
  invokeMessage.value = ''
  invokeReply.value = ''
  invokeError.value = null
  invokeFieldError.value = ''
}

function closeInvoke(): void {
  if (invokePending.value) return
  invokeTarget.value = null
}

function onDialogBackdrop(event: MouseEvent): void {
  if (event.target === dialog.value) {
    closeInvoke()
  }
}

watch(
  invokeTarget,
  async (target) => {
    await nextTick()
    if (!dialog.value) return
    if (target && !dialog.value.open) {
      dialog.value.showModal()
      await nextTick()
      messageField.value?.focus()
    } else if (!target && dialog.value.open) {
      dialog.value.close()
    }
  },
  { flush: 'post' },
)

async function submitInvoke(): Promise<void> {
  const target = invokeTarget.value
  if (!target || invokePending.value) return
  if (invokeMessage.value.trim().length === 0) {
    invokeFieldError.value = '请输入要发送给 Agent 的消息。'
    messageField.value?.focus()
    return
  }
  if (overLimit.value) {
    invokeFieldError.value = '消息超过 32,768 字符上限，请删减后再调用。'
    return
  }

  invokeFieldError.value = ''
  invokeError.value = null
  invokeReply.value = ''
  invokePending.value = true
  try {
    const result = await invokeAgent(target.name, invokeMessage.value)
    invokeReply.value = result.reply
    pushToast(`Agent ${target.name} 调用完成`, 'success')
  } catch (error) {
    const apiError = normalizeError(error)
    invokeError.value = apiError
    reportError(apiError, { toast: true })
  } finally {
    invokePending.value = false
  }
}

function handleMessageKeydown(event: KeyboardEvent): void {
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
    event.preventDefault()
    if (canInvoke.value) void submitInvoke()
  }
}

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
      description="工作区已注册的 Agent 档案。试运行使用一次性会话，不保留历史，也不返回会话 ID。"
    >
      <template #actions>
        <span class="registry-caption mono">{{ agentCount }} agents</span>
        <button
          type="button"
          class="btn btn--secondary btn--sm"
          :disabled="refreshing || profilesLoading"
          @click="refresh"
        >
          <AppIcon name="refresh" size="14" :class="{ spinning: refreshing || profilesLoading }" />
          {{ refreshing ? '刷新中' : '刷新' }}
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
        <span class="registry-cell__label">接入渠道</span>
        <strong class="registry-cell__value mono">{{ channelCount }}</strong>
      </div>
      <div class="registry-cell registry-cell--note">
        <span class="registry-cell__label">调用语义</span>
        <span class="registry-cell__text">每次试运行独立建会话，调用结束即弃用</span>
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
      title="尚未加载任何 Agent"
      description="尚未加载任何 Agent，请检查工作区 agents 目录。"
      action-label="重新加载"
      icon="agents"
      @action="refresh"
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
        <article v-for="(profile, index) in profiles" :key="profile.name" class="profile-card">
          <header class="profile-card__head">
            <div class="profile-identity">
              <span class="profile-index mono">#{{ String(index + 1).padStart(2, '0') }}</span>
              <h2 class="profile-name mono">{{ profile.name }}</h2>
            </div>
            <span class="profile-state">
              <span class="status-dot" aria-hidden="true" />
              已注册
            </span>
          </header>

          <p class="profile-description" :class="{ 'profile-description--empty': !profile.description }">
            {{ profile.description || '该 Agent 未提供描述。' }}
          </p>

          <dl class="profile-meta">
            <div class="profile-meta__item">
              <dt>Provider</dt>
              <dd class="mono">{{ profile.provider || '—' }}</dd>
            </div>
            <div class="profile-meta__item">
              <dt>Model</dt>
              <dd class="mono">{{ profile.model || '—' }}</dd>
            </div>
          </dl>

          <div class="profile-section">
            <span class="profile-section__label">
              Tools
              <span class="mono">{{ profile.tools.length }}</span>
            </span>
            <div v-if="profile.tools.length > 0" class="chip-row">
              <span v-for="tool in profile.tools" :key="tool" class="chip">{{ tool }}</span>
            </div>
            <p v-else class="profile-empty-line">未挂载工具</p>
          </div>

          <div class="profile-section">
            <span class="profile-section__label">
              Channels
              <span class="mono">{{ profile.channels.length }}</span>
            </span>
            <div v-if="profile.channels.length > 0" class="chip-row">
              <span v-for="channel in profile.channels" :key="channel" class="chip">
                {{ channel }}
              </span>
            </div>
            <p v-else class="profile-empty-line">未声明渠道</p>
          </div>

          <footer class="profile-card__foot">
            <span class="profile-foot-note">一次性调用 · 无会话 ID</span>
            <button type="button" class="btn btn--accent-ghost btn--sm" @click="openInvoke(profile)">
              <AppIcon name="play" size="12" />
              试运行
            </button>
          </footer>
        </article>
      </div>
    </template>

    <dialog
      ref="dialog"
      class="dialog invoke-dialog"
      aria-labelledby="invoke-dialog-title"
      @cancel.prevent="closeInvoke"
      @click="onDialogBackdrop"
    >
      <div class="dialog__head">
        <div>
          <div class="eyebrow">One-shot invoke</div>
          <h2 id="invoke-dialog-title" class="dialog__title">
            试运行 <span class="mono">{{ invokeTarget?.name }}</span>
          </h2>
        </div>
        <button
          ref="closeButton"
          type="button"
          class="icon-button"
          aria-label="关闭试运行对话框"
          :disabled="invokePending"
          @click="closeInvoke"
        >
          <AppIcon name="close" size="16" />
        </button>
      </div>

      <div class="dialog__body invoke-body">
        <p class="invoke-note">
          <AppIcon name="info" size="14" />
          一次性调用，不保留会话，不返回会话 ID。
        </p>

        <dl v-if="invokeTarget" class="invoke-context">
          <div>
            <dt>Agent</dt>
            <dd class="mono">{{ invokeTarget.name }}</dd>
          </div>
          <div>
            <dt>Model</dt>
            <dd class="mono">{{ invokeTarget.model || invokeTarget.provider || '—' }}</dd>
          </div>
        </dl>

        <div class="form-field">
          <label class="form-label" for="invoke-message">
            消息
            <span class="char-count mono" :class="{ 'char-count--over': overLimit }">
              {{ charCount.toLocaleString('zh-CN') }} / {{ MAX_MESSAGE_CHARS.toLocaleString('zh-CN') }}
            </span>
          </label>
          <textarea
            id="invoke-message"
            ref="messageField"
            v-model="invokeMessage"
            class="textarea invoke-input"
            placeholder="例如：用一句话介绍你自己"
            :disabled="invokePending"
            :aria-invalid="overLimit || Boolean(invokeFieldError)"
            @input="invokeFieldError = ''"
            @keydown="handleMessageKeydown"
          />
        </div>

        <p v-if="invokeFieldError" class="field-error">{{ invokeFieldError }}</p>

        <div v-if="invokePending" class="invoke-progress" aria-live="polite">
          <div class="loading-rule" />
          <span>Agent 处理中，最长等待约 60 秒。</span>
        </div>

        <div v-if="invokeError" class="inline-alert inline-alert--danger">
          <AppIcon name="alert" size="15" />
          <span class="invoke-error__text">
            {{ invokeError.message }}
            <span class="inline-alert__trace">traceId: {{ invokeError.traceId || '—' }}</span>
          </span>
          <button type="button" class="inline-alert__action" @click="openDiagnostics(invokeError)">诊断</button>
        </div>

        <section v-if="invokeReply" class="invoke-reply" aria-live="polite">
          <div class="invoke-reply__head">
            <span class="eyebrow">Reply</span>
            <span class="invoke-reply__hint">本次调用不产生会话</span>
          </div>
          <p class="invoke-reply__text">{{ invokeReply }}</p>
        </section>
      </div>

      <div class="dialog__footer">
        <span class="invoke-footer-note">Ctrl / ⌘ + Enter 发送</span>
        <button type="button" class="btn btn--secondary" :disabled="invokePending" @click="closeInvoke">
          关闭
        </button>
        <button type="button" class="btn btn--primary" :disabled="!canInvoke" @click="submitInvoke">
          <AppIcon name="play" size="12" />
          {{ invokePending ? '调用中…' : '发送调用' }}
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
