<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import EmptyState from '../components/EmptyState.vue'
import ErrorState from '../components/ErrorState.vue'
import PageHeader from '../components/PageHeader.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'
import StatusChip from '../components/StatusChip.vue'
import {
  healthState,
  infoError,
  infoLoading,
  lastRefreshedAt,
  refreshSystem,
  systemInfo,
} from '../composables/useSystemStatus'
import { openDiagnostics, pushToast, reportError } from '../composables/useUiFeedback'
import type { ProviderStatus } from '../types/api'
import { formatClock, formatLatency } from '../utils/format'

const router = useRouter()
const sessionId = ref('')
const sessionIdError = ref('')
const refreshing = ref(false)

const providers = computed<ProviderStatus[]>(() => systemInfo.value?.providers ?? [])
const providerCount = computed(() => providers.value.length)
const reachableCount = computed(() => providers.value.filter((provider) => provider.reachable).length)
const allReachable = computed(
  () => providerCount.value > 0 && reachableCount.value === providerCount.value,
)

const lastRefreshText = computed(() =>
  lastRefreshedAt.value ? formatClock(lastRefreshedAt.value) : '—',
)

const healthLabel = computed(() => {
  if (healthState.value === 'checking') return '健康检查中'
  if (healthState.value === 'ok') return '后端健康'
  if (healthState.value === 'down') return '后端不可达'
  return '等待探测'
})

const healthTone = computed(() => {
  if (healthState.value === 'ok') return 'success'
  if (healthState.value === 'down') return 'failure'
  if (healthState.value === 'checking') return 'warning'
  return 'neutral'
})

function providerTone(provider: ProviderStatus): 'success' | 'failure' {
  return provider.reachable ? 'success' : 'failure'
}

function providerLabel(provider: ProviderStatus): string {
  return provider.reachable ? '可达' : '不可达'
}

async function refreshOverview(): Promise<void> {
  if (refreshing.value) return
  refreshing.value = true
  const result = await refreshSystem()
  const issue = result.infoError ?? result.healthError
  if (issue) {
    reportError(issue)
  } else {
    pushToast('系统状态已刷新', 'success')
  }
  refreshing.value = false
}

function openSession(): void {
  const id = sessionId.value.trim()
  if (!id) {
    sessionIdError.value = '请输入会话 ID，例如 web:alice:default'
    return
  }
  sessionIdError.value = ''
  void router.push({ name: 'sessions', query: { id } })
}
</script>

