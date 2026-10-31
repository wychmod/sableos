<script setup lang="ts">
import { computed, nextTick, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import CopyButton from '../components/CopyButton.vue'
import EmptyState from '../components/EmptyState.vue'
import ErrorState from '../components/ErrorState.vue'
import PageHeader from '../components/PageHeader.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'
import StatusChip from '../components/StatusChip.vue'
import { ApiError, normalizeError } from '../api/client'
import {
  archiveSession,
  createSession,
  getSession,
  listSessions,
  sendSessionMessage,
} from '../api/endpoints'
import { loadProfiles, profiles, profilesError, profilesLoading } from '../composables/useProfiles'
import { refreshTick } from '../composables/useRefreshSignal'
import { openDiagnostics, pushToast, reportError } from '../composables/useUiFeedback'
import type { SessionMessage, SessionSummary, SessionView } from '../types/api'
import { formatDateTime } from '../utils/format'

const MAX_HISTORY = 100
const MAX_MESSAGE_CHARS = 32_768
const RECENT_KEY = 'sableos.admin.recentSessions.v1'

const route = useRoute()
const router = useRouter()

const sessionInput = ref('')
const sessionInputError = ref('')
const recentIds = ref<string[]>([])
const serverSessions = ref<SessionSummary[]>([])
const sessionsLoading = ref(false)
const sessionsError = shallowRef<ApiError | null>(null)
const currentSession = shallowRef<SessionView | null>(null)
const sessionLoading = ref(false)
const sessionError = shallowRef<ApiError | null>(null)

const createProfile = ref('')
const createUserId = ref('')
const creating = ref(false)
const createError = shallowRef<ApiError | null>(null)
const createNotice = ref('')

const messageDraft = ref('')
const sending = ref(false)
const sendError = shallowRef<ApiError | null>(null)

const archiveOpen = ref(false)
const archiving = ref(false)
const archiveError = shallowRef<ApiError | null>(null)

const sessionInputField = ref<HTMLInputElement | null>(null)
const createProfileField = ref<HTMLSelectElement | null>(null)
const messageField = ref<HTMLTextAreaElement | null>(null)
const timeline = ref<HTMLElement | null>(null)
const knownSessionIds = new Set<string>()

const messages = computed(() => currentSession.value?.messages ?? [])
const charCount = computed(() => messageDraft.value.length)
const overLimit = computed(() => charCount.value > MAX_MESSAGE_CHARS)
const canSend = computed(
  () =>
    Boolean(currentSession.value) &&
    !sending.value &&
    messageDraft.value.trim().length > 0 &&
    !overLimit.value,
)

const statusLabel = computed(() =>
  currentSession.value?.status === 'archived' ? 'archived · 已归档' : 'active · 活跃',
)
const statusTone = computed(() =>
  currentSession.value?.status === 'archived' ? 'warning' : 'success',
)

function loadRecentIds(): void {
  try {
    const raw = localStorage.getItem(RECENT_KEY)
    if (!raw) return
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return
    recentIds.value = parsed
      .filter((value): value is string => typeof value === 'string' && value.length > 0)
      .slice(0, 8)
  } catch {
    recentIds.value = []
  }
}

function persistRecentIds(): void {
  try {
    localStorage.setItem(RECENT_KEY, JSON.stringify(recentIds.value.slice(0, 8)))
  } catch {
    // The console remains fully usable when storage is unavailable.
  }
}

function rememberSession(id: string): void {
  knownSessionIds.add(id)
  recentIds.value = [id, ...recentIds.value.filter((value) => value !== id)].slice(0, 8)
  persistRecentIds()
}

async function loadServerSessions(silent = false): Promise<void> {
  if (!silent) sessionsLoading.value = true
  sessionsError.value = null
  try {
    serverSessions.value = await listSessions()
  } catch (error) {
    sessionsError.value = normalizeError(error)
  } finally {
    sessionsLoading.value = false
  }
}

async function scrollTimelineToBottom(): Promise<void> {
  await nextTick()
  if (timeline.value) {
    timeline.value.scrollTop = timeline.value.scrollHeight
  }
}

async function openById(rawId: string = sessionInput.value): Promise<void> {
  const id = rawId.trim()
  if (!id) {
    sessionInputError.value = '请输入完整的会话 ID。'
    sessionInputField.value?.focus()
    return
  }

  sessionInput.value = id
  sessionInputError.value = ''
  sessionLoading.value = true
  sessionError.value = null
  sendError.value = null
  archiveError.value = null

  try {
    const session = await getSession(id)
    currentSession.value = session
    messageDraft.value = ''
    rememberSession(session.id)
    void router.replace({ name: 'sessions', query: { id: session.id } })
    await scrollTimelineToBottom()
  } catch (error) {
    const apiError = normalizeError(error)
    currentSession.value = null
    sessionError.value = apiError
    reportError(apiError, { toast: true })
  } finally {
    sessionLoading.value = false
  }
}

async function refreshCurrentSession(silent = false): Promise<boolean> {
  if (!currentSession.value) return false
  const id = currentSession.value.id
  try {
    const session = await getSession(id)
    currentSession.value = session
    rememberSession(session.id)
    await scrollTimelineToBottom()
    if (!silent) pushToast('会话已刷新', 'success')
    return true
  } catch (error) {
    reportError(normalizeError(error), { toast: true })
    return false
  }
}

async function createSessionAndOpen(): Promise<void> {
  if (!createProfile.value) {
    createError.value = new ApiError('请选择 profile。')
    return
  }

  creating.value = true
  createError.value = null
  createNotice.value = ''
  try {
    const session = await createSession({
      profile: createProfile.value,
      userId: createUserId.value.trim() || undefined,
    })
    const reused =
      knownSessionIds.has(session.id) ||
      recentIds.value.includes(session.id) ||
      session.messages.length > 0

    currentSession.value = session
    sessionError.value = null
    sessionInput.value = session.id
    messageDraft.value = ''
    rememberSession(session.id)
    createNotice.value = reused
      ? `已复用既有会话，载入 ${session.messages.length} 条历史。`
      : '会话已创建并打开。'

    void router.replace({ name: 'sessions', query: { id: session.id } })
    pushToast(reused ? '已复用既有会话' : '会话已创建', 'success')
    await scrollTimelineToBottom()
    await loadServerSessions(true)
    await nextTick()
    messageField.value?.focus()
  } catch (error) {
    const apiError = normalizeError(error)
    createError.value = apiError
    reportError(apiError, { toast: true })
  } finally {
    creating.value = false
  }
}

async function sendMessage(): Promise<void> {
  if (!canSend.value || !currentSession.value) return
  const content = messageDraft.value
  const sessionId = currentSession.value.id
  sending.value = true
  sendError.value = null

  try {
    await sendSessionMessage(sessionId, content)
    messageDraft.value = ''
    const refreshed = await refreshCurrentSession(true)
    await loadServerSessions(true)
    if (refreshed) {
      pushToast('消息已发送，会话历史已更新', 'success')
    } else {
      pushToast('消息已发送，但历史刷新失败', 'error')
    }
  } catch (error) {
    const apiError = normalizeError(error)
    sendError.value = apiError
    reportError(apiError, { toast: true })
  } finally {
    sending.value = false
  }
}

async function confirmArchive(): Promise<void> {
  if (!currentSession.value) return
  archiving.value = true
  archiveError.value = null

  try {
    await archiveSession(currentSession.value.id)
    currentSession.value = { ...currentSession.value, status: 'archived' }
    archiveOpen.value = false
    pushToast('会话已归档', 'success')
    await refreshCurrentSession(true)
    await loadServerSessions(true)
  } catch (error) {
    const apiError = normalizeError(error)
    archiveError.value = apiError
    reportError(apiError, { toast: true })
  } finally {
    archiving.value = false
  }
}

function clearSessionView(): void {
  currentSession.value = null
  sessionError.value = null
  sessionInput.value = ''
  sessionInputError.value = ''
  messageDraft.value = ''
  sendError.value = null
  void router.replace({ name: 'sessions' })
}

async function startNewSession(): Promise<void> {
  clearSessionView()
  await nextTick()
  createProfileField.value?.focus()
}

async function focusSessionInput(): Promise<void> {
  await nextTick()
  sessionInputField.value?.focus()
}

function handleComposerKeydown(event: KeyboardEvent): void {
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
    event.preventDefault()
    if (canSend.value) void sendMessage()
  }
}

