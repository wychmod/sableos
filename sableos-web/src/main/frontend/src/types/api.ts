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
