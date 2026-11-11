import { useEffect, useState } from 'react'
import { api } from '../api'
import { CloseIcon } from './Icons'
import './DeploymentIntegration.css'
import type { DeploymentApproval, DeploymentIntake, DeploymentIntakePage, DeploymentIntakeState } from '../types/deployment'

interface Props { onNotify: (text: string, kind?: 'ok' | 'error') => void; onAssetsChanged?: () => Promise<void> | void }
const date = (value: string | null) => value ? new Date(value).toLocaleString('zh-CN') : '未记录'
const labels: Record<DeploymentIntakeState, string> = { pending_review: '待审核', approved: '已批准', rejected: '未批准' }
const fields = [
  ['model', '设备型号', 120], ['cpu', 'CPU', 200], ['memory', '内存', 200],
  ['storage', '硬盘', 200], ['graphicsCard', '显卡', 200], ['manufacturerSerialNumber', 'SN', 160],
] as const
type Draft = Record<(typeof fields)[number][0], string> & { name: string; categoryId: string; note: string; assignedTo: string; ownershipDepartment: string; domainJoinStatus: 'JOINED' | 'NOT_JOINED' | 'UNKNOWN' }
const draftOf = (row: DeploymentIntake): Draft => ({
  name: row.name ?? row.model ?? '', model: row.model || '', cpu: row.cpu || '', memory: row.memory || '',
  storage: row.storage || '', graphicsCard: row.graphicsCard || '集成显卡', manufacturerSerialNumber: row.manufacturerSerialNumber || '',
  categoryId: row.categoryId ? String(row.categoryId) : '', note: '',
  assignedTo: row.assignedTo || '', ownershipDepartment: row.ownershipDepartment || '', domainJoinStatus: row.domainJoinStatus || 'UNKNOWN',
})