function roleLabel(role: SessionMessage['role']): string {
  if (role === 'USER') return '用户输入'
  if (role === 'ASSISTANT') return 'Agent 回复'
  return '工具结果'
}

function roleMark(role: SessionMessage['role']): string {
  if (role === 'USER') return 'U'
  if (role === 'ASSISTANT') return 'A'
  return 'T'
}

function toolStatusLabel(message: SessionMessage): string {
  if (message.success === true) return '执行成功'
  if (message.success === false) return '执行失败'
  return '状态未知'
}

function toolStatusTone(message: SessionMessage): 'success' | 'failure' | 'neutral' {
  if (message.success === true) return 'success'
  if (message.success === false) return 'failure'
  return 'neutral'
}

watch(
  profiles,
  (list) => {
    if (!createProfile.value && list.length > 0) {
      createProfile.value = list[0]?.name ?? ''
    }
  },
  { immediate: true },
)

watch(refreshTick, async () => {
  await loadProfiles(true)
  await loadServerSessions(true)
  if (currentSession.value && !sending.value) {
    await refreshCurrentSession(true)
  }
})

onMounted(async () => {
  loadRecentIds()
  await loadProfiles()
  await loadServerSessions()

  const queryId = typeof route.query.id === 'string' ? route.query.id : ''
  if (queryId) {
    sessionInput.value = queryId
    await openById(queryId)
  } else if (route.query.action === 'new') {
    await nextTick()
    createProfileField.value?.focus()
  }
})
</script>

