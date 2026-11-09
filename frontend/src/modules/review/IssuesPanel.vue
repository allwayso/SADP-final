<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { user } from '../../api'
import { formatTime, issueStatusLabels, issueStatusTones } from './labels'
import { issueItems, issueMine, issueStatuses, loadIssues } from './store'
import type { IssueStatus } from './types'
import StatusPill from './StatusPill.vue'

const emit = defineEmits<{ open: [issueId: string] }>()

const isAdmin = computed(() => user.value?.roles.includes('ADMIN') ?? false)
const allStatuses: IssueStatus[] = ['OPEN', 'PENDING_RECHECK', 'DISPUTED', 'IN_ARBITRATION', 'RESOLVED', 'CLOSED']

onMounted(loadIssues)

async function toggleStatus(status: IssueStatus) {
  issueStatuses.value = issueStatuses.value.includes(status)
    ? issueStatuses.value.filter(item => item !== status)
    : [...issueStatuses.value, status]
  await loadIssues()
}

async function toggleMine() {
  issueMine.value = !issueMine.value
  await loadIssues()
}
</script>

<template>
  <div class="review-panel">
    <div class="filter-row">
      <span class="filter-label">状态</span>
      <button
        v-for="status in allStatuses"
        :key="status"
        type="button"
        class="chip"
        :class="{ 'chip-on': issueStatuses.includes(status) }"
        @click="toggleStatus(status)"
      >{{ issueStatusLabels[status] }}</button>
      <button v-if="isAdmin" type="button" class="chip" :class="{ 'chip-on': !issueMine }" @click="toggleMine">
        {{ issueMine ? '只看我的' : '查看全部' }}
      </button>
    </div>

    <p v-if="!issueItems.length" class="empty-state">没有符合条件的错误单。</p>

    <ul class="issue-list">
      <li v-for="issue in issueItems" :key="issue.id">
        <button type="button" class="issue-row" @click="emit('open', issue.id)">
          <span class="issue-main">
            <span class="issue-title">{{ issue.description }}</span>
            <span class="hint">
              {{ issue.poiId }} · 采集者 {{ issue.collector.displayName }} · 核验者 {{ issue.reviewer.displayName }} ·
              更新于 {{ formatTime(issue.updatedAt) }}
            </span>
          </span>
          <StatusPill :label="issueStatusLabels[issue.status]" :tone="issueStatusTones[issue.status]" />
        </button>
      </li>
    </ul>
  </div>
</template>