export function DeploymentIntakes({ onNotify, onAssetsChanged }: Props) {
  const [state, setState] = useState<DeploymentIntakeState>('pending_review')
  const [page, setPage] = useState(0)
  const [data, setData] = useState<DeploymentIntakePage | null>(null)
  const [categories, setCategories] = useState<{ id: number; name: string }[]>([])
  const [refresh, setRefresh] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [selected, setSelected] = useState<DeploymentIntake | null>(null)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [busy, setBusy] = useState(false)
  const [formError, setFormError] = useState('')
  const [rejecting, setRejecting] = useState(false)
  const [resubmitting, setResubmitting] = useState(false)

  useEffect(() => {
    let cancelled = false
    setLoading(true); setError('')
    Promise.all([api.listDeploymentIntakes(page, state), api.deploymentIntakeOptions()])
      .then(([rows, options]) => { if (!cancelled) { setData(rows); setCategories(options.categories) } })
      .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : '读取待审核电脑失败') })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [page, state, refresh])

  useEffect(() => {
    if (selected) return
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') setRefresh(value => value + 1)
    }, 20000)
    return () => window.clearInterval(timer)
  }, [selected])

  const open = (row: DeploymentIntake) => {
    setSelected(row); setDraft(draftOf(row)); setFormError(''); setRejecting(false); setResubmitting(false)
  }
  const close = () => { if (!busy) { setSelected(null); setDraft(null) } }
  const update = (field: keyof Draft, value: string) => setDraft(current => current ? { ...current, [field]: value } : current)
  const decide = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!selected || !draft || busy || (selected.state !== 'pending_review' && !(selected.state === 'rejected' && resubmitting))) return
    setFormError('')
    if (rejecting && !draft.note.trim()) { setFormError('请填写不批准原因'); return }
    if (!rejecting && (!draft.model.trim() || !draft.name.trim() || !categories.some(item => String(item.id) === draft.categoryId))) {
      setFormError('请补充资产名称、真实设备型号，并选择有效电脑分类'); return
    }
    setBusy(true)
    try {
      if (rejecting) {
        await api.rejectDeploymentIntake(selected.externalRegistrationId, selected.revision, draft.note.trim())
        onNotify('已记录不批准原因，未创建资产')
      } else {
        const payload: DeploymentApproval = {
          revision: selected.revision, categoryId: Number(draft.categoryId), name: draft.name.trim(), model: draft.model.trim(),
          cpu: draft.cpu.trim() || null, memory: draft.memory.trim() || null, storage: draft.storage.trim() || null,
          graphicsCard: draft.graphicsCard.trim() || null, manufacturerSerialNumber: draft.manufacturerSerialNumber.trim() || null,
          note: draft.note.trim() || null,
          assignedTo: draft.assignedTo.trim() || null, ownershipDepartment: draft.ownershipDepartment.trim() || null,
          domainJoinStatus: draft.domainJoinStatus,
        }
        if (resubmitting) {
          await api.resubmitDeploymentIntake(selected.externalRegistrationId, payload)
          onNotify('已重新送审，尚未创建资产'); setState('pending_review'); setPage(0)
        } else {
          const result = await api.approveDeploymentIntake(selected.externalRegistrationId, payload)
          await onAssetsChanged?.()
          onNotify('已批准并创建资产：' + (result.asset?.assetTag || ''))
        }
      }
      setSelected(null); setDraft(null); setRefresh(value => value + 1)
    } catch (e) {
      setFormError(e instanceof Error ? e.message : '提交审核失败，请刷新后核对结果')
      setRefresh(value => value + 1)
    } finally { setBusy(false) }
  }

  return <section className="deployment-section deployment-intakes" aria-label="资产入库">
    <div className="deployment-intake-toolbar"><div><h3>资产入库</h3><p>采集后自动送审，批准后生成资产编号并入库。</p></div>
      <button className="button ghost" disabled={loading || busy} onClick={() => setRefresh(value => value + 1)}>刷新待审核列表</button></div>
    <div className="deployment-tabs" role="group" aria-label="审核状态">
      {(Object.keys(labels) as DeploymentIntakeState[]).map(value => <button key={value} className={'button ' + (state === value ? 'primary' : 'ghost')}
        aria-pressed={state === value} disabled={busy} onClick={() => { setState(value); setPage(0) }}>{labels[value]}</button>)}
    </div>
    {error && <p role="alert" className="deployment-error">{error}</p>}
    {loading && <p role="status">正在读取装机记录…</p>}
    {!error && !loading && <>
      <div className="deployment-table"><table><thead><tr><th>电脑 / SN</th><th>CPU / 内存</th><th>硬盘 / 显卡</th><th>收到时间</th><th>结果</th><th>操作</th></tr></thead><tbody>
        {data?.items.map(row => <tr key={row.externalRegistrationId}>
          <td><strong>{row.name || row.model || '名称待补充'}</strong>{row.name && row.model && row.name !== row.model && <small>{row.model}</small>}<small>{row.manufacturerSerialNumber || 'SN 未采集'}</small></td>
          <td>{row.cpu || 'CPU 未采集'}<small>{row.memory || '内存未采集'}</small></td>
          <td>{row.storage || '硬盘未采集'}<small>{row.graphicsCard || (row.state === 'approved' ? '显卡未采集' : '集成显卡（默认）')}</small></td>
          <td>{date(row.receivedAt)}</td><td><span className={'deployment-intake-state state-' + row.state}>{labels[row.state]}</span>{row.asset && <small>{row.asset.assetTag}</small>}</td>
          <td><button className="button ghost" onClick={() => open(row)}>{row.state === 'pending_review' ? '核对 / 审核' : '查看结果'}</button></td>
        </tr>)}
        {!data?.items.length && <tr><td colSpan={6}>暂无{labels[state]}电脑</td></tr>}
      </tbody></table></div>
      <div className="deployment-pagination"><span>共 {data?.totalElements || 0} 条 · 第 {page + 1} 页</span>
        <button className="button ghost" disabled={page === 0} onClick={() => setPage(value => value - 1)}>上一页</button>
        <button className="button ghost" disabled={!data || page + 1 >= data.totalPages} onClick={() => setPage(value => value + 1)}>下一页</button></div>
    </>}
    {selected && draft && <div className="modal-backdrop"><section className="deployment-dialog deployment-review-dialog" role="dialog" aria-modal="true" aria-labelledby="deployment-review-title"
      onKeyDown={event => { if (event.key === 'Escape') close() }}>
      <header className="deployment-dialog-head"><div><h3 id="deployment-review-title">{resubmitting ? '修改并重新送审' : selected.state === 'pending_review' ? '核对装机电脑' : labels[selected.state]}</h3><p>来源：{selected.source || 'AD 装机平台'} · 采集于 {date(selected.capturedAt)}</p></div>
        <button className="deployment-dialog-close" title="关闭" aria-label="关闭审核" disabled={busy} onClick={close}><CloseIcon /></button></header>
      <form onSubmit={event => void decide(event)}>
        <div className="deployment-dialog-body">
          {selected.state === 'pending_review' || resubmitting ? <>
            {selected.reviewedAt && selected.reviewNote && <p>上次不批准：{selected.reviewNote} · {selected.reviewedBy} / {date(selected.reviewedAt)}</p>}
            {!rejecting && <><div className="deployment-review-defaults">编号批准后生成 · 微丰启 / 公司 · {draft.assignedTo.trim() ? '已经领出' : '当前可用'}</div>
              <div className="deployment-review-fields">
                <label>资产名称<input autoFocus required maxLength={160} value={draft.name} disabled={busy} onChange={event => update('name', event.target.value)} /></label>
                <label>资产分类<select aria-label="资产分类" required value={draft.categoryId} disabled={busy} onChange={event => update('categoryId', event.target.value)}><option value="">选择电脑分类</option>{categories.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
                {fields.map(([key, label, max]) => <label key={key}>{label}{key === 'model' ? ' *' : ''}<input required={key === 'model'} maxLength={max} value={draft[key]} disabled={busy} onChange={event => update(key, event.target.value)} />{key === 'graphicsCard' && !selected.graphicsCard && <small>未采集，默认填写集成显卡</small>}</label>)}
                <label>领用人<input maxLength={120} value={draft.assignedTo} disabled={busy} onChange={event => update('assignedTo', event.target.value)} placeholder="选填" /></label>
                <label>部门<input maxLength={120} value={draft.ownershipDepartment} disabled={busy} onChange={event => update('ownershipDepartment', event.target.value)} placeholder="选填" /></label>
                <label>入域状态<select value={draft.domainJoinStatus} disabled={busy} onChange={event => update('domainJoinStatus', event.target.value as Draft['domainJoinStatus'])}><option value="UNKNOWN">待确认</option><option value="JOINED">已入域</option><option value="NOT_JOINED">未入域</option></select></label>
              </div></>}
            <label className="deployment-review-note">{rejecting ? '不批准原因 *' : '审核备注'}<textarea autoFocus={rejecting} required={rejecting} maxLength={500} rows={3} value={draft.note} disabled={busy} onChange={event => update('note', event.target.value)} /></label>
          </> : <>
            <dl className="deployment-review-result"><dt>资产编号</dt><dd>{selected.asset?.assetTag || '未创建资产'}</dd><dt>资产名称</dt><dd>{selected.name || selected.model || '未记录'}</dd>
              {fields.map(([key, label]) => <div key={key}><dt>{label}</dt><dd>{selected[key] || '未记录'}</dd></div>)}
              <dt>领用人 / 部门</dt><dd>{selected.assignedTo || '未领用'} / {selected.ownershipDepartment || '未指定'}</dd><dt>入域状态</dt><dd>{({JOINED:'已入域',NOT_JOINED:'未入域',UNKNOWN:'待确认'})[selected.domainJoinStatus || 'UNKNOWN']}</dd>
              <dt>审核人 / 时间</dt><dd>{selected.reviewedBy || '未记录'} / {date(selected.reviewedAt)}</dd><dt>审核备注</dt><dd>{selected.reviewNote || '无'}</dd></dl>
          </>}
          {formError && <p role="alert" className="deployment-error">{formError}</p>}
        </div>
        <footer className="deployment-dialog-footer">
          <button type="button" className="button ghost" disabled={busy} onClick={close}>关闭</button>
          {selected.state === 'rejected' && !resubmitting && <button type="button" className="button primary" disabled={busy} onClick={() => { setResubmitting(true); setFormError('') }}>修改并重新送审</button>}
          {resubmitting && <button type="submit" className="button primary" disabled={busy}>{busy ? '提交中…' : '重新送审'}</button>}
          {selected.state === 'pending_review' && <>
            <button type="button" className="button ghost" disabled={busy} onClick={() => { setRejecting(value => !value); setFormError('') }}>{rejecting ? '返回核对' : '不批准'}</button>
            <button type="submit" className="button primary" disabled={busy}>{busy ? '提交中…' : rejecting ? '确认不批准' : '批准并创建资产'}</button>
          </>}
        </footer>
      </form>
    </section></div>}
  </section>
}
