<script setup lang="ts">
import { formatTime, position, purposeLabels, revisionTitle } from './labels'
import type { PoiRevision, UserRef } from './types'

defineProps<{ title: string; revision: PoiRevision | null; submittedBy?: UserRef | null }>()

/** 现场采集版本等没有显示名时退化为标识前缀，避免出现空白。 */
function shortId(id: string | null | undefined): string {
  return id ? `${id.slice(0, 8)}…` : '—'
}
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
        <div><dt>提交人</dt><dd>{{ submittedBy?.displayName ?? shortId(revision.submittedBy) }}</dd></div>
        <div><dt>照片</dt><dd><img v-for="photo in revision.photoIds" :key="photo" :src="`/api/pois/${revision.poiId}/photos/${photo}`" alt="采集照片" style="max-width:100%;max-height:360px" /></dd></div>
        <div><dt>版本标识</dt><dd class="mono">{{ revision.id }}</dd></div>
      </dl>
    </template>
    <p v-else class="hint">没有对应版本。</p>
  </article>
</template>
