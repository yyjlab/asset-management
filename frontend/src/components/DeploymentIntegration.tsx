import { useEffect, useState } from 'react'
import { api } from '../api'
import type { DeploymentCredential, DeploymentEntry, DeploymentHistory, DeploymentStatus } from '../types/deployment'
import './DeploymentIntegration.css'

interface Props { onNotify: (text: string, kind?: 'ok' | 'error') => void }
const time = (value: string | null) => value ? new Date(value).toLocaleString('zh-CN') : '暂无'
const resultLabels: Record<string, string> = { created: '新资产已登记', already_exists: '已存在，配置一致', configuration_conflict: '配置冲突，待核对' }
const fields: Record<string, string> = { model: '型号', cpu: 'CPU', memory: '内存', storage: '硬盘', graphicsCard: '显卡', assetProfile: '资产类型' }

export function DeploymentIntegration({ onNotify }: Props) {
  const [status, setStatus] = useState<DeploymentStatus | null>(null)
  const [credentials, setCredentials] = useState<DeploymentCredential[]>([])
  const [history, setHistory] = useState<DeploymentHistory | null>(null)
  const [page, setPage] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [refresh, setRefresh] = useState(0)
  const [name, setName] = useState('AD 装机平台')
  const [days, setDays] = useState(90)
  const [busy, setBusy] = useState(false)
  const [issued, setIssued] = useState<{ credential: DeploymentCredential; token: string } | null>(null)
  const [revoke, setRevoke] = useState<DeploymentCredential | null>(null)
  const [detail, setDetail] = useState<DeploymentEntry | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true); setError('')
    Promise.all([api.getDeploymentStatus(), api.listDeploymentCredentials(), api.listDeploymentRegistrations(page)])
      .then(([nextStatus, nextCredentials, nextHistory]) => {
        if (!cancelled) { setStatus(nextStatus); setCredentials(nextCredentials); setHistory(nextHistory) }
      })
      .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : '读取接入信息失败') })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [page, refresh])

  const issue = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!name.trim() || name.trim().length > 80 || !Number.isInteger(days) || days < 1 || days > 366) {
      onNotify('名称为1到80字，有效期为1到366天', 'error'); return
    }
    setBusy(true)
    try {
      const result = await api.issueDeploymentCredential(name.trim(), new Date(Date.now() + days * 86400000).toISOString())
      setIssued(result); setRefresh(value => value + 1)
    } catch (e) { onNotify(e instanceof Error ? e.message : '创建凭据失败', 'error') }
    finally { setBusy(false) }
  }
  const confirmRevoke = async () => {
    if (!revoke) return
    setBusy(true)
    try {
      await api.revokeDeploymentCredential(revoke.id)
      setRevoke(null); setRefresh(value => value + 1); onNotify('凭据已撤销')
    } catch (e) { onNotify(e instanceof Error ? e.message : '撤销失败', 'error') }
    finally { setBusy(false) }
  }
  const copyToken = async () => {
    if (!issued) return
    try {
      if (!navigator.clipboard) throw new Error('Clipboard unavailable')
      await navigator.clipboard.writeText(issued.token)
      onNotify('已复制凭据')
    } catch { onNotify('复制失败，请选中密钥手动复制', 'error') }
  }

  return <div className="deployment-settings">
    <header className="deployment-header"><div><h2>装机平台接入</h2><p>AD 服务器提交 PE 采集的资产信息；不自动领用或覆盖已有资产。</p></div>
      <button className="button ghost" disabled={loading || busy} onClick={() => setRefresh(value => value + 1)}>刷新</button></header>
    {loading && <p role="status">正在读取接入信息…</p>}
    {error && <p className="deployment-error" role="alert">{error}</p>}
    {!loading && !error && status && <>
      <section className="deployment-section" aria-label="接入状态">
        <dl className="deployment-status">
          <div><dt>登记接口</dt><dd>{status.enabled ? '已启用' : '未启用'}</dd></div>
          <div><dt>后端识别 HTTPS</dt><dd>{status.secureRequest ? '是' : '否，不能签发凭据'}</dd></div>
          <div><dt>有效凭据</dt><dd>{credentials.filter(item => item.active).length}</dd></div>
          <div><dt>最近收到登记</dt><dd>{time(status.lastReceivedAt)}</dd></div>
        </dl>
        <p className="deployment-endpoint">POST <code>{status.endpoint}</code></p>
        {!status.enabled && <p>后端需配置 AD_DEPLOYMENT_REGISTRATION_ENABLED=true；本页不会更改服务器配置。</p>}
        {!status.lastReceivedAt && <p>尚无登记记录，未验证 AD 发送端连通性。</p>}
      </section>
      <section className="deployment-section"><h3>专用服务凭据</h3>
        <form className="deployment-create" onSubmit={event => void issue(event)}>
          <label>凭据名称<input required maxLength={80} value={name} onChange={event => setName(event.target.value)} /></label>
          <label>有效期（天）<input required type="number" min={1} max={366} step={1} value={Number.isNaN(days) ? '' : days} onChange={event => setDays(event.target.valueAsNumber)} /></label>
          <button className="button primary" disabled={busy || !status.secureRequest}>{busy ? '处理中…' : '创建凭据'}</button>
        </form>
        <div className="deployment-table"><table><thead><tr><th>名称</th><th>创建时间</th><th>到期时间</th><th>状态</th><th>操作</th></tr></thead><tbody>
          {credentials.map(item => <tr key={item.id}><td>{item.name}</td><td>{time(item.createdAt)}</td><td>{time(item.expiresAt)}</td><td>{item.revokedAt ? '已撤销' : item.active ? '有效' : '已过期'}</td><td><button className="button ghost" disabled={busy || !!item.revokedAt} onClick={() => setRevoke(item)}>撤销</button></td></tr>)}
          {!credentials.length && <tr><td colSpan={5}>暂无服务凭据</td></tr>}
        </tbody></table></div>
      </section>
      <section className="deployment-section"><h3>登记记录</h3><p>仅列出已记录的新增、配置一致和配置冲突结果；认证及校验失败不在此列表。</p>
        <div className="deployment-table"><table><thead><tr><th>资产编号</th><th>收到时间</th><th>声明操作人</th><th>登记结果</th><th>操作</th></tr></thead><tbody>
          {history?.items.map(item => <tr key={item.externalRegistrationId}><td>{item.assetTag}</td><td>{time(item.receivedAt)}</td><td>{item.declaredOperator.displayName}（{item.declaredOperator.id}）</td><td>{resultLabels[item.result] || item.result}</td><td><button className="button ghost" onClick={() => setDetail(item)}>查看详情</button></td></tr>)}
          {!history?.items.length && <tr><td colSpan={5}>暂无登记记录</td></tr>}
        </tbody></table></div>
        <div className="deployment-pagination"><span>共 {history?.totalElements || 0} 条 · 第 {page + 1} 页</span><button className="button ghost" disabled={page === 0} onClick={() => setPage(value => value - 1)}>上一页</button><button className="button ghost" disabled={!history || page + 1 >= history.totalPages} onClick={() => setPage(value => value + 1)}>下一页</button></div>
      </section>
    </>}
    {issued && <div className="modal-backdrop"><section className="deployment-dialog" role="dialog" aria-modal="true" aria-labelledby="deployment-token-title">
      <h3 id="deployment-token-title">凭据已创建，仅显示本次</h3><p>仅供 AD 后端保存。关闭后无法再次查看；不要发给 PE 或员工。</p>
      <label>服务密钥<textarea autoFocus readOnly value={issued.token} rows={4} onFocus={event => event.target.select()} /></label>
      <div className="modal-actions"><button className="button ghost" onClick={() => void copyToken()}>复制</button><button className="button primary" onClick={() => setIssued(null)}>已保存，关闭</button></div>
    </section></div>}
    {revoke && <div className="modal-backdrop"><section className="deployment-dialog" role="alertdialog" aria-modal="true" aria-labelledby="deployment-revoke-title"><h3 id="deployment-revoke-title">撤销服务凭据？</h3><p>撤销“{revoke.name}”后，使用此凭据的 AD 服务器无法继续登记。已有资产和登记记录不会删除。</p><div className="modal-actions"><button autoFocus className="button ghost" disabled={busy} onClick={() => setRevoke(null)}>取消</button><button className="button primary" disabled={busy} onClick={() => void confirmRevoke()}>确认撤销</button></div></section></div>}
    {detail && <div className="modal-backdrop"><section className="deployment-dialog" role="dialog" aria-modal="true" aria-labelledby="deployment-detail-title"><h3 id="deployment-detail-title">登记详情</h3>
      <dl><dt>外部登记标识</dt><dd>{detail.externalRegistrationId}</dd><dt>资产编号 / ID</dt><dd>{detail.assetTag} / {detail.assetId}</dd><dt>服务凭据</dt><dd>{credentials.find(item => item.id === detail.credentialId)?.name || detail.credentialId}</dd><dt>来源</dt><dd>AD 装机平台</dd></dl>
      <p>{resultLabels[detail.result] || detail.result}</p>
      {detail.differences.map(item => <div key={item.field} className="deployment-difference"><strong>{fields[item.field] || item.field}</strong><p>现有：{item.existing || '未记录'}</p><p>提交：{item.incoming || '未采集'}</p></div>)}
      {!!detail.differences.length && <p>仅供核对，本页不会覆盖已有配置。</p>}
      {detail.warnings.map((warning, index) => <p key={index}>{warning}</p>)}
      <div className="modal-actions"><button autoFocus className="button primary" onClick={() => setDetail(null)}>关闭</button></div>
    </section></div>}
  </div>
}
