import { useEffect, useRef, useState } from 'react'
import { ApiError, api } from '../api'
import type { AuthUser, InventoryAction, InventoryDetail, InventoryPageData } from '../types'
import { requestKey } from './HandoverPage'
import { canManageWorkflows } from './OperationPage'
import { ConfirmDialog } from './ConfirmDialog'
import './WorkflowPages.css'

const empty: InventoryPageData = { items: [], page: 0, totalElements: 0, totalPages: 0, canManage: false }
const results = { PRESENT: '设备在手', MISSING: '设备缺失', MISMATCH: '信息不符' }
const states = { OPEN: '盘点中', COMPLETED: '已完成', CANCELLED: '已取消' }
const message = (error: unknown) => error instanceof Error ? error.message : '盘点操作失败'
interface Props { user: AuthUser; onNotify: (text: string, kind?: 'ok' | 'error') => void }

export function InventoryPage({ user, onNotify }: Props) {
  const [data, setData] = useState(empty)
  const [page, setPage] = useState(0)
  const [revision, setRevision] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [selected, setSelected] = useState<InventoryDetail | null>(null)
  const [creating, setCreating] = useState(false)
  const [busy, setBusy] = useState(false)
  const [opening, setOpening] = useState(false)
  const [itemIndex, setItemIndex] = useState<number | null>(null)
  const [result, setResult] = useState<'PRESENT' | 'MISSING' | 'MISMATCH'>('PRESENT')
  const [note, setNote] = useState('')
  const [summary, setSummary] = useState('')
  const [closing, setClosing] = useState<'CLOSE' | 'CANCEL' | null>(null)
  const selection = useRef(0)
  const mutation = useRef(false)
  const manager = canManageWorkflows(user) && data.canManage
  useEffect(() => {
    let active = true; setLoading(true); setError('')
    api.listInventories(page).then(value => { if (active) setData(value) })
      .catch(failure => { if (active) { selection.current++; setData(empty); setSelected(null); setCreating(false); setOpening(false); setError(message(failure)) } }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [page, revision, user.id])
  useEffect(() => () => { selection.current++ }, [])
  const reset = () => { setItemIndex(null); setResult('PRESENT'); setNote(''); setSummary(''); setClosing(null) }
  const open = async (id: number) => {
    const token = ++selection.current; setOpening(true); setSelected(null); reset()
    try { const value = await api.getInventory(id); if (token === selection.current) {
      if (!canManageWorkflows(user) && (value.canManage || value.items.some(item => item.ownerId !== user.id))) throw new Error('盘点范围不一致，请刷新登录')
      setSelected(value)
    } } catch (failure) { if (token === selection.current) { setSelected(null); onNotify(message(failure), 'error') } } finally { if (token === selection.current) setOpening(false) }
  }
  const action = async (actionName: InventoryAction['action']) => {
    if (!selected || mutation.current) return
    const payload: InventoryAction = { version: selected.batch.version, action: actionName, note: ['CLOSE', 'CANCEL'].includes(actionName) ? summary : note,
      ...(itemIndex !== null ? { index: itemIndex } : {}), ...(actionName === 'CONFIRM' ? { result } : {}) }
    mutation.current = true; setBusy(true); selection.current++; setOpening(false)
    try { setSelected(await api.inventoryAction(selected.batch.id, payload)); setRevision(value => value + 1); reset(); onNotify('盘点记录已保存，正式资产台账未修改') }
    catch (failure) { setClosing(null); onNotify(message(failure), 'error'); if (failure instanceof ApiError && [403, 404].includes(failure.status)) { setSelected(null); setRevision(value => value + 1) } else if (failure instanceof ApiError && failure.status === 409) await open(selected.batch.id) }
    finally { mutation.current = false; setBusy(false) }
  }
  const batch = selected?.batch
  const selectedItem = selected?.items.find(item => item.index === itemIndex)
  return <section className="workflow-page">
    <header className="workflow-head"><div><h2>资产盘点</h2><p>员工确认本人设备；管理员记录实物核实和差异处理。</p></div><div className="workflow-actions"><button className="button ghost" disabled={busy || opening || creating} onClick={() => { setRevision(value => value + 1); if (selected) void open(selected.batch.id) }}>刷新</button>{manager && !selected && !creating && <button className="button primary" disabled={loading || !!error} onClick={() => setCreating(true)}>发起部门盘点</button>}</div></header>
    <p className="workflow-notice">盘点保存发起时的设备快照；所有盘点操作均不自动修改资产。普通员工仅能查看与确认本人项，管理员核实不能代替员工签认。台账发生变化时会提示人工复核。</p>
    {error && <p role="alert" className="workflow-error">{error}，请刷新重试。</p>}
    {creating && manager ? <InventoryEditor onNotify={onNotify} onCancel={() => setCreating(false)} onCreated={value => { setCreating(false); setSelected(value); reset(); setRevision(value => value + 1); onNotify('盘点已发起，正式台账未修改') }} /> : selected && batch ? <>
      <div className="workflow-head"><button className="button ghost" disabled={busy || opening} onClick={() => { selection.current++; setSelected(null); reset() }}>返回列表</button><h3>#{batch.id} · {batch.title}</h3><span>{states[batch.state]}</span></div>
      <div className="workflow-meta"><span>部门：{batch.department || '全部部门'}</span><span>截止：{batch.dueDate}</span><span>{batch.confirmed}/{batch.total} 已确认，{batch.resolved} 已核实</span></div>
      {selected.items.map(item => <article className="workflow-card" key={item.index}><h3>{item.assetTag} · {item.name}</h3><div className="workflow-meta"><span>领用人：{item.ownerName || '未关联账号（需管理员核实）'}</span><span>{item.result ? results[item.result] : '待员工确认'}</span><span>{item.resolved ? '管理员已核实' : '待管理员核实'}</span></div>
        {item.ledgerChanged && <p className="workflow-warning">该资产台账在盘点发起后已变化或已不存在，请管理员复核当前台账及实物。</p>}
        {item.employeeNote && <p className="workflow-pre">员工说明：{item.employeeNote}</p>}{item.resolution && <p className="workflow-pre">核实处理：{item.resolution}</p>}
        {batch.state === 'OPEN' && !item.resolved && <div className="workflow-actions">{item.ownerId === user.id && !item.result && <button className="button primary" disabled={busy || opening} onClick={() => { setItemIndex(item.index); setResult('PRESENT'); setNote('') }}>确认本人设备</button>}{manager && selected.canManage && <button className="button ghost" disabled={busy || opening} onClick={() => { setItemIndex(item.index); setNote('') }}>管理员核实／处理差异</button>}</div>}
        {itemIndex === item.index && batch.state === 'OPEN' && !item.resolved && <div className="workflow-card">
          {item.ownerId === user.id && !item.result && <label>本人盘点结果<select value={result} disabled={busy} onChange={event => setResult(event.target.value as typeof result)}>{Object.entries(results).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>}
          <label>实物核对／差异说明<textarea maxLength={1000} value={note} disabled={busy} onChange={event => setNote(event.target.value)} /></label><div className="workflow-actions"><button className="button ghost" disabled={busy} onClick={() => setItemIndex(null)}>收起</button>{item.ownerId === user.id && !item.result && <button className="button primary" disabled={busy || (result !== 'PRESENT' && !note.trim())} onClick={() => void action('CONFIRM')}>提交本人确认</button>}{manager && selected.canManage && <button className="button ghost" disabled={busy || !note.trim()} onClick={() => void action('RESOLVE')}>保存管理员核实（不改台账）</button>}</div>
        </div>}
      </article>)}
      {manager && selected.canManage && batch.state === 'OPEN' && <section className="workflow-card"><label>盘点总结／取消原因<textarea maxLength={1000} value={summary} disabled={busy} onChange={event => setSummary(event.target.value)} /></label><div className="workflow-actions"><button className="button primary" disabled={busy || opening || !summary.trim() || selected.items.some(item => !item.resolved)} onClick={() => setClosing('CLOSE')}>完成盘点</button><button className="button ghost" disabled={busy || opening || !summary.trim()} onClick={() => setClosing('CANCEL')}>取消并保留盘点记录</button></div><p className="workflow-muted">全部设备核实处理后才能完成，信息差异请在资产台账另行核对修正。</p></section>}
      {!!selected.events.length && <section className="workflow-history"><h3>处理记录</h3>{selected.events.map((event, index) => <div key={index}><time>{new Date(event.occurredAt).toLocaleString('zh-CN')}</time><strong>{event.actor}</strong><span>{event.description}</span></div>)}</section>}
      {closing && <ConfirmDialog title={closing === 'CLOSE' ? '完成盘点' : '取消盘点'} description={closing === 'CLOSE' ? '确认所有设备已实物核实并处理差异？此操作保留盘点记录，不会自动修改资产台账。' : '确认取消本次盘点？已填写的记录保留，正式台账不变。'} confirmLabel="确认保存" busy={busy} onCancel={() => !busy && setClosing(null)} onConfirm={() => void action(closing)} />}
    </> : !creating && <>
      {loading || opening ? <p className="workflow-empty">正在读取盘点…</p> : <div className="workflow-table-wrap"><table className="workflow-table"><thead><tr><th>盘点</th><th>部门／截止</th><th>本人可见范围进度</th><th>状态</th><th /></tr></thead><tbody>{data.items.map(item => <tr key={item.id}><td>#{item.id} · {item.title}</td><td>{item.department || '全部部门'} / {item.dueDate}</td><td>{item.confirmed}/{item.total} 确认 · {item.resolved} 核实</td><td>{states[item.state]}</td><td><button className="button ghost" onClick={() => void open(item.id)}>查看盘点</button></td></tr>)}</tbody></table>{!data.items.length && <p className="workflow-empty">暂无相关盘点</p>}</div>}
      <footer className="workflow-pagination"><span>共 {data.totalElements} 次盘点</span><div className="workflow-actions"><button className="button ghost" disabled={loading || opening || page === 0} onClick={() => setPage(value => value - 1)}>上一页</button><span>{data.totalPages ? page + 1 : 0} / {data.totalPages}</span><button className="button ghost" disabled={loading || opening || page + 1 >= data.totalPages} onClick={() => setPage(value => value + 1)}>下一页</button></div></footer>
    </>}
    {selectedItem && !selectedItem.resolved && selectedItem.result && !manager && <p className="workflow-muted">已提交结果，修改请联系管理员记录处理。</p>}
  </section>
}

function InventoryEditor({ onCancel, onCreated, onNotify }: { onCancel: () => void; onCreated: (detail: InventoryDetail) => void; onNotify: Props['onNotify'] }) {
  const [draft, setDraft] = useState(() => ({ requestKey: requestKey(), title: '', department: '', dueDate: '' }))
  const [busy, setBusy] = useState(false)
  const lock = useRef(false)
  const submit = async (event: React.FormEvent) => { event.preventDefault(); if (lock.current) return; lock.current = true; setBusy(true); try { onCreated(await api.createInventory(draft)) } catch (failure) { onNotify(message(failure), 'error') } finally { lock.current = false; setBusy(false) } }
  const today = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date())
  return <form className="workflow-card" onSubmit={event => void submit(event)}><h3>发起部门盘点（最多500项）</h3><label>盘点标题<input required maxLength={160} value={draft.title} disabled={busy} onChange={event => setDraft({ ...draft, title: event.target.value })} /></label><div className="workflow-grid"><label>部门（与台账名称一致，留空盘点全部）<input maxLength={120} value={draft.department} disabled={busy} onChange={event => setDraft({ ...draft, department: event.target.value })} /></label><label>截止日期<input required type="date" min={today} value={draft.dueDate} disabled={busy} onChange={event => setDraft({ ...draft, dueDate: event.target.value })} /></label></div><p>发起时读取设备快照，不占用、不改写任何现有设备。</p><div className="workflow-actions"><button type="button" className="button ghost" disabled={busy} onClick={onCancel}>取消</button><button className="button primary" disabled={busy}>{busy ? '保存中…' : '发起盘点'}</button></div></form>
}
