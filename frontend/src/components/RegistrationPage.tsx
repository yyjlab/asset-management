import { useEffect, useRef, useState } from 'react'
import { ApiError, api } from '../api'
import type { Asset, AuthUser, LookupValue, RegistrationDetail, RegistrationPageData, RegistrationState, RegistrationSubmit } from '../types'
import { ConfirmDialog } from './ConfirmDialog'
import { prepareImage } from './AssetForm'
import { requestKey } from './HandoverPage'
import { PlusIcon, RefreshIcon } from './Icons'
import './RegistrationPage.css'

const states: Record<RegistrationState, string> = { SUBMITTED: '待审核', NEEDS_INFO: '待补充资料', APPROVED: '审核通过', REJECTED: '已驳回', WITHDRAWN: '已撤回' }
const empty: RegistrationPageData = { items: [], page: 0, totalElements: 0, totalPages: 0, canReview: false }
const message = (error: unknown) => error instanceof Error ? error.message : '登记操作失败'
interface Props { user: AuthUser; review?: boolean; initialId?: number; assets: Asset[]; lookups: LookupValue[]; onNotify: (text: string, kind?: 'ok' | 'error') => void; onAssetsChanged: () => void }

export function RegistrationPage({ user, review = false, initialId, assets, lookups, onNotify, onAssetsChanged }: Props) {
  const [data, setData] = useState(empty)
  const [page, setPage] = useState(0)
  const [state, setState] = useState(review ? 'SUBMITTED' : '')
  const [revision, setRevision] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [selected, setSelected] = useState<RegistrationDetail | null>(null)
  const [editor, setEditor] = useState<'new' | RegistrationDetail | null>(null)
  const [busy, setBusy] = useState(false)
  const [opening, setOpening] = useState(false)
  const [note, setNote] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [company, setCompany] = useState('')
  const [category, setCategory] = useState('')
  const [location, setLocation] = useState('')
  const [linkedAsset, setLinkedAsset] = useState('')
  const [assetSearch, setAssetSearch] = useState('')
  const [approval, setApproval] = useState<'new' | 'existing' | null>(null)
  const selection = useRef(0)
  useEffect(() => () => { selection.current++ }, [])
  useEffect(() => {
    let active = true
    setLoading(true); setError('')
    api.listRegistrations(page, state, review).then(result => { if (active) {
      if (review && !result.canReview || !review && result.items.some(item => item.ownerId !== user.id)) throw new Error('登记数据范围不一致，请刷新并核对权限')
      setData(result)
    } }).catch(failure => { if (active) { selection.current++; setOpening(false); setData(empty); setSelected(null); setEditor(null); setError(message(failure)) } })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [page, state, review, revision, user.id])
  const resetReview = () => { setNote(''); setConfirmed(false); setCompany(''); setCategory(''); setLocation(''); setLinkedAsset(''); setAssetSearch(''); setApproval(null) }
  const open = async (id: number) => {
    const current = ++selection.current
    setOpening(true); setSelected(null); resetReview()
    try { const result = await api.getRegistration(id); if (current === selection.current) {
      if (!review && result.request.ownerId !== user.id) throw new Error('登记不属于当前账号')
      setSelected(result)
    } } catch (failure) { if (current === selection.current) { setSelected(null); onNotify(message(failure), 'error') } }
    finally { if (current === selection.current) setOpening(false) }
  }
  useEffect(() => { if (initialId) void open(initialId) }, [initialId])
  const mutate = async (operation: () => Promise<RegistrationDetail>, success: string, assetChanged = false) => {
    selection.current++; setOpening(false); setBusy(true); setApproval(null)
    try { const result = await operation(); setSelected(result); setRevision(value => value + 1); resetReview(); onNotify(success); if (assetChanged) onAssetsChanged() }
    catch (failure) { onNotify(message(failure), 'error'); if (failure instanceof ApiError && [403, 404].includes(failure.status)) { setSelected(null); setRevision(value => value + 1) }
      else if (failure instanceof ApiError && failure.status === 409 && selected) await open(selected.request.id) }
    finally { setBusy(false) }
  }
  const close = () => { selection.current++; setOpening(false); setSelected(null); resetReview() }
  const request = selected?.request
  const openState = request && ['SUBMITTED', 'NEEDS_INFO'].includes(request.state)
  const chosenAsset = assets.find(asset => asset.id === Number(linkedAsset))
  const candidates = request ? assets.filter(asset => !asset.assignedUserId || asset.assignedUserId === request.ownerId)
    .filter(asset => [asset.assetTag, asset.name, asset.model.name, asset.manufacturerSerialNumber].some(value => value?.toLowerCase().includes(assetSearch.trim().toLowerCase()))) : []

  return <section className="registration-page">
    <header className="handover-head"><div><h2>{review ? '设备登记审核' : '我的设备登记'}</h2><p>{review ? '核对实物和重复设备后，明确选择新增入库或记录已有资产关联。' : '先登记，再由管理员审核；提交不会直接改动正式台账。'}</p></div><div className="handover-actions">
      <button className="button ghost" disabled={busy || opening || !!editor} onClick={() => { setRevision(value => value + 1); if (request) void open(request.id) }}><RefreshIcon />刷新</button>
      {!review && !selected && !editor && <button className="button primary" onClick={() => setEditor('new')}><PlusIcon />登记我的设备</button>}
    </div></header>
    {editor ? <RegistrationEditor key={editor === 'new' ? 'new' : `${editor.request.id}:${editor.request.version}`} original={editor === 'new' ? null : editor} onCancel={() => setEditor(null)} onSaved={result => { setEditor(null); setSelected(result); setRevision(value => value + 1); onNotify('登记已提交，等待管理员审核，旧台账未修改') }} onNotify={onNotify} />
      : selected && request ? <>
        <div className="handover-detail-head"><button className="button ghost" disabled={busy || opening} onClick={close}>返回列表</button><h3>{request.ownerName} · {request.name}</h3><span className="handover-status">{states[request.state]}</span></div>
        <div className="registration-card"><dl className="registration-details">
          <div><dt>型号</dt><dd>{request.model}</dd></div><div><dt>SN</dt><dd>{request.serialNumber || '未填写，请核对照片'}</dd></div>
          <div><dt>部门</dt><dd>{selected.department || '未填写'}</dd></div><div><dt>位置</dt><dd>{selected.location || '未填写'}</dd></div>
          <div><dt>CPU / 内存 / 硬盘</dt><dd>{[selected.cpu, selected.memory, selected.storage].filter(Boolean).join(' / ') || '未填写'}</dd></div>
          <div><dt>员工说明</dt><dd>{selected.notes || '无'}</dd></div><div><dt>最近审核意见</dt><dd>{selected.reviewNote || '暂无'}</dd></div>
        </dl><div className="registration-photos">{selected.photos.map((photo, index) => <a href={photo} target="_blank" rel="noreferrer" key={photo}><img src={photo} alt={`设备登记照片${index + 1}`} /></a>)}</div></div>
        {request.approvedAssetTag && <p className="handover-notice">审核结果关联资产：{request.approvedAssetTag}。记录已有资产关联不会自动改变旧领用人；本人资产未展示时请管理员核对台账账号关联。</p>}
        {!review && request.ownerId === user.id && openState && <div className="registration-card">
          {request.state === 'NEEDS_INFO' && <button className="button primary" disabled={busy} onClick={() => setEditor(selected)}>补充资料并重新提交</button>}
          <label>撤回原因<textarea maxLength={1000} disabled={busy} value={note} onChange={event => setNote(event.target.value)} /></label><button className="button ghost" disabled={busy || !note.trim()} onClick={() => void mutate(() => api.withdrawRegistration(request.id, request.version, note), '登记已撤回，历史记录保留')}>撤回本次登记</button>
        </div>}
        {review && data.canReview && openState && <div className="registration-card">
          <label>审核意见<textarea maxLength={1000} disabled={busy} value={note} onChange={event => setNote(event.target.value)} placeholder="要求补充或驳回时必须填写具体原因" /></label>
          <div className="handover-actions"><button className="button ghost" disabled={busy || !note.trim()} onClick={() => void mutate(() => api.reviewRegistration(request.id, request.version, 'NEEDS_INFO', note), '已记录补充资料要求')}>要求补充资料</button><button className="button ghost" disabled={busy || !note.trim()} onClick={() => void mutate(() => api.reviewRegistration(request.id, request.version, 'REJECTED', note), '登记已驳回，原资产未修改')}>驳回登记</button></div>
          {request.state === 'SUBMITTED' && <>
            {selected.serialAlreadyInAssets && <p role="status" className="handover-notice">台账已存在此SN，请核对并关联已有资产，不能重复新增。</p>}
            <label className="registration-confirm"><input type="checkbox" checked={confirmed} disabled={busy} onChange={event => setConfirmed(event.target.checked)} />我已核对实物、SN、领用账号和重复设备。</label>
            <div className="registration-fields">{[['COMPANY', '公司', company, setCompany], ['CATEGORY', '分类', category, setCategory], ['LOCATION', '入库位置', location, setLocation]].map(([type, label, value, setter]) => <label key={String(type)}>{String(label)}<select disabled={busy} value={String(value)} onChange={event => (setter as (value: string) => void)(event.target.value)}><option value="">请选择</option>{lookups.filter(lookup => lookup.type === type).map(lookup => <option key={lookup.id} value={lookup.id}>{lookup.name}</option>)}</select></label>)}</div>
            <button className="button primary" disabled={busy || !confirmed || !company || !category || !location || selected.serialAlreadyInAssets} onClick={() => setApproval('new')}>审核通过并新增资产</button>
            <hr /><label>查找已有资产<input value={assetSearch} disabled={busy} onChange={event => setAssetSearch(event.target.value)} placeholder="按资产编号、型号或SN筛选" /></label>
            <label>关联已有资产（不修改旧台账）<select value={linkedAsset} disabled={busy} onChange={event => setLinkedAsset(event.target.value)}><option value="">请选择并核对资产编号</option>{candidates.map(asset => <option value={asset.id} key={asset.id}>{asset.assetTag} · {asset.name} · {asset.manufacturerSerialNumber || '无SN'}</option>)}</select></label>
            <button className="button ghost" disabled={busy || !confirmed || !chosenAsset} onClick={() => setApproval('existing')}>审核通过并记录关联</button>
          </>}
        </div>}
        <section className="handover-history"><h3>登记与审核记录</h3>{selected.events.map((event, index) => <div key={index}><time>{new Date(event.occurredAt).toLocaleString('zh-CN', { hour12: false })}</time><strong>{event.actorName}</strong><span>{event.description}</span></div>)}</section>
        {approval && <ConfirmDialog title={approval === 'new' ? '确认新增正式资产' : '确认记录已有资产关联'} description={approval === 'new' ? '审核后会新增一项正式资产，并关联登记员工。不会覆盖旧资产；请确认不是重复设备。' : `确认登记设备与资产 ${chosenAsset?.assetTag ?? ''} 为同一实物？只记录关联，不改变旧台账字段。`} busy={busy} onCancel={() => !busy && setApproval(null)} onConfirm={() => {
          if (approval === 'new') void mutate(() => api.approveNewRegistration(request.id, request.version, Number(company), Number(category), Number(location), note, confirmed), '审核通过，已新增资产', true)
          else if (chosenAsset) void mutate(() => api.approveExistingRegistration(request.id, request.version, chosenAsset.id, chosenAsset.version, note, confirmed), '审核通过，已记录关联，旧台账未修改')
        }} />}
      </> : <>
        <label className="registration-filter">处理状态<select value={state} disabled={opening} onChange={event => { setState(event.target.value); setPage(0) }}><option value="">全部状态</option>{Object.entries(states).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
        {error ? <div role="alert" className="handover-empty">{error}，请刷新重试。</div> : loading || opening ? <div className="handover-empty"><span className="spinner" />正在读取登记…</div> : data.items.length ? <div className="handover-table-wrap"><table className="handover-table"><thead><tr><th>员工</th><th>设备</th><th>型号</th><th>SN</th><th>状态</th><th>操作</th></tr></thead><tbody>{data.items.map(item => <tr key={item.id}><td>{item.ownerName}</td><td>{item.name}</td><td>{item.model}</td><td>{item.serialNumber || '待核对照片'}</td><td>{states[item.state]}</td><td><button className="button ghost" onClick={() => void open(item.id)}>查看登记</button></td></tr>)}</tbody></table></div> : <div className="handover-empty">暂无{review ? '符合条件的设备登记' : '本人设备登记，可点击“登记我的设备”提交'}。</div>}
        <footer className="handover-pagination"><span>共 {data.totalElements} 项登记</span><div className="handover-actions"><button className="button ghost" disabled={loading || opening || page === 0} onClick={() => setPage(value => value - 1)}>上一页</button><span>{data.totalPages ? page + 1 : 0} / {data.totalPages}</span><button className="button ghost" disabled={loading || opening || page + 1 >= data.totalPages} onClick={() => setPage(value => value + 1)}>下一页</button></div></footer>
      </>}
  </section>
}

function RegistrationEditor({ original, onCancel, onSaved, onNotify }: { original: RegistrationDetail | null; onCancel: () => void; onSaved: (result: RegistrationDetail) => void; onNotify: Props['onNotify'] }) {
  const [draft, setDraft] = useState<RegistrationSubmit>(() => original ? {
    requestKey: original.requestKey, version: original.request.version, name: original.request.name, model: original.request.model,
    serialNumber: original.request.serialNumber, department: original.department, location: original.location, cpu: original.cpu,
    memory: original.memory, storage: original.storage, notes: original.notes,
  } : { requestKey: requestKey(), name: '笔记本电脑', model: '', serialNumber: '', department: '', location: '', cpu: '', memory: '', storage: '', notes: '' })
  const [photos, setPhotos] = useState<{ preview: string; file: File }[]>([])
  const [busy, setBusy] = useState(false)
  const [preparing, setPreparing] = useState(false)
  const upload = async (files: FileList | null) => {
    if (!files?.length) return
    if (photos.length + files.length > 2) { onNotify('最多上传2张照片', 'error'); return }
    setPreparing(true)
    try {
      const added: { preview: string; file: File }[] = []
      for (const file of Array.from(files)) {
        const preview = await prepareImage(file)
        const [header, encoded] = preview.split(','); const type = header.slice(5, header.indexOf(';'))
        const bytes = Uint8Array.from(atob(encoded), character => character.charCodeAt(0))
        added.push({ preview, file: new File([bytes], 'registration-photo', { type }) })
      }
      setPhotos(current => [...current, ...added])
    } catch (failure) { onNotify(message(failure), 'error') }
    finally { setPreparing(false) }
  }
  const submit = async (event: React.FormEvent) => {
    event.preventDefault(); setBusy(true)
    try { onSaved(original ? await api.supplementRegistration(original.request.id, draft, photos.map(photo => photo.file)) : await api.submitRegistration(draft, photos.map(photo => photo.file))) }
    catch (failure) { onNotify(message(failure), 'error') }
    finally { setBusy(false) }
  }
  return <form className="registration-card" onSubmit={event => void submit(event)}><h3>{original ? '补充设备资料' : '登记我的设备'}</h3><p>不确定SN可先上传设备或铭牌照片，由管理员核对。照片会压缩上传，不进行自动识别；不会直接写入正式台账。</p>
    <div className="registration-fields">{[['name', '设备名称', 160, true], ['model', '设备型号', 120, true], ['serialNumber', 'SN（不确定可留空）', 160, false], ['department', '部门', 120, false], ['location', '使用位置', 120, false], ['cpu', 'CPU', 200, false], ['memory', '内存', 200, false], ['storage', '硬盘', 200, false]].map(([key, label, limit, required]) => <label key={String(key)}>{String(label)}<input required={Boolean(required)} maxLength={Number(limit)} disabled={busy || preparing} value={String(draft[key as keyof RegistrationSubmit] ?? '')} onChange={event => setDraft(current => ({ ...current, [String(key)]: event.target.value }))} /></label>)}</div>
    <label>补充说明<textarea maxLength={2000} disabled={busy || preparing} value={draft.notes} onChange={event => setDraft({ ...draft, notes: event.target.value })} /></label>
    <label>设备／铭牌照片（最多2张）<input type="file" accept="image/jpeg,image/png,image/webp" capture="environment" multiple disabled={busy || preparing || photos.length >= 2} onChange={event => { void upload(event.target.files); event.target.value = '' }} /></label>
    {original?.photos.length && !photos.length ? <p>未选择新照片时保留原照片；选择新照片后，原照片仍作为历史资料保留。</p> : null}
    <div className="registration-photos">{photos.map((photo, index) => <div key={index}><img src={photo.preview} alt={`待上传照片${index + 1}`} /><button type="button" className="button ghost" disabled={busy || preparing} onClick={() => setPhotos(current => current.filter((_, position) => position !== index))}>移除此待上传照片</button></div>)}</div>
    <div className="handover-actions"><button type="button" className="button ghost" disabled={busy || preparing} onClick={onCancel}>取消</button><button type="submit" className="button primary" disabled={busy || preparing || !draft.model.trim() || !draft.name.trim() || !draft.serialNumber.trim() && !photos.length && !original?.photos.length}>{preparing ? '正在处理照片…' : busy ? '正在提交…' : '提交管理员审核'}</button></div>
  </form>
}
