<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { reviewApi } from './api'
import { failure, loadIssues, loadPending, pendingItems } from './store'
import RevisionCard from './RevisionCard.vue'

const busy = ref('')
const notice = ref('')
const error = ref('')
const flagging = ref<string | null>(null)
const description = ref('')

onMounted(loadPending)

async function pass(revisionId: string) {
  busy.value = revisionId
  error.value = ''
  notice.value = ''
  try {
    await reviewApi.review(revisionId, 'PASSED')
    notice.value = '已核验通过，地图进展将同步为“核验通过”。'
    await Promise.all([loadPending(), loadIssues()])
  } catch (cause) {
    error.value = failure(cause)
  } finally {
    busy.value = ''
  }
}

function startFlag(revisionId: string) {
  flagging.value = revisionId
  description.value = ''
  error.value = ''
  notice.value = ''
}

async function submitFlag(revisionId: string) {
  if (!description.value.trim()) {
    error.value = '请填写错误说明，说明会通知原采集者'
    return
  }
  busy.value = revisionId
  error.value = ''
  try {
    await reviewApi.review(revisionId, 'ISSUE', description.value.trim())
    flagging.value = null
    description.value = ''
    notice.value = '已保存错误标识并通知原采集者，可在“错误单”页签跟进整改。'
    await Promise.all([loadPending(), loadIssues()])
  } catch (cause) {
    error.value = failure(cause)
  } finally {
    busy.value = ''
  }
}
</script>

<template>
  <div class="review-panel">
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="notice" class="success">{{ notice }}</p>
    <p class="hint">列表只包含最新采集版本、没有未关闭错误单且不是本人提交的数据。核验必须针对当前版本。</p>

    <p v-if="!pendingItems.length" class="empty-state">
      暂无待核验数据。M2 采集模块接入后，新采集版本会自动进入此列表；使用桩时请确认已设置
      <code>APP_SEED_DEMO=true</code>。
    </p>

    <article v-for="item in pendingItems" :key="item.revision.id" class="review-item">
      <RevisionCard title="待核验版本" :revision="item.revision" :submitted-by="item.collector" />
      <div class="item-actions">
        <button class="primary-button" :disabled="busy === item.revision.id" @click="pass(item.revision.id)">核验通过</button>
        <button class="secondary-button" :disabled="busy === item.revision.id" @click="startFlag(item.revision.id)">标识错误</button>
      </div>
      <form v-if="flagging === item.revision.id" class="flag-form" @submit.prevent="submitFlag(item.revision.id)">
        <label>错误说明<textarea v-model="description" rows="3" maxlength="2000" placeholder="例如：招牌与分类不符，实际是文具店；坐标偏移约 30 米"></textarea></label>
        <div class="form-actions">
          <button class="primary-button" :disabled="busy === item.revision.id">保存错误标识并通知采集者</button>
          <button type="button" class="text-button" @click="flagging = null">取消</button>
        </div>
      </form>
    </article>
  </div>
</template>
