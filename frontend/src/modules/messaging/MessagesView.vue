<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { user, api } from '../../api'
import { useRoute } from 'vue-router'
const route = useRoute()
const announcementTitle = ref('')
const announcementBody = ref('')
async function announce() {
  try { await api('/announcements', { method: 'POST', body: JSON.stringify({ title: announcementTitle.value, body: announcementBody.value }) }); announcementTitle.value = ''; announcementBody.value = ''; showNotice('公告已发布') } catch (cause) { fail(cause) }
}
import {
  listConversations,
  listMembers,
  listMessages,
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  redeliverNotification,
  retryPending,
  runScenario,
  sendMessage,
  setBrokerFault,
  loadStubStatus,
  unreadCount,
  type ChatMessage,
  type Conversation,
  type ConversationMember,
  type NotificationItem,
  type NotificationPage,
  type StubStatus,
} from './api'
import ChatPanel from './ChatPanel.vue'
import NotificationList from './NotificationList.vue'
import StubPanel from './StubPanel.vue'
import './messaging.css'

const PAGE_SIZE = 20

const tab = ref<'notifications' | 'chats'>('notifications')
const page = ref<NotificationPage>({ items: [], total: 0, page: 0, size: PAGE_SIZE, unread: 0 })
const pageIndex = ref(0)
const conversations = ref<Conversation[]>([])
const selectedId = ref('')
const messages = ref<ChatMessage[]>([])
const members = ref<ConversationMember[]>([])
const stub = ref<StubStatus | null>(null)
const draft = ref('')
const error = ref('')
const notice = ref('')
const loadingChat = ref(false)
const sending = ref(false)

const selected = computed(() => conversations.value.find(item => item.id === selectedId.value) ?? null)
const pageCount = computed(() => Math.max(1, Math.ceil(page.value.total / PAGE_SIZE)))
const currentUserId = computed(() => user.value?.id ?? '')

let pollTimer: number | undefined
let chatTimer: number | undefined
let noticeTimer: number | undefined

function fail(cause: unknown) {
  error.value = cause instanceof Error ? cause.message : '操作失败，请稍后重试'
  window.clearTimeout(noticeTimer)
  noticeTimer = window.setTimeout(() => { error.value = '' }, 8000)
}

function showNotice(text: string) {
  notice.value = text
  window.clearTimeout(noticeTimer)
  noticeTimer = window.setTimeout(() => { notice.value = '' }, 8000)
}

async function refreshNotifications(target = pageIndex.value) {
  try {
    page.value = await listNotifications(target, PAGE_SIZE)
    pageIndex.value = target
    unreadCount.value = page.value.unread
  } catch (cause) {
    fail(cause)
  }
}

async function refreshConversations() {
  try {
    conversations.value = await listConversations()
    if (!selectedId.value && conversations.value.length) {
      await openChat(conversations.value.find(c => c.id === route.query.conversation) ?? conversations.value[0]!)
      if (route.query.conversation) tab.value = 'chats'
    }
  } catch (cause) {
    fail(cause)
  }
}

async function refreshConversationsQuietly() {
  try {
    conversations.value = await listConversations()
  } catch {
    // 轮询失败不打断页面，下一次继续。
  }
}

async function openChat(conversation: Conversation) {
  if (selectedId.value === conversation.id) return
  selectedId.value = conversation.id
  messages.value = []
  members.value = []
  loadingChat.value = true
  try {
    const [history, people] = await Promise.all([
      listMessages(conversation.id),
      listMembers(conversation.id),
    ])
    messages.value = history
    members.value = people
  } catch (cause) {
    fail(cause)
  } finally {
    loadingChat.value = false
  }
}

async function send() {
  const body = draft.value.trim()
  if (!body || !selectedId.value || sending.value) return
  sending.value = true
  try {
    const message = await sendMessage(selectedId.value, body)
    messages.value = [...messages.value, message]
    draft.value = ''
    await refreshConversationsQuietly()
  } catch (cause) {
    fail(cause)
  } finally {
    sending.value = false
  }
}

async function read(item: NotificationItem) {
  if (item.read) return
  try {
    await markNotificationRead(item.id)
    item.read = true
    item.readAt = new Date().toISOString()
    page.value.unread = Math.max(0, page.value.unread - 1)
    unreadCount.value = page.value.unread
  } catch (cause) {
    fail(cause)
  }
}

async function readAll() {
  try {
    const updated = await markAllNotificationsRead()
    page.value.items.forEach(item => { item.read = true })
    page.value.unread = 0
    unreadCount.value = 0
    showNotice(updated ? `已把 ${updated} 条通知标为已读` : '没有未读通知')
  } catch (cause) {
    fail(cause)
  }
}

async function scenario(name: string) {
  try {
    const result = await runScenario(name)
    showNotice(result.summary)
    await refreshNotifications(0)
    await refreshConversationsQuietly()
    if (result.conversationId) {
      const conversation = conversations.value.find(item => item.id === result.conversationId)
      if (conversation) await openChat(conversation)
      tab.value = 'chats'
    }
    await refreshStub()
  } catch (cause) {
    fail(cause)
  }
}

async function toggleFault() {
  if (!stub.value) return
  try {
    stub.value = await setBrokerFault(stub.value.brokerAvailable)
    showNotice(stub.value.brokerAvailable
      ? '已恢复消息中间件（模拟）'
      : '已模拟中间件中断；之后的通知会先记录为待重试，恢复后由重试任务补投')
  } catch (cause) {
    fail(cause)
  }
}