<template>
  <div class="page-shell page-stack sessions-page">
    <PageHeader
      eyebrow="Session workspace / 02"
      title="会话工作台"
      description="打开既有会话或创建新会话，查看最近历史并向 Agent 发送消息。"
    >
      <template #actions>
        <span class="history-note">历史接口最多返回最近 {{ MAX_HISTORY }} 条</span>
      </template>
    </PageHeader>

    <div class="session-layout">
      <aside class="panel session-rail" aria-label="会话入口">
        <section class="rail-section">
          <div class="rail-section__head">
            <div>
              <span class="eyebrow">Open by id</span>
              <h2 class="rail-title">打开会话</h2>
            </div>
            <span class="rail-index">01</span>
          </div>

          <form class="rail-form" @submit.prevent="openById()">
            <div class="form-field">
              <label class="form-label" for="session-id-input">会话 ID</label>
              <input
                id="session-id-input"
                ref="sessionInputField"
                v-model="sessionInput"
                class="input mono"
                placeholder="web:alice:default"
                autocomplete="off"
                :aria-invalid="Boolean(sessionInputError)"
                aria-describedby="session-id-help session-id-error"
                @input="sessionInputError = ''"
              />
            </div>
            <button type="submit" class="btn btn--primary rail-submit">
              <AppIcon name="arrow-right" size="14" />
              打开会话
            </button>
          </form>
          <p id="session-id-help" class="field-help">
            路径参数会自动编码，支持包含冒号的完整 ID。
          </p>
          <p v-if="sessionInputError" id="session-id-error" class="field-error">
            {{ sessionInputError }}
          </p>

          <div class="recent-block">
            <div class="recent-block__head">
              <div>
                <span class="eyebrow">Recent sessions</span>
                <span class="recent-block__count">最近 {{ serverSessions.length }} 条</span>
              </div>
              <button
                type="button"
                class="icon-button"
                aria-label="刷新会话列表"
                title="刷新会话列表"
                :disabled="sessionsLoading"
                @click="loadServerSessions()"
              >
                <AppIcon name="refresh" size="14" />
              </button>
            </div>

            <div v-if="sessionsLoading" class="recent-loading" aria-label="会话列表加载中">
              <SkeletonBlock v-for="index in 3" :key="index" width="100%" height="38px" />
            </div>
            <div v-else-if="sessionsError" class="inline-alert inline-alert--danger rail-alert">
              <AppIcon name="alert" size="15" />
              <span>
                {{ sessionsError.message }}
                <button type="button" class="inline-alert__action" @click="loadServerSessions()">
                  重试
                </button>
              </span>
            </div>
            <p v-else-if="serverSessions.length === 0" class="field-help">
              还没有会话，创建一条后这里会出现。
            </p>
            <div v-else class="recent-list">
              <button
                v-for="session in serverSessions"
                :key="session.id"
                type="button"
                class="recent-item"
                :class="{ 'recent-item--active': currentSession?.id === session.id }"
                :title="session.id"
                @click="openById(session.id)"
              >
                <span class="recent-item__body">
                  <span class="recent-item__id mono">{{ session.id }}</span>
                  <span class="recent-item__meta">
                    {{ session.profileName }} · {{ session.messageCount }} 条 ·
                    {{ formatDateTime(session.lastActiveAt) }}
                  </span>
                </span>
                <AppIcon name="chevron-right" size="13" />
              </button>
            </div>
          </div>
        </section>

        <div class="rail-divider" />

        <section class="rail-section">
          <div class="rail-section__head">
            <div>
              <span class="eyebrow">Create session</span>
              <h2 class="rail-title">新建会话</h2>
            </div>
            <span class="rail-index">02</span>
          </div>

          <div v-if="profilesError" class="inline-alert inline-alert--danger rail-alert">
            <AppIcon name="alert" size="15" />
            <span>
              {{ profilesError.message }}
              <span class="inline-alert__trace">traceId: {{ profilesError.traceId || '—' }}</span>
              <button type="button" class="inline-alert__action" @click="loadProfiles(true)">重试</button>
            </span>
          </div>

          <form class="rail-form" @submit.prevent="createSessionAndOpen">
            <div class="form-field">
              <label class="form-label" for="create-profile">Profile</label>
              <select
                id="create-profile"
                ref="createProfileField"
                v-model="createProfile"
                class="select"
                :disabled="creating || profilesLoading || profiles.length === 0"
              >
                <option v-if="profilesLoading" value="" disabled>正在加载 Agent…</option>
                <option v-else-if="profiles.length === 0" value="" disabled>尚未加载任何 Agent</option>
                <option v-for="profile in profiles" :key="profile.name" :value="profile.name">
                  {{ profile.name }}
                </option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-label" for="create-user-id">
                userId
                <span class="optional-label">可选</span>
              </label>
              <input
                id="create-user-id"
                v-model="createUserId"
                class="input mono"
                placeholder="anonymous"
                autocomplete="off"
                :disabled="creating"
              />
            </div>
            <button
              type="submit"
              class="btn btn--secondary rail-submit"
              :disabled="creating || !createProfile"
            >
              <AppIcon name="plus" size="14" />
              {{ creating ? '正在创建…' : '创建并打开' }}
            </button>
          </form>

          <p class="field-help">同一 profile + userId 会复用既有会话。</p>
          <p v-if="createNotice" class="create-notice">{{ createNotice }}</p>
          <div v-if="createError" class="inline-alert inline-alert--danger rail-alert">
            <AppIcon name="alert" size="15" />
            <span>
              {{ createError.message }}
              <span class="inline-alert__trace">traceId: {{ createError.traceId || '—' }}</span>
            </span>
            <button type="button" class="inline-alert__action" @click="openDiagnostics(createError)">
              诊断
            </button>
          </div>
        </section>
      </aside>

      <section class="panel session-workspace">
        <div v-if="sessionLoading" class="workspace-loading" aria-label="会话加载中">
          <SkeletonBlock width="42%" height="18px" />
          <SkeletonBlock width="72%" height="28px" />
          <div class="workspace-loading__meta">
            <SkeletonBlock v-for="index in 5" :key="index" width="100%" height="48px" />
          </div>
          <SkeletonBlock width="100%" height="180px" />
        </div>

        <div v-else-if="sessionError?.code === 404" class="error-state session-missing" role="alert">
          <span class="empty-state__mark"><AppIcon name="alert" size="18" /></span>
          <h2 class="error-state__title">会话不存在</h2>
          <p class="error-state__description">{{ sessionError.message }}</p>
          <p class="error-state__trace">traceId: {{ sessionError.traceId || '—' }}</p>
          <div class="button-row">
            <button type="button" class="btn btn--secondary" @click="openById()">重新打开</button>
            <button type="button" class="btn btn--ghost" @click="clearSessionView">清空当前会话</button>
            <button type="button" class="btn btn--primary" @click="startNewSession">新建会话</button>
          </div>
        </div>

        <ErrorState
          v-else-if="sessionError"
          title="无法加载会话"
          :error="sessionError"
          @retry="openById()"
          @inspect="openDiagnostics(sessionError)"
        />

        <EmptyState
          v-else-if="!currentSession"
          title="没有打开的会话"
          description="从左侧最近的会话里选一条，或输入完整会话 ID / 新建会话加载运行时上下文。"
          action-label="输入会话 ID"
          icon="sessions"
          @action="focusSessionInput"
        />

        <template v-else>
          <header class="session-head">
            <div class="session-head__top">
              <div class="session-head__caption">
                <span class="eyebrow">Session runtime</span>
                <StatusChip :label="statusLabel" :tone="statusTone" />
              </div>
              <div class="button-row">
                <button
                  v-if="currentSession.status === 'active'"
                  type="button"
                  class="btn btn--danger btn--sm"
                  @click="archiveOpen = true"
                >
                  <AppIcon name="archive" size="14" />
                  归档
                </button>
                <button
                  type="button"
                  class="icon-button"
                  aria-label="刷新当前会话"
                  title="刷新当前会话"
                  @click="refreshCurrentSession()"
                >
                  <AppIcon name="refresh" size="15" />
                </button>
              </div>
            </div>

            <div class="session-id-line">
              <code>{{ currentSession.id }}</code>
              <CopyButton :value="currentSession.id" label="复制会话 ID" />
            </div>

            <dl class="session-meta">
              <div class="session-meta__item">
                <dt>Profile</dt>
                <dd class="mono">{{ currentSession.profileName }}</dd>
              </div>
              <div class="session-meta__item">
                <dt>Channel</dt>
                <dd class="mono">{{ currentSession.channel }}</dd>
              </div>
              <div class="session-meta__item">
                <dt>UserId</dt>
                <dd class="mono">{{ currentSession.userId }}</dd>
              </div>
              <div class="session-meta__item">
                <dt>Created</dt>
                <dd>{{ formatDateTime(currentSession.createdAt) }}</dd>
              </div>
              <div class="session-meta__item">
                <dt>Last active</dt>
                <dd>{{ formatDateTime(currentSession.lastActiveAt) }}</dd>
              </div>
            </dl>
          </header>

          <div v-if="currentSession.status === 'archived'" class="inline-alert inline-alert--warning archive-banner">
            <AppIcon name="archive" size="16" />
            <span>该会话已归档。归档是软归档，历史仍可查看；后端不会阻止继续发消息。</span>
          </div>

          <div class="timeline-block">
            <div class="timeline-head">
              <div class="timeline-head__title">
                <span class="eyebrow">Transcript</span>
                <span class="timeline-head__count mono">{{ messages.length }} 条</span>
              </div>
              <span class="timeline-head__limit">仅展示最近 {{ MAX_HISTORY }} 条</span>
            </div>

            <div ref="timeline" class="session-messages">
              <div v-if="messages.length === 0" class="timeline-empty">
                <span class="empty-state__mark"><AppIcon name="terminal" size="18" /></span>
                <h3 class="empty-state__title">会话还没有消息</h3>
                <p class="empty-state__description">在下方输入内容，Agent 的回复与工具结果会依次出现在这里。</p>
              </div>

              <article
                v-for="(message, index) in messages"
                :key="`${message.role}-${index}`"
                class="message-row"
                :class="`message-row--${message.role.toLowerCase()}`"
              >
                <div class="message-role">
                  <span class="message-role__mark mono">{{ roleMark(message.role) }}</span>
                  <span class="message-role__label">{{ roleLabel(message.role) }}</span>
                </div>

                <div class="message-body">
                  <template v-if="message.role !== 'TOOL'">
                    <p
                      v-if="message.content !== null && message.content !== ''"
                      class="message-text"
                    >
                      {{ message.content }}
                    </p>
                    <p v-else-if="message.role === 'ASSISTANT'" class="message-placeholder">
                      <AppIcon name="pulse" size="14" />
                      模型发起了工具调用
                    </p>
                    <p v-else class="message-placeholder">（空消息）</p>
                  </template>

                  <div
                    v-else
                    class="tool-result"
                    :class="`tool-result--${message.success === true ? 'success' : message.success === false ? 'failure' : 'neutral'}`"
                  >
                    <div class="tool-result__head">
                      <span class="tool-result__name mono">{{ message.toolName || 'unknown_tool' }}</span>
                      <StatusChip :label="toolStatusLabel(message)" :tone="toolStatusTone(message)" />
                    </div>
                    <pre class="tool-result__content">{{ message.content || '（无工具输出）' }}</pre>
                  </div>
                </div>
              </article>
            </div>
          </div>

          <footer class="session-composer">
            <div class="composer-head">
              <label class="form-label" for="session-message-input">发送消息</label>
              <span class="char-count mono" :class="{ 'char-count--over': overLimit }">
                {{ charCount.toLocaleString('zh-CN') }} / {{ MAX_MESSAGE_CHARS.toLocaleString('zh-CN') }}
              </span>
            </div>
            <textarea
              id="session-message-input"
              ref="messageField"
              v-model="messageDraft"
              class="textarea composer-input"
              placeholder="输入要发送给 Agent 的消息…"
              :disabled="sending"
              :aria-invalid="overLimit"
              aria-describedby="message-limit-help"
              @keydown="handleComposerKeydown"
            />
            <p v-if="overLimit" class="field-error">
              消息超过 32,768 字符上限，请删减后再发送。
            </p>
            <div v-if="sending" class="composer-progress" aria-live="polite">
              <div class="loading-rule" />
              <span>Agent 处理中，最长等待约 60 秒。</span>
            </div>
            <div v-if="sendError" class="inline-alert inline-alert--danger">
              <AppIcon name="alert" size="15" />
              <span>
                {{ sendError.message }}
                <span class="inline-alert__trace">traceId: {{ sendError.traceId || '—' }}</span>
              </span>
              <button type="button" class="inline-alert__action" @click="openDiagnostics(sendError)">
                诊断
              </button>
            </div>
            <div class="composer-footer">
              <span id="message-limit-help" class="field-help">Ctrl / ⌘ + Enter 发送</span>
              <button type="button" class="btn btn--primary" :disabled="!canSend" @click="sendMessage">
                <AppIcon name="send" size="14" />
                {{ sending ? 'Agent 处理中' : '发送' }}
              </button>
            </div>
          </footer>
        </template>
      </section>
    </div>

    <ConfirmDialog
      :open="archiveOpen"
      title="归档当前会话？"
      description="归档是软归档，会话仍可查看；后端不会阻止继续发消息。"
      confirm-label="确认归档"
      :pending="archiving"
      danger
      @cancel="archiveOpen = false"
      @confirm="confirmArchive"
    >
      <div v-if="archiveError" class="inline-alert inline-alert--danger archive-error">
        <AppIcon name="alert" size="15" />
        <span>
          {{ archiveError.message }}
          <span class="inline-alert__trace">traceId: {{ archiveError.traceId || '—' }}</span>
        </span>
        <button type="button" class="inline-alert__action" @click="openDiagnostics(archiveError)">
          诊断
        </button>
      </div>
    </ConfirmDialog>
  </div>
