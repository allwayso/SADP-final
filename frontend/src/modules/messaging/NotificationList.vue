<script setup lang="ts">
import type { NotificationItem } from './api'
import { formatDateTime, kindLabel } from './format'

defineProps<{ items: NotificationItem[] }>()
defineEmits<{ read: [item: NotificationItem] }>()

/** 跳到当前用户有权限进入的业务页面；B/C 页面接入后按同一 query 处理。 */
function referenceLink(item: NotificationItem): string {
  const id = encodeURIComponent(item.referenceId ?? '')
  if (item.referenceKind === 'POI') return `/poi/${id}`
  if (item.referenceKind === 'ISSUE') return `/review?issue=${id}`
  if (item.referenceKind === 'TASK') return `/review?task=${id}`
  return '/'

}
</script>

<template>
  <div v-if="!items.length" class="msg-empty">
    <p>还没有通知。</p>
    <p class="small">核验任务、错误整改和最终结果的通知会出现在这里；刷新页面或稍后回来查看。</p>
  </div>
  <ul v-else class="msg-notification-list">
    <li v-for="item in items" :key="item.id" class="msg-notification" :class="{ unread: !item.read }">
      <span class="msg-kind" :data-kind="item.kind">{{ kindLabel(item.kind) }}</span>
      <div class="msg-notification-main">
        <div class="msg-notification-title">
          <strong>{{ item.title }}</strong>
          <time>{{ formatDateTime(item.createdAt) }}</time>
        </div>
        <p class="msg-notification-text">{{ item.body }}</p>
        <div class="msg-notification-meta">
          <span>{{ item.senderName ? `来自 ${item.senderName}` : '系统通知' }}</span>
          <RouterLink v-if="item.referenceKind && item.referenceId" class="inline-link" :to="referenceLink(item)">
            查看关联 {{ item.referenceKind }} · {{ item.referenceId }}
          </RouterLink>
          <button v-if="!item.read" class="text-button" @click="$emit('read', item)">标为已读</button>
          <span v-else class="msg-read-state">已读</span>
        </div>
      </div>
    </li>
  </ul>
</template>