<template>
  <div class="page-shell page-stack overview-page">
    <PageHeader
      eyebrow="System overview / 01"
      title="系统总览"
      description="SableOS 运行控制面：查看应用身份、Provider 连通态势与常用操作入口。"
    >
      <template #actions>
        <StatusChip :label="healthLabel" :tone="healthTone" />
        <span class="refresh-caption">最后同步 {{ lastRefreshText }}</span>
        <button
          type="button"
          class="btn btn--secondary btn--sm"
          :disabled="refreshing"
          @click="refreshOverview"
        >
          <AppIcon name="refresh" size="14" :class="{ spinning: refreshing }" />
          {{ refreshing ? '刷新中' : '刷新' }}
        </button>
      </template>
    </PageHeader>

    <section class="summary-strip" aria-label="系统摘要">
      <article class="summary-card">
        <span class="summary-card__index">01 / INSTANCE</span>
        <template v-if="infoLoading && !systemInfo">
          <SkeletonBlock width="72%" height="30px" />
          <SkeletonBlock width="52%" height="12px" />
        </template>
        <template v-else>
          <strong class="summary-card__value truncate">{{ systemInfo?.app || '—' }}</strong>
          <span class="summary-card__note">应用实例</span>
        </template>
      </article>

      <article class="summary-card">
        <span class="summary-card__index">02 / BUILD</span>
        <template v-if="infoLoading && !systemInfo">
          <SkeletonBlock width="82%" height="30px" />
          <SkeletonBlock width="44%" height="12px" />
        </template>
        <template v-else>
          <strong class="summary-card__value mono truncate">{{ systemInfo?.version || '—' }}</strong>
          <span class="summary-card__note">后端版本</span>
        </template>
      </article>

      <article class="summary-card">
        <span class="summary-card__index">03 / PROVIDERS</span>
        <template v-if="infoLoading && !systemInfo">
          <SkeletonBlock width="36%" height="30px" />
          <SkeletonBlock width="56%" height="12px" />
        </template>
        <template v-else>
          <strong class="summary-card__value mono">{{ providerCount }}</strong>
          <span class="summary-card__note">已配置 Provider</span>
        </template>
      </article>

      <article class="summary-card summary-card--accent">
        <span class="summary-card__index">04 / REACHABLE</span>
        <template v-if="infoLoading && !systemInfo">
          <SkeletonBlock width="46%" height="30px" />
          <SkeletonBlock width="64%" height="12px" />
        </template>
        <template v-else>
          <strong class="summary-card__value mono">
            {{ reachableCount }}<span class="summary-card__denominator"> / {{ providerCount }}</span>
          </strong>
          <span class="summary-card__note">
            {{ allReachable ? '全链路可达' : providerCount === 0 ? '尚未配置 Provider' : '需要检查连通性' }}
          </span>
        </template>
      </article>
    </section>

    <div class="overview-grid">
      <section class="panel provider-panel">
        <div class="panel__head">
          <div class="panel__title-group">
            <span class="eyebrow">Connectivity matrix</span>
            <h2 class="panel__title">Provider 连通态势</h2>
          </div>
          <span class="panel__meta">实时 HTTP 探测 · 最后同步 {{ lastRefreshText }}</span>
        </div>

        <div v-if="infoLoading && !systemInfo" class="skeleton-table" aria-label="Provider 加载中">
          <div v-for="index in 3" :key="index" class="skeleton-row">
            <SkeletonBlock width="32%" height="14px" />
            <SkeletonBlock width="16%" height="22px" />
            <SkeletonBlock width="20%" height="20px" />
            <SkeletonBlock width="48%" height="14px" />
          </div>
        </div>

        <ErrorState
          v-else-if="infoError && !systemInfo"
          title="系统信息加载失败"
          :error="infoError"
          @retry="refreshOverview"
          @inspect="openDiagnostics(infoError)"
        />

        <div v-else class="provider-panel__body">
          <div v-if="infoError" class="inline-alert inline-alert--danger provider-alert">
            <AppIcon name="alert" size="16" />
            <span>
              {{ infoError.message }}
              <span class="inline-alert__trace">traceId: {{ infoError.traceId || '—' }}</span>
            </span>
            <button type="button" class="inline-alert__action" @click="openDiagnostics(infoError)">
              诊断
            </button>
          </div>

          <EmptyState
            v-if="providers.length === 0"
            title="尚未配置 Provider"
            description="当前应用没有可探测的模型提供方。添加配置后，重新探测即可看到连通状态。"
            action-label="重新探测"
            icon="pulse"
            @action="refreshOverview"
          />

          <div v-else class="provider-table" role="table" aria-label="Provider 列表">
            <div class="provider-table__head" role="row">
              <span role="columnheader">Provider</span>
              <span role="columnheader">类型</span>
              <span role="columnheader">连通性</span>
              <span role="columnheader" class="is-right">延迟</span>
              <span role="columnheader">诊断</span>
            </div>

            <div
              v-for="provider in providers"
              :key="provider.name"
              class="provider-row"
              role="row"
            >
              <span class="provider-cell provider-name mono" role="cell" data-label="Provider">
                {{ provider.name }}
              </span>
              <span class="provider-cell" role="cell" data-label="类型">
                <span class="chip">{{ provider.type || '—' }}</span>
              </span>
              <span class="provider-cell" role="cell" data-label="连通性">
                <StatusChip :label="providerLabel(provider)" :tone="providerTone(provider)" />
              </span>
              <span class="provider-cell provider-latency mono" role="cell" data-label="延迟">
                {{ formatLatency(provider.latencyMs) }}
              </span>
              <span
                class="provider-cell provider-error"
                :class="{ 'provider-error--none': !provider.error }"
                role="cell"
                data-label="诊断"
              >
                {{ provider.error || '—' }}
              </span>
            </div>
          </div>
        </div>
      </section>

      <aside class="panel command-panel">
        <div class="panel__head">
          <div class="panel__title-group">
            <span class="eyebrow">Command deck</span>
            <h2 class="panel__title">控制入口</h2>
          </div>
        </div>
        <div class="command-panel__body">
          <form class="session-jump" @submit.prevent="openSession">
            <label class="form-label" for="overview-session-id">按 ID 打开会话</label>
            <div class="session-jump__row">
              <input
                id="overview-session-id"
                v-model="sessionId"
                class="input mono"
                placeholder="web:alice:default"
                autocomplete="off"
                :aria-invalid="Boolean(sessionIdError)"
                aria-describedby="overview-session-help overview-session-error"
              />
              <button type="submit" class="btn btn--primary">打开</button>
            </div>
            <p id="overview-session-help" class="field-help">支持带冒号的完整会话 ID。</p>
            <p v-if="sessionIdError" id="overview-session-error" class="field-error">
              {{ sessionIdError }}
            </p>
          </form>

          <div class="quick-links">
            <RouterLink :to="{ name: 'sessions', query: { action: 'new' } }" class="quick-link">
              <span class="quick-link__icon"><AppIcon name="plus" size="16" /></span>
              <span class="quick-link__copy">
                <strong>新建会话</strong>
                <span>选择 profile 与用户标识</span>
              </span>
              <AppIcon name="arrow-right" size="15" class="quick-link__arrow" />
            </RouterLink>
            <RouterLink :to="{ name: 'tools' }" class="quick-link">
              <span class="quick-link__icon"><AppIcon name="tools" size="16" /></span>
              <span class="quick-link__copy">
                <strong>查看工具</strong>
                <span>浏览已注册工具与入参 Schema</span>
              </span>
              <AppIcon name="arrow-right" size="15" class="quick-link__arrow" />
            </RouterLink>
            <RouterLink :to="{ name: 'memory' }" class="quick-link">
              <span class="quick-link__icon"><AppIcon name="memory" size="16" /></span>
              <span class="quick-link__copy">
                <strong>查看记忆</strong>
                <span>读取 Agent 的长期记忆全文</span>
              </span>
              <AppIcon name="arrow-right" size="15" class="quick-link__arrow" />
            </RouterLink>
          </div>

          <p class="command-note">
            后端不提供会话列表接口。会话入口仅支持 ID 打开、最近访问与本页新建。
          </p>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.overview-page {
  animation: page-in var(--duration-base) var(--ease-out);
}

