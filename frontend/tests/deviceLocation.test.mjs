import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { locate, distanceMeters } from '../src/modules/poi/deviceLocation.ts'

const originalNavigator = Object.getOwnPropertyDescriptor(globalThis, 'navigator')
const originalWindow = globalThis.window
afterEach(() => {
  if (originalNavigator) Object.defineProperty(globalThis, 'navigator', originalNavigator)
  else delete globalThis.navigator
  globalThis.window = originalWindow
})

function position(accuracy, lon = 114.355338, lat = 30.528738) {
  return { coords: { longitude: lon, latitude: lat, accuracy }, timestamp: Date.now() }
}

function mockLocation(deliver) {
  const cleared = []
  globalThis.window = { setTimeout }
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { geolocation: {
    watchPosition(success, failure, options) {
      assert.equal(options.enableHighAccuracy, true)
      assert.equal(options.maximumAge, 0)
      queueMicrotask(() => deliver(success, failure))
      return 42
    },
    clearWatch(id) { cleared.push(id) },
  } } })
  return cleared
}

test('coarse first response is replaced by the best available fix, not the latest drifting fix', async () => {
  const cleared = mockLocation(success => {
    success(position(4000, 139.748060, 35.631050))
    success(position(40))
    success(position(200, 114.36, 30.53))
  })
  const result = await locate(25)
  assert.equal(result.lon, 114.355338)
  assert.equal(result.accuracyMeters, 40)
  assert.equal(result.crs, 'WGS84')
  assert.deepEqual(cleared, [42])
})

test('accurate fix finishes immediately and releases the location watcher', async () => {
  const cleared = mockLocation(success => success(position(8)))
  const result = await locate(1000)
  assert.equal(result.accuracyMeters, 8)
  assert.deepEqual(cleared, [42])
})

test('permission denial fails even when a coarse sample was received earlier', async () => {
  const cleared = mockLocation((success, failure) => {
    success(position(2000))
    failure({ code: 1 })
  })
  await assert.rejects(locate(1000), /定位被拒绝/)
  assert.deepEqual(cleared, [42])
})

test('invalid or stale positions do not become a usable collection location', async () => {
  const cleared = mockLocation(success => {
    success(position(5, NaN, 30))
    success(position(5, 114, 91))
    success({ ...position(5), timestamp: Date.now() - 120000 })
  })
  await assert.rejects(locate(25), /未取得有效位置/)
  assert.deepEqual(cleared, [42])
})

test('leaving the form or replacing a photo cancels the active location watcher', async () => {
  const cleared = mockLocation(() => {})
  const controller = new AbortController()
  const pending = locate(1000, controller.signal)
  controller.abort()
  await assert.rejects(pending, /定位已取消/)
  assert.deepEqual(cleared, [42])
})

test('Tokyo origin is thousands of kilometres from Wuhan, not a coordinate offset', () => {
  assert.ok(distanceMeters({ lon: 139.748060, lat: 35.631050 }, { lon: 114.355338, lat: 30.528738 }) > 2000000)
  assert.equal(distanceMeters({ lon: 114, lat: 30 }, { lon: 114, lat: 30 }), 0)
})
