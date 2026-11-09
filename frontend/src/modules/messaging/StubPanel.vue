<script setup lang="ts">
import type { StubStatus } from './api'
import { statusLabel } from './format'

defineProps<{ status: StubStatus }>()
defineEmits<{ scenario: [value: string]; 'toggle-fault': []; retry: []; redeliver: [id: string] }>()

const scenarios = [
  { key: 'NEW_DATA', label: '新数据待检', hint: '群发核验者' },
  { key: 'ANNOUNCEMENT', label: '暂停工作公告', hint: '群发采集者' },
  { key: 'ISSUE_RAISED', label: '错误标识', hint: '点发采集者并建立私聊' },
  { key: 'FIELD_TASK', label: '现场复核任务', hint: '点发第三方工作人员' },
  { key: 'FINAL_RESULT', label: '最终裁定', hint: '分别点发当事双方' },
]
</script>

<template>
  <details class="msg-stub card">
    <summary>
      <span>开发消息桩</span>
      <small>模拟 B/C 的采集、核验调用，仅用于联调与演示</small>
    </summary>
    <div class="msg-stub-body">
      <p class="small">
        当前投递通道：<code>{{ status.transport }}</code>。B/C 的真实流程尚未接入时，用下面的按钮触发同样的
        notify 调用；通知、收件记录和聊天都会写入数据库。生产环境应设置
        <code>MESSAGING_STUB_ENABLED=false</code> 关闭本面板。
      </p>
      <div class="msg-scenarios">
        <button v-for="scenario in scenarios" :key="scenario.key" class="secondary-button"
                @click="$emit('scenario', scenario.key)">
          {{ scenario.label }}<small>{{ scenario.hint }}</small>
        </button>
      </div>
      <div class="msg-stub-actions">
        <button class="secondary-button" @click="$emit('toggle-fault')">
          {{ status.brokerAvailable ? '模拟中间件中断' : '恢复中间件' }}
        </button>
        <button class="secondary-button" @click="$emit('retry')">立即重试待投递</button>
        <span class="small">
          投递中 {{ status.counts.PENDING ?? 0 }} · 待重试 {{ status.counts.FAILED ?? 0 }} ·
          已完成 {{ status.counts.DELIVERED ?? 0 }} · 退避 {{ Math.round(status.retryIntervalMs / 1000) }}s
        </span>
      </div>
      <table v-if="status.recent.length" class="msg-diagnostics">
        <thead>
          <tr><th>通知</th><th>接收目标</th><th>状态</th><th>尝试</th><th>收件/已确认</th><th></th></tr>
        </thead>
        <tbody>
          <tr v-for="row in status.recent" :key="row.id">
            <td>{{ row.title }}</td>
            <td>{{ row.target }}</td>
            <td><span class="msg-status" :data-status="row.status">{{ statusLabel(row.status) }}</span></td>
            <td>{{ row.attempts }}</td>
            <td>{{ row.recipients }} / {{ row.delivered }}</td>
            <td><button class="text-button" @click="$emit('redeliver', row.id)">重复投递</button></td>
          </tr>
        </tbody>
      </table>
      <p v-else class="small">当前账号还没有发出通知。先点上面的场景按钮生成一条。</p>
    </div>
  </details>
</template>
