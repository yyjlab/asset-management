import { useEffect, useRef, useState } from 'react'
import { api } from '../api'
import type { DingTalkQrAuthorization } from '../types'
import { DingTalkQrLogin } from './DingTalkQrLogin'

interface Props {
  loading: boolean
  error: string
  onLogin: (username: string, password: string) => Promise<void>
}

export function LoginPage({ loading, error, onLogin }: Props) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [dingtalkEnabled, setDingtalkEnabled] = useState(false)
  const [qrMode, setQrMode] = useState(false)
  const [qr, setQr] = useState<DingTalkQrAuthorization | null>(null)
  const [qrLoading, setQrLoading] = useState(false)
  const [qrError, setQrError] = useState('')
  const qrRequest = useRef(0)
  const qrBusy = useRef(false)
  useEffect(() => {
    let active = true
    api.dingtalkStatus().then(status => { if (active) setDingtalkEnabled(status.enabled) }).catch(() => {})
    return () => { active = false; qrRequest.current++ }
  }, [])

  const startQr = async () => {
    if (loading || qrBusy.current) return
    const revision = ++qrRequest.current
    qrBusy.current = true; setQrMode(true); setQr(null); setQrError(''); setQrLoading(true)
    try { const authorization = await api.beginDingtalkQr(); if (revision === qrRequest.current) setQr(authorization) }
    catch (failure) { if (revision === qrRequest.current) setQrError(failure instanceof Error ? failure.message : '二维码初始化失败，请重试') }
    finally { if (revision === qrRequest.current) { qrBusy.current = false; setQrLoading(false) } }
  }
  const startQuickLogin = () => {
    if (loading || qrBusy.current) return
    window.location.assign('/api/v1/auth/dingtalk/login')
  }

  return <main className="login-page">
    <section className={`login-card${qrMode ? ' qr-mode' : ''}`}>
      <h1>Asset Management</h1>
      {qrMode ? <div className="dingtalk-login">
        <button type="button" className="button primary" disabled={loading || qrLoading} onClick={startQuickLogin}>使用电脑钉钉快捷登录</button>
        {qrLoading && <p role="status">正在加载钉钉二维码…</p>}
        {qrError && <><p className="login-error" role="alert">{qrError}</p><button type="button" className="button primary" onClick={() => void startQr()}>重试扫码登录</button></>}
        {qr && <DingTalkQrLogin key={qr.state} authorization={qr} onRefresh={() => void startQr()} />}
        <button type="button" className="button ghost" disabled={qrLoading} onClick={() => { qrRequest.current++; setQrMode(false); setQr(null); setQrError('') }}>返回账号密码登录</button>
      </div> : <><form onSubmit={(event) => { event.preventDefault(); void onLogin(username, password) }}>
        <label><span>账号</span><input autoFocus autoComplete="username" value={username} onChange={(event) => setUsername(event.target.value)} placeholder="请输入登录账号" /></label>
        <label><span>密码</span><input type="password" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} placeholder="请输入登录密码" /></label>
        {error && <div className="login-error" role="alert">{error}</div>}
        <button className="login-submit" disabled={loading || !username.trim() || !password} type="submit">
          <span>{loading ? '正在登录…' : '登录系统'}</span><b aria-hidden="true">↗</b>
        </button>
      </form>
      {dingtalkEnabled && <div className="dingtalk-login">
        <button type="button" className="button primary" disabled={loading} onClick={startQuickLogin}>钉钉登录</button>
        <small>优先通过钉钉官方页面识别已登录账号并确认授权，无需强制扫码。</small>
        <button type="button" className="button ghost" disabled={loading} onClick={() => void startQr()}>改用扫码登录</button>
        <small>本企业员工首次钉钉登录后自动创建普通账号；已有本地账号可登录后在头像窗口绑定钉钉。</small>
      </div>}</>}
    </section>
  </main>
}
