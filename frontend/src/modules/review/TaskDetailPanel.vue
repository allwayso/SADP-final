<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api, user } from '../../api'
import { reviewApi } from './api'
import { formatTime, taskStatusLabels, taskStatusTones, verdictLabels } from './labels'
import { failure, loadIssues, loadTasks } from './store'
import type { CollectionInput, IssueDetail, TaskView, Verdict } from './types'
import CollectionForm from './CollectionForm.vue'
import RevisionCard from './RevisionCard.vue'
import StatusPill from './StatusPill.vue'

const props = defineProps<{ taskId: string }>()
const emit = defineEmits<{ back: []; openIssue: [issueId: string] }>()

const task = ref<TaskView | null>(null)
const issue = ref<IssueDetail | null>(null)
const error = ref('')
const notice = ref('')
const busy = ref(false)
const verdict = ref<Verdict>('REPLACED')
const reason = ref('')
const collection = ref<CollectionInput | null>(null)
const assignee = ref('')
const workers = ref<{ id: string; displayName: string }[]>([])

const isAdmin = computed(() => user.value?.roles.includes('ADMIN') ?? false)
const isAssignee = computed(() => task.value?.assignee?.id === user.value?.id)
const canDecide = computed(() => task.value?.status === 'ASSIGNED' && isAssignee.value)
const canAssign = computed(() => isAdmin.value && task.value?.status !== 'COMPLETED')

async function load() {
  error.value = ''
  try {
    task.value = await reviewApi.task(props.taskId)
    try {
      issue.value = await reviewApi.issue(task.value.issueId)
    } catch {
      issue.value = null
    }
    reason.value = ''
    collection.value = null
    verdict.value = 'REPLACED'
    if (canAssign.value && workers.value.length === 0) {
      workers.value = (await api<{ id: string; displayName: string; roles: string[] }[]>('/admin/users'))
        .filter(account => account.roles.includes('FIELD_WORKER'))
    }
  } catch (cause) {
    error.value = failure(cause)
  }
}

onMounted(load)
watch(() => props.taskId, load)

async function submit() {
  if (!task.value) return
  if (!reason.value.trim()) {
    error.value = '请填写裁定依据'
    return
  }
  if (verdict.value === 'REPLACED' && !collection.value) {
    error.value = '以现场版本替换时必须提交现场采集内容'
    return
  }
  busy.value = true
  error.value = ''
  try {
    task.value = await reviewApi.submitResult(task.value.id, verdict.value, reason.value.trim(), collection.value)
    notice.value = '最终裁定已提交，异议单与错误单已关闭，系统已通知争议双方。'
    reason.value = ''
    collection.value = null
    await Promise.all([loadTasks(), loadIssues()])
  } catch (cause) {
    error.value = failure(cause)
  } finally {
    busy.value = false
  }
}

