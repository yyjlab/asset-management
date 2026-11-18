import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ApiError, api } from '../api'
import type { AuthUser, PublicAsset } from '../types'
import { KnownDefectDetails } from './KnownDefects'
import { BoxesIcon, CheckIcon, CloseIcon, ReturnIcon, UserIcon } from './Icons'

interface Props { qrToken: string }

const value = (text: string | null) => text?.trim() || '—'
const time = (text: string) => new Date(text).toLocaleString('zh-CN', { hour12: false })
const stateLabel = (asset: PublicAsset) => asset.status?.includes('报废') ? '已报废'
  : asset.status?.includes('维护') || asset.status?.includes('维修') ? '维护中'
    : asset.checkedOut ? '已经领出' : '当前可用'
const stateTone = (asset: PublicAsset) => asset.status?.includes('报废') ? 'scrapped'
  : asset.status?.includes('维护') || asset.status?.includes('维修') ? 'maintenance'
    : asset.checkedOut ? 'checked-out' : 'available'
const canReturn = (user: AuthUser) => user.role === 'SUPER_ADMIN' || user.permissions.includes('ASSET_RETURN')

export function PublicAssetPage({ qrToken }: Props) {
  const [asset, setAsset] = useState<PublicAsset | null>(null)
  const [error, setError] = useState('')
  const [authUser, setAuthUser] = useState<AuthUser | null>(null)
  const [authReady, setAuthReady] = useState(false)
  const [loginOpen, setLoginOpen] = useState(false)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [loginLoading, setLoginLoading] = useState(false)
  const [returning, setReturning] = useState(false)
  const [checkingReturn, setCheckingReturn] = useState(false)
  const [actionError, setActionError] = useState('')
  const [success, setSuccess] = useState('')
  const [dingtalkEnabled, setDingtalkEnabled] = useState(false)
  const resumedReturn = useRef(false)

  const refreshAsset = () => api.getPublicAsset(qrToken)
    .then((result) => { setAsset(result); document.title = `${result.assetTag} · 资产档案`; return result })

  useEffect(() => {
    refreshAsset()
      .catch((reason) => setError(reason instanceof Error ? reason.message : '无法读取资产档案'))
    api.me().then(setAuthUser).catch(() => setAuthUser(null)).finally(() => setAuthReady(true))
    api.dingtalkStatus().then(status => setDingtalkEnabled(status.enabled)).catch(() => {})
  }, [qrToken])

  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || loginLoading || returning) return
      setLoginOpen(false)
      setConfirmOpen(false)
      setActionError('')
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [loginLoading, returning])

  const prepareReturn = async () => {
    setCheckingReturn(true)
    try {
      const details = await api.getQrReturnInfo(qrToken)
      setAsset(details)
      if (!details.checkedOut) { setActionError('资产已归还，无需重复操作'); return }
      setLoginOpen(false)
      setConfirmOpen(true)
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) { setAuthUser(null); setLoginOpen(true) }
      setActionError(reason instanceof Error ? reason.message : '无法核对归还信息')
    } finally { setCheckingReturn(false) }
  }

  const requestReturn = async () => {
    if (!authReady || checkingReturn) return
    setActionError('')
    setSuccess('')
    if (!authUser) { setLoginOpen(true); return }
    if (!canReturn(authUser)) { setActionError('当前登录账号没有资产归还权限'); return }
    await prepareReturn()
  }

  useEffect(() => {
    if (!asset || !authReady || resumedReturn.current) return
    const url = new URL(window.location.href)
    const loginError = url.searchParams.get('dingtalkError')
    if (!loginError && url.searchParams.get('qrReturn') !== '1') return
    // Consume once under StrictMode; arrival only opens confirmation, never submits a return.
    resumedReturn.current = true
    url.searchParams.delete('qrReturn')
    url.searchParams.delete('dingtalkError')
    window.history.replaceState(window.history.state, '', url.pathname + url.search + url.hash)
    if (loginError) { setActionError(loginError); return }
    if (!authUser) { setLoginOpen(true); return }
    if (!canReturn(authUser)) { setActionError('当前登录账号没有资产归还权限，请联系管理员授权'); return }
    void prepareReturn()
  }, [asset, authReady, authUser, qrToken])

  const loginWithDingtalk = () => {
    if (loginLoading) return
    setLoginLoading(true)
    window.location.assign(`/api/v1/auth/dingtalk/login?qrToken=${encodeURIComponent(qrToken)}`)
  }

  const loginForReturn = async (event: FormEvent) => {
    event.preventDefault()
    setLoginLoading(true)
    setActionError('')
    try {
      const user = await api.login(username, password)
      setAuthUser(user)
      setPassword('')
      if (!canReturn(user)) {
        setActionError('登录成功，但该账号没有资产归还权限')
        return
      }
      await prepareReturn()
    } catch (reason) {
      setActionError(reason instanceof Error ? reason.message : '登录失败，请稍后重试')
    } finally {
      setLoginLoading(false)
    }
  }

  const confirmReturn = async () => {
    if (!asset) return
    setReturning(true)
    setActionError('')
    try {
      const updated = await api.returnAssetByQrToken(qrToken, asset.version)
      setAsset(current => current ? {
        ...current, checkedOut: updated.checkedOut, assignedTo: updated.assignedTo,
        status: updated.status.name, updatedAt: updated.updatedAt, version: updated.version,
        boundDisplays: updated.boundDisplays, boundComputer: updated.boundComputer,
      } : current)
      setConfirmOpen(false)
      setSuccess(`资产已经归还，领用人已清空，状态：${updated.status.name}`)
    } catch (reason) {
      setActionError(reason instanceof Error ? reason.message : '归还失败，请稍后重试')
    } finally {
      setReturning(false)
    }
  }

  if (error) return <main className="public-asset-state"><div><BoxesIcon /><h1>无法打开资产档案</h1><p>{error}</p><small>请确认有此资产的查看权限，或联系资产管理员。</small></div></main>
  if (!asset) return <main className="public-asset-state"><div><span className="spinner" /><h1>正在读取设备信息</h1><p>请稍候…</p></div></main>

  const deviceFields = asset.assetProfile === 'COMPUTER' ? [
    ['电脑型号', value(asset.computerModel)],
    ['CPU', value(asset.cpu)], ['内存', value(asset.memory)],
    ['硬盘', value(asset.storage)], ['显卡', value(asset.graphicsCard)],
  ] : asset.assetProfile === 'DISPLAY' ? [
    ['显示器型号', value(asset.computerModel)],
    ['屏幕尺寸', value(asset.screenSize)], ['分辨率', value(asset.displayResolution)],
    ['显示接口', value(asset.displayInterface)], ['订单号', value(asset.orderNumber)],
  ] : [
    ['设备型号', value(asset.computerModel)],
    ['订单号', value(asset.orderNumber)], ['设备模板', '通用设备'],
  ]
  const fields = [
    ...deviceFields,
    ['资产分类', value(asset.category)], ['所属公司', value(asset.company)],
    ['归属部门', value(asset.ownershipDepartment)],
    ['存放位置', value(asset.location)],
  ].filter(([, content]) => content !== '—')
  const boundAssets = asset.assetProfile === 'COMPUTER' ? asset.boundDisplays : asset.boundComputer ? [asset.boundComputer] : []
  const images = asset.imageUrls?.length ? asset.imageUrls : asset.imageUrl ? [asset.imageUrl] : []
  const assignee = asset.checkedOut ? asset.assignedTo?.trim() : ''

  return <main className="public-asset-page">
    <article className="public-asset-card">
      <header className="public-asset-head">
        <div className="public-asset-mark"><BoxesIcon /></div>
        <div className="public-asset-title"><h1>{asset.name}</h1><p>资产编号 <strong>{asset.assetTag}</strong></p>
          <div className="public-asset-ownership">
            <b className={`public-status ${stateTone(asset)}`}>{stateLabel(asset)}</b>
            <div className={`public-assignee ${assignee ? 'has-holder' : 'no-holder'}`}><span>领用人</span><strong>{asset.checkedOut ? assignee || '领用人待补充' : '暂未领用'}</strong></div>
          </div>
        </div>
      </header>

      {Boolean(asset.knownDefects?.length) && <KnownDefectDetails defects={asset.knownDefects} />}

      {images.length > 0 && <div className="public-asset-gallery">{images.map((source, index) => <img src={source} alt={`${asset.name}图片${index + 1}`} key={`${source.slice(-24)}-${index}`} />)}</div>}

      <section className="public-asset-fields">
        {fields.map(([label, content]) => <div key={label}><span>{label}</span><strong>{content}</strong></div>)}
      </section>

      {boundAssets.length > 0 && asset.assetProfile !== 'GENERAL' && <section className="public-related-section">
        <div className="public-section-title"><h2>设备绑定</h2><span>{boundAssets.length} 项</span></div>
        <div className="public-related-list">{boundAssets.map((device) => <div className="public-related-item" key={device.assetTag}>
              <div><strong>{device.name}</strong><span>{device.assetTag}</span></div>
              <div><span>分类</span><strong>{value(device.category)}</strong></div>
              <div><span>型号</span><strong>{value(device.model)}</strong></div>
              <b>×1</b>
            </div>)}</div>
      </section>}

      {asset.relatedDevices.length > 0 && <section className="public-related-section public-accessory-section">
        <div className="public-section-title"><h2>随附配件</h2><span>{asset.relatedDevices.length} 项</span></div>
        <div className="public-related-list">{asset.relatedDevices.map((device, index) => <div className="public-related-item" key={`${device.name}-${index}`}>
              <div><strong>{device.name}</strong>{device.model && <span>{device.model}</span>}</div>
              {device.specification && <div><span>规格</span><strong>{device.specification}</strong></div>}
              {device.serialNumber && <div><span>序列号</span><strong>{device.serialNumber}</strong></div>}
              <b>×{device.quantity}</b>
            </div>)}</div>
      </section>}

      <section className="public-action-shell" aria-label="资产业务操作">
        {asset.checkedOut
            ? <button className="public-return-cta" type="button" disabled={!authReady || checkingReturn} onClick={() => void requestReturn()}><span>{checkingReturn ? '正在核对设备' : authReady ? '归还资产' : '正在验证权限'}</span><b><ReturnIcon /></b></button>
            : <div className="public-action-complete"><CheckIcon /><span>当前无需归还</span></div>}
        {success && <div className="public-action-message success" role="status"><CheckIcon /><span>{success}</span></div>}
        {actionError && !loginOpen && !confirmOpen && <div className="public-action-message error" role="alert"><span>{actionError}</span></div>}
      </section>

      <footer className="public-asset-foot"><span>数据更新时间</span><strong>{time(asset.updatedAt)}</strong><small>扫码可查看设备基本信息，归还需要登录并具备归还权限</small></footer>
    </article>

    {loginOpen && <div className="public-action-backdrop" onMouseDown={(event) => event.target === event.currentTarget && !loginLoading && setLoginOpen(false)}>
      <div className="public-modal-shell">
        <section className="public-action-modal" role="dialog" aria-modal="true" aria-labelledby="public-login-title">
          <button className="public-modal-close" type="button" disabled={loginLoading} aria-label="关闭" onClick={() => { setLoginOpen(false); setActionError('') }}><CloseIcon /></button>
          <div className="public-modal-icon"><UserIcon /></div>
          <span className="public-modal-eyebrow">IDENTITY CHECK</span>
          <h2 id="public-login-title">登录后继续归还</h2>
          <p>登录只用于验证操作人身份，登录成功后还会再次显示资产确认信息。</p>
          {dingtalkEnabled && <div className="dingtalk-login"><button type="button" className="button primary" disabled={loginLoading} onClick={loginWithDingtalk}>钉钉登录并继续</button></div>}
          <form className="public-login-form" onSubmit={loginForReturn}>
            <label><span>账号</span><input autoFocus={!dingtalkEnabled} autoComplete="username" value={username} onChange={(event) => setUsername(event.target.value)} placeholder="请输入管理账号" /></label>
            <label><span>密码</span><input type="password" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} placeholder="请输入登录密码" /></label>
            {actionError && <div className="public-modal-error" role="alert">{actionError}</div>}
            <button className="public-modal-primary" type="submit" disabled={loginLoading || !username.trim() || !password}><span>{loginLoading ? '正在登录…' : '登录并继续'}</span><b>→</b></button>
          </form>
        </section>
      </div>
    </div>}

    {confirmOpen && <div className="public-action-backdrop" onMouseDown={(event) => event.target === event.currentTarget && !returning && setConfirmOpen(false)}>
      <div className="public-modal-shell">
        <section className="public-action-modal public-return-confirm" role="alertdialog" aria-modal="true" aria-labelledby="public-return-title">
          <button className="public-modal-close" type="button" disabled={returning} aria-label="关闭" onClick={() => { setConfirmOpen(false); setActionError('') }}><CloseIcon /></button>
          <div className="public-modal-icon return"><ReturnIcon /></div>
          <span className="public-modal-eyebrow">FINAL CONFIRMATION</span>
          <h2 id="public-return-title">确认归还这项资产？</h2>
          <p>请最后核对标签和实物。确认后将清空当前领用人；维修、维护和报废状态保留，其他资产恢复为当前可用。</p>
          <div className="public-return-summary">
            <div><span>资产编号</span><strong>{asset.assetTag}</strong></div>
            <div><span>资产名称</span><strong>{asset.name}</strong></div>
            <div><span>当前领用人</span><strong>{value(asset.assignedTo)}</strong></div>
            <div><span>存放位置</span><strong>{value(asset.location)}</strong></div>
            <div><span>操作账号</span><strong>{authUser?.username || '—'}</strong></div>
          </div>
          {actionError && <div className="public-modal-error" role="alert">{actionError}</div>}
          <div className="public-modal-actions">
            <button className="public-modal-secondary" type="button" disabled={returning} onClick={() => { setConfirmOpen(false); setActionError('') }}>取消</button>
            <button className="public-modal-primary" type="button" disabled={returning} onClick={() => void confirmReturn()}><span>{returning ? '归还中…' : '确认归还'}</span><b><CheckIcon /></b></button>
          </div>
        </section>
      </div>
    </div>}
  </main>
}