</template>

<style scoped>
.sessions-page {
  animation: page-in var(--duration-base) var(--ease-out);
}

@keyframes page-in {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
}

.history-note {
  color: var(--text-faint);
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.03em;
  white-space: nowrap;
}

.session-layout {
  display: grid;
  align-items: start;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: var(--space-4);
}

.session-rail {
  display: flex;
  min-width: 0;
  flex-direction: column;
  overflow: hidden;
}

.rail-section {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-5);
}

.rail-section__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
}

.rail-title {
  margin-top: 2px;
  color: var(--text);
  font-size: var(--fs-16);
  font-weight: 600;
  letter-spacing: -0.01em;
  line-height: var(--lh-24);
}

.rail-index {
  color: var(--text-faint);
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.08em;
  white-space: nowrap;
}

.rail-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.rail-submit {
  width: 100%;
  height: 36px;
}

.rail-divider {
  height: 1px;
  background: var(--border);
}

.rail-alert {
  align-items: flex-start;
}

.optional-label {
  color: var(--text-faint);
  font-size: var(--fs-11);
  font-weight: 400;
}

.create-notice {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  padding: 8px 10px;
  border: 1px solid rgba(34, 197, 94, 0.28);
  border-radius: var(--radius-sm);
  background: var(--success-soft);
  color: #86efac;
  font-size: var(--fs-12);
  line-height: var(--lh-18);
  overflow-wrap: anywhere;
}

