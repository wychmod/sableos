<script setup lang="ts">
import { onMounted, ref, shallowRef, watch } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import EmptyState from '../components/EmptyState.vue'
import ErrorState from '../components/ErrorState.vue'
import PageHeader from '../components/PageHeader.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'
import StatusChip from '../components/StatusChip.vue'
import { normalizeError, type ApiError } from '../api/client'
import {
  listScheduleExecutions,
  listSchedules,
  runSchedule,
  setScheduleEnabled,
} from '../api/endpoints'
import { refreshTick } from '../composables/useRefreshSignal'
import { openDiagnostics, pushToast, reportError } from '../composables/useUiFeedback'
import type { ScheduleExecution, ScheduleView } from '../types/api'
import { formatDateTime, formatLatency } from '../utils/format'

type ChipTone = 'success' | 'failure' | 'warning' | 'neutral' | 'accent'

const schedules = ref<ScheduleView[]>([])
const loading = ref(false)
const refreshing = ref(false)
const error = shallowRef<ApiError | null>(null)
const busyId = ref<number | null>(null)
const expandedId = ref<number | null>(null)
const executions = ref<Record<number, ScheduleExecution[]>>({})
const executionsLoading = ref<number | null>(null)
const executionsError = shallowRef<ApiError | null>(null)

async function load(force = false): Promise<void> {
  if (loading.value) return
  if (schedules.value.length > 0 && !force && !error.value) return
  loading.value = true
  error.value = null
  try {
    schedules.value = await listSchedules()
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
    pushToast('定时任务已刷新', 'success')
  }
  refreshing.value = false
}

function replaceTask(updated: ScheduleView): void {
  schedules.value = schedules.value.map((task) =>
    task.scheduleId === updated.scheduleId ? updated : task,
  )
}

async function toggleEnabled(task: ScheduleView): Promise<void> {
  if (busyId.value !== null) return
  busyId.value = task.scheduleId
  try {
    const updated = await setScheduleEnabled(task.scheduleId, !task.enabled)
    replaceTask(updated)
    pushToast(updated.enabled ? '任务已启用' : '任务已停用', 'success')
  } catch (caught) {
    reportError(normalizeError(caught), { toast: true })
  } finally {
    busyId.value = null
  }
}

async function runNow(task: ScheduleView): Promise<void> {
  if (busyId.value !== null) return
  busyId.value = task.scheduleId
  try {
    const execution = await runSchedule(task.scheduleId)
    pushToast(
      execution.success ? '立即执行完成' : `立即执行失败：${execution.errorMessage ?? '未知原因'}`,
      execution.success ? 'success' : 'error',
    )
    await load(true)
    if (expandedId.value === task.scheduleId) {
      await loadExecutions(task.scheduleId)
    }
  } catch (caught) {
    reportError(normalizeError(caught), { toast: true })
  } finally {
    busyId.value = null
  }
}

async function toggleHistory(task: ScheduleView): Promise<void> {
  if (expandedId.value === task.scheduleId) {
    expandedId.value = null
    return
  }
  expandedId.value = task.scheduleId
  await loadExecutions(task.scheduleId)
}

async function loadExecutions(id: number): Promise<void> {
  executionsLoading.value = id
  executionsError.value = null
  try {
    const rows = await listScheduleExecutions(id)
    executions.value = { ...executions.value, [id]: rows }
  } catch (caught) {
    executionsError.value = normalizeError(caught)
    reportError(executionsError.value, { toast: false })
  } finally {
    executionsLoading.value = null
  }
}

function statusLabel(task: ScheduleView): string {
  if (task.retired) return '已退役'
  return task.enabled ? '启用中' : '已停用'
}

function statusTone(task: ScheduleView): ChipTone {
  if (task.retired) return 'neutral'
  return task.enabled ? 'success' : 'warning'
}

function lastStatusLabel(task: ScheduleView): string {
  if (task.lastStatus === 'success') return '成功'
  if (task.lastStatus === 'failed') return '失败'
  return '未执行'
}

function lastStatusTone(task: ScheduleView): ChipTone {
  if (task.lastStatus === 'success') return 'success'
  if (task.lastStatus === 'failed') return 'failure'
  return 'neutral'
}

function executionTone(execution: ScheduleExecution): ChipTone {
  return execution.success ? 'success' : 'failure'
}

