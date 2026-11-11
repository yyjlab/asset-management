import { useEffect, useMemo, useRef, useState } from 'react'
import { api } from '../api'
import type { RosterEntry, RosterImportResult, RosterPerson } from '../types/roster'
import './DeploymentIntegration.css'

const labels = { registered: '已登记', missing: '未登记', pending: '待审核', confirm: '待确认' }
export function parseRoster(text: string) {
  const people: RosterPerson[] = [], errors: string[] = []
  let emptySeats = 0
  text.replace(/^\uFEFF/, '').split(/\r?\n/).forEach((line, index) => {
    if (!line.trim()) return
    const cells = line.split('\t').map(value => value.trim())
    if (cells.length === 1 && cells[0] === '姓名' || cells.join('|') === '座位序号|部门|姓名' || cells.join('|') === '座位号|部门|姓名') return
    if (cells.length !== 1 && cells.length !== 3) { errors.push(`第 ${index + 1} 行：请粘贴姓名一列，或座位号、部门、姓名三列`); return }
    const person = cells.length === 1 ? { name: cells[0], department: '', seat: '' } : { name: cells[2], department: cells[1], seat: cells[0] }
    if (person.name === '空置') { emptySeats++; return }
    if (!person.name || person.name.length > 120 || person.department.length > 120 || person.seat.length > 20) { errors.push(`第 ${index + 1} 行：姓名必填，姓名/部门最多120字，座位号最多20字`); return }
    people.push(person)
  })
  if (people.length > 1000) errors.push('每次最多导入1000人')
  return { people, errors, emptySeats }
}

