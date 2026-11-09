<script setup lang="ts">
import { onBeforeUnmount, reactive, ref } from 'vue'
import type { CollectionInput } from './types'

const props = defineProps<{ submitLabel?: string; busy?: boolean; hint?: string }>()
const emit = defineEmits<{ submit: [input: CollectionInput] }>()

// 分类由 M2 拥有；M2 未接入时先用固定候选，接入后替换为分类接口
const categories = ['餐饮', '零售', '公共交通', '公共设施', '景点', '医疗', '教育', '其他']
const form = reactive({ category: categories[0], name: '', description: '' })
const photoId = ref('')
const preview = ref('')
const located = ref<{ latitude: number; longitude: number; accuracyMeters: number | null; locatedAt: string } | null>(null)
const locating = ref(false)
const message = ref('')

onBeforeUnmount(() => {
  if (preview.value) URL.revokeObjectURL(preview.value)
})

function choosePhoto(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  if (preview.value) URL.revokeObjectURL(preview.value)
  preview.value = URL.createObjectURL(file)
  // 照片上传属于 M2；这里只生成标识供 M2 接入后替换为真实照片记录
  photoId.value = `photo-${crypto.randomUUID()}`
  message.value = ''
}

async function locate() {
  message.value = ''
  if (!navigator.geolocation) {
    message.value = '当前浏览器不支持设备定位，请更换设备后再采集'
    return
  }
  locating.value = true
  try {
    const position = await new Promise<GeolocationPosition>((resolve, reject) =>
      navigator.geolocation.getCurrentPosition(resolve, reject, { enableHighAccuracy: true, timeout: 15000, maximumAge: 0 }))
    located.value = {
      latitude: position.coords.latitude,
      longitude: position.coords.longitude,
      accuracyMeters: Number.isFinite(position.coords.accuracy) ? position.coords.accuracy : null,
      locatedAt: new Date().toISOString(),
    }
  } catch (cause) {
    message.value = locateFailure(cause)
  } finally {
    locating.value = false
  }
}

function locateFailure(cause: unknown): string {
  const code = (cause as { code?: number } | null)?.code
  const reason = code === 1 ? '定位权限被拒绝' : code === 2 ? '设备暂时无法取得位置' : code === 3 ? '定位超时' : '定位失败'
  return `${reason}。请授权定位后重试；缺少位置的采集不能提交。`
}

function submit() {
  message.value = ''
  if (!photoId.value) {
    message.value = '请拍摄或选择照片'
    return
  }
  if (!form.category) {
    message.value = '请选择 POI 分类'
    return
  }
  if (!located.value) {
    message.value = '尚未取得设备定位，请先点击“获取定位”'
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
  })
}
</script>

<template>
  <div class="collection-form">
    <p v-if="hint" class="hint">{{ hint }}</p>
    <div class="field-grid">
      <label>POI 分类<select v-model="form.category"><option v-for="item in categories" :key="item" :value="item">{{ item }}</option></select></label>
      <label>名称<input v-model="form.name" maxlength="200" placeholder="如：邻家面馆" /></label>
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
        <button type="button" class="secondary-button" :disabled="locating" @click="locate">{{ locating ? '正在定位…' : located ? '重新定位' : '获取定位' }}</button>
      </div>
      <div class="capture-box">
        <span class="capture-label">照片</span>
        <img v-if="preview" :src="preview" class="photo-preview" alt="待提交的照片预览" />
        <span v-else class="hint">尚未选择照片</span>
        <input type="file" accept="image/*" @change="choosePhoto" />
        <span class="hint">照片上传由 M2 负责；本次仅记录照片标识用于联调。</span>
      </div>
    </div>

    <p v-if="message" class="error" role="alert">{{ message }}</p>
    <div class="form-actions">
      <button type="button" class="primary-button" :disabled="props.busy" @click="submit">{{ props.busy ? '正在提交…' : props.submitLabel ?? '提交' }}</button>
    </div>
  </div>
</template>
