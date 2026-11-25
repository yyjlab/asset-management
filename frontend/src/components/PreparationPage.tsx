import { useEffect, useRef, useState } from 'react'
import { api } from '../api'
import type { Asset } from '../types'
import { preparationChecks, preparationLabels, preparationOf } from '../preparation'
import type { PreparationCheck, PreparationDetail, PreparationOverview, PreparationState } from '../preparation'
import { PreparationBadge } from './PreparationBadge'
import { KnownDefectDetails } from './KnownDefects'
import { ConfirmDialog } from './ConfirmDialog'
import { ArchiveIcon, CheckIcon, ChevronIcon, ClipboardIcon, CloseIcon, RefreshIcon, SearchIcon } from './Icons'
import './PreparationPage.css'

interface Props {
  assets: Asset[]; records: PreparationOverview[] | null; initialAssetId?: number; canManage: boolean
  onNotify: (message: string, kind?: 'ok' | 'error') => void; onRefresh: () => Promise<void>; onOpenAsset: (asset: Asset) => void
}
const choices = { UNCHECKED: '未检查', PASS: '通过', ISSUE: '异常', NA: '不适用' }
const actionLabels: Record<string, string> = { START: '开始整备', SAVE: '保存检查', READY: '确认可配发', MAINTENANCE: '转维护中', REQUEST_SCRAP: '登记待报废', SCRAP: '确认报废' }
const message = (e: unknown) => e instanceof Error ? e.message : '整备操作失败'
const sections = [
  { id: 'pending', label: '待处理', icon: ClipboardIcon, empty: '暂无待处理设备' },
  { id: 'ready', label: '可配发', icon: CheckIcon, empty: '暂无可配发设备' },
  { id: 'scrapped', label: '已报废', icon: ArchiveIcon, empty: '暂无已报废设备' },
] as const
type PreparationSection = typeof sections[number]['id']
const pendingOrder: PreparationState[] = ['PENDING', 'IN_PROGRESS', 'BLOCKED', 'RETIREMENT_PENDING', 'UNVERIFIED', 'UNAVAILABLE']
const rowActions: Partial<Record<PreparationState, string>> = {
  PENDING: '开始检查', IN_PROGRESS: '继续检查', BLOCKED: '处理异常',
  RETIREMENT_PENDING: '核对报废', UNVERIFIED: '开始核验',
}

