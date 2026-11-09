<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { user } from '../api'
import FieldTasksPanel from '../modules/review/FieldTasksPanel.vue'
import IssueDetailPanel from '../modules/review/IssueDetailPanel.vue'
import IssuesPanel from '../modules/review/IssuesPanel.vue'
import PendingPanel from '../modules/review/PendingPanel.vue'
import StubPanel from '../modules/review/StubPanel.vue'
import TaskDetailPanel from '../modules/review/TaskDetailPanel.vue'
import { error as listError, issueItems, issueMine, loadIssues, loadPending, loadTasks, pendingItems, taskItems, taskMine } from '../modules/review/store'

type Tab = 'pending' | 'issues' | 'tasks' | 'stub'

const route = useRoute()
const router = useRouter()

const roles = computed(() => user.value?.roles ?? [])
const isReviewer = computed(() => roles.value.includes('REVIEWER'))
const isWorker = computed(() => roles.value.includes('FIELD_WORKER'))
const isAdmin = computed(() => roles.value.includes('ADMIN'))

const tabs = computed(() => {
  const items: { key: Tab; label: string; count?: number }[] = []
  if (isReviewer.value) items.push({ key: 'pending', label: '待核验', count: pendingItems.value.length })
  items.push({ key: 'issues', label: '错误单', count: issueItems.value.length })
  if (isWorker.value || isAdmin.value) items.push({ key: 'tasks', label: '现场任务', count: taskItems.value.length })
  if (isReviewer.value || isAdmin.value) items.push({ key: 'stub', label: 'M2/M4 桩状态' })
  return items
})

const defaultTab = computed<Tab>(() => (isReviewer.value ? 'pending' : isWorker.value ? 'tasks' : 'issues'))
const tab = computed<Tab>(() => {
  const requested = route.query.tab as Tab | undefined
  return requested && tabs.value.some(item => item.key === requested) ? requested : defaultTab.value
})
const openIssue = computed(() => typeof route.query.issue === 'string' ? route.query.issue : '')
const openTask = computed(() => typeof route.query.task === 'string' ? route.query.task : '')

onMounted(async () => {
  if (isAdmin.value) {
    issueMine.value = false
    taskMine.value = false
  }
  const jobs: Promise<void>[] = [loadIssues()]
  if (isReviewer.value) jobs.push(loadPending())
  if (isWorker.value || isAdmin.value) jobs.push(loadTasks())
  await Promise.all(jobs)
})

function switchTab(next: Tab) {
  void router.replace({ path: '/review', query: { tab: next } })
}

async function refresh() {
  const jobs: Promise<void>[] = [loadIssues()]
  if (isReviewer.value) jobs.push(loadPending())
  if (isWorker.value || isAdmin.value) jobs.push(loadTasks())
  await Promise.all(jobs)
}

function showIssue(issueId: string) {
  void router.replace({ path: '/review', query: { tab: 'issues', issue: issueId } })
}

function showTask(taskId: string) {
  void router.replace({ path: '/review', query: { tab: 'tasks', task: taskId } })
}

function close() {
  void router.replace({ path: '/review', query: { tab: tab.value } }).then(refresh)
}
</script>

<template>
  <div class="review-page">
    <section class="page-heading">
      <div class="eyebrow">M3 核验与异议处理</div>
      <h1>核验与异议处理</h1>
      <p>核查最新采集版本，标识错误、跟进整改与驳回，并将无法达成共识的问题交由第三方现场复核裁定。</p>
    </section>

    <p v-if="listError" class="error" role="alert">{{ listError }}</p>

    <nav class="review-tabs" aria-label="核验模块页签">
      <button
        v-for="item in tabs"
        :key="item.key"
        type="button"
        :class="{ 'tab-on': !openIssue && !openTask && tab === item.key }"
        @click="switchTab(item.key)"
      >
        {{ item.label }}<span v-if="item.count !== undefined" class="tab-count">{{ item.count }}</span>
      </button>
    </nav>

    <IssueDetailPanel v-if="openIssue" :issue-id="openIssue" @back="close" @open-task="showTask" />
    <TaskDetailPanel v-else-if="openTask" :task-id="openTask" @back="close" @open-issue="showIssue" />
    <PendingPanel v-else-if="tab === 'pending'" />
    <IssuesPanel v-else-if="tab === 'issues'" @open="showIssue" />
    <FieldTasksPanel v-else-if="tab === 'tasks'" @open="showTask" />
    <StubPanel v-else />
  </div>
</template>
