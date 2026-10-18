import { useEffect, useRef, useState } from 'react'
import { ApiError, api } from '../api'
import type { AuthUser, Consumable, ConsumableMovementPage, ConsumablePageData } from '../types'
import { requestKey } from './HandoverPage'
import { canManageWorkflows } from './OperationPage'
import { ConfirmDialog } from './ConfirmDialog'
import './WorkflowPages.css'

const empty: ConsumablePageData = { items: [], page: 0, totalElements: 0, totalPages: 0, canManage: false }
const emptyMovements: ConsumableMovementPage = { items: [], page: 0, totalElements: 0, totalPages: 0, canManage: false }
const message = (error: unknown) => error instanceof Error ? error.message : '耗材操作失败'
interface Props { user: AuthUser; onNotify: (text: string, kind?: 'ok' | 'error') => void }

export function ConsumablePage({ user, onNotify }: Props) {
  const [data, setData] = useState(empty)
  const [movements, setMovements] = useState(emptyMovements)
  const [tab, setTab] = useState<'stock' | 'movements'>('stock')
  const [page, setPage] = useState(0)
  const [query, setQuery] = useState('')
  const [draftQuery, setDraftQuery] = useState('')
  const [lowStock, setLowStock] = useState(false)
  const [mine, setMine] = useState(true)
  const [revision, setRevision] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [selected, setSelected] = useState<Consumable | null>(null)
  const [editor, setEditor] = useState<'new' | 'edit' | null>(null)
  const [busy, setBusy] = useState(false)
  const [opening, setOpening] = useState(false)
  const [move, setMove] = useState(() => ({ requestKey: requestKey(), kind: 'IN' as 'IN' | 'OUT', quantity: '1', recipientUsername: '', note: '' }))
  const [moveDialog, setMoveDialog] = useState(false)
  const selection = useRef(0)
  const lock = useRef(false)
  const manager = canManageWorkflows(user) && (tab === 'stock' ? data.canManage : movements.canManage)
  useEffect(() => {
    let active = true; setLoading(true); setError('')
    const load = tab === 'stock' ? api.listConsumables(page, query, lowStock).then(value => { if (active) setData(value) })
      : api.listConsumableMovements(page, null, mine).then(value => { if (active) {
        if ((mine || !value.canManage) && value.items.some(item => item.kind !== 'OUT' || item.recipientId !== user.id)) throw new Error('领用流水范围不一致，请刷新登录')
        setMovements(value)
      } })
    load.catch(failure => { if (active) { selection.current++; setData(empty); setMovements(emptyMovements); setSelected(null); setEditor(null); setOpening(false); setError(message(failure)) } }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [tab, page, query, lowStock, mine, revision, user.id])
  useEffect(() => () => { selection.current++ }, [])
  const resetMove = () => { setMove({ requestKey: requestKey(), kind: 'IN', quantity: '1', recipientUsername: '', note: '' }); setMoveDialog(false) }
  const open = async (id: number) => {
    const token = ++selection.current; setOpening(true); setSelected(null); resetMove()
    try { const value = await api.getConsumable(id); if (token === selection.current) setSelected(value) } catch (failure) { if (token === selection.current) { setSelected(null); onNotify(message(failure), 'error') } } finally { if (token === selection.current) setOpening(false) }
  }
  const moveStock = async () => {
    if (!selected || lock.current) return
    lock.current = true; setBusy(true)
    try { const result = await api.moveConsumable(selected.id, { ...move, version: selected.version, quantity: Number(move.quantity) }); setSelected(result.item); resetMove(); setRevision(value => value + 1); onNotify('库存及不可删除的流水已保存') }
    catch (failure) { setMoveDialog(false); onNotify(message(failure), 'error'); if (failure instanceof ApiError && [403, 404].includes(failure.status)) { setSelected(null); setRevision(value => value + 1) } else if (failure instanceof ApiError && failure.status === 409) await open(selected.id) }
    finally { lock.current = false; setBusy(false) }
  }
  const validMove = Number.isSafeInteger(Number(move.quantity)) && Number(move.quantity) > 0 && Number(move.quantity) <= 1000000000 && move.note.trim() && (move.kind === 'IN' || move.recipientUsername.trim()) && (move.kind === 'IN' || Number(move.quantity) <= (selected?.quantity ?? 0))
  return <section className="workflow-page">
    <header className="workflow-head"><div><h2>耗材管理</h2><p>按数量入库、发放、保留流水；库存达到阈值时提示。</p></div><div className="workflow-actions"><button className="button ghost" disabled={busy || opening || !!editor} onClick={() => { setRevision(value => value + 1); if (selected) void open(selected.id) }}>刷新</button>{manager && !selected && !editor && tab === 'stock' && <button className="button primary" disabled={loading || !!error} onClick={() => setEditor('new')}>新增耗材</button>}</div></header>
    <p className="workflow-notice">员工可以查看库存及本人领用流水；只有授权管理员能入库和发放。耗材独立记账，不改电脑等固定资产；不允许负库存，也没有删除流水入口。</p>
    {error && <p className="workflow-error" role="alert">{error}，请刷新重试。</p>}
    {editor && manager ? <ConsumableEditor key={editor + (selected?.id ?? '')} item={editor === 'edit' ? selected : null} onCancel={() => setEditor(null)} onNotify={onNotify} onSaved={item => { setSelected(item); setEditor(null); resetMove(); setRevision(value => value + 1); onNotify('耗材配置已保存，库存未直接改写') }} /> : selected ? <>
      <div className="workflow-head"><button className="button ghost" disabled={busy || opening} onClick={() => { selection.current++; setSelected(null); resetMove() }}>返回库存</button><h3>{selected.name}</h3>{manager && <button className="button ghost" disabled={busy || opening} onClick={() => setEditor('edit')}>修改名称／预警配置</button>}</div>
      <div className="workflow-meta"><span>库存：{selected.quantity} {selected.unit}</span><span>预警阈值：{selected.lowStockThreshold} {selected.unit}</span><span>版本：{selected.version}</span></div>{selected.lowStock && <p className="workflow-warning">库存不足预警：当前数量已达到或低于阈值，请安排补货。</p>}<p className="workflow-pre">{selected.notes}</p>
      {manager && <form className="workflow-card" onSubmit={event => { event.preventDefault(); if (validMove) setMoveDialog(true) }}><h3>库存变动</h3><div className="workflow-grid"><label>操作<select disabled={busy || opening} value={move.kind} onChange={event => setMove({ ...move, kind: event.target.value as 'IN' | 'OUT', recipientUsername: '' })}><option value="IN">入库</option><option value="OUT">发放／领用出库</option></select></label><label>数量<input required type="number" min="1" max="1000000000" step="1" disabled={busy || opening} value={move.quantity} onChange={event => setMove({ ...move, quantity: event.target.value })} /></label></div>{move.kind === 'OUT' && <label>领用人系统账号（非姓名）<input required maxLength={60} disabled={busy || opening} value={move.recipientUsername} onChange={event => setMove({ ...move, recipientUsername: event.target.value })} /></label>}<label>入库／发放说明<textarea required maxLength={1000} disabled={busy || opening} value={move.note} onChange={event => setMove({ ...move, note: event.target.value })} /></label><button className="button primary" disabled={busy || opening || !validMove}>核对后保存{move.kind === 'IN' ? '入库' : '出库'}</button></form>}
      {moveDialog && <ConfirmDialog title={`确认${move.kind === 'IN' ? '入库' : '发放'}`} description={`${selected.name}：${move.kind === 'IN' ? '增加' : '减少'} ${move.quantity} ${selected.unit}${move.kind === 'OUT' ? `，领用账号 ${move.recipientUsername}` : ''}。确认实物和数量无误？库存与流水将同时保存。`} confirmLabel="确认保存库存变动" busy={busy} onCancel={() => !busy && setMoveDialog(false)} onConfirm={() => void moveStock()} />}
    </> : !editor && <>
      <div className="workflow-actions"><button className={`button ${tab === 'stock' ? 'primary' : 'ghost'}`} disabled={loading || opening} onClick={() => { setTab('stock'); setPage(0) }}>库存目录</button><button className={`button ${tab === 'movements' ? 'primary' : 'ghost'}`} disabled={loading || opening} onClick={() => { setTab('movements'); setPage(0) }}>领用／入库流水</button></div>
      {tab === 'stock' ? <><form className="workflow-actions" onSubmit={event => { event.preventDefault(); setPage(0); setQuery(draftQuery) }}><label>耗材名称<input value={draftQuery} maxLength={120} disabled={loading || opening} onChange={event => setDraftQuery(event.target.value)} /></label><button className="button ghost" disabled={loading || opening}>搜索</button><label className="workflow-check"><input type="checkbox" checked={lowStock} disabled={loading || opening} onChange={event => { setLowStock(event.target.checked); setPage(0) }} />仅看库存预警</label></form>{loading || opening ? <p className="workflow-empty">正在读取库存…</p> : <div className="workflow-table-wrap"><table className="workflow-table"><thead><tr><th>耗材</th><th>库存</th><th>预警阈值</th><th>状态</th><th /></tr></thead><tbody>{data.items.map(item => <tr key={item.id}><td>{item.name}</td><td>{item.quantity} {item.unit}</td><td>{item.lowStockThreshold} {item.unit}</td><td>{item.lowStock ? '库存预警' : '正常'}</td><td><button className="button ghost" onClick={() => void open(item.id)}>查看{manager ? '／出入库' : ''}</button></td></tr>)}</tbody></table>{!data.items.length && <p className="workflow-empty">暂无符合条件的耗材</p>}</div>}</> : <>
        {canManageWorkflows(user) && <label className="workflow-check"><input type="checkbox" checked={!mine} disabled={loading || opening} onChange={event => { setMine(!event.target.checked); setPage(0) }} />全部出入库流水（管理员）</label>}
        {loading ? <p className="workflow-empty">正在读取流水…</p> : <div className="workflow-table-wrap"><table className="workflow-table"><thead><tr><th>时间</th><th>耗材／操作</th><th>数量／余量</th><th>领用人</th><th>经办人／说明</th></tr></thead><tbody>{movements.items.map(item => <tr key={item.id}><td>{new Date(item.createdAt).toLocaleString('zh-CN')}</td><td>{item.consumableName} · {item.kind === 'IN' ? '入库' : '领用出库'}</td><td>{item.quantity} {item.unit} / {item.balanceAfter}</td><td>{item.recipientName || '—'}</td><td>{item.actorName} · {item.note}</td></tr>)}</tbody></table>{!movements.items.length && <p className="workflow-empty">暂无相关流水</p>}</div>}
      </>}
      <footer className="workflow-pagination"><span>共 {(tab === 'stock' ? data : movements).totalElements} 条</span><div className="workflow-actions"><button className="button ghost" disabled={loading || opening || page === 0} onClick={() => setPage(value => value - 1)}>上一页</button><span>{(tab === 'stock' ? data : movements).totalPages ? page + 1 : 0} / {(tab === 'stock' ? data : movements).totalPages}</span><button className="button ghost" disabled={loading || opening || page + 1 >= (tab === 'stock' ? data : movements).totalPages} onClick={() => setPage(value => value + 1)}>下一页</button></div></footer>
    </>}
  </section>
}

function ConsumableEditor({ item, onCancel, onSaved, onNotify }: { item: Consumable | null; onCancel: () => void; onSaved: (item: Consumable) => void; onNotify: Props['onNotify'] }) {
  const [draft, setDraft] = useState(() => ({ requestKey: requestKey(), name: item?.name || '', unit: item?.unit || '个', lowStockThreshold: item?.lowStockThreshold ?? 0, notes: item?.notes || '' }))
  const [busy, setBusy] = useState(false)
  const lock = useRef(false)
  const submit = async (event: React.FormEvent) => { event.preventDefault(); if (lock.current) return; lock.current = true; setBusy(true); try { onSaved(item ? await api.updateConsumable(item.id, { ...draft, version: item.version }) : await api.createConsumable(draft)) } catch (failure) { onNotify(message(failure), 'error') } finally { lock.current = false; setBusy(false) } }
  return <form className="workflow-card" onSubmit={event => void submit(event)}><h3>{item ? '修改耗材配置（库存不变）' : '新增耗材（初始库存0）'}</h3><div className="workflow-grid"><label>名称<input required maxLength={120} value={draft.name} disabled={busy} onChange={event => setDraft({ ...draft, name: event.target.value })} /></label><label>单位<input required maxLength={20} value={draft.unit} disabled={busy} onChange={event => setDraft({ ...draft, unit: event.target.value })} /></label><label>库存预警阈值<input required type="number" min="0" max="1000000000" step="1" value={draft.lowStockThreshold} disabled={busy} onChange={event => setDraft({ ...draft, lowStockThreshold: Number(event.target.value) })} /></label></div><label>备注<textarea maxLength={1000} value={draft.notes} disabled={busy} onChange={event => setDraft({ ...draft, notes: event.target.value })} /></label><div className="workflow-actions"><button type="button" className="button ghost" disabled={busy} onClick={onCancel}>取消</button><button className="button primary" disabled={busy || !Number.isSafeInteger(draft.lowStockThreshold) || draft.lowStockThreshold < 0}>{busy ? '保存中…' : '保存配置'}</button></div></form>
}
