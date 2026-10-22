import { useState } from 'react'
import { Alert, Button, Form, Input } from 'antd'
import { ArrowRightOutlined, LockOutlined, UserOutlined } from '@ant-design/icons'
import RippleDistortion from './RippleDistortion'
import './LoginPage.css'

type Credentials = { username: string; password: string }
type Props = {
  onSubmit: (credentials: Credentials) => Promise<void>
  loading: boolean
  error: string
  apiBase: string
  onApplyApiBase: (value: string) => void
}

export default function LoginPage({ onSubmit, loading, error, apiBase, onApplyApiBase }: Props) {
  const [apiInput, setApiInput] = useState(apiBase)
  return (
    <main className="login-page">
      <section className="login-brand-panel">
        <RippleDistortion
          className="login-ripple-surface"
          src="/login-ripple.svg"
          brushSize={180}
          strength={0.11}
          swirl={0.55}
          rings={3}
          spacing={22}
          fade={2.4}
          tint="#56D9BC"
          tintAmount={0.18}
          grayscale={false}
          glint={0.2}
          trigger="both"
          quality="low"
        />
        <div className="login-art-shade" aria-hidden="true" />
        <div className="login-brand-content">
          <div className="login-brand">
            <img src="/favicon.svg" alt="" />
            <strong>排班系统</strong>
          </div>
          <div className="login-brand-copy">
            <h1>让每一次轮班<br /><em>都清晰、公平。</em></h1>
            <p>统一管理人员、岗位资格与轮班安排，让排班结果有据可查。</p>
            <div className="login-flow-strip">
              <div><small>轮班节奏</small><strong>上二 · 休二</strong></div>
              <ArrowRightOutlined />
              <div><small>排班流程</small><strong>生成 · 调整 · 发布</strong></div>
            </div>
          </div>
          <div className="login-brand-foot">ShiftBalance · 排班工作台</div>
        </div>
      </section>

      <section className="login-form-panel">
        <div className="login-form-wrap">
          <div className="login-form-heading">
            <span className="login-form-icon"><LockOutlined /></span>
            <h2>欢迎回来</h2>
            <p>登录以继续管理排班工作</p>
          </div>

          {error && <Alert className="login-submit-result" type="error" showIcon title={error} />}

          <Form<Credentials> layout="vertical" requiredMark={false} onFinish={onSubmit} className="login-form">
            <Form.Item label="账号" name="username" rules={[{ required: true, message: '请输入账号' }]}>
              <Input size="large" autoComplete="username" prefix={<UserOutlined />} placeholder="请输入账号" disabled={loading} />
            </Form.Item>
            <Form.Item label="密码" name="password" rules={[{ required: true, message: '请输入密码' }]}>
              <Input.Password size="large" autoComplete="current-password" prefix={<LockOutlined />} placeholder="请输入密码" disabled={loading} />
            </Form.Item>
            <Button block type="primary" size="large" htmlType="submit" loading={loading}>登录</Button>
          </Form>

          <div className="login-form-foot">账号由系统管理员配置，请使用分配的账号登录。</div>
          <details className="login-api-settings">
            <summary>接口地址设置</summary>
            <div className="login-api-row">
              <Input size="small" aria-label="API 根地址" value={apiInput} onChange={(event) => setApiInput(event.target.value)} placeholder="留空使用本地开发代理" />
              <Button size="small" onClick={() => onApplyApiBase(apiInput)}>应用</Button>
            </div>
          </details>
        </div>
      </section>
    </main>
  )
}
