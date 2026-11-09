<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import type { ChatMessage, Conversation, ConversationMember } from './api'
import { formatDateTime } from './format'

const props = defineProps<{
  conversation: Conversation | null
  messages: ChatMessage[]
  members: ConversationMember[]
  loading: boolean
  sending: boolean
  draft: string
  currentUserId: string
}>()

const emit = defineEmits<{ send: []; 'update:draft': [value: string] }>()

const panel = ref<HTMLElement | null>(null)

watch(() => props.messages.length, async () => {
  await nextTick()
  const element = panel.value
  if (element) element.scrollTop = element.scrollHeight
})

function onInput(event: Event) {
  emit('update:draft', (event.target as HTMLTextAreaElement).value)
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    emit('send')
  }
}
</script>

<template>
  <section class="msg-chat card">
    <template v-if="conversation">
      <header class="msg-chat-head">
        <div>
          <span class="msg-conversation-kind">{{ conversation.kind === 'ROLE_GROUP' ? '角色群聊' : '错误单讨论' }}</span>
          <h2>{{ conversation.title }}</h2>
          <p class="msg-members">
            {{ members.length ? members.map(member => member.displayName).join('、') : '正在加载成员…' }}
          </p>
        </div>
      </header>
      <div ref="panel" class="msg-messages" aria-live="polite">
        <p v-if="loading" class="msg-empty small">正在加载消息…</p>
        <template v-else>
          <p v-if="!messages.length" class="msg-empty small">还没有消息，发一条开始讨论吧。</p>
          <article v-for="message in messages" :key="message.id" class="msg-message"
                   :class="{ own: message.senderId === currentUserId }">
            <div class="msg-message-head">
              <strong>{{ message.senderName }}</strong>
              <time>{{ formatDateTime(message.createdAt) }}</time>
            </div>
            <p>{{ message.body }}</p>
          </article>
        </template>
      </div>
      <form class="msg-composer" @submit.prevent="$emit('send')">
        <textarea :value="draft" rows="2" maxlength="2000" aria-label="消息内容"
                  placeholder="输入消息，Enter 发送，Shift+Enter 换行"
                  @input="onInput" @keydown="onKeydown" />
        <button class="primary-button" :disabled="sending || !draft.trim()">
          {{ sending ? '发送中…' : '发送' }}
        </button>
      </form>
    </template>
    <p v-else class="msg-empty">从左侧选择一个会话，或者先等待核验流程建立错误单讨论。</p>
  </section>
</template>
