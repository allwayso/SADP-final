<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { convertGps, loadAmap } from './amapSdk'

const props = defineProps<{
  origin: { lon: number; lat: number }
  destination: { lon: number; lat: number }
  mode: 'walk' | 'car'
}>()
const host = ref<HTMLElement | null>(null)
const panel = ref<HTMLElement | null>(null)
const error = ref('')
const summary = ref('')
const busy = ref(true)
let amap: any
let map: any
let planner: any
let generation = 0
let disposed = false

async function plan() {
  if (!amap || !map) return
  const request = ++generation
  planner?.clear()
  map.clearMap()
  error.value = ''
  summary.value = ''
  busy.value = true
  try {
    const [start, end] = await Promise.all([convertGps(amap, props.origin), convertGps(amap, props.destination)])
    if (disposed || request !== generation) return
    map.setCenter(start)
    const markers = [new amap.Marker({ position: start, label: { content: '起点', direction: 'top' } }),
      new amap.Marker({ position: end, label: { content: '终点', direction: 'top' } })]
    map.add(markers)
    map.setFitView(markers)
    const plugin = props.mode === 'car' ? 'AMap.Driving' : 'AMap.Walking'
    await new Promise<void>((resolve, reject) => {
      const timer = window.setTimeout(() => reject(new Error('高德路线插件加载超时')), 15000)
      amap.plugin(plugin, () => { clearTimeout(timer); resolve() })
    })
    if (disposed || request !== generation) return
    const Planner = props.mode === 'car' ? amap.Driving : amap.Walking
    planner = new Planner({ map, panel: panel.value, hideMarkers: true })
    const result = await new Promise<any>((resolve, reject) => {
      const timer = window.setTimeout(() => reject(new Error('高德路线查询超时，请重试')), 20000)
      planner.search(start, end, (status: string, data: any) => {
        clearTimeout(timer)
        if (status === 'complete' && data.routes?.[0]) resolve(data.routes[0])
        else reject(new Error('高德未返回可用路线，请核对起终点或切换出行方式'))
      })
    })
    if (disposed || request !== generation) return
    summary.value = `高德路线：约 ${result.distance >= 1000 ? (result.distance / 1000).toFixed(2) + ' 公里' : Math.round(result.distance) + ' 米'}，${Math.max(1, Math.round(result.time / 60))} 分钟`
  } catch (cause) {
    if (!disposed && request === generation) error.value = cause instanceof Error ? cause.message : '路线查询失败'
  } finally {
    if (!disposed && request === generation) busy.value = false
  }
}

async function initialize() {
  busy.value = true
  error.value = ''
  try {
    amap = await loadAmap()
    if (disposed) return
    map = new amap.Map(host.value, { center: [104, 35], zoom: 14 })
    await plan()
  } catch (cause) {
    if (!disposed) { error.value = cause instanceof Error ? cause.message : '地图加载失败'; busy.value = false }
  }
}
onMounted(initialize)
watch(() => [props.origin, props.destination, props.mode], plan)
onBeforeUnmount(() => { disposed = true; ++generation; planner?.clear(); map?.destroy() })
</script>

<template>
  <section class="amap-route" aria-label="高德路线预览">
    <p v-if="busy" role="status">正在计算高德路线…</p>
    <p v-if="summary" class="success" role="status">{{ summary }}</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <button v-if="error" class="secondary-button" type="button" @click="map ? plan() : initialize()">重试路线</button>
    <div ref="host" class="amap-route-map" aria-label="导航起终点和路线"></div>
    <div ref="panel" class="amap-route-panel"></div>
  </section>
</template>
