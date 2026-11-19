import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'

const source = await readFile(new URL('../ui/setup.js', import.meta.url), 'utf8')
const tick = () => new Promise(resolve => setImmediate(resolve))
async function page(initialized, failure = false) {
  const elements = Object.fromEntries(['setup-form', 'root', 'error', 'status', 'status-text', 'account', 'start', 'choose', 'username', 'password', 'intro'].map(id => [id, {
    value: '', hidden: false, disabled: false, textContent: '', events: {},
    addEventListener(event, handler) { this.events[event] = handler },
  }]))
  elements.username.value = 'admin'
  elements.password.value = 'local-password'
  elements['setup-form'].elements = [elements.root, elements.account, elements.start, elements.choose, elements.username, elements.password]
  let onStatus
  const calls = []
  const api = {
    core: { async invoke(command, values) {
      calls.push({ command, values })
      if (command === 'startup_info') return { initialized, root: 'D:\\排班 数据' }
      if (command === 'choose_directory') return 'E:\\ShiftBalance'
      if (command === 'start_services' && failure) throw '目录不可写'
    } },
    event: { async listen(_, handler) { onStatus = handler } },
  }
  const context = vm.createContext({ document: { querySelector: selector => elements[selector.slice(1)] }, window: { __TAURI__: api }, TextEncoder })
  vm.runInContext(source, context)
  await tick()
  return { elements, calls, onStatus }
}

const first = await page(false)
assert.equal(first.elements.root.value, 'D:\\排班 数据')
assert.equal(first.calls.some(call => call.command === 'start_services'), false)
await first.elements.choose.events.click()
assert.equal(first.elements.root.value, 'E:\\ShiftBalance')
first.elements.password.value = '长'.repeat(25)
first.elements['setup-form'].events.submit({ preventDefault() {} })
await tick()
assert.match(first.elements.error.textContent, /72 字节/)
assert.equal(first.calls.some(call => call.command === 'start_services'), false)
first.elements.password.value = 'valid-password'
first.elements['setup-form'].events.submit({ preventDefault() {} })
await tick()
const submitted = first.calls.find(call => call.command === 'start_services').values
assert.equal(submitted.root, 'E:\\ShiftBalance')
assert.equal(submitted.password, 'valid-password')
assert.equal(first.elements.password.value, '')
assert.equal(first.elements.start.disabled, true)
first.onStatus({ payload: { message: '正在启动本地数据库…' } })
assert.equal(first.elements['status-text'].textContent, '正在启动本地数据库…')

const reopened = await page(true)
assert.equal(reopened.elements.account.hidden, true)
assert.equal(reopened.elements.account.disabled, true)
const auto = reopened.calls.find(call => call.command === 'start_services')
assert.equal(auto.values.username, '')
assert.equal(auto.values.password, '')

const failed = await page(true, true)
assert.equal(failed.elements.start.disabled, false)
assert.equal(failed.elements.account.disabled, true)
assert.equal(failed.elements.error.textContent, '目录不可写')
console.log('Desktop setup checks passed: directory selection, credential limits, initialization, safe reopen and error recovery.')
