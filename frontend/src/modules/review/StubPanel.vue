<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { reviewApi } from './api'
import { formatTime, progressLabels } from './labels'
import type { StubNotification, StubPoi } from './types'

const pois = ref<StubPoi[]>([])
const notifications = ref<StubNotification[]>([])
const error = ref('')
const unavailable = ref('')

onMounted(async () => {
  try {
    notifications.value = await reviewApi.stubNotifications()
  } catch (cause) {
    unavailable.value = cause instanceof Error ? cause.message : '通知桩不可用'
  }
  try {
    pois.value = await reviewApi.stubPois()
  } catch (cause) {
    if (!unavailable.value) error.value = cause instanceof Error ? cause.message : '无法读取 POI 桩'
  }
})

function label(progress: StubPoi['progress']): string {
  return progress ? progressLabels[progress] : '—'
}
</script>

<template>
  <div class="review-panel">
    <p class="hint">
      联调辅助信息：M2 采集与地图、M4 消息尚未接入时，核验模块通过端口调用内存桩。
      这里显示的是 M3 写给地图的核验摘要，以及发给 M4 的通知记录，用于核对状态同步与通知对象。
      真实适配器接入后（<code>REVIEW_POI_STUB=false</code>、<code>REVIEW_NOTIFICATION_STUB=false</code>）本页不再有数据。
    </p>
    <p v-if="unavailable" class="empty-state">{{ unavailable }}</p>

    <section class="card stub-card">
      <h3>地图核验摘要（M3 → M2）</h3>
      <p v-if="!pois.length" class="hint">暂无数据。</p>
      <table v-else class="stub-table">
        <thead><tr><th>POI</th><th>名称</th><th>分类</th><th>版本数</th><th>进展</th><th>说明</th></tr></thead>
        <tbody>
          <tr v-for="poi in pois" :key="poi.poiId">
            <td class="mono">{{ poi.poiId }}</td>
            <td>{{ poi.name ?? '—' }}</td>
            <td>{{ poi.category }}</td>
            <td>{{ poi.revisionCount }}</td>
            <td>{{ label(poi.progress) }}</td>
            <td>{{ poi.note ?? '—' }}</td>
          </tr>
        </tbody>
      </table>
    </section>

    <section class="card stub-card">
      <h3>发给我的通知（M3 → M4）</h3>
      <p v-if="!notifications.length" class="hint">暂无通知记录。</p>
      <table v-else class="stub-table">
        <thead><tr><th>类型</th><th>目标</th><th>关联</th><th>正文</th><th>时间</th></tr></thead>
        <tbody>
          <tr v-for="item in notifications" :key="item.id">
            <td class="mono">{{ item.type }}</td>
            <td class="mono">{{ item.target }}</td>
            <td class="mono">{{ item.referenceType }} {{ item.referenceId.slice(0, 8) }}…</td>
            <td>{{ item.text }}</td>
            <td>{{ formatTime(item.createdAt) }}</td>
          </tr>
        </tbody>
      </table>
    </section>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </div>
</template>
