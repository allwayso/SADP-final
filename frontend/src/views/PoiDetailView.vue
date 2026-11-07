<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { user } from '../api'
import { categories, getPoi, navigationTarget, photoUrl, purposeLabels } from '../modules/poi/api'
import type { NavigationTarget, PoiCategory, PoiDetail } from '../modules/poi/api'

const categoryLabels = new Map(categories.map(item => [item.value, item.label]))

function categoryLabel(value: PoiCategory): string {
  return categoryLabels.get(value) ?? value
}

const route = useRoute()
const poiId = computed(() => String(route.params.id ?? ''))
const detail = ref<PoiDetail | null>(null)
const loading = ref(false)
const error = ref('')
const navigation = ref<NavigationTarget | null>(null)
const navBusy = ref(false)
const notice = ref(typeof route.query.notice === 'string' ? route.query.notice : '')

const canCollect = computed(() => Boolean(user.value?.roles.includes('COLLECTOR')))

function formatTime(value: string | null | undefined): string {
  if (!value) return '—'
  return new Date(value).toLocaleString()
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    detail.value = await getPoi(poiId.value)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '无法读取该 POI'
  } finally {
    loading.value = false
  }
}

async function openNavigation() {
  error.value = ''
  navBusy.value = true
  try {
    const target = await navigationTarget(poiId.value)
    navigation.value = target
    window.open(target.url, '_blank', 'noopener')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '无法打开导航入口'
  } finally {
    navBusy.value = false
  }
}

onMounted(load)
watch(poiId, load)
</script>

<template>
  <section class="page-heading">
    <div class="eyebrow">POI 详情</div>
    <h1>{{ detail?.poi.name ?? 'POI' }}</h1>
    <p>采集版本与照片由本模块保存；核验状态与处理进展由核验环节同步，此处只展示。</p>
  </section>

  <p v-if="notice" class="success">{{ notice }}</p>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
  <p v-if="loading" class="muted">正在读取…</p>

  <template v-if="detail">
    <div class="poi-detail">
      <section class="card poi-panel">
        <h2>基本信息</h2>
        <dl class="details">
          <div><dt>分类</dt><dd>{{ detail.poi.categoryLabel }}</dd></div>
          <div><dt>位置</dt><dd class="mono">{{ detail.poi.lon }}, {{ detail.poi.lat }}</dd></div>
          <div><dt>坐标系</dt><dd>{{ detail.poi.crs }}（原始坐标）</dd></div>
          <div><dt>当前版本</dt><dd>第 {{ detail.poi.revisionNo }} 版，共 {{ detail.poi.revisionCount }} 个采集版本</dd></div>
          <div><dt>描述</dt><dd>{{ detail.poi.description || '未填写' }}</dd></div>
          <div><dt>提交时间</dt><dd>{{ formatTime(detail.poi.createdAt) }}</dd></div>
          <div><dt>最近更新</dt><dd>{{ formatTime(detail.poi.updatedAt) }}</dd></div>
        </dl>
        <div class="poi-actions">
          <button class="primary-button" type="button" :disabled="navBusy" @click="openNavigation">
            {{ navBusy ? '正在取得…' : '路线 / 导航到此处' }}
          </button>
          <RouterLink v-if="canCollect" class="secondary-button" :to="`/poi/${detail.poi.id}/revisions/new`">提交新版本</RouterLink>
          <RouterLink class="inline-link" to="/poi">返回分布列表 →</RouterLink>
        </div>
        <p v-if="navigation" class="small-note">
          {{ navigation.note }}<span v-if="navigation.simulated" class="badge badge-muted">模拟入口</span>
          <a :href="navigation.url" target="_blank" rel="noopener" class="inline-link">再次打开地图链接</a>
        </p>
      </section>

      <section class="card poi-panel">
        <h2>核验摘要</h2>
        <dl v-if="detail.poi.review" class="details">
          <div><dt>状态</dt><dd>{{ detail.poi.review.state ?? '—' }}</dd></div>
          <div><dt>处理进展</dt><dd>{{ detail.poi.review.progress ?? '—' }}</dd></div>
          <div><dt>错误标识</dt><dd>{{ detail.poi.review.errorFlag ? '已标识错误' : '无错误标识' }}</dd></div>
          <div><dt>说明</dt><dd>{{ detail.poi.review.text || '—' }}</dd></div>
          <div><dt>同步时间</dt><dd>{{ formatTime(detail.poi.review.updatedAt) }}</dd></div>
        </dl>
        <p v-else class="muted">核验环节尚未同步摘要。地图上的错误标识与进展会在核验后出现。</p>
      </section>
    </div>

    <section class="poi-revisions">
      <h2>采集版本</h2>
      <article v-for="revision in detail.revisions" :key="revision.id" class="card poi-panel poi-revision">
        <div class="poi-row-head">
          <strong>第 {{ revision.revisionNo }} 版</strong>
          <span class="badge">{{ purposeLabels[revision.purpose] }}</span>
          <span v-if="revision.id === detail.poi.currentRevisionId" class="badge badge-current">当前有效版本</span>
          <span v-if="revision.sourceTaskId" class="badge badge-muted">现场任务 {{ revision.sourceTaskId.slice(0, 8) }}…</span>
        </div>
        <p class="small-note mono">{{ revision.lon }}, {{ revision.lat }} · {{ revision.crs }}
          <span v-if="revision.accuracyMeters != null">· 精度 ±{{ revision.accuracyMeters }} 米</span>
        </p>
        <p class="small-note">拍照定位时间 {{ formatTime(revision.locatedAt) }} · 提交于 {{ formatTime(revision.submittedAt) }}</p>
        <p class="small-note">{{ categoryLabel(revision.category) }} · {{ revision.description || '无描述' }}</p>
        <div v-if="revision.photos.length" class="photo-previews">
          <img v-for="photo in revision.photos" :key="photo.id" :src="photoUrl(detail.poi.id, photo.id)" :alt="`第 ${revision.revisionNo} 版照片`" />
        </div>
        <p v-if="revision.photos.some(photo => photo.ocrText)" class="small-note">
          识别文字：{{ revision.photos.map(photo => photo.ocrText).filter(Boolean).join(' / ') }}
        </p>
      </article>
    </section>
  </template>
</template>
