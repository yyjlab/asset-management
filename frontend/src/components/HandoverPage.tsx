import { useEffect, useRef, useState } from 'react'
import { ApiError, api } from '../api'
import type { Asset, HandoverCreate, HandoverDetail, HandoverEmployee, HandoverKind, HandoverPageData, HandoverState } from '../types'
import { CheckIcon, ChevronIcon, ClipboardIcon, PlusIcon, RefreshIcon } from './Icons'
import { ConfirmDialog } from './ConfirmDialog'
import './HandoverPage.css'
import { HandoverKitPicker } from './EquipmentKitPage'

const kinds: Record<HandoverKind, string> = { ONBOARDING: '入职配发', OFFBOARDING: '离职交接' }
const states: Record<HandoverState, string> = { PENDING: '待交接', IN_PROGRESS: '交接中', COMPLETED: '已完结', CANCELLED: '已取消' }
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
const emptyPage: HandoverPageData = { items: [], page: 0, totalElements: 0, totalPages: 0, canManage: false }
const safeMessage = (error: unknown) => error instanceof Error ? error.message : '交接操作失败'

interface Props { currentUserId: number; assets: Asset[]; selfService?: boolean; initialId?: number; canUseKits?: boolean; onOpenOa?: () => void; onNotify: (text: string, kind?: 'ok' | 'error') => void }

