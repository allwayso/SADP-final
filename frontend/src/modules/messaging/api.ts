import { ref } from 'vue'
import { api } from '../../api'

export type NotificationItem = {
  id: string
  kind: string
  title: string
  body: string
  referenceKind: string | null
  referenceId: string | null
  senderName: string | null
  createdAt: string
  read: boolean
  readAt: string | null
}

export type NotificationPage = {
  items: NotificationItem[]
  total: number
  page: number
  size: number
  unread: number
}

export type Conversation = {
  id: string
  kind: string
  title: string
  role: string | null
  issueId: string | null
  createdAt: string
  lastMessageAt: string | null
  lastMessagePreview: string | null
  lastSenderName: string | null
}

export type ChatMessage = {
  id: string
  conversationId: string
  senderId: string
  senderName: string
  body: string
  createdAt: string
}

export type ConversationMember = { id: string; displayName: string }

export type StubNotificationRow = {
  id: string
  kind: string
  title: string
  status: string
  attempts: number
  lastError: string | null
  target: string
  recipients: number
  delivered: number
  createdAt: string
}

export type StubStatus = {
  transport: string
  brokerAvailable: boolean
  retryIntervalMs: number
  maxAttempts: number
  counts: Record<string, number>
  recent: StubNotificationRow[]
}

export type DeliveryResult = {
  notificationId: string
  statusChanged: boolean
  newlyDeliveredRecipients: number
  recipients: number
  status: string
}

export type ScenarioResult = {
  scenario: string
  summary: string
  notificationIds: string[]
  conversationId: string | null
}

/** 导航栏未读角标与其他页面共享的计数。 */
export const unreadCount = ref(0)

export async function refreshUnreadCount(): Promise<void> {
  try {
    const result = await api<{ unread: number }>('/notifications/unread-count')
    unreadCount.value = result.unread
  } catch {
    // 未登录或会话失效时保持安静，登录页会处理跳转。
  }
}

export async function listNotifications(page = 0, size = 20): Promise<NotificationPage> {
  return api<NotificationPage>(`/notifications?page=${page}&size=${size}`)
}

export async function markNotificationRead(id: string): Promise<void> {
  await api(`/notifications/${id}/read`, { method: 'POST' })
}

export async function markAllNotificationsRead(): Promise<number> {
  const result = await api<{ updated: number }>('/notifications/read-all', { method: 'POST' })
  return result.updated
}

export async function listConversations(): Promise<Conversation[]> {
  return api<Conversation[]>('/conversations')
}

export async function listMembers(conversationId: string): Promise<ConversationMember[]> {
  return api<ConversationMember[]>(`/conversations/${conversationId}/members`)
}

export async function listMessages(conversationId: string, after?: string): Promise<ChatMessage[]> {
  const query = after ? `?after=${encodeURIComponent(after)}` : ''
  return api<ChatMessage[]>(`/conversations/${conversationId}/messages${query}`)
}

export async function sendMessage(conversationId: string, body: string): Promise<ChatMessage> {
  return api<ChatMessage>(`/conversations/${conversationId}/messages`, {
    method: 'POST',
    body: JSON.stringify({ body }),
  })
}

/** 开发消息桩不可用（生产关闭）时返回 null。 */
export async function loadStubStatus(): Promise<StubStatus | null> {
  const response = await fetch('/api/messaging/stub/status', { credentials: 'same-origin' })
  return response.ok ? await response.json() as StubStatus : null
}

export async function runScenario(scenario: string): Promise<ScenarioResult> {
  return api<ScenarioResult>('/messaging/stub/scenario', {
    method: 'POST',
    body: JSON.stringify({ scenario }),
  })
}

export async function setBrokerFault(down: boolean): Promise<StubStatus> {
  return api<StubStatus>('/messaging/stub/fault', {
    method: 'POST',
    body: JSON.stringify({ down }),
  })
}

export async function retryPending(): Promise<{ retried: number; status: StubStatus }> {
  return api<{ retried: number; status: StubStatus }>('/messaging/stub/retry', { method: 'POST' })
}

export async function redeliverNotification(id: string): Promise<DeliveryResult> {
  return api<DeliveryResult>(`/messaging/stub/redeliver/${id}`, { method: 'POST' })
}
