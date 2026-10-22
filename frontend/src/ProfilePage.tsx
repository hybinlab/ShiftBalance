import { type ChangeEvent, useEffect, useRef, useState } from 'react'
import { App as AntApp, Avatar, Button, Card, Form, Input, Popconfirm } from 'antd'
import { CameraOutlined, KeyOutlined, LockOutlined, LogoutOutlined, UserOutlined } from '@ant-design/icons'
import './ProfilePage.css'

type UserProfile = { username: string; avatarAvailable: boolean }
type ApiRequest = <T>(base: string, path: string, init?: RequestInit) => Promise<T>

type Props = {
  apiBase: string
  apiRequest: ApiRequest
  username: string
  avatarVersion: number
  onProfileLoaded: (profile: UserProfile) => void
  onAvatarUpdated: (version: number) => void
  onUsernameUpdated: (username: string) => void
  onLogout: () => Promise<void>
}

export default function ProfilePage({
  apiBase, apiRequest, username, avatarVersion, onProfileLoaded, onAvatarUpdated, onUsernameUpdated, onLogout,
}: Props) {
  const { message } = AntApp.useApp()
  const [profile, setProfile] = useState<UserProfile>({ username, avatarAvailable: false })
  const [loading, setLoading] = useState(true)
  const [avatarLoading, setAvatarLoading] = useState(false)
  const [usernameLoading, setUsernameLoading] = useState(false)
  const [passwordLoading, setPasswordLoading] = useState(false)
  const [usernameForm] = Form.useForm<{ username: string }>()
  const [passwordForm] = Form.useForm<{ currentPassword: string; newPassword: string; confirmPassword: string }>()
  const fileInput = useRef<HTMLInputElement>(null)
  const avatarUrl = profile.avatarAvailable
    ? `${apiBase.trim().replace(/\/$/, '')}/api/auth/profile/avatar?v=${avatarVersion}`
    : undefined

  useEffect(() => {
    let active = true
    setLoading(true)
    void apiRequest<UserProfile>(apiBase, '/api/auth/profile')
      .then((result) => {
        if (!active) return
        setProfile(result)
        usernameForm.setFieldsValue({ username: result.username })
        onProfileLoaded(result)
      })
      .catch((error) => {
        if (active) message.error(error instanceof Error ? `读取用户资料失败：${error.message}` : '读取用户资料失败')
      })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [apiBase, apiRequest, message, onProfileLoaded, usernameForm])

  const uploadAvatar = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.currentTarget.files?.[0]
    event.currentTarget.value = ''
    if (!file) return
    if (file.size > 2 * 1024 * 1024) {
      message.error('头像文件不能超过 2 MB')
      return
    }
    const data = new FormData()
    data.append('file', file)
    setAvatarLoading(true)
    try {
      const result = await apiRequest<UserProfile>(apiBase, '/api/auth/profile/avatar', { method: 'PUT', body: data })
      setProfile(result)
      onProfileLoaded(result)
      onAvatarUpdated(avatarVersion + 1)
      message.success('头像已更新')
    } catch (error) {
      message.error(error instanceof Error ? `头像上传失败：${error.message}` : '头像上传失败')
    } finally {
      setAvatarLoading(false)
    }
  }

  const saveUsername = async (values: { username: string }) => {
    setUsernameLoading(true)
    try {
      const result = await apiRequest<UserProfile>(apiBase, '/api/auth/profile', {
        method: 'PUT', body: JSON.stringify(values),
      })
      setProfile(result)
      onProfileLoaded(result)
      onUsernameUpdated(result.username)
      usernameForm.setFieldsValue({ username: result.username })
      message.success('用户名已更新')
    } catch (error) {
      message.error(error instanceof Error ? `用户名修改失败：${error.message}` : '用户名修改失败')
    } finally {
      setUsernameLoading(false)
    }
  }

  const savePassword = async (values: { currentPassword: string; newPassword: string }) => {
    setPasswordLoading(true)
    try {
      await apiRequest<void>(apiBase, '/api/auth/profile/password', {
        method: 'PUT', body: JSON.stringify({
          currentPassword: values.currentPassword,
          newPassword: values.newPassword,
        }),
      })
      passwordForm.resetFields()
      message.success('密码已更新')
    } catch (error) {
      message.error(error instanceof Error ? `密码修改失败：${error.message}` : '密码修改失败')
    } finally {
      setPasswordLoading(false)
    }
  }

  return (
    <div className="profile-page">
      <section className="page-heading">
        <div>
          <h1>用户资料</h1>
          <p className="page-subtitle">管理登录用户名、头像和密码。</p>
        </div>
      </section>

      <div className="profile-grid">
        <Card className="profile-panel surface" title={<span><UserOutlined />　账号资料</span>} loading={loading}>
          <div className="profile-avatar-row">
            <Avatar size={76} src={avatarUrl} crossOrigin="use-credentials" icon={<UserOutlined />}>
              {profile.username.slice(0, 1).toUpperCase()}
            </Avatar>
            <div className="profile-avatar-copy">
              <strong>{profile.username || username}</strong>
              <span>头像支持 PNG、JPEG 或 WebP，最大 2 MB。</span>
              <input ref={fileInput} className="profile-file-input" type="file" accept="image/png,image/jpeg,image/webp" onChange={(event) => void uploadAvatar(event)} aria-label="选择头像图片" />
              <Button icon={<CameraOutlined />} loading={avatarLoading} onClick={() => fileInput.current?.click()}>
                {profile.avatarAvailable ? '更换头像' : '上传头像'}
              </Button>
            </div>
          </div>
          <div className="profile-form-section">
            <h2>用户名</h2>
            <p>使用 3–100 个字母、数字、点、短横线或下划线；不区分大小写。</p>
            <Form form={usernameForm} layout="vertical" initialValues={{ username }} onFinish={(values) => void saveUsername(values)}>
              <Form.Item name="username" label="登录用户名" rules={[
                { required: true, message: '请输入用户名' },
                { min: 3, max: 100, message: '用户名长度需为 3–100 个字符' },
                { pattern: /^[A-Za-z0-9._-]+$/, message: '仅支持英文字母、数字、点、短横线和下划线' },
              ]}>
                <Input autoComplete="username" maxLength={100} prefix={<UserOutlined />} />
              </Form.Item>
              <Button type="primary" htmlType="submit" icon={<UserOutlined />} loading={usernameLoading}>保存用户名</Button>
            </Form>
          </div>
        </Card>

        <Card className="profile-panel surface" title={<span><LockOutlined />　修改密码</span>}>
          <p className="profile-password-intro">输入当前密码后设置新密码。新密码至少 10 个字符。</p>
          <Form form={passwordForm} layout="vertical" onFinish={(values) => void savePassword(values)}>
            <Form.Item name="currentPassword" label="当前密码" rules={[{ required: true, message: '请输入当前密码' }]}>
              <Input.Password autoComplete="current-password" prefix={<KeyOutlined />} />
            </Form.Item>
            <Form.Item name="newPassword" label="新密码" rules={[
              { required: true, message: '请输入新密码' },
              { min: 10, max: 100, message: '新密码长度需为 10–100 个字符' },
            ]}>
              <Input.Password autoComplete="new-password" prefix={<LockOutlined />} />
            </Form.Item>
            <Form.Item name="confirmPassword" label="确认新密码" dependencies={['newPassword']} rules={[
              { required: true, message: '请再次输入新密码' },
              ({ getFieldValue }) => ({
                validator(_, value: string) {
                  return !value || getFieldValue('newPassword') === value
                    ? Promise.resolve()
                    : Promise.reject(new Error('两次输入的新密码不一致'))
                },
              }),
            ]}>
              <Input.Password autoComplete="new-password" prefix={<LockOutlined />} />
            </Form.Item>
            <Button type="primary" htmlType="submit" loading={passwordLoading}>更新密码</Button>
          </Form>
        </Card>
      </div>

      <Card className="profile-logout surface">
        <div>
          <strong>退出当前账号</strong>
          <span>退出后需要重新输入用户名和密码。</span>
        </div>
        <Popconfirm title="确定退出当前账号吗？" onConfirm={() => void onLogout()} okText="退出" cancelText="取消">
          <Button danger icon={<LogoutOutlined />}>退出登录</Button>
        </Popconfirm>
      </Card>
    </div>
  )
}
