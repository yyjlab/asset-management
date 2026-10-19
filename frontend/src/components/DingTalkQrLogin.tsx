import { useEffect, useRef, useState } from 'react'
import type { DingTalkQrAuthorization } from '../types'

const loginOrigin = 'https://login.dingtalk.com'
const callbackPath = '/api/v1/auth/dingtalk/callback'

// Use the official DTFrameLogin iframe protocol without loading a global SDK/listener.
// Keep callbacks on this site; never navigate to an unchecked postMessage URL.
export function qrFrameUrl(authorization: DingTalkQrAuthorization, origin: string) {
  const callback = new URL(authorization.redirectUri)
  if (callback.origin !== origin || callback.pathname !== callbackPath || callback.search || callback.hash || callback.username || callback.password) {
    throw new Error('请使用与钉钉回调地址相同的域名、协议和端口访问资产系统')
  }
  const frame = new URL(authorization.authorizationUrl)
  if (frame.origin !== loginOrigin || frame.pathname !== '/oauth2/auth' || frame.username || frame.password || frame.hash
    || frame.searchParams.get('iframe') !== 'true' || frame.searchParams.get('redirect_uri') !== authorization.redirectUri
    || frame.searchParams.get('state') !== authorization.state || !/^[A-Za-z0-9_-]{43}$/.test(authorization.state)
    || frame.searchParams.get('response_type') !== 'code' || frame.searchParams.get('scope') !== 'openid'
    || frame.searchParams.get('prompt') !== 'consent' || !frame.searchParams.get('client_id')
    || !Number.isFinite(authorization.expiresAt)) throw new Error('钉钉二维码参数无效，请刷新重试')
  return frame.href
}

export function qrCallbackUrl(data: unknown, authorization: DingTalkQrAuthorization) {
  if (!data || typeof data !== 'object' || !('success' in data) || data.success !== true
    || !('redirectUrl' in data) || typeof data.redirectUrl !== 'string' || data.redirectUrl.length > 5000) return null
  try {
    const result = new URL(data.redirectUrl)
    const expected = new URL(authorization.redirectUri)
    if (result.origin !== expected.origin || result.pathname !== expected.pathname || result.username || result.password || result.hash
      || result.searchParams.has('error') || result.searchParams.getAll('state').length !== 1
      || result.searchParams.get('state') !== authorization.state || result.searchParams.getAll('authCode').length !== 1
      || result.searchParams.has('code')) return null
    const code = result.searchParams.get('authCode')
    if (!code || !code.trim() || code.length > 2048) return null
    return `${callbackPath}?${new URLSearchParams({ authCode: code, state: authorization.state })}`
  } catch { return null }
}

export function DingTalkQrLogin({ authorization, onRefresh }: { authorization: DingTalkQrAuthorization; onRefresh: () => void }) {
  const frame = useRef<HTMLIFrameElement>(null)
  const [expired, setExpired] = useState(false)
  const [error, setError] = useState('')
  let src = '', configurationError = ''
  try { src = qrFrameUrl(authorization, window.location.origin) }
  catch (failure) { configurationError = failure instanceof Error ? failure.message : '钉钉二维码参数无效' }
  useEffect(() => {
    if (!src) return
    let active = true, completed = false
    const expiresIn = Math.max(0, authorization.expiresAt - Date.now())
    const timer = window.setTimeout(() => { if (active) setExpired(true) }, expiresIn)
    const receive = (event: MessageEvent) => {
      if (!active || completed || Date.now() >= authorization.expiresAt || event.origin !== loginOrigin
        || !frame.current?.contentWindow || event.source !== frame.current.contentWindow) return
      const callback = qrCallbackUrl(event.data, authorization)
      if (callback) { completed = true; window.location.assign(callback) }
      else if (event.data && typeof event.data === 'object' && event.data.success === false) setError('扫码授权未完成，请刷新二维码重试')
    }
    window.addEventListener('message', receive)
    return () => { active = false; window.clearTimeout(timer); window.removeEventListener('message', receive) }
  }, [authorization, src])

  return <section className="dingtalk-qr-login" aria-label="钉钉扫码登录">
    <h2>钉钉扫码登录</h2><p>请使用手机钉钉扫一扫，并在手机上确认登录。</p>
    {configurationError ? <p className="login-error" role="alert">{configurationError}</p>
      : expired ? <p className="login-error" role="alert">二维码会话已过期，请刷新后重新扫码。</p>
        : <iframe ref={frame} src={src} title="钉钉登录二维码" className="dingtalk-qr-frame" referrerPolicy="no-referrer" onError={() => setError('二维码加载失败，请检查钉钉网络连接并刷新重试')} />}
    {error && <p className="login-error" role="alert">{error}</p>}
    {!configurationError && <><button type="button" className="button ghost" onClick={onRefresh}>刷新二维码</button><small>如二维码未显示，请确认浏览器能访问 login.dingtalk.com 后重试。</small></>}
  </section>
}
