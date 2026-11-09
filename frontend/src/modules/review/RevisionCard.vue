<script setup lang="ts">
import { formatTime, position, purposeLabels, revisionTitle } from './labels'
import type { PoiRevision, UserRef } from './types'

defineProps<{ title: string; revision: PoiRevision | null; submittedBy?: UserRef | null }>()
</script>

<template>
  <article class="card revision-card">
    <header class="revision-head">
      <div>
        <span class="eyebrow">{{ title }}</span>
        <h3>{{ revisionTitle(revision) }}</h3>
      </div>
      <span v-if="revision" class="pill tone-info">{{ revision.category }} · v{{ revision.version }} · {{ purposeLabels[revision.purpose] }}</span>
    </header>
    <template v-if="revision">
      <dl class="revision-details">
        <div><dt>描述</dt><dd>{{ revision.description || '未填写' }}</dd></div>
        <div><dt>拍照位置</dt><dd class="mono">{{ position(revision) }}</dd></div>
        <div><dt>定位时间</dt><dd>{{ formatTime(revision.locatedAt) }}</dd></div>
        <div><dt>提交人</dt><dd>{{ submittedBy?.displayName ?? '—' }}</dd></div>
        <div><dt>照片标识</dt><dd class="mono">{{ revision.photoId }}</dd></div>
        <div><dt>版本标识</dt><dd class="mono">{{ revision.id }}</dd></div>
      </dl>
    </template>
    <p v-else class="hint">没有对应版本。</p>
  </article>
</template>