.recent-block {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin-top: var(--space-1);
  padding-top: var(--space-3);
  border-top: 1px solid var(--border-subtle);
}

.recent-block__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
}

.recent-block__count {
  color: var(--text-faint);
  font-family: var(--font-mono);
  font-size: 10px;
}

.recent-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.recent-item {
  position: relative;
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  padding: 7px 10px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  text-align: left;
  transition:
    background-color var(--duration-fast) var(--ease-out),
    border-color var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out);
}

.recent-item:hover {
  background: var(--hover);
  color: var(--text);
}

.recent-item--active {
  border-color: var(--border-strong);
  background: #141414;
  color: var(--text);
}

.recent-item--active::before {
  position: absolute;
  top: 7px;
  bottom: 7px;
  left: 0;
  width: 2px;
  border-radius: var(--radius-pill);
  background: var(--accent);
  content: '';
}

.recent-item__body {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 1px;
}

.recent-item__id {
  min-width: 0;
  overflow: hidden;
  font-size: var(--fs-11);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recent-item__meta {
  overflow: hidden;
  color: var(--text-faint);
  font-size: 10px;
  line-height: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recent-loading {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.session-workspace {
  display: flex;
  min-width: 0;
  min-height: 520px;
  flex-direction: column;
}

.session-workspace > .empty-state,
.session-workspace > .error-state {
  flex: 1 1 auto;
}

.workspace-loading {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  padding: var(--space-5);
}

.workspace-loading__meta {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.session-missing {
  min-height: 360px;
}

.session-head {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-5);
  border-bottom: 1px solid var(--border);
}

.session-head__top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.session-head__caption {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.session-id-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: 9px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #080808;
}

.session-id-line code {
  min-width: 0;
  color: var(--text);
  font-family: var(--font-mono);
  font-size: var(--fs-12);
  line-height: var(--lh-18);
  overflow-wrap: anywhere;
}

.session-meta {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(132px, 1fr));
  gap: var(--space-3) var(--space-5);
}

.session-meta__item {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 1px;
}

.session-meta__item dt {
  color: var(--text-faint);
  font-size: 10px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.session-meta__item dd {
  color: var(--text-secondary);
  font-size: var(--fs-12);
  line-height: var(--lh-18);
  overflow-wrap: anywhere;
}

.archive-banner {
  margin: var(--space-4) var(--space-5) 0;
}

.timeline-block {
  display: flex;
  min-height: 0;
  flex-direction: column;
}

.timeline-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-4) var(--space-5) var(--space-3);
}

.timeline-head__title {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
}

.timeline-head__count {
  color: var(--text-secondary);
  font-size: var(--fs-12);
}

.timeline-head__limit {
  color: var(--text-faint);
  font-size: var(--fs-11);
  white-space: nowrap;
}

.session-messages {
  display: flex;
  max-height: 460px;
  flex-direction: column;
  overflow-y: auto;
  padding: 0 var(--space-5) var(--space-4);
  scrollbar-gutter: stable;
}

.timeline-empty {
  display: flex;
  min-height: 220px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  padding: var(--space-6);
  text-align: center;
}

.message-row {
  display: grid;
  grid-template-columns: 26px minmax(0, 1fr);
  gap: var(--space-3);
  padding: var(--space-4) 0;
  border-bottom: 1px solid var(--border-subtle);
}

.message-row:last-child {
  border-bottom: 0;
}

.message-role {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}

.message-role__mark {
  display: grid;
  width: 22px;
  height: 22px;
  border: 1px solid var(--border-strong);
  border-radius: 3px;
  background: #080808;
  color: var(--text-muted);
  font-size: 10px;
  place-items: center;
}

.message-row--user .message-role__mark {
  border-color: #4a4a4a;
  color: var(--text);
}

.message-row--assistant .message-role__mark {
  border-color: var(--accent-border);
  background: var(--accent-soft);
  color: var(--accent);
}

.message-role__label {
  color: var(--text-faint);
  font-size: 9px;
  letter-spacing: 0.04em;
  white-space: nowrap;
}

.message-body {
  min-width: 0;
  padding-top: 1px;
}

.message-text {
  color: var(--text);
  font-size: var(--fs-14);
  line-height: var(--lh-22);
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.message-row--assistant .message-text {
  color: #ededed;
}

.message-placeholder {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--text-muted);
  font-size: var(--fs-12);
  line-height: var(--lh-18);
}

.tool-result {
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #080808;
}

.tool-result--success {
  border-color: rgba(34, 197, 94, 0.26);
}

.tool-result--failure {
  border-color: rgba(239, 68, 68, 0.28);
}

.tool-result__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: 7px 10px;
  border-bottom: 1px solid var(--border-subtle);
  background: #0b0b0b;
}

