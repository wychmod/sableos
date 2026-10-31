import { API_BASE_V2, request } from './client'
import type {
  AgentDetailView,
  AgentView,
  CreateSessionPayload,
  GeneratedFilesView,
  HealthView,
  MemoryView,
  ProfileView,
  ReplyView,
  ScheduleExecution,
  ScheduleView,
  SessionSummary,
  SessionView,
  SystemInfo,
  ToolView,
} from '../types/api'

const sessionPath = (id: string) => `/sessions/${encodeURIComponent(id)}`

export function createSession(payload: CreateSessionPayload): Promise<SessionView> {
  return request<SessionView>('/sessions', {
    method: 'POST',
    body: payload,
    timeoutMs: 15_000,
  })
}

export function sendSessionMessage(id: string, content: string): Promise<ReplyView> {
  return request<ReplyView>(`${sessionPath(id)}/messages`, {
    method: 'POST',
    body: { content },
    timeoutMs: 90_000,
  })
}

export function getSession(id: string): Promise<SessionView> {
  return request<SessionView>(sessionPath(id), {
    timeoutMs: 15_000,
  })
}

export function listSessions(status?: 'active' | 'archived'): Promise<SessionSummary[]> {
  const query = status ? `?status=${encodeURIComponent(status)}` : ''
  return request<SessionSummary[]>(`/sessions${query}`, {
    timeoutMs: 15_000,
  })
}

export function archiveSession(id: string): Promise<null> {
  return request<null>(sessionPath(id), {
    method: 'DELETE',
    timeoutMs: 15_000,
  })
}

export function invokeAgent(name: string, message: string): Promise<ReplyView> {
  return request<ReplyView>(`/agents/${encodeURIComponent(name)}/invoke`, {
    method: 'POST',
    body: { message },
    timeoutMs: 90_000,
  })
}

export function listProfiles(): Promise<ProfileView[]> {
  return request<ProfileView[]>('/profiles', {
    timeoutMs: 15_000,
  })
}

export function getMemory(profile: string): Promise<MemoryView> {
  return request<MemoryView>(`/memory?profile=${encodeURIComponent(profile)}`, {
    timeoutMs: 15_000,
  })
}

export function listTools(): Promise<ToolView[]> {
  return request<ToolView[]>('/tools', {
    timeoutMs: 15_000,
  })
}

export function getHealth(): Promise<HealthView> {
  return request<HealthView>('/health', {
    timeoutMs: 15_000,
  })
}

export function getInfo(): Promise<SystemInfo> {
  return request<SystemInfo>('/info', {
    timeoutMs: 20_000,
  })
}

const schedulePath = (id: number) => `/schedules/${id}`

export function listSchedules(): Promise<ScheduleView[]> {
  return request<ScheduleView[]>('/schedules', {
    base: API_BASE_V2,
    timeoutMs: 15_000,
  })
}

export function listScheduleExecutions(id: number): Promise<ScheduleExecution[]> {
  return request<ScheduleExecution[]>(`${schedulePath(id)}/executions`, {
    base: API_BASE_V2,
    timeoutMs: 15_000,
  })
}

export function runSchedule(id: number): Promise<ScheduleExecution> {
  return request<ScheduleExecution>(`${schedulePath(id)}/run`, {
    method: 'POST',
    base: API_BASE_V2,
    timeoutMs: 120_000,
  })
}

export function setScheduleEnabled(id: number, enabled: boolean): Promise<ScheduleView> {
  return request<ScheduleView>(schedulePath(id), {
    method: 'PUT',
    body: { enabled },
    base: API_BASE_V2,
    timeoutMs: 15_000,
  })
}

// ---------------------------------------------------------------- 第 30 节：Agent 管理与工作区

const agentPath = (name: string) => `/agents/${encodeURIComponent(name)}`

export function listAgents(): Promise<AgentView[]> {
  return request<AgentView[]>('/agents', { timeoutMs: 15_000 })
}

export function getAgent(name: string): Promise<AgentDetailView> {
  return request<AgentDetailView>(agentPath(name), { timeoutMs: 15_000 })
}

export function createAgent(name: string, description: string): Promise<AgentDetailView> {
  return request<AgentDetailView>('/agents', {
    method: 'POST',
    body: { name, description },
    timeoutMs: 30_000,
  })
}

export function updateAgent(name: string, content: string): Promise<AgentDetailView> {
  return request<AgentDetailView>(agentPath(name), {
    method: 'PUT',
    body: { content },
    timeoutMs: 30_000,
  })
}

export function deleteAgent(name: string): Promise<null> {
  return request<null>(agentPath(name), { method: 'DELETE', timeoutMs: 30_000 })
}

/** 一句话生成文件草稿（不落盘、不注册）；需要后端配置 sableos.author.model，未配置时 503。 */
export function generateAgentFiles(name: string, description: string): Promise<GeneratedFilesView> {
  return request<GeneratedFilesView>(`${agentPath(name)}/generate-files`, {
    method: 'POST',
    body: { description },
    timeoutMs: 120_000,
  })
}

export function saveAgentFiles(
  name: string,
  files: Record<string, string>,
): Promise<AgentDetailView> {
  return request<AgentDetailView>(`${agentPath(name)}/files`, {
    method: 'POST',
    body: { files },
    timeoutMs: 30_000,
  })
}

export function getAgentMemory(name: string): Promise<MemoryView> {
  return request<MemoryView>(`${agentPath(name)}/memory`, { timeoutMs: 15_000 })
}

export function getAgentSession(name: string): Promise<SessionView> {
  return request<SessionView>(`${agentPath(name)}/session`, { timeoutMs: 15_000 })
}

export function sendAgentSessionMessage(name: string, content: string): Promise<ReplyView> {
  return request<ReplyView>(`${agentPath(name)}/session/messages`, {
    method: 'POST',
    body: { content },
    timeoutMs: 90_000,
  })
}

export function getWorkspaceFile(path: string): Promise<string> {
  return request<string>(`/workspace/file?path=${encodeURIComponent(path)}`, {
    timeoutMs: 15_000,
  })
}

export function writeWorkspaceFile(path: string, content: string): Promise<null> {
  return request<null>('/workspace/file', {
    method: 'POST',
    body: { path, content },
    timeoutMs: 30_000,
  })
}
