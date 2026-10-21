import { useEffect, useRef, useState } from 'react'
import { api } from '../api'
import type { AuthUser } from '../types'
import type { OaCandidate, OaItem, OaPage, OaStatus } from '../types/dingtalkOa'
import { requestKey } from './HandoverPage'
import { canManageWorkflows } from './OperationPage'
import { ConfirmDialog } from './ConfirmDialog'
import './WorkflowPages.css'

const states: Record<string, string> = { STARTING: '发起中／待核实', START_UNCERTAIN: '发起结果待核实', WAITING: '待钉钉审批', CHECK_FAILED: '同步失败，将重试', LEDGER_FAILED: '未入账，请处理', AGREED: '审批通过，办理中', APPLIED: '审批通过，已入账', REFUSED: '已驳回，未改台账', TERMINATED: '已终止，未改台账', SKIPPED: '异常已关闭，未改台账' }
const empty: OaPage = { items: [], page: 0, totalElements: 0, totalPages: 0, canManage: false }
export const oaStateLabel = (state: string) => states[state] || '未知状态，请联系管理员'
const message = (error: unknown) => error instanceof Error ? error.message : '钉钉审批操作失败'

export function DingTalkOaPage({ user, onBack, onNotify, onAssetsChanged }: { user: AuthUser; onBack: () => void; onNotify: (message: string, kind?: 'ok' | 'error') => void; onAssetsChanged: () => void }) {
  const [status, setStatus] = useState<OaStatus | null>(null)
  const [data, setData] = useState(empty)
  const [page, setPage] = useState(0)
  const [mine, setMine] = useState(true)
  const [revision, setRevision] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [creating, setCreating] = useState(false)
  const [kind, setKind] = useState<'ISSUE' | 'RETURN'>('ISSUE')
  const [candidates, setCandidates] = useState<OaCandidate[]>([])
  const [assetId, setAssetId] = useState('')
  const [description, setDescription] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [recovery, setRecovery] = useState<number | null>(null)
  const [instanceId, setInstanceId] = useState('')
  const [closing, setClosing] = useState<OaItem | null>(null)
  const [closeNote, setCloseNote] = useState('')
  const [closeDialog, setCloseDialog] = useState(false)
  const lock = useRef(false)
  const key = useRef(requestKey())
  const manager = canManageWorkflows(user) && data.canManage
  useEffect(() => {
    let active = true; setLoading(true); setError('')
    Promise.all([api.oaStatus(), api.listOa(page, mine)]).then(([settings, result]) => {
      if (!active) return
      if ((mine || !result.canManage) && result.items.some(item => item.ownerId !== user.id)) throw new Error('审批数据范围不一致，请重新登录')
      setStatus(settings); setData(result)
    }).catch(failure => { if (active) { setError(message(failure)); setData(empty); setStatus(null) } }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [page, mine, revision, user.id])
  useEffect(() => {
    if (!creating) return
    let active = true; setCandidates([]); setAssetId(''); setConfirmed(false)
    api.oaCandidates(kind).then(result => { if (active) setCandidates(result) }).catch(failure => { if (active) onNotify(message(failure), 'error') })
    return () => { active = false }
  }, [creating, kind, user.id])
  const mutate = async (work: () => Promise<OaItem>) => {
    if (lock.current) return
    lock.current = true; setBusy(true)
    try {
      const result = await work()
      if (result.approvalState === 'APPLIED') { onAssetsChanged(); onNotify('钉钉审批通过，领用／归还已写入台账') }
      else onNotify(result.error || `工单 #${result.id}：${oaStateLabel(result.approvalState)}`, result.error ? 'error' : 'ok')
      setRevision(value => value + 1)
      return result
    } catch (failure) { onNotify(message(failure), 'error') }
    finally { lock.current = false; setBusy(false) }
  }
  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    const chosen = candidates.find(asset => asset.id === Number(assetId))
    if (!chosen || !confirmed || !description.trim()) return
    const result = await mutate(() => api.createOa({ requestKey: key.current, kind, assetId: chosen.id, assetVersion: chosen.version, description: description.trim(), confirmed }))
    if (result) { setCreating(false); setDescription(''); setConfirmed(false); key.current = requestKey() }
  }
  return <section className="workflow-page">
    <header className="workflow-head"><div><h2>钉钉 OA 交接</h2><p>本人确认实物 → 钉钉审批（含运维实物核验） → 自动领用／归还并留痕。</p></div><div className="workflow-actions"><button className="button ghost" disabled={busy} onClick={onBack}>返回交接记录</button><button className="button ghost" disabled={busy || loading} onClick={() => setRevision(value => value + 1)}>刷新列表</button><button className="button primary" disabled={busy || loading || !status?.enabled || !status.identityReady || creating} onClick={() => setCreating(true)}>发起实物交接审批</button></div></header>
    <p className="workflow-notice">此入口用于已核对实物的设备领用／归还，不代替采购申请或 HR 离职审批。网站不重复审批；未通过或台账冲突不自动改账。旧交接单保持原样。</p>
    {error && <p role="alert" className="workflow-error">{error}</p>}
    {status && !status.enabled && <p className="workflow-notice">钉钉 OA 尚未启用；请管理员按接入文档配置应用权限和审批模板，原有功能不受影响。</p>}
    {status?.enabled && !status.identityReady && <p className="workflow-error">请先用钉钉重新登录或绑定账号，以补全企业身份；不能用姓名代替账号标识。</p>}
    {creating && <form className="workflow-card" onSubmit={event => void submit(event)}>
      <h3>本人实物交接确认</h3><label>类型<select disabled={busy} value={kind} onChange={event => setKind(event.target.value as typeof kind)}><option value="ISSUE">已收到设备，申请领用入账</option><option value="RETURN">已交还设备，申请归还验收</option></select></label>
      <label>设备<select required disabled={busy} value={assetId} onChange={event => { setAssetId(event.target.value); setConfirmed(false) }}><option value="">请选择并核对资产编号</option>{candidates.map(asset => <option key={asset.id} value={asset.id}>{asset.assetTag} · {asset.name}</option>)}</select></label>
      <label>实物、配件和交接说明<textarea required maxLength={1000} disabled={busy} value={description} onChange={event => setDescription(event.target.value)} /></label>
      <label className="workflow-check"><input type="checkbox" disabled={busy} checked={confirmed} onChange={event => setConfirmed(event.target.checked)} />本人已核对设备编号、实物和配件，确认已经收到／交还；不是预约申请。</label>
      <div className="workflow-actions"><button className="button primary" disabled={busy || !confirmed || !assetId || !description.trim()}>提交到钉钉审批</button><button type="button" className="button ghost" disabled={busy} onClick={() => setCreating(false)}>取消填写</button></div>
    </form>}
    {canManageWorkflows(user) && <label className="workflow-check"><input type="checkbox" disabled={busy || loading} checked={!mine} onChange={event => { setMine(!event.target.checked); setPage(0) }} />查看全部 OA 工单</label>}
    {loading ? <div className="workflow-empty">正在读取审批记录…</div> : <div className="workflow-table-wrap"><table className="workflow-table"><thead><tr><th>工单／设备</th><th>员工／类型</th><th>审批与入账</th><th>操作</th></tr></thead><tbody>{data.items.map(item => <tr key={item.id}>
      <td>#{item.id} · {item.assetTag}<details><summary>查看关联信息</summary><p>业务单号：{item.requestKey}</p><p>审批实例：{item.instanceId || '待核实'}</p><p>{item.description}</p></details></td>
      <td>{item.ownerName} · {item.kind === 'ISSUE' ? '领用' : '归还'}</td>
      <td>{oaStateLabel(item.approvalState)}{item.error && <p role="alert" className="workflow-error">{item.error}</p>}<small>{item.checkedAt ? `最近同步：${new Date(item.checkedAt).toLocaleString('zh-CN')}` : '等待同步'}</small></td>
      <td>{item.instanceId && !['APPLIED', 'REFUSED', 'TERMINATED', 'SKIPPED'].includes(item.approvalState) && <button className="button ghost" disabled={busy || !status?.enabled} onClick={() => void mutate(() => api.syncOa(item.id))}>从钉钉同步</button>}
        {manager && !item.instanceId && ['STARTING', 'START_UNCERTAIN'].includes(item.approvalState) && <button className="button ghost" disabled={busy || !status?.enabled} onClick={() => { setRecovery(item.id); setInstanceId('') }}>核实发起结果</button>}
        {manager && ['STARTING', 'START_UNCERTAIN', 'LEDGER_FAILED'].includes(item.approvalState) && <button className="button ghost" disabled={busy || !status?.enabled} onClick={() => { setClosing(item); setCloseNote('') }}>关闭异常（不改台账）</button>}</td>
    </tr>)}</tbody></table>{!data.items.length && <p className="workflow-empty">暂无 OA 交接工单</p>}</div>}
    {recovery !== null && <form className="workflow-card" onSubmit={event => { event.preventDefault(); void mutate(() => api.attachOa(recovery, instanceId.trim())).then(result => { if (result) setRecovery(null) }) }}><h3>管理员恢复关联 #{recovery}</h3><p>先在钉钉核实模板、申请人和业务单号完全一致，再填写真实审批实例 ID。此操作不会重新发起审批，也不会绕过验收。</p><input aria-label="审批实例ID" required maxLength={160} value={instanceId} disabled={busy} onChange={event => setInstanceId(event.target.value)} /><button className="button primary" disabled={busy}>核实并关联</button><button type="button" className="button ghost" disabled={busy} onClick={() => setRecovery(null)}>关闭</button></form>}
    {closing && <form className="workflow-card" onSubmit={event => { event.preventDefault(); if (closeNote.trim()) setCloseDialog(true) }}><h3>关闭异常工单 #{closing.id}</h3><p>只停止本地自动办理并释放设备预留，不撤销钉钉审批，也不代表设备已领用或归还。请先核实实物和审批，必要时重新申请。</p><label>关闭原因<textarea required maxLength={1000} value={closeNote} disabled={busy} onChange={event => setCloseNote(event.target.value)} /></label><button className="button primary" disabled={busy || !closeNote.trim()}>核对并关闭</button><button className="button ghost" type="button" disabled={busy} onClick={() => setClosing(null)}>取消</button></form>}
    {closeDialog && closing && <ConfirmDialog title="关闭异常且不改台账" description="此操作不能替代钉钉撤销审批或实物交接；后续回调不会自动处理此工单。确认已核实并记录原因？" confirmLabel="确认关闭（不改台账）" busy={busy} onCancel={() => !busy && setCloseDialog(false)} onConfirm={() => void mutate(() => api.closeOaException(closing.id, closing.version, closeNote.trim(), true)).then(result => { setCloseDialog(false); if (result) setClosing(null) })} />}
    <footer className="workflow-pagination"><span>共 {data.totalElements} 条</span><div className="workflow-actions"><button className="button ghost" disabled={busy || loading || page === 0} onClick={() => setPage(value => value - 1)}>上一页</button><span>{data.totalPages ? page + 1 : 0} / {data.totalPages}</span><button className="button ghost" disabled={busy || loading || page + 1 >= data.totalPages} onClick={() => setPage(value => value + 1)}>下一页</button></div></footer>
  </section>
}
