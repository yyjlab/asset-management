import { useEffect, useRef, useState } from 'react'
import { api } from '../api'
import type { DomainJoinStatus, DomainStatusOption } from '../types'
import { SaveIcon } from './Icons'
import './FoundationData.css'
import { ModelOptions } from './ModelOptions'
import { AccessoryOptions } from './AccessoryOptions'

export function FoundationData({ onNotify, onLookupsChanged }: { onNotify: (message: string, kind?: 'ok' | 'error') => void; onLookupsChanged?: () => void }) {
  const [options, setOptions] = useState<DomainStatusOption[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [saving, setSaving] = useState<DomainJoinStatus | null>(null)
  const [tab, setTab] = useState<'MODEL' | 'CPU' | 'ASSET_NAME' | 'DOMAIN' | 'ACCESSORY'>('MODEL')
  const [modelRevision, setModelRevision] = useState(0)
  const [accessoryRevision, setAccessoryRevision] = useState(0)
  const busy = useRef(false)
  const revision = useRef(0)

  const refresh = async () => {
    const current = ++revision.current
    setLoading(true); setError('')
    try {
      const data = await api.domainStatuses()
      if (revision.current === current) setOptions(data)
    } catch (reason) {
      if (revision.current === current) setError(reason instanceof Error ? reason.message : '读取基础资料失败')
    } finally { if (revision.current === current) setLoading(false) }
  }
  useEffect(() => { if (tab === 'DOMAIN') void refresh(); return () => { revision.current++ } }, [tab])
  const save = async (option: DomainStatusOption) => {
    if (busy.current) return
    busy.current = true; setSaving(option.code)
    try {
      const saved = await api.updateDomainStatus(option.code, { description: option.description, sortOrder: option.sortOrder, version: option.version })
      setOptions(current => current.map(value => value.code === saved.code ? saved : value).sort((a, b) => a.sortOrder - b.sortOrder || a.code.localeCompare(b.code)))
      onNotify('入域状态选项已保存；已有资产记录不变')
    } catch (reason) { onNotify(reason instanceof Error ? reason.message : '保存失败', 'error') }
    finally { busy.current = false; setSaving(null) }
  }

  return <section className="foundation-page">
    <header className="foundation-heading"><h2>基础资料</h2><button className="button ghost" disabled={tab === 'DOMAIN' && (loading || saving !== null)} onClick={() => tab === 'MODEL' || tab === 'CPU' || tab === 'ASSET_NAME' ? setModelRevision(value => value + 1) : tab === 'ACCESSORY' ? setAccessoryRevision(value => value + 1) : void refresh()}>刷新</button></header>
    <nav className="foundation-tabs" aria-label="基础资料分类"><button type="button" aria-pressed={tab === 'MODEL'} onClick={() => setTab('MODEL')}>设备型号</button><button type="button" aria-pressed={tab === 'CPU'} disabled={saving !== null} onClick={() => setTab('CPU')}>CPU</button><button type="button" aria-pressed={tab === 'ASSET_NAME'} disabled={saving !== null} onClick={() => setTab('ASSET_NAME')}>资产名称</button><button type="button" aria-pressed={tab === 'DOMAIN'} disabled={saving !== null} onClick={() => setTab('DOMAIN')}>入域状态</button><button type="button" aria-pressed={tab === 'ACCESSORY'} disabled={saving !== null} onClick={() => setTab('ACCESSORY')}>随附配件</button></nav>
    {tab === 'MODEL' || tab === 'CPU' || tab === 'ASSET_NAME' ? <ModelOptions key={tab} type={tab} revision={modelRevision} onNotify={onNotify} onChanged={onLookupsChanged} /> : tab === 'ACCESSORY' ? <AccessoryOptions revision={accessoryRevision} onNotify={onNotify} /> : <article className="foundation-card">
      <div className="foundation-card-heading"><h3>电脑入域状态</h3><p>固定保留三个状态，可修改说明和排列顺序；名称及含义不可改成其他状态。这里只维护选项，具体电脑请到“编辑资产 → 电脑配置”选择。</p></div>
      {error ? <p role="alert">{error}，请点击刷新重试。</p> : loading ? <p role="status">正在读取选项…</p> : <div className="foundation-options">
        {options.map(option => <form className="foundation-option" key={option.code} onSubmit={event => { event.preventDefault(); void save(option) }}>
          <div className="foundation-option-name"><span className={`domain-status-badge domain-${option.code.toLowerCase()}`}>{option.label}</span><small>{option.code}</small></div>
          <label><span>选项说明</span><input aria-label={`${option.label}的说明`} maxLength={200} value={option.description} disabled={saving !== null} onChange={event => setOptions(current => current.map(value => value.code === option.code ? { ...value, description: event.target.value } : value))} /></label>
          <label><span>排序</span><input aria-label={`${option.label}的排序`} type="number" min={0} max={999} step={1} required value={Number.isNaN(option.sortOrder) ? '' : option.sortOrder} disabled={saving !== null} onChange={event => setOptions(current => current.map(value => value.code === option.code ? { ...value, sortOrder: event.target.valueAsNumber } : value))} /></label>
          <button type="submit" className="button primary" disabled={saving !== null}><SaveIcon />{saving === option.code ? '保存中…' : '保存'}</button>
        </form>)}
      </div>}
      <p className="foundation-footnote">旧电脑未核实时为“待确认”，非电脑不适用。此状态仅作人工记录，不连接 AD，也不会自动拦截领用。</p>
    </article>}
  </section>
}