async function assign() {
  if (!task.value) return
  busy.value = true
  error.value = ''
  try {
    task.value = await reviewApi.assign(task.value.id, assignee.value || null)
    notice.value = '已更新现场任务处理人'
  } catch (cause) {
    error.value = failure(cause)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="review-panel">
    <button type="button" class="text-button back-link" @click="emit('back')">← 返回任务列表</button>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="notice" class="success">{{ notice }}</p>

    <template v-if="task">
      <section class="card detail-head">
        <div class="detail-title">
          <div>
            <span class="eyebrow">第三方现场任务</span>
            <h2>{{ task.disputeReason || '异议现场复核' }}</h2>
          </div>
          <StatusPill :label="taskStatusLabels[task.status]" :tone="taskStatusTones[task.status]" />
        </div>
        <dl class="revision-details">
          <div><dt>POI</dt><dd class="mono">{{ task.poiId }}</dd></div>
          <div><dt>处理人</dt><dd>{{ task.assignee?.displayName ?? '待分配' }}</dd></div>
          <div><dt>创建时间</dt><dd>{{ formatTime(task.createdAt) }}</dd></div>
          <div><dt>分配时间</dt><dd>{{ formatTime(task.assignedAt) }}</dd></div>
          <div><dt>裁定时间</dt><dd>{{ formatTime(task.decidedAt) }}</dd></div>
          <div><dt>任务标识</dt><dd class="mono">{{ task.id }}</dd></div>
        </dl>
        <button type="button" class="secondary-button" @click="emit('openIssue', task.issueId)">查看关联错误单</button>
      </section>

      <section v-if="task.status === 'COMPLETED'" class="card dispute-card">
        <h3>最终裁定</h3>
        <p><StatusPill :label="task.verdict ? verdictLabels[task.verdict] : '—'" tone="ok" /></p>
        <p>{{ task.reason }}</p>
        <p class="hint">最终有效版本：<span class="mono">{{ task.finalRevisionId }}</span>。当事双方不能再修改该裁定。</p>
      </section>

      <div v-if="issue" class="revision-grid">
        <RevisionCard title="被标识的版本" :revision="issue.flaggedRevision" :submitted-by="issue.issue.collector" />
        <RevisionCard v-if="issue.currentRevision && issue.currentRevision.id !== issue.flaggedRevision?.id" title="采集者当前版本" :revision="issue.currentRevision" :submitted-by="issue.issue.collector" />
        <RevisionCard v-if="issue.finalRevision" title="最终有效版本" :revision="issue.finalRevision" />
      </div>

      <section v-if="canAssign" class="card action-card">
        <h3>{{ task.status === 'UNASSIGNED' ? '分配处理人' : '改派处理人' }}</h3>
        <form class="action-form" @submit.prevent="assign">
          <label>第三方工作人员
            <select v-model="assignee">
              <option value="">按负载自动选择</option>
              <option v-for="account in workers" :key="account.id" :value="account.id">{{ account.displayName }}</option>
            </select>
          </label>
          <p class="hint">异议当事人不能担任处理人；任务裁定后不可改派。</p>
          <div class="form-actions"><button class="primary-button" :disabled="busy">确认</button></div>
        </form>
      </section>

      <section v-if="canDecide" class="card action-card">
        <h3>提交现场结论</h3>
        <p class="hint">到现场重新采集、核验后提交。两种结论都必须指定最终有效版本；提交后即为最终结果。</p>
        <form class="action-form" @submit.prevent="submit">
          <fieldset class="verdict-row">
            <legend>裁定结论</legend>
            <label class="check"><input v-model="verdict" type="radio" value="ORIGINAL_CORRECT" />原数据无误（当前采集者版本为最终版本）</label>
            <label class="check"><input v-model="verdict" type="radio" value="REPLACED" />以现场采集版本替换</label>
          </fieldset>
          <CollectionForm
            v-if="verdict === 'REPLACED'"
            submit-label="先采集现场内容"
            :busy="busy"
            hint="现场采集版本会作为该 POI 的最终有效版本，因此必须包含照片与设备定位。"
            @submit="collection = $event"
          />
          <p v-if="verdict === 'REPLACED' && collection" class="success">
            已填写现场采集内容：{{ collection.category }}，{{ collection.latitude.toFixed(6) }}, {{ collection.longitude.toFixed(6) }}
          </p>
          <label>裁定依据<textarea v-model="reason" rows="3" maxlength="2000" placeholder="现场看到的情况与判断依据"></textarea></label>
          <div class="form-actions">
            <button class="primary-button" :disabled="busy">{{ busy ? '正在提交…' : '提交最终裁定' }}</button>
          </div>
        </form>
      </section>

      <p v-else-if="task.status === 'UNASSIGNED'" class="empty-state">任务尚未分配处理人，请管理员在后台指派。</p>
    </template>
    <p v-else-if="!error" class="hint">正在加载任务…</p>
  </div>
</template>
