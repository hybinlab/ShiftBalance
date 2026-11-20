// Native WebView2 regression check. Node 24's CDP/WebSocket client avoids another browser dependency.
import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { createServer } from 'node:net'
import { mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'

assert.equal(process.platform, 'win32', 'Run this check on Windows with the prepared Tauri bundle')
const root = path.resolve('../backend/target/desktop-ui-check')
await mkdir(root, { recursive: true })
const listener = createServer()
await new Promise(resolve => listener.listen(0, '127.0.0.1', resolve))
const port = listener.address().port
await new Promise(resolve => listener.close(resolve))
// WebView2 150+ ignores environment overrides in an elevated CI host; use its machine policy.
const debugPolicy = 'HKLM\\Software\\Policies\\Microsoft\\Edge\\WebView2\\AdditionalBrowserArguments'
if (process.env.GITHUB_ACTIONS === 'true') {
  assert.equal(spawnSync('reg.exe', ['add', debugPolicy, '/v', 'shiftbalance-desktop.exe', '/t', 'REG_SZ', '/d', `--remote-debugging-port=${port}`, '/f'], { stdio: 'inherit' }).status, 0)
}
const app = spawn(process.env.SHIFTBALANCE_TEST_EXE || path.resolve('src-tauri/target/x86_64-pc-windows-msvc/release/shiftbalance-desktop.exe'), [], {
  env: { ...process.env, WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS: `--remote-debugging-port=${port}` }, stdio: 'inherit',
})
const exited = new Promise(resolve => app.once('exit', resolve))
let socket
let debuggerState
let requestId = 0
const pending = new Map()
const errors = []
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
async function until(check, label, timeout = 90000) {
  const end = Date.now() + timeout
  while (Date.now() < end) {
    if (app.exitCode !== null) throw new Error(`Desktop exited before ${label}: ${app.exitCode}`)
    try { const value = await check(); if (value) return value } catch { /* navigation replaces the execution context */ }
    await delay(200)
  }
  throw new Error(`Timed out: ${label}`)
}
function cdp(method, params = {}) {
  const id = ++requestId
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timeout: ${method}`)) }, 30000)
    pending.set(id, { resolve: value => { clearTimeout(timeout); resolve(value) }, reject: error => { clearTimeout(timeout); reject(error) } })
    socket.send(JSON.stringify({ id, method, params }))
  })
}
async function evaluate(expression) {
  const response = await cdp('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
  if (response.exceptionDetails) throw new Error(JSON.stringify(response.exceptionDetails))
  return response.result?.value
}
async function click(expression) {
  const point = await evaluate(`(async () => {const element = (${expression}); if (!element) throw Error('Missing click target'); const before=element.getBoundingClientRect(); if(before.top<0 || before.bottom>innerHeight) element.scrollIntoView({block:'center',behavior:'instant'}); await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))); const r=element.getBoundingClientRect(); const x=r.x+r.width/2,y=r.y+r.height/2; const hit=document.elementFromPoint(x,y); if(!hit || !element.contains(hit)) throw Error('Click misses target: '+JSON.stringify({target:element.outerHTML,rect:r.toJSON(),hit:hit?.outerHTML})); return {x,y};})()`)
  await cdp('Input.dispatchMouseEvent', { type: 'mouseMoved', ...point })
  await cdp('Input.dispatchMouseEvent', { type: 'mousePressed', button: 'left', clickCount: 1, ...point })
  await cdp('Input.dispatchMouseEvent', { type: 'mouseReleased', button: 'left', clickCount: 1, ...point })
}
const nav = label => `Array.from(document.querySelectorAll('.ant-menu-item')).find(e=>e.textContent.includes(${JSON.stringify(label)}))`
async function openSelect(expression, label) {
  await click(expression)
  await delay(700) // Must remain open after focus and deferred outside-click handlers run.
  const state = await evaluate(`(() => {const e=(${expression}); const input=e.querySelector('[role=combobox]'); return {expanded:input?.getAttribute('aria-expanded'),options:Array.from(document.querySelectorAll('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')).map(e=>{const r=e.getBoundingClientRect();return {text:e.textContent,height:r.height,visible:e.contains(document.elementFromPoint(r.x+r.width/2,r.y+r.height/2))}}),focus:document.activeElement?.outerHTML,events:window.__popupEvents};})()`)
  console.log(`${label}: ${JSON.stringify(state)}`)
  assert.equal(state.expanded, 'true', `${label} closed immediately`)
  assert(state.options.some(option => option.height > 0 && option.visible), `${label} has no visible options`)
}
try {
  const target = await until(async () => {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json/list`, { signal: AbortSignal.timeout(2000) })
      debuggerState = await response.json()
      return debuggerState.find(target => target.webSocketDebuggerUrl)
    } catch (error) { debuggerState = String(error); return false }
  }, 'WebView2 debugger')
  socket = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject })
  socket.onmessage = event => {
    const message = JSON.parse(event.data)
    if (message.id && pending.has(message.id)) {
      const operation = pending.get(message.id); pending.delete(message.id)
      message.error ? operation.reject(new Error(JSON.stringify(message.error))) : operation.resolve(message.result)
    } else if (['Runtime.exceptionThrown', 'Log.entryAdded'].includes(message.method)) errors.push(message)
  }
  await cdp('Runtime.enable')
  await cdp('Page.enable')
  await cdp('Log.enable')
  // RDP commonly requests reduced motion; popup alignment must still use instantaneous layout changes.
  await cdp('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] })
  await cdp('Page.addScriptToEvaluateOnNewDocument', { source: `window.__popupEvents=[];for(const type of ['pointerdown','mousedown','mouseup','click','focusin','focusout','blur'])window.addEventListener(type,e=>{window.__popupEvents.push({type,time:performance.now(),target:e.target?.tagName,classes:e.target?.className,active:document.activeElement?.tagName,related:e.relatedTarget?.tagName});window.__popupEvents=window.__popupEvents.slice(-35)},true);` })
  await until(() => evaluate('!!window.__TAURI__?.core'), 'bundled setup page')
  await evaluate(`window.__TAURI__.core.invoke('start_services',${JSON.stringify({ root, username: 'admin', password: 'native-ui-check-password' })})`)
  await until(() => evaluate(`location.hostname==='127.0.0.1' && !!document.querySelector('input[autocomplete=username]')`), 'login page')
  assert.equal(await evaluate(`(async()=>{const {csrfToken}=await(await fetch('/api/auth/csrf')).json();return(await fetch('/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrfToken},body:JSON.stringify({username:'admin',password:'native-ui-check-password'})})).status})()`), 200)
  await cdp('Page.reload')
  await until(() => evaluate(`!!(${nav('人员管理')})`), 'authenticated workbench')
  await click(nav('人员管理'))
  const filter = `document.querySelector('.management-toolbar .ant-select:not(.ant-select-disabled)')`
  await until(() => evaluate(`!!(${filter}) && document.querySelectorAll('.ant-spin-spinning').length===0`), 'people filters')
  await openSelect(filter, 'People group filter')
  await click(`Array.from(document.querySelectorAll('.ant-select-item-option')).find(e=>e.textContent.includes('A组'))`)
  await until(() => evaluate(`(${filter}).textContent.includes('A组')`), 'selected group')
  await click(`Array.from(document.querySelectorAll('button')).find(e=>e.textContent.includes('新增人员'))`)
  await until(() => evaluate(`!!document.querySelector('.ant-modal .ant-select')`), 'person modal')
  const group = `document.querySelector('.ant-modal .ant-select')`
  await openSelect(group, 'Person modal group')
  await click(`Array.from(document.querySelectorAll('.ant-select-item-option')).find(e=>e.textContent.includes('B组'))`)
  await until(() => evaluate(`(${group}).textContent.includes('B组')`), 'modal selected group')
  const qualifications = `document.querySelectorAll('.ant-modal .ant-select')[1]`
  await openSelect(qualifications, 'Person modal qualifications')
  await click(`Array.from(document.querySelectorAll('.ant-select-item-option')).find(e=>e.textContent.includes('防爆'))`)
  await until(() => evaluate(`(${qualifications}).textContent.includes('防爆')`), 'selected qualification')
  await click(`document.querySelector('.ant-modal-close')`)
  await click(nav('排班管理'))
  await until(() => evaluate(`!!document.querySelector('.date-picker-control')`), 'schedule page')
  await click(`document.querySelector('.date-picker-control input')`)
  await delay(700)
  const calendars = await evaluate(`Array.from(document.querySelectorAll('.ant-picker-dropdown')).map(e=>{const r=e.getBoundingClientRect();return {rect:r.toJSON(),visible:r.height>0 && e.contains(document.elementFromPoint(r.x+r.width/2,r.y+r.height/2))}})` )
  console.log('Date popups:', JSON.stringify(calendars))
  assert(calendars.some(calendar => calendar.visible), 'Date popup must stay visible and accept pointer input')
  console.log('Native Windows WebView2 checks passed: filter, modal single/multiple selects, option selection, date popup.')
} catch (error) {
  console.error('Debugger state:', JSON.stringify({ port, debuggerState }))
  if (!socket) spawnSync('powershell.exe', ['-NoProfile', '-Command', "Get-CimInstance Win32_Process | Where-Object { $_.Name -match 'shiftbalance|msedgewebview2' } | Select-Object Name,ProcessId,ParentProcessId,CommandLine | Format-List"], { stdio: 'inherit' })
  if (socket?.readyState === WebSocket.OPEN) {
    try {
      const screenshot = await cdp('Page.captureScreenshot', { format: 'png' })
      await writeFile(path.join(root, 'dropdown-failure.png'), Buffer.from(screenshot.data, 'base64'))
      await writeFile(path.join(root, 'dropdown-failure.json'), JSON.stringify({ errors, document: await evaluate(`({url:location.href,body:document.body.innerText,events:window.__popupEvents})`) }, null, 2))
    } catch (diagnosticError) { console.error(diagnosticError.message) }
  }
  console.error(JSON.stringify(errors))
  throw error
} finally {
  socket?.close()
  if (app.exitCode === null) {
    spawnSync('powershell.exe', ['-NoProfile', '-Command', `(Get-Process -Id ${app.pid}).CloseMainWindow() | Out-Null`])
    await new Promise(resolve => {
      const timer = setTimeout(() => { if (app.exitCode === null) app.kill(); resolve() }, 95000)
      exited.then(() => { clearTimeout(timer); resolve() })
    })
  }
  if (process.env.GITHUB_ACTIONS === 'true') spawnSync('reg.exe', ['delete', debugPolicy, '/v', 'shiftbalance-desktop.exe', '/f'], { stdio: 'inherit' })
}
