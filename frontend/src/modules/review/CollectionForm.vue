<script setup lang="ts">
import { onBeforeUnmount, reactive, ref } from 'vue'
import type { CollectionInput } from './types'
import { categories as poiCategories, locate as locateDevice } from '../poi/api'

const props = defineProps<{ submitLabel?: string; busy?: boolean; hint?: string }>()
const emit = defineEmits<{ submit: [input: CollectionInput] }>()

// 使用 M2 的分类目录，文件随业务请求上传。
const categories = poiCategories
const files = ref<File[]>([])
const form = reactive({ category: categories[0]!.value, name: '', description: '' })
const photoId = ref('')
const preview = ref('')
const located = ref<{ latitude: number; longitude: number; accuracyMeters: number | null; locatedAt: string } | null>(null)
const locating = ref(false)
const message = ref('')
let locationRequest = 0
let locationController: AbortController | undefined

onBeforeUnmount(() => {
  ++locationRequest
  locationController?.abort()
  if (preview.value) URL.revokeObjectURL(preview.value)
})

function choosePhoto(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  ++locationRequest
  locationController?.abort()
  locating.value = false
  if (preview.value) URL.revokeObjectURL(preview.value)
  preview.value = URL.createObjectURL(file)
  // 保留实际文件，直到整改或现场结果提交。
  files.value = [file]
  photoId.value = file.name
  located.value = null
  message.value = ''
  input.value = ''
}

async function locate() {
  if (!files.value.length || locating.value) return
  const request = ++locationRequest
  const controller = new AbortController()
  locationController = controller
  message.value = ''
  locating.value = true
  located.value = null
  try {
    const position = await locateDevice(15000, controller.signal)
    if (request !== locationRequest) return
    located.value = {
      latitude: position.lat,
      longitude: position.lon,
      accuracyMeters: position.accuracyMeters,
      locatedAt: position.locatedAt,
    }
  } catch (cause) {
    if (request === locationRequest) message.value = cause instanceof Error ? cause.message : '定位失败，请授权后重试'
  } finally {
    if (request === locationRequest) locating.value = false
  }
}

function submit() {
  message.value = ''
  if (!photoId.value) {
    message.value = '请拍摄或选择照片'
    return
  }
  if (!form.name.trim()) { message.value = '请填写 POI 名称'; return }
  if (!form.category) {
    message.value = '请选择 POI 分类'
    return
  }
  if (!located.value) {
    message.value = '尚未取得设备定位，请先点击“授权并获取位置”'
    return
  }
  emit('submit', {
    category: form.category,
    name: form.name.trim() || undefined,
    description: form.description.trim() || undefined,
    latitude: located.value.latitude,
    longitude: located.value.longitude,
    accuracyMeters: located.value.accuracyMeters,
    coordinateSystem: 'WGS84',
    locatedAt: located.value.locatedAt,
    photoId: photoId.value,
    photos: files.value,
  })
}
</script>

<template>
  <div class="collection-form">
    <p v-if="hint" class="hint">{{ hint }}</p>
    <div class="field-grid">
      <label>POI 分类<select v-model="form.category"><option v-for="item in categories" :key="item.value" :value="item.value">{{ item.label }}</option></select></label>
      <label>名称<input v-model="form.name" maxlength="120" placeholder="如：邻家面馆" /></label>
    </div>
    <label>描述<textarea v-model="form.description" rows="3" maxlength="2000" placeholder="招牌、门面、营业时间等可用于复核的信息"></textarea></label>

    <div class="capture-row">
      <div class="capture-box">
        <span class="capture-label">拍照位置（设备定位）</span>
        <template v-if="located">
          <strong class="mono">{{ located.latitude.toFixed(6) }}, {{ located.longitude.toFixed(6) }}</strong>
          <span class="hint">WGS84 · 精度 {{ located.accuracyMeters === null ? '未知' : located.accuracyMeters.toFixed(0) + ' 米' }} · {{ new Date(located.locatedAt).toLocaleString('zh-CN', { hour12: false }) }}</span>
        </template>
        <span v-else class="hint">未定位。位置随照片由设备提供，不能手工填写。</span>
        <button type="button" class="secondary-button" :disabled="locating || !files.length" @click="locate">{{ locating ? '正在定位…' : located ? '重新获取位置' : '授权并获取位置' }}</button>
        <span class="hint">先选择现场照片，再点击按钮请求定位；已授权时浏览器可能不再弹窗。</span>
        <span v-if="located && (located.accuracyMeters === null || located.accuracyMeters > 100)" class="hint">当前定位精度较低，请到开阔处重试并核对位置。</span>
      </div>
      <div class="capture-box">
        <span class="capture-label">照片</span>
        <img v-if="preview" :src="preview" class="photo-preview" alt="待提交的照片预览" />
        <span v-else class="hint">尚未选择照片</span>
        <label>现场拍照<input type="file" accept="image/*" capture="environment" @change="choosePhoto" /></label>
        <label>从相册或本机选图<input type="file" accept="image/*" @change="choosePhoto" /></label>
        <span class="hint">系统选择窗口只授权读取选中的照片，提交后才上传。请在现场定位，旧照片不包含当前设备的拍摄位置。</span>
      </div>
    </div>

    <p v-if="message" class="error" role="alert">{{ message }}</p>
    <div class="form-actions">
      <button type="button" class="primary-button" :disabled="props.busy" @click="submit">{{ props.busy ? '正在提交…' : props.submitLabel ?? '提交' }}</button>
    </div>
  </div>
</template>
