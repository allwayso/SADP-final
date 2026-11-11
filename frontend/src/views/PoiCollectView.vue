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
let locationRequest = 0
let locationController: AbortController | undefined

function releasePreviews() {
  previews.value.forEach(url => URL.revokeObjectURL(url))
  previews.value = []
}

function onFiles(event: Event) {
  const input = event.target as HTMLInputElement
  if (!input.files?.length) return
  ++locationRequest
  locationController?.abort()
  locating.value = false
  releasePreviews()
  files.value = Array.from(input.files ?? []).slice(0, 5)
  previews.value = files.value.map(file => URL.createObjectURL(file))
  ocrText.value = ''
  ocrCategory.value = null
  ocrSimulated.value = false
  fix.value = null
  locationError.value = ''
  input.value = ''
}

async function refreshLocation() {
  if (!files.value.length || locating.value) return
  const request = ++locationRequest
  const controller = new AbortController()
  locationController = controller
  locating.value = true
  locationError.value = ''
  fix.value = null
  try {
    const result = await locate(15000, controller.signal)
    if (request === locationRequest) fix.value = result
  } catch (cause) {
    if (request === locationRequest) locationError.value = cause instanceof Error ? cause.message : '定位失败，请重试'
  } finally {
    if (request === locationRequest) locating.value = false
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
  if (!poiId.value) return
  try {
    const detail = await getPoi(poiId.value)
    existing.value = detail.poi
    category.value = detail.poi.category
    name.value = detail.poi.name
    description.value = detail.poi.description
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '无法读取该 POI'
  }
})

onUnmounted(() => { ++locationRequest; locationController?.abort(); releasePreviews() })
</script>

<template>
  <section class="page-heading">
    <div class="eyebrow">{{ poiId ? '整改或更新采集' : 'POI 采集' }}</div>
    <h1>{{ poiId ? '提交新的采集版本' : '拍摄并上传 POI' }}</h1>
    <p>先拍照或选择照片，再点击“授权并获取位置”。位置由当前设备感知，随照片提交，不能手动填写。</p>
  </section>

  <p v-if="notice" class="success">{{ notice }}</p>
  <p v-if="error" class="error" role="alert">{{ error }}</p>

  <div class="poi-collect">
    <section class="card poi-panel">
      <h2>1. 照片与位置</h2>
      <label class="photo-picker">
        <span>现场拍照</span>
        <input type="file" accept="image/*" capture="environment" @change="onFiles" />
      </label>
      <label class="photo-picker">
        <span>从相册或本机选择照片（最多 5 张）</span>
        <input type="file" accept="image/*" multiple @change="onFiles" />
      </label>
      <p class="small-note">拍照或选图由系统窗口授权，只读取你选中的照片；选择后不会自动上传。请在 POI 现场获取位置，使用旧照片不会恢复其拍摄地点。</p>
      <div v-if="previews.length" class="photo-previews">
        <img v-for="(src, index) in previews" :key="index" :src="src" alt="待上传照片预览" />
      </div>
      <p v-if="files.length" class="small-note">已选择 {{ files.length }} 张照片，尚未提交。</p>
      <dl class="details">
        <div><dt>本次采集位置</dt><dd class="mono">{{ fix ? `${fix.lon}, ${fix.lat}` : locating ? '定位中…' : '尚未取得' }}</dd></div>
        <div><dt>定位精度</dt><dd>{{ fix?.accuracyMeters != null ? `±${fix.accuracyMeters} 米` : '—' }}</dd></div>
        <div><dt>定位时间</dt><dd>{{ fix ? new Date(fix.locatedAt).toLocaleString() : '—' }}</dd></div>
        <div><dt>坐标系</dt><dd>{{ fix?.crs ?? '—' }}（保留原始坐标，不与其他坐标系混用）</dd></div>
      </dl>
      <p v-if="fix && (fix.accuracyMeters === null || fix.accuracyMeters > 100)" class="small-note">当前定位精度较低，请开启精确位置并到开阔处重新定位，核对后再提交。</p>
      <p v-if="locationError" class="error" role="alert">{{ locationError }}</p>
      <p class="small-note">点击下面的按钮后才会请求浏览器定位。若之前已授权，浏览器可能不再弹窗；可在地址栏的网站权限中调整。</p>
      <button class="secondary-button" type="button" :disabled="locating || !files.length" @click="refreshLocation">
        {{ locating ? '正在定位…' : fix ? '重新获取位置' : '授权并获取位置' }}
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
