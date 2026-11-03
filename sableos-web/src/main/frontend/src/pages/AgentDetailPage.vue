<script setup lang="ts">
import { computed, onMounted, ref, shallowRef, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import EmptyState from '../components/EmptyState.vue'
import ErrorState from '../components/ErrorState.vue'
import FileTreeNode from '../components/FileTreeNode.vue'
import PageHeader from '../components/PageHeader.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'
import { normalizeError, type ApiError } from '../api/client'
import {
  deleteAgent,
  generateAgentFiles,
  getAgent,
  getAgentMemory,
  getAgentSession,
  getWorkspaceFile,
  saveAgentFiles,
  sendAgentSessionMessage,
  updateAgent,
  writeWorkspaceFile,
} from '../api/endpoints'
import { openDiagnostics, pushToast, reportError } from '../composables/useUiFeedback'
import { t } from '../i18n'
import type { AgentDetailView, FileNode, SessionView } from '../types/api'

type TabKey = 'basic' | 'generate' | 'files' | 'session' | 'memory'

// 必须是 computed：普通数组只在模块加载时求值一次，切语言后页签不会跟着变。
const TABS = computed<{ key: TabKey; label: string }[]>(() => [
  { key: 'basic', label: t('基本信息') },
  { key: 'generate', label: t('生成') },
  { key: 'files', label: t('文件') },
  { key: 'session', label: t('会话') },
  { key: 'memory', label: t('记忆') },
])

const MAX_MESSAGE_CHARS = 32_768
const MAX_DESCRIPTION_CHARS = 2000

const route = useRoute()
const router = useRouter()

const agentName = computed(() => String(route.params.name ?? ''))

const detail = shallowRef<AgentDetailView | null>(null)
const loading = ref(false)
/** 当前正在加载的目标名，用于区分「重复请求」与「切换目标」两种情况。 */
let loadingTarget = ''
const loadError = shallowRef<ApiError | null>(null)
const activeTab = ref<TabKey>('basic')

const agent = computed(() => detail.value?.agent ?? null)
const agentFiles = computed<FileNode[]>(() => detail.value?.files ?? [])

// ---------------------------------------------------------------- 载入

/**
 * 请求序号：每次发起加载自增。响应回来时若序号已过期，说明用户已经切到别的 Agent，
 * 这一份数据必须丢弃——否则会出现「标题是 b、内容是 a」。
 */
let loadToken = 0

async function load(force = false): Promise<void> {
  if (!agentName.value) return
  // 只在「同一目标的重入」时跳过；切换 Agent 必须放行，否则新目标永远不加载。
  // 判断依据是目标名不变而非 loading 标志，后者会让路由切换被当成重复请求吞掉。
  if (!force && loading.value && loadingTarget === agentName.value) return
  const target = agentName.value
  const token = ++loadToken
  loadingTarget = target
  loading.value = true
  loadError.value = null
  try {
    const result = await getAgent(target)
    if (token !== loadToken) return
    detail.value = result
  } catch (error) {
    if (token !== loadToken) return
    loadError.value = normalizeError(error)
    detail.value = null
  } finally {
    if (token === loadToken) {
      loading.value = false
    }
  }
}

// ---------------------------------------------------------------- 生成

const generateDescription = ref('')
const generatePending = ref(false)
const generated = ref<Record<string, string> | null>(null)
const generateError = shallowRef<ApiError | null>(null)
const savingGenerated = ref(false)

const canGenerate = computed(
  () =>
    !generatePending.value &&
    generateDescription.value.trim().length > 0 &&
    generateDescription.value.length <= MAX_DESCRIPTION_CHARS,
)

async function runGenerate(): Promise<void> {
  if (!canGenerate.value) return
  generateError.value = null
  generatePending.value = true
  generated.value = null
  try {
    const result = await generateAgentFiles(agentName.value, generateDescription.value.trim())
    generated.value = result.files
    pushToast('草稿已生成，请核对后保存', 'success')
  } catch (error) {
    const apiError = normalizeError(error)
    generateError.value = apiError
    reportError(apiError, { toast: false })
  } finally {
    generatePending.value = false
  }
}

async function saveGenerated(): Promise<void> {
  if (!generated.value || savingGenerated.value) return
  savingGenerated.value = true
  try {
    detail.value = await saveAgentFiles(agentName.value, generated.value)
    generated.value = null
    pushToast('已写入并生效', 'success')
    activeTab.value = 'files'
  } catch (error) {
    reportError(normalizeError(error), { toast: true })
  } finally {
    savingGenerated.value = false
  }
}

// ---------------------------------------------------------------- 文件

const selectedPath = ref('')
const fileContent = ref('')
const originalContent = ref('')
const fileLoading = ref(false)
const fileSaving = ref(false)
const fileError = shallowRef<ApiError | null>(null)

const fileDirty = computed(() => fileContent.value !== originalContent.value)
const selectedNode = computed(() => findNode(detail.value?.files ?? [], selectedPath.value))

function findNode(nodes: FileNode[], path: string): FileNode | null {
  for (const node of nodes) {
    if (node.path === path) return node
    const hit = findNode(node.children, path)
    if (hit) return hit
  }
  return null
}

async function openFile(node: FileNode): Promise<void> {
  if (node.directory) return
  if (fileDirty.value && !window.confirm(t('当前文件有未保存修改，确定切换？'))) return
  selectedPath.value = node.path
  fileLoading.value = true
  fileError.value = null
  try {
    const text = await getWorkspaceFile(node.path)
    fileContent.value = text
    originalContent.value = text
  } catch (error) {
    fileError.value = normalizeError(error)
  } finally {
    fileLoading.value = false
  }
}

async function saveFile(): Promise<void> {
  if (!selectedPath.value || fileSaving.value || !fileDirty.value) return
  fileSaving.value = true
  fileError.value = null
  try {
    await writeWorkspaceFile(selectedPath.value, fileContent.value)
    originalContent.value = fileContent.value
    pushToast(t('{path} 已保存并生效', { path: selectedPath.value }), 'success')
    await load(true)
  } catch (error) {
    const apiError = normalizeError(error)
    fileError.value = apiError
    reportError(apiError, { toast: false })
  } finally {
    fileSaving.value = false
  }
}

// ---------------------------------------------------------------- 会话

const session = shallowRef<SessionView | null>(null)
const sessionLoading = ref(false)
const sessionError = shallowRef<ApiError | null>(null)
const sessionInput = ref('')
const sessionSending = ref(false)

async function loadSession(): Promise<void> {
  sessionLoading.value = true
  sessionError.value = null
  try {
    session.value = await getAgentSession(agentName.value)
  } catch (error) {
    sessionError.value = normalizeError(error)
  } finally {
    sessionLoading.value = false
  }
}

async function sendSession(): Promise<void> {
  const content = sessionInput.value.trim()
  if (!content || sessionSending.value) return
  if (content.length > MAX_MESSAGE_CHARS) {
    reportError(new Error(t('消息超过 32,768 字符上限')), { toast: true })
    return
  }
  sessionSending.value = true
  try {
    await sendAgentSessionMessage(agentName.value, content)
    sessionInput.value = ''
    await loadSession()
  } catch (error) {
    reportError(normalizeError(error), { toast: true })
  } finally {
    sessionSending.value = false
  }
}

// ---------------------------------------------------------------- 记忆

const memoryText = ref('')
const memoryLoading = ref(false)
const memoryError = shallowRef<ApiError | null>(null)

async function loadMemory(): Promise<void> {
  memoryLoading.value = true
  memoryError.value = null
  try {
    const result = await getAgentMemory(agentName.value)
    memoryText.value = result.content
  } catch (error) {
    memoryError.value = normalizeError(error)
  } finally {
    memoryLoading.value = false
  }
}

// ---------------------------------------------------------------- 页签切换

watch(activeTab, (tab) => {
  if (tab === 'session' && !session.value) void loadSession()
  if (tab === 'memory') void loadMemory()
})

// ---------------------------------------------------------------- 删除

const deletePending = ref(false)

async function removeAgent(): Promise<void> {
  if (deletePending.value) return
  if (!window.confirm(t('删除 {name}？目录会移动到 archive/，可追溯。', { name: agentName.value }))) return
  deletePending.value = true
  try {
    await deleteAgent(agentName.value)
    pushToast('已删除并归档', 'success')
    void router.push('/agents')
  } catch (error) {
    reportError(normalizeError(error), { toast: true })
  } finally {
    deletePending.value = false
  }
}

watch(agentName, () => {
  // 使旧请求的响应失效：切换后即使旧响应晚到也不会写回
  loadToken++
  detail.value = null
  session.value = null
  memoryText.value = ''
  generated.value = null
  selectedPath.value = ''
  fileContent.value = ''
  originalContent.value = ''
  activeTab.value = 'basic'
  loadingTarget = ''
  loading.value = false
  void load()
})

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page-shell page-stack agent-detail">
    <PageHeader
      eyebrow="Agent detail / 03"
      :title="agent?.name ?? agentName"
      :description="agent?.description || t('该 Agent 未提供描述。')"
    >
      <template #actions>
        <RouterLink class="btn btn--secondary btn--sm" to="/agents">
          <AppIcon name="chevron-right" size="14" />
          {{ t('返回列表') }}
        </RouterLink>
        <button type="button" class="btn btn--danger-ghost btn--sm" :disabled="deletePending" @click="removeAgent">
          <AppIcon name="close" size="12" />
          {{ t('删除') }}
        </button>
      </template>
    </PageHeader>

    <div v-if="loading && !detail" class="detail-skeleton">
      <SkeletonBlock width="100%" height="160px" />
      <SkeletonBlock width="100%" height="240px" />
    </div>

    <ErrorState
      v-else-if="loadError"
      :title="t('Agent 详情加载失败')"
      :error="loadError"
      @retry="load(true)"
      @inspect="openDiagnostics(loadError)"
    />

    <template v-else-if="agent">
      <nav class="tab-bar" role="tablist">
        <button
          v-for="tab in TABS"
          :key="tab.key"
          type="button"
          role="tab"
          class="tab"
          :class="{ 'tab--active': activeTab === tab.key }"
          :aria-selected="activeTab === tab.key"
          @click="activeTab = tab.key"
        >
          {{ tab.label }}
        </button>
      </nav>

      <!-- 基本信息 -->
      <section v-if="activeTab === 'basic'" class="panel">
        <dl class="detail-grid">
          <div><dt>Provider</dt><dd class="mono">{{ agent.provider || '—' }}</dd></div>
          <div><dt>Model</dt><dd class="mono">{{ agent.model || '—' }}</dd></div>
          <div><dt>{{ t('工具') }}</dt><dd class="mono">{{ agent.tools.length }}</dd></div>
          <div><dt>{{ t('定时') }}</dt><dd class="mono">{{ agent.schedules.length }}</dd></div>
        </dl>

        <div v-if="agent.tools.length > 0" class="detail-block">
          <span class="detail-block__label">Tools</span>
          <div class="chip-row">
            <span v-for="tool in agent.tools" :key="tool" class="chip">{{ tool }}</span>
          </div>
        </div>

        <div v-if="agent.schedules.length > 0" class="detail-block">
          <span class="detail-block__label">{{ t('定时声明') }}</span>
          <table class="data-table">
            <thead>
              <tr><th>id</th><th>cron</th><th>zone</th><th>message</th></tr>
            </thead>
            <tbody>
              <tr v-for="(schedule, index) in agent.schedules" :key="index">
                <td class="mono">{{ schedule.id || '—' }}</td>
                <td class="mono">{{ schedule.cron || '—' }}</td>
                <td class="mono">{{ schedule.zone || '—' }}</td>
                <td>{{ schedule.message || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <p class="detail-hint">
          {{ t('要改正文、provider 或定时，请到「文件」页签编辑') }} <code>AGENT.md</code>{{ t('，保存后立即生效。') }}
        </p>
      </section>

      <!-- 生成 -->
      <section v-else-if="activeTab === 'generate'" class="panel">
        <p class="detail-hint">
          {{ t('用一段话描述你想要的 Agent；模型只产出草稿，核对后再保存。需要后端配置') }}
          <code>sableos.author.model</code>{{ t('，未配置时返回 503。') }}
        </p>

        <div class="form-field">
          <label class="form-label" for="generate-description">
            {{ t('需求') }}
            <span class="char-count mono">
              {{ generateDescription.length.toLocaleString('zh-CN') }} /
              {{ MAX_DESCRIPTION_CHARS.toLocaleString('zh-CN') }}
            </span>
          </label>
          <textarea
            id="generate-description"
            v-model="generateDescription"
            class="textarea"
            :placeholder="t('例如：每天早上九点查北京天气，把穿搭建议发到团队群')"
            :disabled="generatePending"
          />
        </div>

        <div v-if="generateError" class="inline-alert inline-alert--danger">
          <AppIcon name="alert" size="15" />
          <span>{{ generateError.message }}</span>
        </div>

        <div class="panel-actions">
          <button type="button" class="btn btn--primary" :disabled="!canGenerate" @click="runGenerate">
            {{ generatePending ? t('生成中…') : t('生成草稿') }}
          </button>
        </div>

        <template v-if="generated">
          <div class="detail-block">
            <span class="detail-block__label">{{ t('草稿文件（{count}）', { count: Object.keys(generated).length }) }}</span>
            <div class="generated-list">
              <label v-for="path in Object.keys(generated)" :key="path" class="generated-item">
                <span class="mono">{{ path }}</span>
              </label>
            </div>
          </div>
          <details class="generated-preview">
            <summary>{{ t('查看草稿内容') }}</summary>
            <article v-for="(content, path) in generated" :key="path" class="generated-file">
              <h4 class="mono">{{ path }}</h4>
              <pre class="code-block">{{ content }}</pre>
            </article>
          </details>
          <div class="panel-actions">
            <button type="button" class="btn btn--primary" :disabled="savingGenerated" @click="saveGenerated">
              {{ savingGenerated ? t('保存中…') : t('写入并生效') }}
            </button>
            <button type="button" class="btn btn--secondary" @click="generated = null">{{ t('丢弃草稿') }}</button>
          </div>
        </template>
      </section>

      <!-- 文件 -->
      <section v-else-if="activeTab === 'files'" class="panel files-layout">
        <aside class="file-tree">
          <template v-for="node in agentFiles" :key="node.path">
            <FileTreeNode :node="node" :selected="selectedPath" @open="openFile" />
          </template>
          <p v-if="!agentFiles.length" class="detail-hint">{{ t('该目录下没有可浏览的文件。') }}</p>
        </aside>

        <div class="file-editor">
          <template v-if="selectedPath">
            <div class="file-editor__head">
              <span class="mono">{{ selectedPath }}</span>
              <button type="button" class="btn btn--primary btn--sm" :disabled="!fileDirty || fileSaving" @click="saveFile">
                {{ fileSaving ? t('保存中…') : t('保存') }}
              </button>
            </div>
            <div v-if="fileError" class="inline-alert inline-alert--danger">
              <AppIcon name="alert" size="15" />
              <span>{{ fileError.message }}</span>
            </div>
            <textarea
              v-if="!fileLoading"
              v-model="fileContent"
              class="textarea code-textarea"
              spellcheck="false"
            />
            <SkeletonBlock v-else width="100%" height="320px" />
          </template>
          <EmptyState
            v-else
            :title="t('选择一个文件')"
            :description="t('左侧点文件名查看与编辑。改 AGENT.md 保存后会立即重新注册。')"
            icon="tools"
          />
        </div>
      </section>

      <!-- 会话 -->
      <section v-else-if="activeTab === 'session'" class="panel">
        <div v-if="sessionError" class="inline-alert inline-alert--danger">
          <AppIcon name="alert" size="15" />
          <span>{{ sessionError.message }}</span>
        </div>
        <SkeletonBlock v-else-if="sessionLoading && !session" width="100%" height="240px" />
        <template v-else-if="session">
          <p class="detail-hint">{{ t('这是该 Agent 的固定管理台会话（') }}<span class="mono">{{ session.id }}</span>{{ t('），刷新后仍在。') }}</p>
          <ol class="message-list">
            <li
              v-for="(message, index) in session.messages"
              :key="index"
              class="message"
              :class="`message--${message.role.toLowerCase()}`"
            >
              <span class="message__role mono">{{ message.role }}</span>
              <span v-if="message.toolName" class="message__tool mono">{{ message.toolName }}</span>
              <p class="message__content">{{ message.content || t('（无内容）') }}</p>
            </li>
            <li v-if="!session.messages.length" class="message message--empty">{{ t('还没有消息，发一句试试。') }}</li>
          </ol>
          <div class="form-field">
            <label class="form-label" for="session-input">{{ t('发消息') }}</label>
            <textarea
              id="session-input"
              v-model="sessionInput"
              class="textarea"
              :placeholder="t('例如：介绍一下你自己')"
              :disabled="sessionSending"
            />
          </div>
          <div class="panel-actions">
            <button type="button" class="btn btn--primary" :disabled="sessionSending || !sessionInput.trim()" @click="sendSession">
              {{ sessionSending ? t('处理中…（最长 60 秒）') : t('发送') }}
            </button>
          </div>
        </template>
      </section>

      <!-- 记忆 -->
      <section v-else class="panel">
        <div v-if="memoryError" class="inline-alert inline-alert--danger">
          <AppIcon name="alert" size="15" />
          <span>{{ memoryError.message }}</span>
        </div>
        <SkeletonBlock v-else-if="memoryLoading" width="100%" height="200px" />
        <template v-else>
          <p class="detail-hint">{{ t('该 Agent 自己的长期记忆（') }}<code>agents/{{ agent.name }}/MEMORY.md</code>{{ t('），只读。') }}</p>
          <pre v-if="memoryText" class="code-block">{{ memoryText }}</pre>
          <EmptyState
            v-else
            :title="t('还没有记忆')"
            :description="t('Agent 调用 save_memory 后，内容会出现在这里。')"
            icon="memory"
          />
          <div class="panel-actions">
            <button type="button" class="btn btn--secondary btn--sm" @click="loadMemory">{{ t('重新读取') }}</button>
          </div>
        </template>
      </section>
    </template>
  </div>
</template>

<style scoped>
.tab-bar {
  display: flex;
  gap: 2px;
  border-bottom: 1px solid var(--border);
}

.tab {
  padding: 8px 14px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--text-secondary);
  font-size: var(--fs-13);
  cursor: pointer;
}

.tab--active {
  border-bottom-color: var(--accent);
  color: var(--text);
}

.panel {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: var(--space-3);
}

.detail-grid dt {
  color: var(--text-faint);
  font-size: 10px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.detail-grid dd {
  color: var(--text);
  font-size: var(--fs-13);
}

.detail-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.detail-block__label {
  color: var(--text-faint);
  font-size: 10px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.detail-hint {
  color: var(--text-secondary);
  font-size: var(--fs-12);
  line-height: var(--lh-18);
}

.panel-actions {
  display: flex;
  gap: var(--space-3);
}

.files-layout {
  display: grid;
  grid-template-columns: minmax(200px, 280px) minmax(0, 1fr);
  gap: var(--space-4);
  align-items: start;
}

.file-tree {
  max-height: 560px;
  overflow: auto;
  padding: var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #080808;
}

.file-node {
  display: block;
  width: 100%;
  padding: 4px 8px;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-secondary);
  font-size: var(--fs-12);
  text-align: left;
  cursor: pointer;
}

.file-node--dir {
  color: var(--text-faint);
}

.file-node--active,
.file-node:hover {
  background: var(--panel);
  color: var(--text);
}

.file-children {
  padding-left: 12px;
}

.file-editor {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: var(--space-3);
}

.file-editor__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  color: var(--text-faint);
  font-size: var(--fs-12);
}

.code-textarea {
  min-height: 420px;
  font-family: var(--font-mono);
  font-size: var(--fs-12);
}

.code-block {
  overflow: auto;
  max-height: 480px;
  margin: 0;
  padding: var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #080808;
  color: var(--text-secondary);
  font-family: var(--font-mono);
  font-size: var(--fs-12);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.message-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

.message {
  padding: var(--space-3);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  background: #080808;
}

.message--user {
  border-left: 2px solid var(--accent);
}

.message__role {
  color: var(--text-faint);
  font-size: 10px;
  letter-spacing: 0.08em;
}

.message__tool {
  margin-left: 8px;
  color: var(--accent);
  font-size: var(--fs-11);
}

.message__content {
  margin: 6px 0 0;
  color: var(--text-secondary);
  font-size: var(--fs-13);
  line-height: var(--lh-20);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.message--empty {
  color: var(--text-faint);
  font-size: var(--fs-12);
}

.generated-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.generated-item {
  color: var(--text-secondary);
  font-size: var(--fs-12);
}

.generated-preview summary {
  color: var(--text-secondary);
  font-size: var(--fs-12);
  cursor: pointer;
}

.generated-file h4 {
  margin: var(--space-3) 0 4px;
  color: var(--text-faint);
  font-size: var(--fs-11);
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-12);
}

.data-table th,
.data-table td {
  padding: 6px 8px;
  border-bottom: 1px solid var(--border-subtle);
  text-align: left;
  overflow-wrap: anywhere;
}

.data-table th {
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

.chip {
  padding: 2px 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  font-size: var(--fs-11);
}
</style>
