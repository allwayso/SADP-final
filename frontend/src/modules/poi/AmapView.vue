<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { loadAmap } from './amapSdk'
import type { PoiSummary } from './api'

const props = defineProps<{ items: PoiSummary[] }>()
const host = ref<HTMLElement | null>(null)
const error = ref('')
const router = useRouter()
// AMap is supplied by the vendor script at runtime.
let map: any
let amap: any
let generation = 0
let disposed = false
async function draw() {
  if (!map || !amap) return
  const current = ++generation
  map.clearMap()
  const markers: any[] = []
  for (const item of props.items) {
    if (item.crs !== 'WGS84') { error.value = '点位坐标系不支持，请检查原始采集数据'; continue }
    await new Promise<void>(resolve => amap.convertFrom([item.lon, item.lat], 'gps', (status: string, result: any) => {
      if (!disposed && current === generation && status === 'complete' && result.locations?.length) {
        const dot = document.createElement('span')
        Object.assign(dot.style, { display: 'block', width: '18px', height: '18px', borderRadius: '50%', border: '2px solid white', background: item.review?.errorFlag ? '#c9603a' : '#2f8f76' })
        const marker = new amap.Marker({ position: result.locations[0], title: item.name, content: dot,
          label: { content: item.review?.errorFlag ? '需处理' : item.categoryLabel, direction: 'top' } })
        marker.on('click', () => router.push(`/poi/${item.id}`))
        markers.push(marker)
        map.add(marker)
      }
      resolve()
    }))
  }
  if (!disposed && current === generation && markers.length) map.setFitView(markers)
}
onMounted(async () => {
  try {
    amap = await loadAmap()
    if (disposed) return
    map = new amap.Map(host.value, { zoom: 12, center: [104, 35] })
    await draw()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '地图加载失败' }
})
watch(() => props.items, draw)
onBeforeUnmount(() => { disposed = true; ++generation; map?.destroy() })
</script>
<template>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
  <div ref="host" style="height:420px;width:100%" aria-label="高德 POI 地图"></div>
</template>