async function retry() {
  try {
    const result = await retryPending()
    stub.value = result.status
    await pollNotifications()
    showNotice(result.retried ? `已重试 ${result.retried} 条待投递通知` : '当前没有需要重试的通知')
  } catch (cause) {
    fail(cause)
  }
}

async function redeliver(id: string) {
  try {
    const result = await redeliverNotification(id)
    await refreshStub()
    showNotice(result.statusChanged
      ? `已重新投递，本次确认 ${result.newlyDeliveredRecipients} 名收件人`
      : `重复投递已去重：收件人 ${result.recipients}，本次新增确认 ${result.newlyDeliveredRecipients}`)
  } catch (cause) {
    fail(cause)
  }
}

function updateDraft(value: string) {
  draft.value = value
}

async function refreshStub() {
  try {
    stub.value = await loadStubStatus()
  } catch {
    stub.value = null
  }
}

async function pollNotifications() {
  if (document.visibilityState !== 'visible') return
  try {
    page.value = await listNotifications(pageIndex.value, PAGE_SIZE)
    unreadCount.value = page.value.unread
  } catch {
    // 轮询失败不打断页面，下一次继续。
  }
  await refreshConversationsQuietly()
}

async function pollChat() {
  if (!selectedId.value || document.visibilityState !== 'visible') return
  const last = messages.value[messages.value.length - 1]
  try {
    const fresh = await listMessages(selectedId.value, last?.createdAt)
    if (fresh.length) {
      messages.value = [...messages.value, ...fresh]
      await refreshConversationsQuietly()
    }
  } catch {
    // 轮询失败不打断页面。
  }
}

onMounted(async () => {
  await Promise.all([refreshNotifications(0), refreshConversations()])
  await refreshStub()
  pollTimer = window.setInterval(pollNotifications, 20_000)
  chatTimer = window.setInterval(pollChat, 5_000)
})

onBeforeUnmount(() => {
  window.clearInterval(pollTimer)
  window.clearInterval(chatTimer)
  window.clearTimeout(noticeTimer)
})
</script>

<template>
  <form v-if="user?.roles.includes('ADMIN')" class="card" style="padding:20px;margin-bottom:20px" @submit.prevent="announce">
    <h2>发布采集公告</h2>
    <input v-model="announcementTitle" placeholder="公告标题" maxlength="200" required />
    <textarea v-model="announcementBody" placeholder="公告内容" maxlength="2000" required />
    <button class="primary-button">发送给全部采集者</button>
  </form>
  <div class="msg-page">
    <section class="page-heading">
      <div class="eyebrow">消息中心</div>
      <h1>通知与交流</h1>
      <p>系统按角色群发任务与公告，错误单相关消息点发到本人；未读消息会保留到下次登录。</p>
    </section>

    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="notice" class="success">{{ notice }}</p>

    <div class="msg-tabs" role="tablist" aria-label="消息分类">
      <button :class="{ active: tab === 'notifications' }" role="tab" @click="tab = 'notifications'">
        通知<span v-if="unreadCount" class="tab-badge">{{ unreadCount }}</span>
      </button>
      <button :class="{ active: tab === 'chats' }" role="tab" @click="tab = 'chats'">
        交流<span class="tab-count">{{ conversations.length }}</span>
      </button>
    </div>

    <section v-if="tab === 'notifications'" class="msg-notifications card">
      <header class="msg-section-head">
        <div>
          <strong>系统通知</strong>
          <small>共 {{ page.total }} 条，未读 {{ page.unread }} 条</small>
        </div>
        <button class="text-button" :disabled="!page.unread" @click="readAll">全部标为已读</button>
      </header>
      <NotificationList :items="page.items" @read="read" />
      <footer v-if="page.total > PAGE_SIZE" class="msg-pager">
        <button class="secondary-button" :disabled="pageIndex === 0" @click="refreshNotifications(pageIndex - 1)">上一页</button>
        <span>{{ pageIndex + 1 }} / {{ pageCount }}</span>
        <button class="secondary-button" :disabled="pageIndex + 1 >= pageCount" @click="refreshNotifications(pageIndex + 1)">下一页</button>
      </footer>
    </section>

    <section v-else class="msg-chat-layout">
      <aside class="msg-conversations card">
        <header class="msg-section-head">
          <div><strong>会话</strong><small>{{ conversations.length }} 个</small></div>
        </header>
        <p v-if="!conversations.length" class="msg-empty small">
          还没有可参与的会话。错误单私聊由核验流程建立；采集者、核验者群聊按角色自动加入。
        </p>
        <button v-for="conversation in conversations" :key="conversation.id" class="msg-conversation"
                :class="{ active: conversation.id === selectedId }" @click="openChat(conversation)">
          <span class="msg-conversation-kind">
            {{ conversation.kind === 'ROLE_GROUP' ? '角色群聊' : '错误单讨论' }}
          </span>
          <strong>{{ conversation.title }}</strong>
          <small>{{ conversation.lastSenderName ? `${conversation.lastSenderName}：` : '' }}{{ conversation.lastMessagePreview || '还没有消息' }}</small>
        </button>
      </aside>
      <ChatPanel :conversation="selected" :messages="messages" :members="members" :loading="loadingChat"
                 :sending="sending" :draft="draft" :current-user-id="currentUserId"
                 @send="send" @update:draft="updateDraft" />
    </section>

    <StubPanel v-if="stub" :status="stub" @scenario="scenario" @toggle-fault="toggleFault"
               @retry="retry" @redeliver="redeliver" />
  </div>
</template>
