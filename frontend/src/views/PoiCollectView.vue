<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { categories, getPoi, locate, suggestFromPhoto, submitNewPoi, submitRevision } from '../modules/poi/api'
import type { DeviceFix, PoiCategory, PoiSummary } from '../modules/poi/api'

const route = useRoute()
const router = useRouter()

const poiId = computed(() => (typeof route.params.id === 'string' ? route.params.id : ''))
const existing = ref<PoiSummary | null>(null)
const nextRevisionNo = computed(() => (existing.value ? existing.value.revisionNo + 1 : 1))

const category = ref<PoiCategory>('SHOP')
const name = ref('')
const description = ref('')
const files = ref<File[]>([])
const previews = ref<string[]>([])
const fix = ref<DeviceFix | null>(null)
const locating = ref(false)
const locationError = ref('')
const ocrText = ref('')
const ocrCategory = ref<PoiCategory | null>(null)
const ocrSimulated = ref(false)
const ocrBusy = ref(false)
const submitting = ref(false)
const error = ref('')
const notice = ref('')

function releasePreviews() {
  previews.value.forEach(url => URL.revokeObjectURL(url))
  previews.value = []
}

function onFiles(event: Event) {
  const input = event.target as HTMLInputElement
  releasePreviews()
  files.value = Array.from(input.files ?? []).slice(0, 5)
  previews.value = files.value.map(file => URL.createObjectURL(file))
  ocrText.value = ''
  ocrCategory.value = null
  ocrSimulated.value = false
  fix.value = null
  if (files.value.length > 0) void refreshLocation()
}

async function refreshLocation() {
  locating.value = true
  locationError.value = ''
  fix.value = null
  try {
    fix.value = await locate()
  } catch (cause) {
    locationError.value = cause instanceof Error ? cause.message : '定位失败，请重试'
  } finally {
    locating.value = false
  }
}

async function runOcr() {
  if (!files.value.length) {
    error.value = '请先拍摄或选择照片，再尝试识别招牌文字'
    return
  }
  ocrBusy.value = true
  error.value = ''
  try {
    const suggestion = await suggestFromPhoto(files.value[0])
    ocrSimulated.value = suggestion.simulated
    ocrText.value = suggestion.text ?? ''
    ocrCategory.value = suggestion.suggestedCategory
    notice.value = suggestion.simulated
      ? '文字识别平台未配置，本次为模拟入口，没有识别结果。'
      : '识别结果只作为建议，请确认后再提交。'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '识别失败，可人工填写分类与描述'
  } finally {
    ocrBusy.value = false
  }
}

function applyOcrCategory() {
  if (!ocrCategory.value) return
  category.value = ocrCategory.value
  notice.value = '已采用识别建议的分类，提交前请再确认。'
}

async function submit() {
  error.value = ''
  notice.value = ''
  if (!files.value.length) {
    error.value = '请先拍摄或选择至少一张照片'
    return
  }
  if (!fix.value) {
    error.value = locationError.value || '尚未取得拍照位置：请允许定位后重试，位置不能手动填写'
    return
  }
  if (!name.value.trim()) {
    error.value = '请填写 POI 名称'
    return
  }
  const form = new FormData()
  if (existing.value) form.append('expectedRevisionId', existing.value.currentRevisionId)
  form.append('category', category.value)
  form.append('name', name.value.trim())
  form.append('description', description.value.trim())
  form.append('lon', String(fix.value.lon))
  form.append('lat', String(fix.value.lat))
  form.append('crs', fix.value.crs)
  form.append('locatedAt', fix.value.locatedAt)
  if (fix.value.accuracyMeters !== null) form.append('accuracyMeters', String(fix.value.accuracyMeters))
  files.value.forEach(file => form.append('photos', file))
  submitting.value = true
  try {
    const result = poiId.value ? await submitRevision(poiId.value, form) : await submitNewPoi(form)
    await router.push({
      path: `/poi/${result.poiId}`,
      query: { notice: `已提交第 ${result.revisionNo} 版，等待核验者处理` },
    })
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '提交失败，请重试'
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  if (!poiId.value) {
    void refreshLocation()
    return
  }
  try {
    const detail = await getPoi(poiId.value)
    existing.value = detail.poi
    category.value = detail.poi.category
    name.value = detail.poi.name
    description.value = detail.poi.description
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '无法读取该 POI'
  }
  void refreshLocation()
})