export function RegistrationRoster({ onNotify, onOpenAsset }: { onNotify: (text: string, kind?: 'ok' | 'error') => void; onOpenAsset: (id: number) => void }) {
  const [rows, setRows] = useState<RosterEntry[]>([]), [loading, setLoading] = useState(true), [error, setError] = useState('')
  const [filter, setFilter] = useState('missing'), [department, setDepartment] = useState(''), [search, setSearch] = useState('')
  const [revision, setRevision] = useState(0), [managing, setManaging] = useState(false), [text, setText] = useState(''), [busy, setBusy] = useState(false)
  const [result, setResult] = useState<RosterImportResult | null>(null), [formError, setFormError] = useState('')
  const lock = useRef(false)
  useEffect(() => {
    let cancelled = false
    setLoading(true); setError('')
    api.registrationRoster().then(data => { if (!cancelled) setRows(data) }).catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : '读取登记核对失败') }).finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [revision])
  useEffect(() => {
    if (managing) return
    const timer = window.setInterval(() => { if (document.visibilityState === 'visible') setRevision(v => v + 1) }, 30000)
    return () => window.clearInterval(timer)
  }, [managing])
  const parsed = useMemo(() => parseRoster(text), [text])
  const shown = rows.filter(row => (filter === 'all' || filter === row.state) && (!department || row.department === department) && (!search.trim() || [row.name, row.seat, row.department].some(value => value.includes(search.trim()))))
  const counts = Object.fromEntries(Object.keys(labels).map(state => [state, rows.filter(row => row.state === state).length]))
  const importPeople = async (event: React.FormEvent) => {
    event.preventDefault()
    if (lock.current || parsed.errors.length || !parsed.people.length) return
    lock.current = true; setBusy(true); setFormError(''); setResult(null)
    try {
      const data = await api.importRegistrationRoster(parsed.people)
      setResult(data); setRevision(v => v + 1); setText(''); onNotify(`新增 ${data.added} 人，重复跳过 ${data.skipped} 人`)
    } catch (e) { setFormError(e instanceof Error ? e.message : '导入失败，请刷新后核对') }
    finally { lock.current = false; setBusy(false) }
  }
  return <section className="deployment-settings deployment-review-page" aria-label="登记核对">
    <header className="deployment-header"><div><h2>登记核对</h2><p>按名单姓名精确核对电脑领用记录；不修改资产，不创建登录账号。</p></div><div><button className="button ghost" onClick={() => { setManaging(true); setFormError('') }}>名单管理</button><button className="button ghost" disabled={loading} onClick={() => setRevision(v => v + 1)}>刷新</button></div></header>
    {error && <p role="alert" className="deployment-error">{error}</p>}
    {loading && !rows.length ? <p role="status">正在核对…</p> : !error && <>
      <p>全部 {rows.length} 人 · 已登记 {counts.registered} 人 · 未登记 {counts.missing} 人 · 待审核 {counts.pending} 人 · 待确认 {counts.confirm} 人</p>
      <div className="deployment-review-fields"><label>登记情况<select aria-label="登记情况" value={filter} onChange={e => setFilter(e.target.value)}><option value="all">全部</option>{Object.entries(labels).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
        <label>部门<select aria-label="部门" value={department} onChange={e => setDepartment(e.target.value)}><option value="">全部部门</option>{[...new Set(rows.map(row => row.department).filter(Boolean))].sort().map(value => <option key={value}>{value}</option>)}</select></label>
        <label>搜索<input type="search" placeholder="姓名 / 座位号" value={search} onChange={e => setSearch(e.target.value)} /></label></div>
      <div className="deployment-table"><table><thead><tr><th>座位号</th><th>姓名</th><th>部门</th><th>登记情况</th><th>电脑资产</th></tr></thead><tbody>
        {shown.map(row => <tr key={row.id}><td>{row.seat || '—'}</td><td>{row.name}</td><td>{row.department || '未填写'}</td><td>{labels[row.state]}{row.reason && <small>{row.reason}</small>}{row.pendingCount > 0 && <small>{row.pendingCount} 条采集待审核</small>}</td><td>{row.computers.map(asset => <div key={asset.id}><button className="button ghost" onClick={() => onOpenAsset(asset.id)}>{asset.assetTag} · {asset.name}</button></div>)}</td></tr>)}
        {!shown.length && <tr><td colSpan={5}>{!rows.length ? '暂无核对名单，请先在名单管理中批量导入人员' : '没有符合条件的人员'}</td></tr>}
      </tbody></table></div></>}
    {managing && <div className="modal-backdrop"><section className="deployment-dialog deployment-review-dialog" role="dialog" aria-modal="true" aria-label="名单管理"><header className="deployment-dialog-head"><h3>名单管理</h3><button className="button ghost" disabled={busy} onClick={() => setManaging(false)}>关闭</button></header>
      <form onSubmit={event => void importPeople(event)}><div className="deployment-dialog-body"><p>一行一个姓名，或从 Excel 复制“座位号、部门、姓名”三列。按姓名去重，重复跳过，不覆盖原资料；“空置”自动忽略。同名不同人也会跳过，需人工核对。</p>
        <label className="deployment-review-note">批量人员<textarea rows={7} maxLength={200000} value={text} disabled={busy} onChange={e => { setText(e.target.value); setResult(null) }} placeholder={'张三\n李四'} /></label>
        {parsed.people.length > 0 && <p>待提交 {parsed.people.length} 行，已忽略空置 {parsed.emptySeats} 行</p>}
        {parsed.errors.map(message => <p key={message} role="alert" className="deployment-error">{message}</p>)}
        {parsed.people.length > 0 && <div className="deployment-table"><table><thead><tr><th>座位号</th><th>部门</th><th>姓名</th><th>预览</th></tr></thead><tbody>{parsed.people.map((person, index) => <tr key={index}><td>{person.seat || '—'}</td><td>{person.department || '—'}</td><td>{person.name}</td><td>{rows.some(row => row.name === person.name) || parsed.people.slice(0, index).some(row => row.name === person.name) ? '重复，将跳过' : '新增'}</td></tr>)}</tbody></table></div>}
        {result && <p role="status">新增 {result.added} 人，重复跳过 {result.skipped} 人{result.duplicates.length ? `：${result.duplicates.join('、')}` : ''}</p>}
        {formError && <p role="alert" className="deployment-error">{formError}</p>}</div><footer className="deployment-dialog-footer"><button className="button primary" disabled={busy || !parsed.people.length || !!parsed.errors.length}>{busy ? '导入中…' : '确认导入名单'}</button></footer></form>
    </section></div>}
  </section>
}
