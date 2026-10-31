import { API_BASE_V2, request } from './client'
import type {
  CreateSessionPayload,
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
