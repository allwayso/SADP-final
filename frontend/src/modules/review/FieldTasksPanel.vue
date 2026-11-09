<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { user } from '../../api'
import { formatTime, taskStatusLabels, taskStatusTones, verdictLabels } from './labels'
import { loadTasks, taskItems, taskMine, taskStatuses } from './store'
import type { FieldTaskStatus } from './types'
import StatusPill from './StatusPill.vue'

const emit = defineEmits<{ open: [taskId: string] }>()

const isAdmin = computed(() => user.value?.roles.includes('ADMIN') ?? false)
const allStatuses: FieldTaskStatus[] = ['UNASSIGNED', 'ASSIGNED', 'COMPLETED']

onMounted(loadTasks)

async function toggleStatus(status: FieldTaskStatus) {
  taskStatuses.value = taskStatuses.value.includes(status)
    ? taskStatuses.value.filter(item => item !== status)
    : [...taskStatuses.value, status]
  await loadTasks()
}

async function toggleMine() {
  taskMine.value = !taskMine.value
  await loadTasks()
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
        :class="{ 'chip-on': taskStatuses.includes(status) }"
        @click="toggleStatus(status)"
      >{{ taskStatusLabels[status] }}</button>
      <button v-if="isAdmin" type="button" class="chip" :class="{ 'chip-on': !taskMine }" @click="toggleMine">
        {{ taskMine ? '只看我的' : '查看全部' }}
      </button>
    </div>

    <p class="hint">异议提交后系统按当前负载分配一名非当事人的第三方工作人员；无可用人员时保留待分配并通知管理员。现场裁定提交后双方都不能再修改。</p>
    <p v-if="!taskItems.length" class="empty-state">暂无现场任务。</p>

    <ul class="issue-list">
      <li v-for="task in taskItems" :key="task.id">
        <button type="button" class="issue-row" @click="emit('open', task.id)">
          <span class="issue-main">
            <span class="issue-title">{{ task.disputeReason || '异议现场复核' }}</span>
            <span class="hint">
              {{ task.poiId }} · 处理人 {{ task.assignee?.displayName ?? '待分配' }} · 创建于 {{ formatTime(task.createdAt) }}
              <template v-if="task.verdict"> · 裁定 {{ verdictLabels[task.verdict] }}</template>
            </span>
          </span>
          <StatusPill :label="taskStatusLabels[task.status]" :tone="taskStatusTones[task.status]" />
        </button>
      </li>
    </ul>
  </div>
</template>