watch(refreshTick, () => {
  void load(true)
})

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page-shell page-stack schedules-page">
    <PageHeader
      eyebrow="Scheduler / 28"
      title="定时任务"
      description="Agent 运行声明里登记的定时任务；这里只管理运行状态与执行，不改 cron 定义。"
    >
      <template #actions>
        <span class="schedules-caption mono">{{ schedules.length }} 个任务</span>
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

    <div v-if="loading && schedules.length === 0" class="schedule-skeleton">
      <div v-for="index in 3" :key="index" class="schedule-skeleton__row">
        <SkeletonBlock width="180px" height="16px" />
        <SkeletonBlock width="120px" height="12px" />
        <SkeletonBlock width="90px" height="12px" />
      </div>
    </div>

    <ErrorState
      v-else-if="error && schedules.length === 0"
      title="定时任务加载失败"
      :error="error"
      @retry="refresh"
      @inspect="openDiagnostics(error)"
    />

    <EmptyState
      v-else-if="schedules.length === 0"
      title="还没有定时任务"
      description="在 Agent 的 AGENT.md frontmatter 里写 schedules，重启 serve 后任务会自动登记。"
      action-label="重新加载"
      icon="clock"
      @action="refresh"
    />

    <template v-else>
      <div v-if="error" class="inline-alert inline-alert--danger">
        <AppIcon name="alert" size="16" />
        <span>
          {{ error.message }}
          <span class="inline-alert__trace">traceId: {{ error.traceId || '—' }}</span>
        </span>
        <button type="button" class="inline-alert__action" @click="openDiagnostics(error)">
          诊断
        </button>
      </div>

      <div class="panel schedule-panel">
        <div class="schedule-table-wrap">
          <table class="schedule-table">
            <thead>
              <tr>
                <th scope="col">任务</th>
                <th scope="col">Profile</th>
                <th scope="col">cron</th>
                <th scope="col">下次触发</th>
                <th scope="col">上次结果</th>
                <th scope="col" class="is-numeric">次数</th>
                <th scope="col">状态</th>
                <th scope="col" class="is-actions">操作</th>
              </tr>
            </thead>
            <tbody>
              <template v-for="task in schedules" :key="task.scheduleId">
                <tr class="schedule-row" :class="{ 'schedule-row--open': expandedId === task.scheduleId }">
                  <td>
                    <div class="schedule-name">
                      <span class="schedule-name__main mono">{{ task.displayName }}</span>
                      <span class="schedule-name__key mono">{{ task.scheduleKey }}</span>
                    </div>
                  </td>
                  <td class="mono">{{ task.profileName }}</td>
                  <td>
                    <div class="schedule-cron">
                      <span class="mono">{{ task.cron }}</span>
                      <span class="schedule-cron__zone">{{ task.zone }}</span>
                    </div>
                  </td>
                  <td>{{ formatDateTime(task.nextRunAt) }}</td>
                  <td>
                    <StatusChip :label="lastStatusLabel(task)" :tone="lastStatusTone(task)" />
                  </td>
                  <td class="is-numeric mono">{{ task.runCount }}</td>
                  <td>
                    <StatusChip :label="statusLabel(task)" :tone="statusTone(task)" />
                  </td>
                  <td class="is-actions">
                    <div class="schedule-actions">
                      <button
                        type="button"
                        class="btn btn--secondary btn--sm"
                        :disabled="busyId !== null"
                        @click="runNow(task)"
                      >
                        <AppIcon name="play" size="13" />
                        立即执行
                      </button>
                      <button
                        type="button"
                        class="btn btn--ghost btn--sm"
                        :disabled="busyId !== null"
                        @click="toggleEnabled(task)"
                      >
                        {{ task.enabled ? '停用' : '启用' }}
                      </button>
                      <button
                        type="button"
                        class="btn btn--ghost btn--sm"
                        :aria-expanded="expandedId === task.scheduleId"
                        @click="toggleHistory(task)"
                      >
                        历史
                        <AppIcon
                          name="chevron-down"
                          size="13"
                          :class="{ 'is-rotated': expandedId === task.scheduleId }"
                        />
                      </button>
                    </div>
                  </td>
                </tr>
                <tr v-if="expandedId === task.scheduleId" class="schedule-history-row">
                  <td colspan="8">
                    <div class="schedule-history">
                      <div class="schedule-history__head">
                        <span class="eyebrow">Execution history</span>
                        <span class="schedule-history__hint mono">
                          {{ executions[task.scheduleId]?.length ?? 0 }} 条
                        </span>
                      </div>

                      <div v-if="executionsLoading === task.scheduleId" class="schedule-history__state">
                        <SkeletonBlock width="70%" height="12px" />
                        <SkeletonBlock width="45%" height="12px" />
                      </div>

                      <div
                        v-else-if="executionsError && executionsLoading === null"
                        class="inline-alert inline-alert--danger"
                      >
                        <AppIcon name="alert" size="16" />
                        <span>{{ executionsError.message }}</span>
                        <button
                          type="button"
                          class="inline-alert__action"
                          @click="loadExecutions(task.scheduleId)"
                        >
                          重试
                        </button>
                      </div>

                      <p
                        v-else-if="(executions[task.scheduleId]?.length ?? 0) === 0"
                        class="schedule-history__empty"
                      >
                        还没有执行记录。
                      </p>

                      <table v-else class="schedule-history-table">
                        <thead>
                          <tr>
                            <th scope="col">#</th>
                            <th scope="col">开始时间</th>
                            <th scope="col">结果</th>
                            <th scope="col">耗时</th>
                            <th scope="col">会话 / 原因</th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr
                            v-for="execution in executions[task.scheduleId]"
                            :key="execution.id"
                          >
                            <td class="mono">{{ execution.id }}</td>
                            <td>{{ formatDateTime(execution.startedAt) }}</td>
                            <td>
                              <StatusChip
                                :label="execution.success ? '成功' : '失败'"
                                :tone="executionTone(execution)"
                              />
                            </td>
                            <td class="mono">{{ formatLatency(execution.durationMs) }}</td>
                            <td class="schedule-history__detail">
                              <span class="mono">{{ execution.sessionId }}</span>
                              <span v-if="execution.errorMessage" class="schedule-history__error">
                                {{ execution.errorMessage }}
                              </span>
                            </td>
                          </tr>
                        </tbody>
                      </table>
                    </div>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.schedules-page {
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

.schedules-caption {
  color: var(--text-faint);
  font-size: 10px;
  letter-spacing: 0.06em;
  white-space: nowrap;
}

.schedule-panel {
  overflow: hidden;
}

.schedule-table-wrap {
  overflow-x: auto;
}

.schedule-table,
.schedule-history-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-12);
}