export function HandoverPage({ currentUserId, assets, selfService = false, initialId, canUseKits = false, onOpenOa, onNotify }: Props) {
  const [data, setData] = useState(emptyPage)
  const [page, setPage] = useState(0)
  const [kind, setKind] = useState('')
  const [state, setState] = useState('')
  const [revision, setRevision] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [employees, setEmployees] = useState<HandoverEmployee[]>([])
  const [selected, setSelected] = useState<HandoverDetail | null>(null)
  const [opening, setOpening] = useState(false)
  const [creating, setCreating] = useState(false)
  const [busy, setBusy] = useState(false)
  const [offlineIndex, setOfflineIndex] = useState<number | null>(null)
  const [offlineNote, setOfflineNote] = useState('')
  const [cancelNote, setCancelNote] = useState('')
  const [linkedEmployeeId, setLinkedEmployeeId] = useState('')
  const [confirmComplete, setConfirmComplete] = useState(false)
  const selectionRevision = useRef(0)

  useEffect(() => {
    let active = true
    setLoading(true); setError('')
    api.listHandovers(page, kind, state, selfService).then(result => { if (active) {
      if (selfService && (result.canManage || result.items.some(task => task.employeeUserId !== currentUserId)))
        throw new Error('本人交接数据范围不一致，请同步更新前后端后重试')
      setData(result)
      if (!result.canManage) setSelected(task => task?.employeeUserId === currentUserId ? task : null)
    } })
      .catch(failure => { if (active) { setData(emptyPage); setError(safeMessage(failure)) } })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [page, kind, state, revision, selfService, currentUserId])

  useEffect(() => {
    if (!data.canManage) { setEmployees([]); setCreating(false); return }
    let active = true
    api.handoverEmployees().then(result => { if (active) setEmployees(result) })
      .catch(failure => { if (active) onNotify(safeMessage(failure), 'error') })
    return () => { active = false }
  }, [data.canManage])
  useEffect(() => () => { selectionRevision.current++ }, [])

  const open = async (id: number) => {
    const next = ++selectionRevision.current
    setOpening(true); setOfflineIndex(null); setOfflineNote(''); setCancelNote(''); setLinkedEmployeeId('')
    try { const task = await api.getHandover(id); if (next === selectionRevision.current) setSelected(task) }
    catch (failure) { if (next === selectionRevision.current) onNotify(safeMessage(failure), 'error') }
    finally { if (next === selectionRevision.current) setOpening(false) }
  }
  useEffect(() => { if (initialId) void open(initialId) }, [initialId])
  const mutate = async (operation: () => Promise<HandoverDetail>, message: string) => {
    selectionRevision.current++; setOpening(false)
    setBusy(true)
    try {
      setSelected(await operation()); setRevision(value => value + 1); setOfflineIndex(null); setOfflineNote('')
      setCancelNote(''); setLinkedEmployeeId(''); setConfirmComplete(false); onNotify(message)
    } catch (failure) {
      setConfirmComplete(false)
      onNotify(safeMessage(failure), 'error')
      if (failure instanceof ApiError && [403, 404].includes(failure.status)) { setSelected(null); setRevision(value => value + 1) }
      else if (failure instanceof ApiError && failure.status === 409 && selected) await open(selected.id)
    } finally { setBusy(false) }
  }
  const closeDetail = () => {
    selectionRevision.current++; setSelected(null); setOpening(false); setOfflineIndex(null)
    setOfflineNote(''); setCancelNote(''); setLinkedEmployeeId(''); setConfirmComplete(false)
  }
  const refresh = () => {
    if (selected) void open(selected.id)
    if (data.canManage) void api.handoverEmployees().then(setEmployees).catch(failure => onNotify(safeMessage(failure), 'error'))
    setRevision(value => value + 1)
  }
  const active = selected && ['PENDING', 'IN_PROGRESS'].includes(selected.state)

  return <section className="handover-page">
    {onOpenOa && <button className="button ghost" onClick={onOpenOa}>钉钉 OA 交接（自动领用／归还）</button>}
    <header className="handover-head"><div><h2>{selfService ? '我的交接' : '入离职交接'}</h2><p>{data.canManage ? '管理员验收与员工确认分开记录，旧资产台账保持不变。' : '只展示明确关联本账号的交接事项，不按同名匹配。'}</p></div><div className="handover-actions">
      <button className="button ghost" disabled={busy || opening || creating} onClick={refresh}><RefreshIcon />刷新</button>
      {!selected && !creating && data.canManage && <button className="button primary" onClick={() => setCreating(true)}><PlusIcon />新建交接单</button>}
    </div></header>
    <div className="handover-notice"><ClipboardIcon /><span>独立交接记录：创建、验收、完结、取消均不会自动更改领用人、资产状态、价格或图片。钉钉审批自动同步尚未启用。</span></div>

    {creating && data.canManage ? <HandoverEditor assets={assets} employees={employees} canUseKits={canUseKits} onCancel={() => setCreating(false)} onCreated={task => { setCreating(false); setSelected(task); setRevision(value => value + 1); onNotify('交接单已创建，原资产台账未修改') }} onNotify={onNotify} />
      : selected ? <>
        <div className="handover-detail-head"><button className="button ghost" disabled={busy} onClick={closeDetail}>返回列表</button><h3>{selected.employeeName} · {kinds[selected.kind]}</h3><span className={`handover-status ${selected.state.toLowerCase()}`}>{states[selected.state]}</span></div>
        <div className="handover-meta"><span>交接单 #{selected.id}</span><span>{selected.department || '未填写部门'}</span><span>{selected.kind === 'ONBOARDING' ? '计划入职' : '最后工作日'}：{selected.plannedDate || '未填写'}</span><span>登记人：{selected.createdByName}</span><span>审批编号：{selected.approvalReference || '未填写（手工创建）'}</span></div>
        {selected.notes && <p className="handover-notes">{selected.notes}</p>}
        {!selected.employeeUserId && <div className="handover-link"><p>此单尚未关联账号，员工暂时看不到；不会按姓名自动关联。</p>{data.canManage && selected.state !== 'CANCELLED' && <div className="handover-actions"><select aria-label="明确关联员工账号" value={linkedEmployeeId} disabled={busy} onChange={event => setLinkedEmployeeId(event.target.value)}><option value="">选择并核对员工账号</option>{employees.map(employee => <option key={employee.id} value={employee.id}>{employee.displayName}（{employee.username}）{!employee.enabled ? ' · 已停用' : ''}</option>)}</select><button className="button ghost" disabled={busy || !linkedEmployeeId} onClick={() => void mutate(() => api.linkHandoverEmployee(selected.id, selected.version, Number(linkedEmployeeId)), '已明确关联账号，原姓名快照保留')}>确认关联</button></div>}</div>}
        <div className="handover-items">{selected.items.map(item => <article className="handover-item" key={item.index}>
          <div className="handover-item-title"><strong>{item.name}</strong><span>{item.assetTag || '手工核对项'}</span></div>
          <div className="handover-item-meta">{item.model && <span>型号：{item.model}</span>}{item.serialNumber && <span>SN：{item.serialNumber}</span>}{item.accessories && <span>随附配件：{item.accessories}</span>}</div>
          <div className="handover-item-progress"><span>{item.checked ? `已验收／配发 · ${item.checkedBy}` : '等待管理员验收／配发'}</span><span>{item.confirmedAt ? `${item.confirmationMethod === 'ONLINE' ? '员工线上确认' : '管理员登记线下确认'} · ${item.confirmedBy} · ${time(item.confirmedAt)}` : '等待员工确认或线下凭证'}</span></div>
          {item.confirmationNote && <p className="handover-notes">凭证／说明：{item.confirmationNote}</p>}
          {active && <div className="handover-actions">
            {data.canManage && !item.confirmedAt && <button className="button ghost" disabled={busy} onClick={() => void mutate(() => api.checkHandoverItem(selected.id, item.index, selected.version, !item.checked), item.checked ? '已撤销此项验收记录' : '此项已记录验收／配发，台账未修改')}>{item.checked ? '撤销验收' : selected.kind === 'ONBOARDING' ? '确认已配发' : '确认已验收'}</button>}
            {item.checked && !item.confirmedAt && selected.employeeUserId === currentUserId && <button className="button primary" disabled={busy} onClick={() => void mutate(() => api.confirmHandoverItem(selected.id, item.index, selected.version, false), '已确认本人交接事项')}><CheckIcon />{selected.kind === 'ONBOARDING' ? '我已收到' : '我已交还'}</button>}
            {data.canManage && item.checked && !item.confirmedAt && <button className="button ghost" disabled={busy} onClick={() => { setOfflineIndex(item.index); setOfflineNote('') }}>登记线下确认</button>}
          </div>}
          {offlineIndex === item.index && active && data.canManage && item.checked && !item.confirmedAt && <div className="handover-inline-form"><label>线下签收／验收凭证说明<textarea maxLength={1000} value={offlineNote} disabled={busy} onChange={event => setOfflineNote(event.target.value)} placeholder="例如：已核对纸质交接单，由谁在何时确认。不能代填员工线上确认。" /></label><div className="handover-actions"><button className="button ghost" disabled={busy} onClick={() => setOfflineIndex(null)}>取消</button><button className="button primary" disabled={busy || !offlineNote.trim()} onClick={() => void mutate(() => api.confirmHandoverItem(selected.id, item.index, selected.version, true, offlineNote), '已记录线下确认及凭证')}>保存线下凭证</button></div></div>}
        </article>)}</div>
        {active && data.canManage && <section className="handover-finish"><button className="button primary" disabled={busy || selected.items.some(item => !item.checked || !item.confirmedAt)} onClick={() => setConfirmComplete(true)}>完结交接单</button><p>需全部验收和确认后才能完结；完结仅代表交接记录完成，资产台账请在原资产页面核对处理。</p><details><summary>取消交接单</summary><label>取消原因<textarea maxLength={1000} value={cancelNote} disabled={busy} onChange={event => setCancelNote(event.target.value)} /></label><button className="button ghost" disabled={busy || !cancelNote.trim()} onClick={() => void mutate(() => api.cancelHandover(selected.id, selected.version, cancelNote), '交接单已取消，历史记录与资产台账均保留')}>确认取消并保留记录</button></details></section>}
        <section className="handover-history"><h3>处理记录</h3>{selected.events.map((event, index) => <div key={index}><time>{time(event.occurredAt)}</time><strong>{event.actorName}</strong><span>{event.description}</span></div>)}</section>
        {confirmComplete && <ConfirmDialog title="完结交接单" description="确认每一项均已实物验收和确认？此操作只完结交接记录，不会清空领用人，也不会自动修改资产台账。" busy={busy} onCancel={() => !busy && setConfirmComplete(false)} onConfirm={() => void mutate(() => api.completeHandover(selected.id, selected.version), '交接记录已完结，资产台账未自动修改')} />}
      </> : <>
        <div className="handover-filters"><label>交接类型<select value={kind} onChange={event => { setKind(event.target.value); setPage(0) }}><option value="">全部类型</option>{Object.entries(kinds).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label><label>处理状态<select value={state} onChange={event => { setState(event.target.value); setPage(0) }}><option value="">全部状态</option>{Object.entries(states).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label></div>
        {error ? <p role="alert" className="handover-empty">{error}，请刷新重试。</p> : loading || opening ? <div className="handover-empty"><span className="spinner" />正在读取交接记录…</div>
          : data.items.length ? <div className="handover-table-wrap"><table className="handover-table"><thead><tr><th>员工</th><th>类型</th><th>部门</th><th>计划日期</th><th>验收／确认</th><th>状态</th><th>查看</th></tr></thead><tbody>{data.items.map(task => <tr key={task.id}><td><strong>{task.employeeName}</strong>{!task.employeeUserId && <small>尚未关联账号</small>}</td><td>{kinds[task.kind]}</td><td>{task.department || '—'}</td><td>{task.plannedDate || '—'}</td><td>{task.checkedItems} / {task.totalItems} 已验收 · {task.confirmedItems} 已确认</td><td><span className={`handover-status ${task.state.toLowerCase()}`}>{states[task.state]}</span></td><td><button className="button ghost" onClick={() => void open(task.id)}>交接清单<ChevronIcon /></button></td></tr>)}</tbody></table></div>
            : <div className="handover-empty">{data.canManage ? '暂无交接单，可先为入职或离职员工建立清单。' : '暂无明确关联本账号的交接单；如已有纸质记录，请联系管理员核对。'}</div>}
        <footer className="handover-pagination"><span>共 {data.totalElements} 张交接单</span><div className="handover-actions"><button className="button ghost" disabled={loading || opening || page === 0} onClick={() => setPage(value => value - 1)}>上一页</button><span>{data.totalPages ? page + 1 : 0} / {data.totalPages}</span><button className="button ghost" disabled={loading || opening || page + 1 >= data.totalPages} onClick={() => setPage(value => value + 1)}>下一页</button></div></footer>
      </>}
  </section>
}

export function requestKey() {
  // getRandomValues also works on the existing intranet HTTP origin; randomUUID requires a secure context.
  const bytes = crypto.getRandomValues(new Uint8Array(16)); bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128
  const hex = Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

function HandoverEditor({ assets, employees, canUseKits, onCancel, onCreated, onNotify }: { assets: Asset[]; employees: HandoverEmployee[]; canUseKits: boolean; onCancel: () => void; onCreated: (task: HandoverDetail) => void; onNotify: Props['onNotify'] }) {
  const [draft, setDraft] = useState<HandoverCreate>(() => ({ requestKey: requestKey(), kind: 'ONBOARDING', employeeUserId: null, employeeName: '', department: '', plannedDate: null, approvalReference: '', notes: '', assetIds: [], includeAssignedAssets: false, allowLegacyAssets: false, manualItems: [] }))
  const [manualText, setManualText] = useState('')
  const [assetSearch, setAssetSearch] = useState('')
  const [saving, setSaving] = useState(false)
  const automatic = draft.includeAssignedAssets ? assets.filter(asset => asset.assignedUserId === draft.employeeUserId) : []
  const choices = assets.filter(asset => draft.kind === 'ONBOARDING' ? !asset.checkedOut && !asset.assignedUserId && !asset.assignedTo && !/维修|维护|报废/.test(asset.status.name) : !asset.assignedUserId || asset.assignedUserId === draft.employeeUserId)
    .filter(asset => [asset.assetTag, asset.name, asset.model.name, asset.manufacturerSerialNumber].some(value => value?.toLowerCase().includes(assetSearch.toLowerCase())))
  const submit = async (event: React.FormEvent) => {
    event.preventDefault(); setSaving(true)
    try { onCreated(await api.createHandover({ ...draft, manualItems: manualText.split(/\r?\n/).map(value => value.trim()).filter(Boolean) })) }
    catch (failure) { onNotify(safeMessage(failure), 'error') }
    finally { setSaving(false) }
  }
  return <form className="handover-editor" onSubmit={event => void submit(event)}>
    <h3>新建交接单</h3>
    {canUseKits && draft.kind === 'ONBOARDING' && <HandoverKitPicker disabled={saving} onApply={kit => {
      const lines = manualText.split(/\r?\n/).map(value => value.trim()).filter(Boolean)
      const note = `使用岗位模板：${kit.name}（版本${kit.version}）；仅生成准备项，实物另行核对。`
      if (lines.length + kit.manualItems.length > 50) { onNotify('追加后超过50项，请分单或减少现有准备项', 'error'); return }
      if (draft.notes.length + note.length + 1 > 2000) { onNotify('备注空间不足，请整理备注后再追加模板', 'error'); return }
      setManualText([...lines, ...kit.manualItems].join('\n')); setDraft({ ...draft, notes: [draft.notes, note].filter(Boolean).join('\n') })
    }} />}
    <div className="handover-form-grid">
      <label>交接类型<select disabled={saving} value={draft.kind} onChange={event => { const kind = event.target.value as HandoverKind; setDraft({ ...draft, kind, assetIds: [], includeAssignedAssets: kind === 'OFFBOARDING' && !!draft.employeeUserId, allowLegacyAssets: false }) }}>{Object.entries(kinds).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      <label>明确关联员工账号<select disabled={saving} value={draft.employeeUserId ?? ''} onChange={event => { const employee = employees.find(value => value.id === Number(event.target.value)); setDraft({ ...draft, employeeUserId: employee?.id ?? null, employeeName: employee?.displayName ?? '', assetIds: [], includeAssignedAssets: draft.kind === 'OFFBOARDING' && !!employee, allowLegacyAssets: false }) }}><option value="">暂无账号／先手工登记</option>{employees.filter(employee => draft.kind === 'OFFBOARDING' || employee.enabled).map(employee => <option key={employee.id} value={employee.id}>{employee.displayName}（{employee.username}）{!employee.enabled ? ' · 已停用' : ''}</option>)}</select></label>
      <label>员工姓名<input required maxLength={80} disabled={saving || !!draft.employeeUserId} value={draft.employeeName} onChange={event => setDraft({ ...draft, employeeName: event.target.value })} /></label>
      <label>部门<input maxLength={120} disabled={saving} value={draft.department} onChange={event => setDraft({ ...draft, department: event.target.value })} /></label>
      <label>{draft.kind === 'ONBOARDING' ? '计划入职日期' : '最后工作日'}<input type="date" disabled={saving} value={draft.plannedDate || ''} onChange={event => setDraft({ ...draft, plannedDate: event.target.value || null })} /></label>
      <label>钉钉审批编号（可选，仅作记录）<input maxLength={160} disabled={saving} value={draft.approvalReference} onChange={event => setDraft({ ...draft, approvalReference: event.target.value })} /></label>
    </div>
    {draft.kind === 'OFFBOARDING' && <div className="handover-link">{draft.employeeUserId && <label className="handover-checkbox"><input type="checkbox" disabled={saving} checked={draft.includeAssignedAssets} onChange={event => setDraft({ ...draft, includeAssignedAssets: event.target.checked })} />自动列出明确关联该账号的资产（当前预览 {automatic.length} 项，保存时重新核对）</label>}<p>姓名文字、电脑绑定的显示器和未建账配件不会自动推断归属，请人工核对。没有匹配结果不代表员工没有资产。</p><label className="handover-checkbox"><input type="checkbox" disabled={saving} checked={draft.allowLegacyAssets} onChange={event => setDraft({ ...draft, allowLegacyAssets: event.target.checked })} />我已人工核对，允许把下面勾选的未关联账号旧资产加入清单</label></div>}
    <section className="handover-asset-picker"><h4>{draft.kind === 'ONBOARDING' ? '选择待配发设备（只记录计划，不占用或修改设备）' : '核对设备清单'}</h4><input aria-label="筛选待交接设备" placeholder="按资产编号、名称、型号或 SN 筛选" value={assetSearch} disabled={saving} onChange={event => setAssetSearch(event.target.value)} /><div className="handover-asset-choices">{choices.map(asset => {
      const auto = automatic.some(value => value.id === asset.id)
      return <label key={asset.id} className="handover-checkbox"><input type="checkbox" disabled={saving || auto} checked={auto || draft.assetIds.includes(asset.id)} onChange={event => setDraft({ ...draft, assetIds: event.target.checked ? [...draft.assetIds, asset.id] : draft.assetIds.filter(id => id !== asset.id) })} /><span>{asset.assetTag} · {asset.name} · {asset.model.name}{auto ? ' · 已自动列入' : !asset.assignedUserId && draft.kind === 'OFFBOARDING' ? ' · 需人工核对归属' : ''}</span></label>
    })}{!choices.length && <p>没有符合条件的设备，可以先填写下面的手工交接项。</p>}</div></section>
    <label>手工交接项（每行一项，最多50项）<textarea value={manualText} disabled={saving} maxLength={8050} onChange={event => setManualText(event.target.value)} placeholder={'例如：准备办公电脑\n核对电源适配器\n核对未关联账号的历史设备'} /></label>
    <label>交接备注<textarea maxLength={2000} disabled={saving} value={draft.notes} onChange={event => setDraft({ ...draft, notes: event.target.value })} placeholder="只记录设备交接所需信息，不填写离职原因等无关隐私。" /></label>
    <div className="handover-actions"><button type="button" className="button ghost" disabled={saving} onClick={onCancel}>取消</button><button className="button primary" disabled={saving}>{saving ? '保存中…' : '创建交接单（不修改台账）'}</button></div>
  </form>
}
