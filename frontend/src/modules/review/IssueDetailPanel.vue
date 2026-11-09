<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api, user } from '../../api'
import { reviewApi } from './api'
import {
  actionLabels,
  formatTime,
  issueStatusLabels,
  issueStatusTones,
  taskStatusLabels,
  taskStatusTones,
  verdictLabels,
} from './labels'
import { failure, loadIssues, loadPending } from './store'
import type { CollectionInput, IssueAction, IssueDetail } from './types'
import CollectionForm from './CollectionForm.vue'
import RevisionCard from './RevisionCard.vue'
import StatusPill from './StatusPill.vue'

const props = defineProps<{ issueId: string }>()
const emit = defineEmits<{ back: []; openTask: [taskId: string] }>()

const detail = ref<IssueDetail | null>(null)
const error = ref('')
const notice = ref('')
const busy = ref(false)
const active = ref<IssueAction | null>(null)
const note = ref('')
const collection = ref<CollectionInput | null>(null)
const assignee = ref('')
const workers = ref<{ id: string; displayName: string }[]>([])

const issue = computed(() => detail.value?.issue ?? null)
const actions = computed(() => detail.value?.allowedActions ?? [])
const needsReason = computed(() => active.value === 'REJECTED' || active.value === 'CHANGES_REQUESTED' || active.value === 'DISPUTED')
const isAdmin = computed(() => user.value?.roles.includes('ADMIN') ?? false)

async function load() {
  error.value = ''
  try {
    detail.value = await reviewApi.issue(props.issueId)
    active.value = null
    note.value = ''
    collection.value = null
    assignee.value = ''
    if (isAdmin.value && detail.value.allowedActions.includes('ASSIGNED') && workers.value.length === 0) {
      workers.value = (await api<{ id: string; displayName: string; roles: string[] }[]>('/admin/users'))
        .filter(account => account.roles.includes('FIELD_WORKER'))
    }
  } catch (cause) {
    error.value = failure(cause)
  }
}

onMounted(load)
watch(() => props.issueId, load)

function start(action: IssueAction) {
  active.value = action
  note.value = ''
  collection.value = null
  error.value = ''
  notice.value = ''
}

async function submit() {
  if (!detail.value) return
  const revisionId = detail.value.issue.currentRevisionId
  if (needsReason.value && !note.value.trim()) {
    error.value = '此操作必须填写说明'
    return
  }
  if (active.value === 'CORRECTED' && !collection.value) {
    error.value = '请先填写现场采集内容'
    return
  }
  busy.value = true
  error.value = ''
  try {
    switch (active.value) {
      case 'NOTE':
        detail.value = await reviewApi.act(detail.value.issue.id, 'notes', { revisionId, note: note.value.trim() || null })
        break
      case 'CORRECTED':
        detail.value = await reviewApi.correct(detail.value.issue.id, revisionId, collection.value as CollectionInput, note.value.trim() || undefined)
        break
      case 'REJECTED':
        detail.value = await reviewApi.act(detail.value.issue.id, 'rejections', { revisionId, note: note.value.trim() })
        break
      case 'RESUMED':
        detail.value = await reviewApi.act(detail.value.issue.id, 'resumptions', { revisionId, note: note.value.trim() || null })
        break
      case 'CONFIRMED':
        detail.value = await reviewApi.act(detail.value.issue.id, 'confirmations', { revisionId, note: note.value.trim() || null })
        break
      case 'CHANGES_REQUESTED':
        detail.value = await reviewApi.act(detail.value.issue.id, 'change-requests', { revisionId, note: note.value.trim() })
        break
      case 'WITHDRAWN':
        detail.value = await reviewApi.act(detail.value.issue.id, 'withdrawals', { revisionId, note: note.value.trim() || null })
        break
      case 'DISPUTED':
        await reviewApi.submitDispute(detail.value.issue.id, revisionId, note.value.trim())
        detail.value = await reviewApi.issue(detail.value.issue.id)
        break
      default:
        break
    }
    notice.value = `已完成：${active.value ? actionLabels[active.value] : '操作'}`
    active.value = null
    note.value = ''
    collection.value = null
    await Promise.all([loadIssues(), loadPending()])
  } catch (cause) {
    error.value = failure(cause)
  } finally {
    busy.value = false
  }
}

