<script setup lang="ts">
import { user } from '../api'
const tasks = [
  { role: 'COLLECTOR', title: 'POI 采集与地图', description: '提交照片和位置，查看地图上的点位与处理进展。', path: '/poi', label: '进入采集' },
  { role: '', title: '核验与异议处理', description: '核查新数据，标记问题并跟进整改、驳回或异议。', path: '/review', label: '进入核验' },
  { role: '', title: '消息与交流', description: '查看系统通知，与相关人员讨论现场问题。', path: '/messages', label: '查看消息' },
]
</script>

<template>
  <div class="home-layout">
    <section class="page-heading"><div class="eyebrow">工作台</div><h1>你好，{{ user?.displayName }}</h1><p>选择你今天要处理的工作。</p></section>
    <section class="task-grid" aria-label="功能入口">
      <RouterLink v-for="task in tasks.filter(item => !item.role || user?.roles.includes(item.role as never))" :key="task.path" :to="task.path" class="task-card">
        <span class="task-icon" aria-hidden="true">◎</span><h2>{{ task.title }}</h2><p>{{ task.description }}</p><span class="task-link">{{ task.label }} <span aria-hidden="true">↗</span></span>
      </RouterLink>
    </section>
    <RouterLink v-if="user?.roles.includes('ADMIN')" class="inline-link" to="/admin/users">管理团队账号 →</RouterLink>
  </div>
</template>
