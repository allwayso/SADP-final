<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { user } from '../api'
import { categories, listPois, locate } from '../modules/poi/api'
import type { PoiSummary } from '../modules/poi/api'

const category = ref('')
const progress = ref('')
const bbox = ref('')
const nearLabel = ref('')
const items = ref<PoiSummary[]>([])
const total = ref(0)
const loading = ref(false)
const locating = ref(false)
const error = ref('')

const canCollect = computed(() => Boolean(user.value?.roles.includes('COLLECTOR')))

/** 未接入地图 SDK 时的示意分布图：按经纬度归一到 0—100 画点，用于演示筛选结果。 */
const points = computed(() => {
  const list = items.value
  if (!list.length) return []
  const lons = list.map(item => item.lon)
  const lats = list.map(item => item.lat)
  const minLon = Math.min(...lons)
  const maxLon = Math.max(...lons)
  const minLat = Math.min(...lats)
  const maxLat = Math.max(...lats)
  const spanLon = maxLon - minLon || 0.0005
  const spanLat = maxLat - minLat || 0.0005
  return list.map(item => ({
    id: item.id,
    name: item.name,
    categoryLabel: item.categoryLabel,
    error: Boolean(item.review?.errorFlag),
    x: 6 + ((item.lon - minLon) / spanLon) * 88,
    y: 6 + (1 - (item.lat - minLat) / spanLat) * 88,
  }))
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const page = await listPois({
      category: category.value,
      progress: progress.value,
      bbox: bbox.value,
      page: 0,
      size: 100,
    })
    items.value = page.items
    total.value = page.total
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '无法加载 POI 列表'
  } finally {
    loading.value = false
  }
}

async function useMyLocation() {
  locating.value = true
  error.value = ''
  try {
    const fix = await locate()
    const delta = 0.01
    bbox.value = [fix.lon - delta, fix.lat - delta, fix.lon + delta, fix.lat + delta]
      .map(value => value.toFixed(6))
      .join(',')
    nearLabel.value = `以 ${fix.lon}, ${fix.lat} 为中心约 2 公里范围`
    await load()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '定位失败'
  } finally {
    locating.value = false
  }
}

function clearBbox() {
  bbox.value = ''
  nearLabel.value = ''
  void load()
}

onMounted(load)
</script>

<template>
  <section class="page-heading">
    <div class="eyebrow">M2 POI 采集与地图</div>
    <h1>POI 分布与处理进展</h1>
    <p>按类别和地图范围查询点位，查看错误标识与处理进展；选中点位可查看照片、版本并打开导航入口。</p>
  </section>

  <div class="poi-toolbar card">
    <label>分类
      <select v-model="category" @change="load">
        <option value="">全部类别</option>
        <option v-for="item in categories" :key="item.value" :value="item.value">{{ item.label }}</option>
      </select>
    </label>
    <label>处理进展（由核验环节写入）
      <input v-model="progress" placeholder="留空看全部，例如 ERROR_OPEN" @keyup.enter="load" />
    </label>
    <div class="poi-toolbar-actions">
      <button class="secondary-button" type="button" :disabled="locating" @click="useMyLocation">
        {{ locating ? '定位中…' : '按我的位置筛选' }}
      </button>
      <button class="primary-button" type="button" :disabled="loading" @click="load">{{ loading ? '查询中…' : '查询' }}</button>
    </div>
  </div>

  <p v-if="nearLabel" class="small-note">
    {{ nearLabel }}
    <button class="text-button" type="button" @click="clearBbox">清除范围</button>
  </p>
  <p v-if="error" class="error" role="alert">{{ error }}</p>

  <p v-if="!loading && !items.length && !error" class="muted">目前没有符合条件的 POI。{{ canCollect ? '可以先提交一次采集。' : '' }}</p>

  <div v-if="items.length" class="poi-layout">
    <section class="card poi-map">
      <div class="poi-map-head">
        <strong>点位示意（共 {{ total }} 条）</strong>
        <span class="small-note">未接入地图平台 SDK，此图为按经纬度绘制的示意分布，接入后替换为地图组件。</span>
      </div>
      <svg viewBox="0 0 100 100" role="img" aria-label="POI 点位示意分布图" class="poi-scatter">
        <rect x="0" y="0" width="100" height="100" rx="2" class="poi-scatter-bg" />
        <line v-for="n in 4" :key="`v${n}`" :x1="n * 20" y1="0" :x2="n * 20" y2="100" class="poi-grid" />
        <line v-for="n in 4" :key="`h${n}`" x1="0" :y1="n * 20" x2="100" :y2="n * 20" class="poi-grid" />
        <circle
          v-for="point in points"
          :key="point.id"
          :cx="point.x"
          :cy="point.y"
          :r="point.error ? 2.6 : 2"
          :class="point.error ? 'poi-dot poi-dot-error' : 'poi-dot'"
        />
      </svg>
      <p class="small-note">橙点为已标识错误的 POI，绿点为其他点位。列表中的链接可进入详情。</p>
      <RouterLink v-if="canCollect" to="/poi/new" class="inline-link">去采集一条新 POI →</RouterLink>
    </section>

    <section class="poi-results" aria-label="POI 列表">
      <RouterLink v-for="item in items" :key="item.id" :to="`/poi/${item.id}`" class="card poi-row">
        <div class="poi-row-head">
          <strong>{{ item.name }}</strong>
          <span class="badge">{{ item.categoryLabel }}</span>
          <span class="badge" :class="item.review?.errorFlag ? 'badge-error' : ''">
            {{ item.review?.errorFlag ? '已标识错误' : '无错误标识' }}
          </span>
          <span class="badge badge-muted">进展：{{ item.review?.progress ?? '未同步' }}</span>
        </div>
        <p class="mono small-note">{{ item.lon }}, {{ item.lat }} · {{ item.crs }}</p>
        <p class="small-note">第 {{ item.revisionNo }} 版 · 共 {{ item.revisionCount }} 个采集版本</p>
      </RouterLink>
    </section>
  </div>
</template>