export function PreparationPage({ assets, records, initialAssetId, canManage, onNotify, onRefresh, onOpenAsset }: Props) {
  const [section, setSection] = useState<PreparationSection>('pending')
  const [statusFilter, setStatusFilter] = useState<PreparationState | ''>('')
  const [search, setSearch] = useState('')
  const [selected, setSelected] = useState<PreparationDetail | null>(null)
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [checks, setChecks] = useState<Record<string, PreparationCheck>>({})
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const [error, setError] = useState('')
  const [confirm, setConfirm] = useState<string | null>(null)
  const revision = useRef(0)
  const mutation = useRef(false)
  const searchInput = useRef<HTMLInputElement>(null)
  const selectedAsset = assets.find(asset => asset.id === selectedId)
  const install = (id: number, detail: PreparationDetail) => {
    setSelectedId(id); setSelected(detail); setNote(detail.note || '')
    setChecks(Object.fromEntries(Object.keys(preparationChecks).map(key => [key, detail.checks[key] || { result: 'UNCHECKED', note: '' }])))
  }
  const open = async (id: number) => {
    if (mutation.current) return
    const current = ++revision.current; setBusy(true); setError(''); setSelected(null); setSelectedId(id)
    try { const detail = await api.getPreparation(id); if (current === revision.current) install(id, detail) }
    catch (e) { if (current === revision.current) setError(message(e)) }
    finally { if (current === revision.current) setBusy(false) }
  }
  useEffect(() => { if (initialAssetId) void open(initialAssetId); return () => { revision.current++ } }, [initialAssetId])
  const act = async (action: string) => {
    if (!selected || selectedId === null || mutation.current || !canManage) return
    mutation.current = true; setBusy(true); setError('')
    try {
      const next = await api.preparationAction(selectedId, { action, version: selected.preparation.version,
        assetVersion: selected.preparation.assetVersion, note, checks })
      install(selectedId, next); setConfirm(null); onNotify(`${actionLabels[action]}已保存`)
      await onRefresh()
    } catch (e) { setError(message(e)) }
    finally { mutation.current = false; setBusy(false) }
  }
  const refresh = async () => {
    const current = revision.current
    setRefreshing(true); setError('')
    try {
      await onRefresh()
      if (selectedId !== null && current === revision.current) await open(selectedId)
    } catch (e) { setError(message(e)) }
    finally { setRefreshing(false) }
  }

  const searchTerm = search.trim().toLowerCase()
  const rows = assets.filter(asset => !asset.checkedOut)
    .map(asset => ({ asset, value: preparationOf(asset, records) }))
    .filter(({ asset, value }) => [asset.assetTag, asset.name, asset.model.name, value.previousHolder, asset.location.name, asset.category.name]
      .join(' ').toLowerCase().includes(searchTerm))
  const groups = {
    pending: rows.filter(row => pendingOrder.includes(row.value.state))
      .sort((a, b) => pendingOrder.indexOf(a.value.state) - pendingOrder.indexOf(b.value.state)),
    ready: rows.filter(row => row.value.state === 'READY'),
    scrapped: rows.filter(row => row.value.state === 'SCRAPPED'),
  }
  const visibleRows = section === 'pending' && statusFilter
    ? groups.pending.filter(row => row.value.state === statusFilter)
    : groups[section]
  const activeSection = sections.find(item => item.id === section)!
  const filtering = Boolean(searchTerm || (section === 'pending' && statusFilter))

  return <section className="preparation-page">
    <header className="preparation-header preparation-title">
      <h2>整备清单</h2>
      <div className="preparation-actions">
        {selectedId !== null && <button type="button" className="button ghost" disabled={busy} onClick={() => { revision.current++; setSelectedId(null); setSelected(null); setError('') }}>返回清单</button>}
        <button type="button" className="button ghost" disabled={busy || refreshing} onClick={() => void refresh()}><RefreshIcon />{refreshing ? '刷新中…' : '刷新'}</button>
      </div>
    </header>
    {error && <p className="preparation-error" role="alert">{error}</p>}
    {selected && selectedAsset ? <div className="preparation-detail">
      <section className="preparation-card"><div className="preparation-header"><div><h3>{selectedAsset.assetTag} · {selectedAsset.name}</h3><p>原领用人：{selected.preparation.previousHolder || '未记录'}</p></div><PreparationBadge value={preparationOf(selectedAsset, [selected.preparation])} /></div>
        <button className="button ghost" disabled={busy} onClick={() => onOpenAsset(selectedAsset)}>查看资产档案</button>
        <KnownDefectDetails defects={selectedAsset.knownDefects} />
        <fieldset disabled={!canManage || busy || refreshing || selectedAsset.checkedOut || selectedAsset.status.name.includes('报废')}>
          <legend>整备检查</legend>
          {Object.entries(preparationChecks).map(([key, label]) => <div className="preparation-check" key={key}><label><span>{label}</span><select aria-label={label} value={checks[key]?.result || 'UNCHECKED'} onChange={event => setChecks(current => ({ ...current, [key]: { ...current[key], result: event.target.value as PreparationCheck['result'] } }))}>{Object.entries(choices).map(([value, text]) => <option key={value} value={value}>{text}</option>)}</select></label><label><span>{label}说明</span><input maxLength={1000} value={checks[key]?.note || ''} placeholder="异常或不适用时必填" onChange={event => setChecks(current => ({ ...current, [key]: { ...current[key], note: event.target.value } }))} /></label></div>)}
          <label className="preparation-note"><span>处理说明／报废原因</span><textarea maxLength={1000} value={note} onChange={event => setNote(event.target.value)} /></label>
          <div className="preparation-actions">{['START', 'SAVE', 'READY', 'MAINTENANCE', 'REQUEST_SCRAP', ...(selected.preparation.state === 'RETIREMENT_PENDING' ? ['SCRAP'] : [])].map(action => <button type="button" key={action} className={`button ${action === 'READY' ? 'primary' : 'ghost'}`} onClick={() => ['START', 'SAVE'].includes(action) ? void act(action) : setConfirm(action)}>{actionLabels[action]}</button>)}</div>
        </fieldset>
      </section>
      <section className="preparation-card"><h3>整备记录</h3>{selected.events.length ? <ol className="preparation-events">{[...selected.events].reverse().map((event, index) => <li key={index}><strong>第 {event.cycle} 轮 · {event.actor}</strong><span>{event.description}</span><small>{new Date(event.occurredAt).toLocaleString('zh-CN')}</small></li>)}</ol> : <p>尚无整备记录</p>}</section>
    </div> : selectedId !== null ? <p>{busy ? '正在读取整备记录…' : '无法读取，请刷新或返回清单。'}</p> : <>
      <div className="preparation-tabs" role="group" aria-label="整备列表范围">
        {sections.map(({ id, label, icon: Icon }) => <button type="button" key={id} className={`preparation-tab ${id}`}
          aria-pressed={section === id} onClick={() => setSection(id)}>
          <span className="preparation-tab-icon"><Icon /></span>
          <span>{label}</span> <strong>{groups[id].length}</strong>
        </button>)}
      </div>
      <section className="preparation-list" aria-label={`${activeSection.label}设备`} aria-busy={refreshing}>
        <div className="preparation-toolbar">
          <div className="preparation-search">
            <SearchIcon />
            <input ref={searchInput} aria-label="搜索设备或原领用人" value={search} onChange={event => setSearch(event.target.value)}
              placeholder="搜索编号、名称、型号、位置或原领用人" />
            {search && <button type="button" aria-label="清除搜索" className="preparation-clear" onClick={() => { setSearch(''); searchInput.current?.focus() }}><CloseIcon /></button>}
          </div>
          {section === 'pending' && <select className="preparation-filter" aria-label="筛选待处理状态" value={statusFilter}
            onChange={event => setStatusFilter(event.target.value as PreparationState | '')}>
            <option value="">全部待处理</option>
            {pendingOrder.map(state =>
              <option value={state} key={state}>{preparationLabels[state]}</option>)}
          </select>}
        </div>
        {visibleRows.length ? <div role="table" aria-label={`${activeSection.label}设备列表`} className="preparation-table">
          <div role="row" className="preparation-list-row preparation-columns">
            <span role="columnheader">设备</span><span role="columnheader">存放位置</span><span role="columnheader">整备状态</span><span role="columnheader">操作</span>
          </div>
          {visibleRows.map(({ asset, value }) => <div role="row" className="preparation-list-row" key={asset.id}>
            <div role="cell" className="preparation-device">
              <strong>{asset.name}</strong>
              <span>{asset.assetTag}{asset.model.name && !asset.name.toLowerCase().includes(asset.model.name.toLowerCase()) && <> · {asset.model.name}</>}</span>
            </div>
            <div role="cell" className="preparation-location"><span>位置</span>{asset.location.name}</div>
            <div role="cell" className="preparation-row-status">
              <PreparationBadge value={value} />
              {value.state !== 'SCRAPPED' && value.state !== 'READY' && <p>{value.reason}</p>}
            </div>
            <div role="cell" className="preparation-row-operation">
              <button type="button" className="preparation-open" onClick={() => { window.scrollTo(0, 0); void open(asset.id) }}>
                {(canManage && rowActions[value.state]) || '查看记录'}<span><ChevronIcon /></span>
              </button>
            </div>
          </div>)}
        </div> : <div className="preparation-empty" role="status">
          <span>{refreshing ? '正在读取设备…' : filtering ? '暂无符合条件的设备' : activeSection.empty}</span>
          {!refreshing && filtering && <button type="button" className="button ghost" onClick={() => { setSearch(''); setStatusFilter('') }}>清除筛选</button>}
        </div>}
      </section>
    </>}
    {confirm && <ConfirmDialog destructive={false} title={actionLabels[confirm]} description={confirm === 'READY' ? '确认已逐项核实实物？不会自动清理资料、加域或清除缺陷。' : confirm === 'SCRAP' ? '确认无法修复或不值得维修，并将资产登记为已报废？档案保留，不代表实物已回收或资料已清理。' : '确认将设备转为维护中并记录原因？不会删除资产或自动处理实物。'} confirmLabel="确认保存" busy={busy} onCancel={() => !busy && setConfirm(null)} onConfirm={() => void act(confirm)} />}
  </section>
}
