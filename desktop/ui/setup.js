const form = document.querySelector('#setup-form')
const root = document.querySelector('#root')
const error = document.querySelector('#error')
const status = document.querySelector('#status')
const statusText = document.querySelector('#status-text')
const account = document.querySelector('#account')
const start = document.querySelector('#start')
const api = window.__TAURI__

function showError(message) {
  error.textContent = String(message)
  error.hidden = false
}

async function launch() {
  error.hidden = true
  const username = account.hidden ? '' : document.querySelector('#username').value
  const password = account.hidden ? '' : document.querySelector('#password').value
  if (!account.hidden && new TextEncoder().encode(password).length > 72) {
    showError('密码最多为 72 字节，请减少密码长度。')
    return
  }
  for (const control of form.elements) control.disabled = true
  status.hidden = false
  statusText.textContent = '正在准备本地排班服务…'
  try {
    await api.core.invoke('start_services', { root: root.value, username, password })
    document.querySelector('#password').value = ''
  } catch (message) {
    for (const control of form.elements) control.disabled = false
    account.disabled = account.hidden
    status.hidden = true
    showError(message)
  }
}

form.addEventListener('submit', (event) => { event.preventDefault(); void launch() })
document.querySelector('#choose').addEventListener('click', async () => {
  try {
    const chosen = await api.core.invoke('choose_directory')
    if (chosen) root.value = chosen
  } catch (message) { showError(message) }
})

async function initialize() {
  if (!api) { showError('请从 ShiftBalance 桌面程序打开此页面。'); start.disabled = true; return }
  await api.event.listen('desktop-status', ({ payload }) => {
    status.hidden = false
    statusText.textContent = payload.message
  })
  const info = await api.core.invoke('startup_info')
  root.value = info.root
  if (info.initialized) {
    account.hidden = true
    account.disabled = true
    start.textContent = '打开排班工作台'
    document.querySelector('#intro').textContent = '正在打开这台电脑上保存的排班数据。'
    await launch()
  }
}
void initialize().catch(showError)
