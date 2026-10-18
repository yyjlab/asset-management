import { useRef, useState } from 'react'
import { api } from '../api'
import type { AuthUser } from '../types'
import type { Kit, KitWrite } from '../types/management'
import { canManageWorkflows } from './OperationPage'
import { requestKey } from './HandoverPage'
import { useRemote, RemoteNotice, PageButtons, failMessage, managementDenied } from './ManagementShared'

interface Props { user: AuthUser; onNotify: (text: string, kind?: 'ok' | 'error') => void }
export function HandoverKitPicker({ disabled, onApply }: { disabled: boolean; onApply: (kit: Kit) => void }) {
  const [page, setPage] = useState(0); const [id, setId] = useState(0); const remote = useRemote(() => api.listKits(page), [page])
  const selected = remote.data?.items.find(item => item.id === id && item.active)
  return <section className="handover-link"><h4>按岗位模板生成准备清单</h4><p>只追加手工准备项，不自动选择实物、不扣库存；已有内容保留。</p><select disabled={disabled || remote.loading} value={id || ''} onChange={event => setId(Number(event.target.value))}><option value="">选择岗位模板</option>{remote.data?.items.filter(item => item.active).map(item => <option key={item.id} value={item.id}>{item.name}（{item.manualItems.length}件）</option>)}</select><button type="button" className="button ghost" disabled={disabled || !selected} onClick={() => selected && onApply(selected)}>追加到准备清单</button><RemoteNotice {...remote} /><PageButtons data={remote.data} page={page} onPage={value => { setPage(value); setId(0) }} disabled={disabled || remote.loading} /></section>
}
export function EquipmentKitPage({ user, onNotify }: Props) {
  const [page, setPage] = useState(0)
  const [editor, setEditor] = useState<Kit | 'new' | null>(null)
  const manager = canManageWorkflows(user)
  const remote = useRemote(() => api.listKits(page), [page], manager)
  if (!manager) return managementDenied
  return <section className="workflow-page"><header className="workflow-head"><div><h2>岗位配发模板</h2><p>模板只生成准备清单，不分配实物、不扣库存；更改模板不会更改已建交接单。</p></div><div className="workflow-actions"><button className="button ghost" disabled={remote.loading || !!editor} onClick={remote.refresh}>刷新</button>{!editor && <button className="button primary" onClick={() => setEditor('new')}>新增岗位模板</button>}</div></header>
    {editor ? <KitEditor key={editor === 'new' ? 'new' : `${editor.id}:${editor.version}`} original={editor === 'new' ? null : editor} onCancel={() => setEditor(null)} onSaved={() => { setEditor(null); remote.refresh(); onNotify('模板已保存；已有交接记录不变') }} onNotify={onNotify} /> : <><RemoteNotice {...remote} />{remote.data?.items.map(kit => <article className="workflow-card" key={kit.id}><div className="workflow-head"><h3>{kit.name} · {kit.active ? '启用' : '已停用'}</h3><button className="button ghost" onClick={() => setEditor(kit)}>修改／停用</button></div><p>{kit.items.map(item => `${item.name} ×${item.quantity}`).join('；')}</p><p className="workflow-pre">{kit.notes}</p><small>在“入离职交接 → 新建交接单 → 岗位模板”中使用。</small></article>)}{remote.data && !remote.data.items.length && <p>暂无模板，可先创建开发、行政等岗位的准备清单。</p>}<PageButtons data={remote.data} page={page} onPage={setPage} disabled={remote.loading} /></>}
  </section>
}
function KitEditor({ original, onCancel, onSaved, onNotify }: { original: Kit | null; onCancel: () => void; onSaved: () => void; onNotify: Props['onNotify'] }) {
  const [draft, setDraft] = useState<KitWrite>(() => ({ requestKey: requestKey(), version: original?.version, name: original?.name || '', notes: original?.notes || '', active: original?.active ?? true, items: original?.items.map(item => ({ ...item })) || [{ name: '笔记本电脑', quantity: 1 }] }))
  const [busy, setBusy] = useState(false); const lock = useRef(false)
  const valid = draft.name.trim() && draft.items.length > 0 && draft.items.every(i => i.name.trim() && Number.isInteger(i.quantity) && i.quantity > 0 && i.quantity <= 50) && draft.items.reduce((sum, item) => sum + item.quantity, 0) <= 50
  const submit = async (event: React.FormEvent) => { event.preventDefault(); if (lock.current || !valid) return; lock.current = true; setBusy(true); try { await api.saveKit(original?.id || null, draft); onSaved() } catch (failure) { onNotify(failMessage(failure), 'error') } finally { lock.current = false; setBusy(false) } }
  return <form className="workflow-card" onSubmit={event => void submit(event)}><label>岗位／模板名称<input required maxLength={120} disabled={busy} value={draft.name} onChange={event => setDraft({ ...draft, name: event.target.value })} /></label><label className="workflow-check"><input type="checkbox" checked={draft.active} disabled={busy} onChange={event => setDraft({ ...draft, active: event.target.checked })} />启用此模板</label>{draft.items.map((item, index) => <div className="workflow-grid" key={index}><label>待准备设备／配件<input required maxLength={120} disabled={busy} value={item.name} onChange={event => setDraft({ ...draft, items: draft.items.map((value, position) => position === index ? { ...value, name: event.target.value } : value) })} /></label><div className="workflow-actions"><label>数量<input required type="number" min="1" max="50" step="1" disabled={busy} value={item.quantity} onChange={event => setDraft({ ...draft, items: draft.items.map((value, position) => position === index ? { ...value, quantity: Number(event.target.value) } : value) })} /></label><button type="button" className="button ghost" disabled={busy} onClick={() => setDraft({ ...draft, items: draft.items.filter((_, position) => position !== index) })}>移除此草稿项</button></div></div>)}<button type="button" className="button ghost" disabled={busy || draft.items.length >= 50} onClick={() => setDraft({ ...draft, items: [...draft.items, { name: '', quantity: 1 }] })}>增加准备项</button><label>说明<textarea maxLength={1000} disabled={busy} value={draft.notes} onChange={event => setDraft({ ...draft, notes: event.target.value })} /></label><p>合计最多50件，将逐件生成交接准备项，不自动选择实际资产。</p><div className="workflow-actions"><button type="button" className="button ghost" disabled={busy} onClick={onCancel}>取消</button><button className="button primary" disabled={busy || !valid}>保存模板</button></div></form>
}
