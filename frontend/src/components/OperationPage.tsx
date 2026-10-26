import { useEffect, useRef, useState } from 'react'
import { ApiError, api } from '../api'
import type { Asset, AuthUser, OperationAction, OperationCreate, OperationDetail, OperationKind, OperationPageData, Permission } from '../types'
import { requestKey } from './HandoverPage'
import { ConfirmDialog } from './ConfirmDialog'
import './WorkflowPages.css'

const kinds: Record<OperationKind, string> = { ISSUE: '领用申请', RETURN: '归还验收', TRANSFER: '转交他人', CORRECTION: '信息纠错', REPAIR: '故障报修' }
const states = { PENDING: '待受理', WAITING_CONFIRM: '待员工确认', PROCESSING: '维修中', CONFIRMED: '待管理员完结', COMPLETED: '已完成', REJECTED: '已驳回', CANCELLED: '已取消', OA_PENDING: '钉钉审批中', OA_FAILED: '钉钉办理需处理', HANDOVER_PENDING: '交接办理中' }
const correctionFields = { cpu: 'CPU', memory: '内存', storage: '硬盘', ownershipDepartment: '归属部门' }
const empty: OperationPageData = { items: [], page: 0, totalElements: 0, totalPages: 0, canManage: false }
export const canManageWorkflows = (user: AuthUser) => user.dataScope === 'ALL' && (['ASSET_VIEW', 'ASSET_EDIT', 'ASSET_CHECKOUT', 'ASSET_RETURN'] as Permission[]).every(value => user.permissions.includes(value))
const message = (error: unknown) => error instanceof Error ? error.message : '工单操作失败'
interface Props { user: AuthUser; assets: Asset[]; repair?: boolean; initialId?: number; onOpenOa?: () => void; onOpenHandover?: (id: number) => void; onNotify: (text: string, kind?: 'ok' | 'error') => void; onAssetsChanged: () => void }

