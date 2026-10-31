export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
  timestamp: string
  traceId: string | null
}

export type MessageRole = 'USER' | 'ASSISTANT' | 'TOOL'

export interface SessionMessage {
  role: MessageRole
  content: string | null
  toolName: string | null
  success: boolean | null
}

export interface SessionView {
  id: string
  profileName: string
  channel: string
  userId: string
  status: 'active' | 'archived'
  createdAt: string
  lastActiveAt: string
  messages: SessionMessage[]
}

export interface SessionSummary {
  id: string
  profileName: string
  channel: string
  userId: string
  status: 'active' | 'archived'
  createdAt: string
  lastActiveAt: string
  messageCount: number
}

export interface CreateSessionPayload {
  profile: string
  userId?: string
}

export interface ReplyView {
  reply: string
}

export interface ProfileView {
  name: string
  description: string | null
  provider: string | null
  model: string | null
  tools: string[]
  channels: string[]
}

export interface ProviderStatus {
  name: string
  type: string
  reachable: boolean
  latencyMs: number | null
  error: string | null
}

export interface SystemInfo {
  app: string
  version: string
  providers: ProviderStatus[]
}

export interface HealthView {
  status: string
}

export interface ToolView {
  name: string
  description: string | null
  inputSchema: string
}

export interface MemoryView {
  profile: string
  content: string
}

export type ScheduleLastStatus = 'success' | 'failed' | null

export interface ScheduleView {
  scheduleId: number
  profileName: string
  scheduleKey: string
  displayName: string
  cron: string
  zone: string
  message: string
  enabled: boolean
  retired: boolean
  nextRunAt: string | null
  lastRunAt: string | null
  lastStatus: ScheduleLastStatus
  runCount: number
  updatedAt: string
}

export interface ScheduleExecution {
  id: number
  scheduleId: number | null
  sessionId: string
  startedAt: string
  success: boolean
  errorMessage: string | null
  durationMs: number
}