.tool-result__name {
  min-width: 0;
  color: var(--text);
  font-size: var(--fs-12);
  overflow-wrap: anywhere;
}

.tool-result__content {
  max-height: 260px;
  margin: 0;
  overflow: auto;
  padding: var(--space-3);
  color: var(--text-secondary);
  font-family: var(--font-mono);
  font-size: var(--fs-12);
  line-height: var(--lh-18);
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.session-composer {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-4) var(--space-5) var(--space-5);
  border-top: 1px solid var(--border);
  background: #0a0a0a;
}

.composer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}

.composer-input {
  min-height: 84px;
}

.char-count {
  color: var(--text-faint);
  font-family: var(--font-mono);
  font-size: var(--fs-11);
  white-space: nowrap;
}

.char-count--over {
  color: #fca5a5;
}

.composer-progress {
  display: flex;
  flex-direction: column;
  gap: 6px;
  color: var(--text-muted);
  font-size: var(--fs-12);
}

.composer-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  flex-wrap: wrap;
  margin-top: var(--space-1);
}

.archive-error {
  margin-top: var(--space-3);
}

@media (max-width: 1199px) {
  .session-layout {
    grid-template-columns: 280px minmax(0, 1fr);
  }
}

@media (max-width: 900px) {
  .session-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .session-workspace {
    min-height: 360px;
  }

  .session-messages {
    max-height: 420px;
  }
}

@media (max-width: 767px) {
  .session-head,
  .session-composer {
    padding-right: var(--space-4);
    padding-left: var(--space-4);
  }

  .timeline-head {
    padding-right: var(--space-4);
    padding-left: var(--space-4);
  }

  .session-messages {
    padding-right: var(--space-4);
    padding-left: var(--space-4);
  }

  .archive-banner {
    margin-right: var(--space-4);
    margin-left: var(--space-4);
  }

  .message-role__label {
    display: none;
  }

  .session-meta {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