@keyframes page-in {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
}

.refresh-caption {
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.03em;
  white-space: nowrap;
}

.spinning {
  animation: spin 700ms linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.summary-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #0a0a0a;
}

.summary-card {
  display: flex;
  min-width: 0;
  min-height: 148px;
  flex-direction: column;
  justify-content: space-between;
  gap: var(--space-4);
  padding: var(--space-5);
  border-right: 1px solid var(--border);
}

.summary-card:last-child {
  border-right: 0;
}

.summary-card__index {
  color: var(--text-faint);
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.1em;
  white-space: nowrap;
}

.summary-card__value {
  display: block;
  font-size: var(--fs-28);
  font-weight: 600;
  letter-spacing: -0.035em;
  line-height: var(--lh-32);
}

.summary-card--accent .summary-card__value {
  color: var(--accent);
}

.summary-card__denominator {
  color: var(--text-muted);
  font-size: var(--fs-16);
  font-weight: 500;
  letter-spacing: 0;
}

.summary-card__note {
  color: var(--text-muted);
  font-size: var(--fs-12);
  line-height: var(--lh-18);
}

.overview-grid {
  display: grid;
  align-items: start;
  grid-template-columns: minmax(0, 1fr) 348px;
  gap: var(--space-6);
}

.provider-panel {
  min-width: 0;
}

.provider-panel__body {
  min-width: 0;
}

.provider-alert {
  margin: var(--space-4) var(--space-5) 0;
}

.provider-table__head,
.provider-row {
  display: grid;
  align-items: center;
  grid-template-columns: minmax(136px, 1.2fr) 100px 116px 82px minmax(150px, 1fr);
  gap: var(--space-3);
}

