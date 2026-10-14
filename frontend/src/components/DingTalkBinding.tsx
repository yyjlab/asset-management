import { useEffect, useState } from 'react'
import { api } from '../api'

export function DingTalkBinding() {
  const [status, setStatus] = useState<{ enabled: boolean; bound: boolean } | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  useEffect(() => {
    let active = true
    api.dingtalkStatus().then(value => { if (active) setStatus(value) }).catch(() => {})
    return () => { active = false }
  }, [])
  const bind = async () => {
    setBusy(true); setError('')
    try {
      const result = await api.bindDingtalk()
      window.location.assign(result.authorizationUrl)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '无法绑定钉钉')
      setBusy(false)
    }
  }
  if (!status?.enabled) return null
  return <div className="dingtalk-binding">
    <button type="button" className="button ghost" disabled={busy || status.bound} onClick={() => void bind()}>
      {status.bound ? '已绑定钉钉' : busy ? '正在跳转…' : '绑定我的钉钉'}
    </button>
    <small>绑定后可使用钉钉登录此账号，原有权限不变。</small>
    {error && <div className="login-error" role="alert">{error}</div>}
  </div>
}
