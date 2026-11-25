import { useEffect, useRef, useState } from 'react'
import { api } from '../api'
import type { Asset } from '../types'
import { preparationChecks, preparationLabels, preparationOf } from '../preparation'
import type { PreparationCheck, PreparationDetail, PreparationOverview, PreparationState } from '../preparation'
import { PreparationBadge } from './PreparationBadge'
import { KnownDefectDetails } from './KnownDefects'
import { ConfirmDialog } from './ConfirmDialog'
import './PreparationPage.css'

interface Props {
  assets: Asset[]; records: PreparationOverview[] | null; initialAssetId?: number; canManage: boolean
  onNotify: (message: string, kind?: 'ok' | 'error') => void; onRefresh: () => Promise<void>; onOpenAsset: (asset: Asset) => void
}
const choices = { UNCHECKED: '未检查', PASS: '通过', ISSUE: '异常', NA: '不适用' }
const actionLabels: Record<string, string> = { START: '开始整备', SAVE: '保存检查', READY: '确认可配发', MAINTENANCE: '转维护中', REQUEST_SCRAP: '登记待报废', SCRAP: '确认报废' }
const message = (e: unknown) => e instanceof Error ? e.message : '整备操作失败'
export function PreparationPage({ assets, records, initialAssetId, canManage, onNotify, onRefresh, onOpenAsset }: Props) {
  const [tab, setTab] = useState<PreparationState | 'ALL'>('ALL')
  const [search, setSearch] = useState('')
  const [selected, setSelected] = useState<PreparationDetail | null>(null)
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [checks, setChecks] = useState<Record<string, PreparationCheck>>({})
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [confirm, setConfirm] = useState<string | null>(null)
  const revision = useRef(0)
  const mutation = useRef(false)
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
  const rows = assets.map(asset => ({ asset, value: preparationOf(asset, records) })).filter(({ asset, value }) =>
    (tab === 'ALL' || value.state === tab) && [asset.assetTag, asset.name, asset.model.name, value.previousHolder || ''].join(' ').toLowerCase().includes(search.trim().toLowerCase()))
  return <section className="preparation-page">
    <header className="preparation-header"><h2>整备清单</h2><div className="preparation-actions"><button className="button ghost" disabled={busy} onClick={() => { void onRefresh().then(() => { if (selectedId !== null) void open(selectedId) }).catch(e => setError(message(e))) }}>刷新</button>{selectedId !== null && <button className="button ghost" disabled={busy} onClick={() => { revision.current++; setSelectedId(null); setSelected(null); setError('') }}>返回清单</button>}</div></header>
    {error && <p className="preparation-error" role="alert">{error}</p>}
    {selected && selectedAsset ? <div className="preparation-detail">
      <section className="preparation-card"><div className="preparation-header"><div><h3>{selectedAsset.assetTag} · {selectedAsset.name}</h3><p>原领用人：{selected.preparation.previousHolder || '未记录'}</p></div><PreparationBadge value={preparationOf(selectedAsset, [selected.preparation])} /></div>
        <button className="button ghost" disabled={busy} onClick={() => onOpenAsset(selectedAsset)}>查看资产档案</button>
        <KnownDefectDetails defects={selectedAsset.knownDefects} />
        <fieldset disabled={!canManage || busy || selectedAsset.checkedOut || selectedAsset.status.name.includes('报废')}>
          <legend>整备检查</legend>
          {Object.entries(preparationChecks).map(([key, label]) => <div className="preparation-check" key={key}><label><span>{label}</span><select aria-label={label} value={checks[key]?.result || 'UNCHECKED'} onChange={event => setChecks(current => ({ ...current, [key]: { ...current[key], result: event.target.value as PreparationCheck['result'] } }))}>{Object.entries(choices).map(([value, text]) => <option key={value} value={value}>{text}</option>)}</select></label><label><span>{label}说明</span><input maxLength={1000} value={checks[key]?.note || ''} placeholder="异常或不适用时必填" onChange={event => setChecks(current => ({ ...current, [key]: { ...current[key], note: event.target.value } }))} /></label></div>)}
          <label className="preparation-note"><span>处理说明／报废原因</span><textarea maxLength={1000} value={note} onChange={event => setNote(event.target.value)} /></label>
          <div className="preparation-actions">{['START', 'SAVE', 'READY', 'MAINTENANCE', 'REQUEST_SCRAP', ...(selected.preparation.state === 'RETIREMENT_PENDING' ? ['SCRAP'] : [])].map(action => <button type="button" key={action} className={`button ${action === 'READY' ? 'primary' : 'ghost'}`} onClick={() => ['START', 'SAVE'].includes(action) ? void act(action) : setConfirm(action)}>{actionLabels[action]}</button>)}</div>
        </fieldset>
      </section>
      <section className="preparation-card"><h3>整备记录</h3>{selected.events.length ? <ol className="preparation-events">{[...selected.events].reverse().map((event, index) => <li key={index}><strong>第 {event.cycle} 轮 · {event.actor}</strong><span>{event.description}</span><small>{new Date(event.occurredAt).toLocaleString('zh-CN')}</small></li>)}</ol> : <p>尚无整备记录</p>}</section>
    </div> : selectedId !== null ? <p>{busy ? '正在读取整备记录…' : '无法读取，请刷新或返回清单。'}</p> : <>
      <div className="preparation-tabs">{(['ALL', 'PENDING', 'IN_PROGRESS', 'READY', 'BLOCKED', 'RETIREMENT_PENDING', 'UNVERIFIED', 'SCRAPPED'] as const).map(value => <button className={`button ${tab === value ? 'primary' : 'ghost'}`} key={value} onClick={() => setTab(value)}>{value === 'ALL' ? '全部' : preparationLabels[value]} {assets.filter(asset => value === 'ALL' || preparationOf(asset, records).state === value).length}</button>)}</div>
      <label className="preparation-search"><span>搜索设备或原领用人</span><input value={search} onChange={event => setSearch(event.target.value)} /></label>
      <div className="preparation-cards">{rows.map(({ asset, value }) => <article className="preparation-card" key={asset.id}><div className="preparation-header"><strong>{asset.assetTag} · {asset.name}</strong><PreparationBadge value={value} /></div><p>{asset.model.name}</p><p>{value.reason}</p><button className="button ghost" onClick={() => void open(asset.id)}>{canManage ? '查看／处理' : '查看记录'}</button></article>)}</div>
      {!rows.length && <p>暂无符合条件的设备</p>}
    </>}
    {confirm && <ConfirmDialog destructive={false} title={actionLabels[confirm]} description={confirm === 'READY' ? '确认已逐项核实实物？不会自动清理资料、加域或清除缺陷。' : confirm === 'SCRAP' ? '确认无法修复或不值得维修，并将资产登记为已报废？档案保留，不代表实物已回收或资料已清理。' : '确认将设备转为维护中并记录原因？不会删除资产或自动处理实物。'} confirmLabel="确认保存" busy={busy} onCancel={() => !busy && setConfirm(null)} onConfirm={() => void act(confirm)} />}
  </section>
}