export function OperationPage({ user, assets, repair = false, initialId, onOpenOa, onOpenHandover, onNotify, onAssetsChanged }: Props) {
  const [data, setData] = useState(empty)
  const [page, setPage] = useState(0)
  const [kind, setKind] = useState(repair ? 'REPAIR' : '')
  const [mine, setMine] = useState(true)
  const [revision, setRevision] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [selected, setSelected] = useState<OperationDetail | null>(null)
  const [creating, setCreating] = useState(false)
  const [busy, setBusy] = useState(false)
  const [opening, setOpening] = useState(false)
  const [note, setNote] = useState('')
  const [assetId, setAssetId] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [outcome, setOutcome] = useState<'FIXED' | 'SCRAP'>('FIXED')
  const [cost, setCost] = useState('0')
  const [completeDialog, setCompleteDialog] = useState(false)
  const selection = useRef(0)
  const mutation = useRef(false)
  const manager = canManageWorkflows(user) && data.canManage
  const own = assets.filter(asset => asset.assignedUserId === user.id)
  const available = assets.filter(asset => !asset.checkedOut && !asset.assignedUserId && !asset.assignedTo && ['当前可用', '可领用'].includes(asset.status.name))
  useEffect(() => {
    let active = true; setLoading(true); setError('')
    api.listOperations(page, kind, mine).then(result => { if (active) {
      if ((mine || !result.canManage) && result.items.some(task => task.ownerId !== user.id && task.targetId !== user.id)) throw new Error('工单数据范围不一致，请刷新登录')
      setData(result)
    } }).catch(failure => { if (active) { selection.current++; setData(empty); setSelected(null); setCreating(false); setOpening(false); setError(message(failure)) } }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [page, kind, mine, revision, user.id])
  useEffect(() => () => { selection.current++ }, [])
  const reset = () => { setNote(''); setAssetId(''); setConfirmed(false); setOutcome('FIXED'); setCost('0'); setCompleteDialog(false) }
  const open = async (id: number) => {
    const token = ++selection.current; setOpening(true); setSelected(null); reset()
    try { const result = await api.getOperation(id); if (token === selection.current) {
      if (!canManageWorkflows(user) && result.task.ownerId !== user.id && result.task.targetId !== user.id) throw new Error('工单不属于当前账号')
      setSelected(result)
    } } catch (failure) { if (token === selection.current) { setSelected(null); onNotify(message(failure), 'error') } }
    finally { if (token === selection.current) setOpening(false) }
  }
  useEffect(() => { if (initialId) void open(initialId) }, [initialId])
  const action = async (actionName: OperationAction['action']) => {
    if (!selected || mutation.current) return
    const chosen = available.find(asset => asset.id === Number(assetId))
    const payload: OperationAction = { version: selected.task.version, action: actionName, note, confirmed,
      ...(actionName === 'APPROVE' && selected.task.kind === 'ISSUE' && chosen ? { assetId: chosen.id, assetVersion: chosen.version } : {}),
      ...(actionName === 'COMPLETE' && selected.assetVersion !== null ? { assetVersion: selected.assetVersion } : {}),
      ...(actionName === 'RESOLVE' ? { outcome, repairCost: Number(cost) } : {}) }
    mutation.current = true; setBusy(true); selection.current++; setOpening(false)
    try { setSelected(await api.operationAction(selected.task.id, payload)); setRevision(value => value + 1); reset(); onNotify(actionName === 'COMPLETE' ? '工单已完结，结果已写入正式台账' : '处理记录已保存，正式台账尚未变更'); if (actionName === 'COMPLETE') onAssetsChanged() }
    catch (failure) { setCompleteDialog(false); onNotify(message(failure), 'error'); if (failure instanceof ApiError && [403, 404].includes(failure.status)) { setSelected(null); setRevision(value => value + 1) } else if (failure instanceof ApiError && failure.status === 409) await open(selected.task.id) }
    finally { mutation.current = false; setBusy(false) }
  }
  const task = selected?.task
  const oaTask = task && ['OA_PENDING', 'OA_FAILED'].includes(task.state)
  const activeTask = task && !oaTask && !task.handoverId && !['COMPLETED', 'REJECTED', 'CANCELLED'].includes(task.state)
  return <section className="workflow-page">
    {!repair && onOpenOa && <button className="button ghost" onClick={onOpenOa}>钉钉 OA 交接（自动领用／归还）</button>}
    {oaTask && <p className="workflow-notice">此工单由钉钉审批办理，请进入“钉钉 OA 交接”查看进度；本页面不能重复审批。</p>}
    <header className="workflow-head"><div><h2>{repair ? '报修维修' : '领用归还与纠错'}</h2><p>申请、受理、员工本人确认、管理员完结分开记录。</p></div><div className="workflow-actions"><button className="button ghost" disabled={busy || opening || creating} onClick={() => { setRevision(value => value + 1); if (selected) void open(selected.task.id); onAssetsChanged() }}>刷新</button>{!selected && !creating && <button className="button primary" disabled={loading || !!error} onClick={() => setCreating(true)}>新建申请</button>}</div></header>
    <p className="workflow-notice">提交、受理和确认只记录流程。仅管理员在员工确认后点击“完结并写入台账”，才更新指定资产；版本冲突时不会覆盖其他修改。</p>
    {error && <p role="alert" className="workflow-error">{error}，请刷新重试。</p>}
    {creating ? <OperationEditor assets={own} repair={repair} onCancel={() => setCreating(false)} onNotify={onNotify} onCreated={result => { setCreating(false); setSelected(result); reset(); setRevision(value => value + 1); onNotify('申请已提交，正式台账未修改') }} /> : selected && task ? <>
      <div className="workflow-head"><button className="button ghost" disabled={busy || opening} onClick={() => { selection.current++; setSelected(null); reset() }}>返回列表</button><h3>#{task.id} · {task.title}</h3><span>{states[task.state]}</span></div>
      <div className="workflow-meta"><span>{kinds[task.kind]}</span><span>申请人：{task.ownerName}</span><span>接收／确认人：{task.targetName}</span><span>设备：{task.assetTag || '等待分配'}</span><span>资产快照版本：{selected.assetVersion ?? '未分配'}</span></div>
      {task.handoverId && onOpenHandover && <div className="workflow-actions"><button className="button ghost" onClick={() => onOpenHandover(task.handoverId!)}>查看交接单 #{task.handoverId}</button></div>}
      <p className="workflow-pre">{selected.description}</p>
      {!!Object.keys(selected.proposed).length && <div className="workflow-card"><h3>申请纠正的信息</h3>{Object.entries(selected.proposed).map(([key, value]) => <p key={key}>{correctionFields[key as keyof typeof correctionFields]}：{value || '清空此字段'}</p>)}</div>}
      {selected.outcome && <p>维修结果：{selected.outcome === 'FIXED' ? '已修复' : '建议报废'} · 费用 ¥{selected.repairCost ?? 0}</p>}
      {activeTask && <div className="workflow-card">
        <label>处理说明／取消原因<textarea maxLength={1000} value={note} disabled={busy || opening} onChange={event => setNote(event.target.value)} /></label>
        {manager && task.state === 'PENDING' && task.kind === 'ISSUE' && <label>分配空闲设备<select value={assetId} disabled={busy} onChange={event => setAssetId(event.target.value)}><option value="">请选择并核对实物</option>{available.map(asset => <option key={asset.id} value={asset.id}>{asset.assetTag} · {asset.name} · {asset.model.name}</option>)}</select>{!available.length && <small>暂无可分配设备；请核对当前可用状态和关联账号后刷新。</small>}</label>}
        {manager && task.state === 'PROCESSING' && <div className="workflow-grid"><label>维修结果<select value={outcome} disabled={busy} onChange={event => setOutcome(event.target.value as 'FIXED' | 'SCRAP')}><option value="FIXED">修复</option><option value="SCRAP">报废（待员工确认及管理员完结）</option></select></label><label>费用（元，无费用填0）<input type="number" min="0" max="9999999999.99" step="0.01" value={cost} disabled={busy} onChange={event => setCost(event.target.value)} /></label></div>}
        {((task.state === 'WAITING_CONFIRM' && task.targetId === user.id) || (manager && task.state === 'CONFIRMED')) && <label className="workflow-check"><input type="checkbox" checked={confirmed} disabled={busy} onChange={event => setConfirmed(event.target.checked)} />{task.state === 'CONFIRMED' ? '我已验收并确认将本次结果写入正式台账' : '我已核对实物、设备信息和处理结果，确认无误'}</label>}
        <div className="workflow-actions">
          {manager && task.state === 'PENDING' && <><button className="button primary" disabled={busy || opening || (task.kind === 'ISSUE' && !assetId)} onClick={() => void action('APPROVE')}>{task.kind === 'REPAIR' ? '受理维修' : '受理并交员工确认'}</button><button className="button ghost" disabled={busy || !note.trim()} onClick={() => void action('REJECT')}>驳回申请</button></>}
          {manager && task.state === 'PROCESSING' && <><button className="button ghost" disabled={busy || !note.trim()} onClick={() => void action('PROGRESS')}>保存维修进度</button><button className="button primary" disabled={busy || !note.trim() || cost === '' || !Number.isFinite(Number(cost)) || Number(cost) < 0} onClick={() => void action('RESOLVE')}>提交结果，等待员工确认</button></>}
          {task.state === 'WAITING_CONFIRM' && task.targetId === user.id && <button className="button primary" disabled={busy || opening || !confirmed} onClick={() => void action('CONFIRM')}>本人确认</button>}
          {manager && task.state === 'CONFIRMED' && <button className="button primary" disabled={busy || opening || !confirmed || !note.trim() || selected.assetVersion === null} onClick={() => setCompleteDialog(true)}>完结并写入台账</button>}
          {(manager || task.ownerId === user.id && task.state === 'PENDING') && <button className="button ghost" disabled={busy || opening || !note.trim()} onClick={() => void action('CANCEL')}>取消并保留记录</button>}
        </div>
        {task.state === 'WAITING_CONFIRM' && task.targetId !== user.id && <p className="workflow-muted">等待 {task.targetName} 本人确认，管理员不能代签。</p>}
      </div>}
      <section className="workflow-history"><h3>完整处理记录</h3>{selected.events.map((event, index) => <div key={index}><time>{new Date(event.occurredAt).toLocaleString('zh-CN')}</time><strong>{event.actor}</strong><span>{event.description}</span></div>)}</section>
      {completeDialog && <ConfirmDialog title="完结并更新正式资产" description={`将按工单 #${task.id} 更新资产 ${task.assetTag}。请确认实物验收、员工确认和处理说明；如台账已被修改，系统将拒绝覆盖。`} confirmLabel="确认写入台账" busy={busy} onCancel={() => !busy && setCompleteDialog(false)} onConfirm={() => void action('COMPLETE')} />}
    </> : !creating && <>
      <div className="workflow-actions">{!repair && <label>类型<select value={kind} disabled={loading || opening} onChange={event => { setKind(event.target.value); setPage(0) }}><option value="">全部</option>{Object.entries(kinds).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>}{canManageWorkflows(user) && <label className="workflow-check"><input type="checkbox" checked={!mine} disabled={loading || opening} onChange={event => { setMine(!event.target.checked); setPage(0) }} />查看全部申请（管理员）</label>}</div>
      {loading || opening ? <div className="workflow-empty">正在读取工单…</div> : <div className="workflow-table-wrap"><table className="workflow-table"><thead><tr><th>申请</th><th>类型／设备</th><th>申请人／确认人</th><th>状态</th><th /></tr></thead><tbody>{data.items.map(item => <tr key={item.id}><td>#{item.id} · {item.title}</td><td>{kinds[item.kind]} · {item.assetTag || '待分配'}</td><td>{item.ownerName} / {item.targetName}</td><td>{states[item.state]}</td><td><button className="button ghost" disabled={busy} onClick={() => void open(item.id)}>查看处理</button></td></tr>)}</tbody></table>{!data.items.length && <p className="workflow-empty">暂无相关申请</p>}</div>}
      <footer className="workflow-pagination"><span>共 {data.totalElements} 条</span><div className="workflow-actions"><button className="button ghost" disabled={loading || opening || page === 0} onClick={() => setPage(value => value - 1)}>上一页</button><span>{data.totalPages ? page + 1 : 0} / {data.totalPages}</span><button className="button ghost" disabled={loading || opening || page + 1 >= data.totalPages} onClick={() => setPage(value => value + 1)}>下一页</button></div></footer>
    </>}
  </section>
}

function OperationEditor({ assets, repair, onCancel, onCreated, onNotify }: { assets: Asset[]; repair: boolean; onCancel: () => void; onCreated: (detail: OperationDetail) => void; onNotify: Props['onNotify'] }) {
  const [draft, setDraft] = useState<OperationCreate>(() => ({ requestKey: requestKey(), kind: repair ? 'REPAIR' : 'ISSUE', title: '', description: '', assetId: null, targetUsername: '', proposed: {} }))
  const [busy, setBusy] = useState(false)
  const lock = useRef(false)
  const submit = async (event: React.FormEvent) => {
    event.preventDefault(); if (lock.current) return; lock.current = true; setBusy(true)
    try { onCreated(await api.createOperation(draft)) } catch (failure) { onNotify(message(failure), 'error') } finally { lock.current = false; setBusy(false) }
  }
  return <form className="workflow-card" onSubmit={event => void submit(event)}><h3>新建{repair ? '报修' : '领用／归还／转交／纠错'}申请</h3>
    {!repair && <label>申请类型<select value={draft.kind} disabled={busy} onChange={event => setDraft({ ...draft, kind: event.target.value as OperationKind, assetId: null, targetUsername: '', proposed: {} })}>{Object.entries(kinds).filter(([value]) => value !== 'REPAIR').map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>}
    <label>标题<input required maxLength={160} value={draft.title} disabled={busy} onChange={event => setDraft({ ...draft, title: event.target.value })} /></label>
    {draft.kind !== 'ISSUE' && <label>本人设备<select required value={draft.assetId ?? ''} disabled={busy} onChange={event => setDraft({ ...draft, assetId: Number(event.target.value) || null })}><option value="">请选择本人设备</option>{assets.map(asset => <option key={asset.id} value={asset.id}>{asset.assetTag} · {asset.name} · {asset.model.name}</option>)}</select>{!assets.length && <small>没有明确关联本账号的设备，请联系管理员核对关联领用账号。</small>}</label>}
    {draft.kind === 'TRANSFER' && <label>接收人系统账号（非姓名）<input required maxLength={60} value={draft.targetUsername} disabled={busy} onChange={event => setDraft({ ...draft, targetUsername: event.target.value })} /></label>}
    {draft.kind === 'CORRECTION' && <div className="workflow-grid">{Object.entries(correctionFields).map(([key, label]) => <label key={key} className="workflow-card"><span><input type="checkbox" disabled={busy} checked={Object.hasOwn(draft.proposed, key)} onChange={event => { const proposed = { ...draft.proposed }; if (event.target.checked) proposed[key] = ''; else delete proposed[key]; setDraft({ ...draft, proposed }) }} /> 修改{label}</span>{Object.hasOwn(draft.proposed, key) && <input maxLength={key === 'ownershipDepartment' ? 120 : 200} value={draft.proposed[key]} disabled={busy} placeholder="留空表示申请清空" onChange={event => setDraft({ ...draft, proposed: { ...draft.proposed, [key]: event.target.value } })} />}</label>)}</div>}
    <label>{repair ? '故障描述、发生时间及影响' : '申请原因与情况说明'}<textarea required maxLength={2000} value={draft.description} disabled={busy} onChange={event => setDraft({ ...draft, description: event.target.value })} /></label>
    <div className="workflow-actions"><button type="button" className="button ghost" disabled={busy} onClick={onCancel}>取消</button><button className="button primary" disabled={busy || (draft.kind !== 'ISSUE' && !draft.assetId) || (draft.kind === 'CORRECTION' && !Object.keys(draft.proposed).length)}>{busy ? '提交中…' : '提交申请（不修改台账）'}</button></div>
  </form>
}