.schedule-table th,
.schedule-table td,
.schedule-history-table th,
.schedule-history-table td {
  padding: 10px var(--space-4);
  border-bottom: 1px solid var(--border-subtle);
  text-align: left;
  vertical-align: top;
}

.schedule-table th,
.schedule-history-table th {
  color: var(--text-faint);
  font-size: var(--fs-11);
  font-weight: 550;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  white-space: nowrap;
}

.schedule-table tbody tr:last-child td,
.schedule-history-table tbody tr:last-child td {
  border-bottom: 0;
}

.schedule-row--open td {
  background: #0c0c0c;
}

.schedule-name {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.schedule-name__main {
  color: var(--text);
  font-weight: 600;
  overflow-wrap: anywhere;
}

.schedule-name__key,
.schedule-cron__zone {
  color: var(--text-faint);
  font-size: var(--fs-11);
}

.schedule-cron {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.is-numeric {
  text-align: right;
  white-space: nowrap;
}

.is-actions {
  text-align: right;
  white-space: nowrap;
}

.schedule-actions {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-2);
}

.is-rotated {
  transform: rotate(180deg);
}

.schedule-history-row td {
  padding: 0;
  background: #080808;
}

.schedule-history {
  border-top: 1px solid var(--border-subtle);
  padding: var(--space-4);
}

.schedule-history__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
}

.schedule-history__hint {
  color: var(--text-faint);
  font-size: var(--fs-11);
}

.schedule-history__state {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-3) 0;
}

.schedule-history__empty {
  margin: 0;
  padding: var(--space-3) 0;
  color: var(--text-muted);
  font-size: var(--fs-12);
}

.schedule-history-table {
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  background: #0b0b0b;
}

.schedule-history__detail {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 240px;
  overflow-wrap: anywhere;
}

.schedule-history__error {
  color: #fca5a5;
}

.schedule-skeleton {
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
}

.schedule-skeleton__row {
  display: flex;
  align-items: center;
  gap: var(--space-5);
  padding: var(--space-4) var(--space-5);
  border-bottom: 1px solid var(--border);
}

.schedule-skeleton__row:last-child {
  border-bottom: 0;
}

@media (max-width: 900px) {
  .schedule-table th:nth-child(3),
  .schedule-table td:nth-child(3),
  .schedule-table th:nth-child(4),
  .schedule-table td:nth-child(4) {
    display: none;
  }
}

@media (max-width: 640px) {
  .schedule-table th,
  .schedule-table td {
    padding: 9px var(--space-3);
  }

  .schedule-actions {
    flex-direction: column;
    align-items: flex-end;
  }
}
</style>