.provider-table__head {
  min-height: 36px;
  padding: 0 var(--space-5);
  border-bottom: 1px solid var(--border);
  background: #090909;
  color: var(--text-faint);
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.provider-row {
  min-height: 58px;
  padding: 10px var(--space-5);
  border-bottom: 1px solid var(--border-subtle);
  transition: background-color var(--duration-fast) var(--ease-out);
}

.provider-row:last-child {
  border-bottom: 0;
}

.provider-row:hover {
  background: #101010;
}

.provider-cell {
  min-width: 0;
  color: var(--text-secondary);
  font-size: var(--fs-12);
  line-height: var(--lh-18);
}

.provider-name {
  color: var(--text);
  font-size: var(--fs-12);
  font-weight: 500;
  overflow-wrap: anywhere;
}

.is-right,
.provider-latency {
  justify-self: end;
  text-align: right;
  white-space: nowrap;
}

.provider-latency {
  color: var(--text-secondary);
  font-size: var(--fs-11);
}

.provider-error {
  color: #fca5a5;
  font-size: var(--fs-11);
  overflow-wrap: anywhere;
}

.provider-error--none {
  color: var(--text-faint);
}

.skeleton-table {
  display: flex;
  flex-direction: column;
}

.skeleton-row {
  display: grid;
  min-height: 58px;
  align-items: center;
  grid-template-columns: 1.2fr 0.6fr 0.7fr 1.3fr;
  gap: var(--space-4);
  padding: 0 var(--space-5);
  border-bottom: 1px solid var(--border-subtle);
}

.command-panel__body {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
  padding: var(--space-5);
}

.session-jump {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.session-jump__row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--space-2);
}

.quick-links {
  display: flex;
  flex-direction: column;
  border-top: 1px solid var(--border);
}

.quick-link {
  display: grid;
  min-height: 66px;
  align-items: center;
  grid-template-columns: 30px minmax(0, 1fr) auto;
  gap: var(--space-3);
  padding: var(--space-3) 0;
  border-bottom: 1px solid var(--border);
  color: var(--text);
  text-decoration: none;
}

.quick-link:hover {
  color: var(--text);
}

.quick-link__icon {
  display: grid;
  width: 30px;
  height: 30px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #080808;
  color: var(--text-muted);
  place-items: center;
}

.quick-link:hover .quick-link__icon {
  border-color: var(--accent-border);
  background: var(--accent-soft);
  color: var(--accent);
}

.quick-link__copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 1px;
}

.quick-link__copy strong {
  font-size: var(--fs-13);
  font-weight: 550;
  line-height: var(--lh-20);
}

.quick-link__copy span {
  color: var(--text-muted);
  font-size: var(--fs-11);
  line-height: var(--lh-18);
}

.quick-link__arrow {
  color: var(--text-faint);
  transition: color var(--duration-fast) var(--ease-out);
}

.quick-link:hover .quick-link__arrow {
  color: var(--accent);
}

.command-note {
  color: var(--text-muted);
  font-size: var(--fs-11);
  line-height: var(--lh-18);
}

@media (max-width: 1199px) {
  .overview-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 900px) {
  .summary-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .summary-card:nth-child(2) {
    border-right: 0;
  }

  .summary-card:nth-child(-n + 2) {
    border-bottom: 1px solid var(--border);
  }

  .provider-table__head {
    display: none;
  }

  .provider-row {
    display: flex;
    flex-wrap: wrap;
    gap: 8px 18px;
    padding: 14px var(--space-4);
  }

  .provider-cell {
    display: inline-flex;
    align-items: center;
    gap: 6px;
  }

  .provider-cell::before {
    color: var(--text-faint);
    content: attr(data-label);
    font-family: var(--font-mono);
    font-size: 9px;
    letter-spacing: 0.05em;
    text-transform: uppercase;
  }

  .provider-name {
    flex: 1 0 100%;
    font-size: var(--fs-13);
  }

  .provider-name::before,
  .provider-latency::before,
  .provider-error::before {
    display: none;
  }

  .provider-latency {
    justify-self: auto;
  }

  .provider-error {
    flex: 1 0 100%;
    color: #fca5a5;
  }

  .provider-error--none {
    display: none;
  }

  .provider-alert {
    margin: var(--space-3) var(--space-4) 0;
  }

  .skeleton-row {
    grid-template-columns: 1fr 0.7fr;
  }
}

@media (max-width: 767px) {
  .refresh-caption {
    display: none;
  }

  .summary-card {
    min-height: 128px;
    padding: var(--space-4);
  }

  .summary-card__value {
    font-size: var(--fs-24);
  }

  .command-panel__body {
    padding: var(--space-4);
  }

  .skeleton-row {
    padding: 0 var(--space-4);
  }
}
</style>