async function assign() {
  if (!detail.value?.task) return
  busy.value = true
  error.value = ''
  try {
    await reviewApi.assign(detail.value.task.id, assignee.value || null)
    notice.value = '已更新现场任务处理人，被指派人员会收到通知'
    active.value = null
    detail.value = await reviewApi.issue(detail.value.issue.id)
  } catch (cause) {
    error.value = failure(cause)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="review-panel">
    <button type="button" class="text-button back-link" @click="emit('back')">← 返回列表</button>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="notice" class="success">{{ notice }}</p>

    <template v-if="detail && issue">
      <section class="card detail-head">
        <div class="detail-title">
          <div>
            <span class="eyebrow">错误单</span>
            <h2>{{ issue.description }}</h2>
          </div>
          <StatusPill :label="issueStatusLabels[issue.status]" :tone="issueStatusTones[issue.status]" />
        </div>
        <dl class="revision-details">
          <div><dt>POI</dt><dd class="mono">{{ issue.poiId }}</dd></div>
          <div><dt>采集者</dt><dd>{{ issue.collector.displayName }}</dd></div>
          <div><dt>核验者</dt><dd>{{ issue.reviewer.displayName }}</dd></div>
          <div><dt>建立时间</dt><dd>{{ formatTime(issue.createdAt) }}</dd></div>
          <div><dt>当前版本</dt><dd class="mono">{{ issue.currentRevisionId }}</dd></div>
          <div><dt>最终版本</dt><dd class="mono">{{ issue.finalRevisionId ?? '尚未确定' }}</dd></div>
        </dl>
      </section>

      <div class="revision-grid">
        <RevisionCard title="被标识的版本" :revision="detail.flaggedRevision" />
        <RevisionCard
          v-if="detail.currentRevision && detail.currentRevision.id !== detail.flaggedRevision?.id"
          title="当前整改版本"
          :revision="detail.currentRevision"
        />
        <RevisionCard v-if="detail.finalRevision" title="最终有效版本" :revision="detail.finalRevision" />
      </div>

      <section v-if="detail.dispute" class="card dispute-card">
        <h3>异议</h3>
        <p>{{ detail.dispute.reason }}</p>
        <p class="hint">提交人 {{ detail.dispute.submittedBy.displayName }} · {{ formatTime(detail.dispute.createdAt) }} ·
          {{ detail.dispute.status === 'DECIDED' ? '已裁定' : '处理中' }}</p>
      </section>

      <section v-if="detail.task" class="card task-card-inline">
        <h3>现场任务</h3>
        <p>
          <StatusPill :label="taskStatusLabels[detail.task.status]" :tone="taskStatusTones[detail.task.status]" />
          <span class="hint">处理人 {{ detail.task.assignee?.displayName ?? '待分配' }}</span>
        </p>
        <p v-if="detail.task.verdict" class="hint">裁定：{{ verdictLabels[detail.task.verdict] }} · {{ detail.task.reason }}</p>
        <button type="button" class="secondary-button" @click="emit('openTask', detail.task.id)">查看现场任务</button>
      </section>

      <section class="card action-card">
        <h3>当前可执行操作</h3>
        <p v-if="!actions.length" class="hint">当前账号在此状态下没有可执行的操作。</p>
        <div class="action-bar">
          <button
            v-for="item in actions.filter(action => action !== 'DECIDED')"
            :key="item"
            type="button"
            class="secondary-button"
            :class="{ 'is-active': active === item }"
            @click="start(item)"
          >{{ item === 'ASSIGNED' ? '分配/改派第三方' : actionLabels[item] }}</button>
          <button v-if="actions.includes('DECIDED') && detail.task" type="button" class="primary-button" @click="emit('openTask', detail.task.id)">
            去提交最终裁定
          </button>
        </div>

        <form v-if="active && active !== 'ASSIGNED'" class="action-form" @submit.prevent="submit">
          <CollectionForm
            v-if="active === 'CORRECTED'"
            submit-label="与说明一起提交"
            :busy="busy"
            hint="整改需要新的采集版本：位置由设备定位，描述与照片用于核验者复核。"
            @submit="collection = $event"
          />
          <p v-if="active === 'CORRECTED' && collection" class="success">已填写采集内容：{{ collection.category }}，{{ collection.latitude.toFixed(6) }}, {{ collection.longitude.toFixed(6) }}</p>
          <label>
            说明<span v-if="needsReason" class="required">必填</span>
            <textarea v-model="note" rows="3" maxlength="2000" :placeholder="active === 'DISPUTED' ? '说明无法达成共识的原因，系统会安排第三方现场复核' : needsReason ? '说明理由，便于对方理解' : '可选补充说明'"></textarea>
          </label>
          <div class="form-actions">
            <button class="primary-button" :disabled="busy">{{ busy ? '正在提交…' : actionLabels[active] }}</button>
            <button type="button" class="text-button" @click="active = null">取消</button>
          </div>
        </form>

        <form v-if="active === 'ASSIGNED'" class="action-form" @submit.prevent="assign">
          <label>第三方处理人
            <select v-model="assignee">
              <option value="">按负载自动选择</option>
              <option v-for="account in workers" :key="account.id" :value="account.id">{{ account.displayName }}</option>
            </select>
          </label>
          <p class="hint">异议当事人不能担任第三方处理人，后端会再次校验。</p>
          <div class="form-actions">
            <button class="primary-button" :disabled="busy">确认分配</button>
            <button type="button" class="text-button" @click="active = null">取消</button>
          </div>
        </form>
      </section>

      <section class="card timeline-card">
        <h3>处理历史</h3>
        <ol class="timeline">
          <li v-for="(event, index) in detail.events" :key="index">
            <span class="timeline-dot" aria-hidden="true"></span>
            <div>
              <strong>{{ actionLabels[event.action] }}</strong>
              <span class="hint"> · {{ event.actor.displayName }} · {{ formatTime(event.createdAt) }}</span>
              <p v-if="event.note" class="timeline-note">{{ event.note }}</p>
              <p class="hint">{{ event.fromStatus ? issueStatusLabels[event.fromStatus] + ' → ' : '' }}{{ issueStatusLabels[event.toStatus] }}</p>
            </div>
          </li>
        </ol>
      </section>
    </template>
    <p v-else-if="!error" class="hint">正在加载错误单…</p>
  </div>
</template>
