import { useEffect, useState } from 'react'
import { api } from '../api'

interface Props {
  loading: boolean
  error: string
  onLogin: (username: string, password: string) => Promise<void>
}

export function LoginPage({ loading, error, onLogin }: Props) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [dingtalkEnabled, setDingtalkEnabled] = useState(false)
  useEffect(() => {
    let active = true
    api.dingtalkStatus().then(status => { if (active) setDingtalkEnabled(status.enabled) }).catch(() => {})
    return () => { active = false }
  }, [])

  return <main className="login-page">
    <section className="login-card">
      <h1>Asset Management</h1>
      <form onSubmit={(event) => { event.preventDefault(); void onLogin(username, password) }}>
        <label><span>账号</span><input autoFocus autoComplete="username" value={username} onChange={(event) => setUsername(event.target.value)} placeholder="请输入登录账号" /></label>
        <label><span>密码</span><input type="password" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} placeholder="请输入登录密码" /></label>
        {error && <div className="login-error" role="alert">{error}</div>}
        <button className="login-submit" disabled={loading || !username.trim() || !password} type="submit">
          <span>{loading ? '正在登录…' : '登录系统'}</span><b aria-hidden="true">↗</b>
        </button>
      </form>
      {dingtalkEnabled && <div className="dingtalk-login">
        <button className="button primary" disabled={loading} onClick={() => { window.location.assign('/api/v1/auth/dingtalk/login') }}>钉钉登录</button>
        <small>本企业员工首次钉钉登录后自动创建普通账号；已有本地账号可登录后在头像窗口绑定钉钉。</small>
      </div>}
    </section>
  </main>
}