onUnmounted(releasePreviews)
</script>

<template>
  <section class="page-heading">
    <div class="eyebrow">{{ poiId ? '整改或更新采集' : 'POI 采集' }}</div>
    <h1>{{ poiId ? '提交新的采集版本' : '拍摄并上传 POI' }}</h1>
    <p>拍照位置由设备自动感知并随照片上传，不能手动填写。定位失败时请重试或允许定位，照片草稿会保留。</p>
  </section>

  <p v-if="notice" class="success">{{ notice }}</p>
  <p v-if="error" class="error" role="alert">{{ error }}</p>

  <div class="poi-collect">
    <section class="card poi-panel">
      <h2>1. 照片与位置</h2>
      <label class="photo-picker">
        <span>拍摄或选择照片（最多 5 张）</span>
        <input type="file" accept="image/*" capture="environment" multiple @change="onFiles" />
      </label>
      <div v-if="previews.length" class="photo-previews">
        <img v-for="(src, index) in previews" :key="index" :src="src" alt="待上传照片预览" />
      </div>
      <dl class="details">
        <div><dt>拍照位置</dt><dd class="mono">{{ fix ? `${fix.lon}, ${fix.lat}` : locating ? '定位中…' : '尚未取得' }}</dd></div>
        <div><dt>定位精度</dt><dd>{{ fix?.accuracyMeters != null ? `±${fix.accuracyMeters} 米` : '—' }}</dd></div>
        <div><dt>定位时间</dt><dd>{{ fix ? new Date(fix.locatedAt).toLocaleString() : '—' }}</dd></div>
        <div><dt>坐标系</dt><dd>{{ fix?.crs ?? '—' }}（保留原始坐标，不与其他坐标系混用）</dd></div>
      </dl>
      <p v-if="locationError" class="error" role="alert">{{ locationError }}</p>
      <button class="secondary-button" type="button" :disabled="locating" @click="refreshLocation">
        {{ locating ? '正在定位…' : '重新定位' }}
      </button>
    </section>

    <section class="card poi-panel">
      <h2>2. 分类与描述</h2>
      <form @submit.prevent="submit">
        <label>分类
          <select v-model="category">
            <option v-for="item in categories" :key="item.value" :value="item.value">{{ item.label }}</option>
          </select>
        </label>
        <label>名称<input v-model="name" maxlength="120" placeholder="例如：老张面馆" /></label>
        <label>描述<textarea v-model="description" rows="3" placeholder="门牌、招牌、外观等可供核验的说明"></textarea></label>
        <div class="ocr-box">
          <div class="ocr-head">
            <strong>文字识别辅助</strong>
            <button type="button" class="text-button" :disabled="ocrBusy" @click="runOcr">{{ ocrBusy ? '识别中…' : '识别招牌文字' }}</button>
          </div>
          <p v-if="ocrSimulated" class="small-note">识别平台未接入，此次为模拟入口，无识别结果，可人工填写分类与描述。</p>
          <p v-else-if="ocrText" class="mono">{{ ocrText }}</p>
          <p v-else class="small-note">识别结果只作为分类建议，必须由你确认后才写入数据。</p>
          <button v-if="ocrCategory" type="button" class="secondary-button" @click="applyOcrCategory">采用建议分类</button>
        </div>
        <p class="small-note">{{ poiId ? `本次提交将形成第 ${nextRevisionNo} 版，历史版本保留。` : '首次提交将形成第 1 个采集版本。' }}</p>
        <button class="primary-button" type="submit" :disabled="submitting">{{ submitting ? '提交中…' : poiId ? '提交新版本' : '提交采集' }}</button>
      </form>
      <RouterLink v-if="poiId" class="inline-link" :to="`/poi/${poiId}`">返回该 POI 详情 →</RouterLink>
    </section>
  </div>
</template>
