const {test} = require('node:test')
const assert = require('node:assert/strict')
const vm = require('node:vm')
const fs = require('node:fs')
const path = require('node:path')
const source = fs.readFileSync(path.join(__dirname, '../pages/confirm/confirm.js'), 'utf8')
function page(previewTicket) {
  let result
  vm.runInNewContext(source, {Page: value => {result=value}, getApp: () => ({globalData:{previewTicket}})})
  result.data={...result.data}; result.setData=value=>Object.assign(result.data,value)
  return result
}
test('preview package supplies request without manual pairing',()=>{
 const p=page('a'.repeat(32));p.onLoad({});assert.equal(p.data.ticket,'a'.repeat(32));assert.equal(p.data.pairing,'')
})
test('real QR scene overrides preview request',()=>{
 const p=page('a'.repeat(32));p.onLoad({scene:'b'.repeat(32)});assert.equal(p.data.ticket,'b'.repeat(32))
})
test('invalid preview request cannot become a valid identity ticket',()=>{
 const p=page('invalid');p.onLoad({});assert.equal(p.data.ticket,'');assert.ok(p.data.message)
})
