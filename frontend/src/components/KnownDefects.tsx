import type { KnownDefect, KnownDefectInput } from '../types'
import { ArchiveIcon, BellIcon, PlusIcon, ReturnIcon, TrashIcon } from './Icons'
import './KnownDefects.css'

export function KnownDefectBadge({ defects = [] }: { defects?: KnownDefect[] }) {
  const current = defects.filter(item => !item.archivedAt)
  if (!current.length) return null
  return <span className="known-defect-badge" title={current.map(item => item.description).join('；')}><BellIcon />已知缺陷 {current.length}</span>
}

export function KnownDefectEditor({ value, onChange }: { value: KnownDefectInput[]; onChange: (value: KnownDefectInput[]) => void }) {
  const current = value.map((item, index) => ({ ...item, index })).filter(item => !item.archived)
  const history = value.map((item, index) => ({ ...item, index })).filter(item => item.archived)
  const update = (index: number, patch: Partial<KnownDefectInput>) => onChange(value.map((item, i) => i === index ? { ...item, ...patch } : item))
  return <section className="form-section known-defect-editor" aria-label="设备已知缺陷">
    <div className="form-section-head">
      <div><h3><BellIcon />设备已知缺陷{current.length > 0 && <b className="known-defect-count">{current.length}</b>}</h3><p>仅记录设备现状，不改变资产状态</p></div>
      <button type="button" className="button ghost" disabled={value.length >= 50} onClick={() => onChange([...value, { id: null, description: '', archived: false }])}><PlusIcon />添加缺陷</button>
    </div>
    {!current.length && <p className="known-defect-empty">暂无已知缺陷</p>}
    <div className="known-defect-rows">{current.map((item, ordinal) => <div className="known-defect-edit-row" key={item.id || `new-${item.index}`}>
      <label><span>缺陷描述 {ordinal + 1}</span><input autoFocus={!item.id} required maxLength={300} value={item.description} placeholder="例如：P 键失灵、Type-C 接口无法充电" onChange={event => update(item.index, { description: event.target.value })} /></label>
      {item.id ? <button type="button" className="button ghost" onClick={() => update(item.index, { archived: true })} title="仅移入历史，不代表已经修复"><ArchiveIcon />移入历史</button>
        : <button type="button" className="icon-button" title="移除未保存的缺陷" aria-label={`移除缺陷 ${ordinal + 1}`} onClick={() => onChange(value.filter((_, i) => i !== item.index))}><TrashIcon /></button>}
    </div>)}</div>
    {history.length > 0 && <details className="known-defect-history"><summary>历史记录（{history.length}）</summary><ul>{history.map(item => <li key={item.id}><span>{item.description}</span><button type="button" className="button ghost" onClick={() => update(item.index, { archived: false })}><ReturnIcon />恢复高亮</button></li>)}</ul></details>}
    {value.length >= 50 && <small className="known-defect-empty">含历史记录最多50条</small>}
  </section>
}

export function KnownDefectDetails({ defects = [], onEdit }: { defects?: KnownDefect[]; onEdit?: () => void }) {
  const current = defects.filter(item => !item.archivedAt)
  const history = defects.filter(item => item.archivedAt)
  const metadata = (item: KnownDefect) => `${item.recordedBy} · ${new Date(item.recordedAt).toLocaleString('zh-CN', { hour12: false })}`
  return <section className={`record-subsection known-defect-details${current.length ? ' has-defects' : ''}`} aria-label="设备已知缺陷">
    <div className="known-defect-heading"><h3><BellIcon />设备已知缺陷{current.length > 0 && <b className="known-defect-count">{current.length}</b>}</h3>{onEdit && <button type="button" className="button ghost" onClick={onEdit}>{current.length ? '编辑记录' : '记录缺陷'}</button>}</div>
    {current.length ? <ul className="known-defect-list">{current.map(item => <li key={item.id}><strong>{item.description}</strong><small>{metadata(item)}</small></li>)}</ul> : <p className="known-defect-empty">暂无已知缺陷</p>}
    {history.length > 0 && <details className="known-defect-history"><summary>历史记录（{history.length}）</summary><ul>{history.map(item => <li key={item.id}><div><span>{item.description}</span><small>{metadata(item)} · {item.archivedBy} 移入历史</small></div></li>)}</ul></details>}
  </section>
}
