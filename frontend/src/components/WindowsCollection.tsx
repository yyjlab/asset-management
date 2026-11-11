import { useEffect, useRef, useState } from 'react'
import { api } from '../api'
import type { DeploymentCredential } from '../types/deployment'
import './DeploymentIntegration.css'

export function WindowsCollection({ onNotify }: { onNotify: (text: string, kind?: 'ok' | 'error') => void }) {
  const [name, setName] = useState('Windows 资产采集')
  const [baseUrl, setBaseUrl] = useState(location.origin)
  const [days, setDays] = useState('7')
  const [items, setItems] = useState<DeploymentCredential[]>([])
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [revision, setRevision] = useState(0)
  const lock = useRef(false)
  const [revoke, setRevoke] = useState<DeploymentCredential | null>(null)
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    api.listWindowsCollectionPackages().then(rows => { if (!cancelled) { setItems(rows); setError('') } })
      .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : '读取采集包失败') })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [revision])
  const download = async (event: React.FormEvent) => {
    event.preventDefault()
    if (lock.current) return
    lock.current = true; setBusy(true); setError('')
    try {
      await api.downloadWindowsCollection({ name: name.trim(), baseUrl: baseUrl.trim(), days: Number(days) })
      onNotify('采集包已下载，完整解压后双击“一键采集”，填写使用人和部门后提交')
      setRevision(v => v + 1)
    } catch (e) { setError(e instanceof Error ? e.message : '下载失败，请刷新后检查采集包列表') }
    finally { lock.current = false; setBusy(false) }
  }
  const confirmRevoke = async () => {
    if (!revoke || lock.current) return
    lock.current = true; setBusy(true)
    try { await api.revokeWindowsCollectionPackage(revoke.id); setRevoke(null); setRevision(v => v + 1); onNotify('采集包已撤销，不影响已提交记录') }
    catch (e) { setError(e instanceof Error ? e.message : '撤销失败') }
    finally { lock.current = false; setBusy(false) }
  }
  const date = (value: string) => new Date(value).toLocaleString('zh-CN')
  return <section className="deployment-settings" aria-label="Windows 采集">
    <header className="deployment-header"><div><h2>Windows 采集</h2><p>采集包可放在电脑或 U 盘。双击运行，填写实际使用人姓名和部门并确认提交，结果进入资产入库审核。</p></div>
      <button className="button ghost" disabled={busy || loading} onClick={() => setRevision(v => v + 1)}>刷新</button></header>
    <form className="deployment-section" onSubmit={event => void download(event)}>
      <div className="deployment-review-fields">
        <label>采集包名称<input required maxLength={80} value={name} disabled={busy} onChange={e => setName(e.target.value)} /></label>
        <label>有效期（天）<input type="number" required min={1} max={90} value={days} disabled={busy} onChange={e => setDays(e.target.value)} /></label>
        <label>资产系统地址<input type="url" required value={baseUrl} disabled={busy} onChange={e => setBaseUrl(e.target.value)} /></label>
      </div>
      <p>只采集配置，不安装常驻程序、不修改系统。采集包包含专用凭据，请勿公开传播；HTTP 仅用于已确认的受信内网。</p>
      <button className="button primary" disabled={busy}>{busy ? '处理中…' : '生成并下载采集包'}</button>
    </form>
    {error && <p role="alert" className="deployment-error">{error}</p>}
    {loading ? <p role="status">正在读取采集包…</p> : <div className="deployment-table"><table><thead><tr><th>名称</th><th>创建时间</th><th>到期时间</th><th>状态</th><th>操作</th></tr></thead><tbody>
      {items.map(row => <tr key={row.id}><td>{row.name}</td><td>{date(row.createdAt)}</td><td>{date(row.expiresAt)}</td><td>{row.active ? '有效' : row.revokedAt ? '已撤销' : '已过期'}</td><td><button className="button ghost" disabled={busy || !!row.revokedAt} onClick={() => setRevoke(row)}>撤销</button></td></tr>)}
      {!items.length && <tr><td colSpan={5}>暂无 Windows 采集包</td></tr>}
    </tbody></table></div>}
    {revoke && <div className="modal-backdrop"><section className="deployment-dialog" role="dialog" aria-modal="true" aria-label="撤销采集包"><h3>撤销采集包</h3><p>撤销“{revoke.name}”后，使用该包的所有 U 盘将不能再提交；已入库资产和待审记录不变。</p><div className="deployment-dialog-footer"><button className="button ghost" disabled={busy} onClick={() => setRevoke(null)}>取消</button><button className="button primary" disabled={busy} onClick={() => void confirmRevoke()}>确认撤销</button></div></section></div>}
  </section>
}
