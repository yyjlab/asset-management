import { useEffect, useRef, useState } from 'react'
import { api } from '../api'
import type { AccessoryOption, AssetAccessory, RelatedDevice } from '../types'
import { CloseIcon, PlusIcon, TrashIcon } from './Icons'
import './Accessories.css'

const normalized = (value: string) => value.normalize('NFKC').replace(/\s+/g, '').toLocaleLowerCase()
type Choice = AccessoryOption & { stockId?: number; available?: number }
export function matchingAccessories(options: AccessoryOption[], query: string): AccessoryOption[] {
  const term = normalized(query)
  return options.filter(option => !option.disabled && (!term || [option.name, option.model, option.specification].some(value => normalized(value).includes(term))))
}
export function accessorySnapshot(option: Choice): RelatedDevice {
  return { name: option.name, model: option.model, specification: option.specification, quantity: 1, serialNumber: '', orderNumber: '', stockId: option.stockId }
}

interface Props {
  companyId: number | null
  locationId: number | null
  relatedDevices: RelatedDevice[]
  accessories: AssetAccessory[]
  disabled: boolean
  onChange: (relatedDevices: RelatedDevice[], accessories: AssetAccessory[]) => void
}

export function AssetAccessories({ companyId, locationId, relatedDevices, accessories, disabled, onChange }: Props) {
  const [open, setOpen] = useState(false)
  const [options, setOptions] = useState<Choice[]>([])
  const [type, setType] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState<number[]>([])
  const [revision, setRevision] = useState(0)
  const request = useRef(0)
  const search = useRef<HTMLInputElement>(null)
  const choose = useRef<HTMLButtonElement>(null)
  const picker = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    if (!open) return
    const token = ++request.current
    setLoading(true); setError(''); setOptions([]); setSelected([])
    if (picker.current && !picker.current.open) picker.current.showModal()
    if (!companyId || !locationId) { setError('请先选择公司和存放位置'); setLoading(false); return () => { request.current++ } }
    api.accessoryStockChoices(companyId,locationId).then(rows => { if (token === request.current) setOptions(
      rows.map(row => ({ id: -row.id, stockId: row.id, name: row.type, model: row.brand, specification: '', disabled: false, version: row.version, available: row.available })),
    ) })
      .catch(reason => { if (token === request.current) setError(reason instanceof Error ? reason.message : '读取配件目录失败') })
      .finally(() => { if (token === request.current) setLoading(false) })
    search.current?.focus()
    return () => { request.current++ }
  }, [open, revision, companyId, locationId])

  const close = () => { setOpen(false); setQuery(''); setSelected([]); choose.current?.focus() }
  const update = (index: number, change: Partial<RelatedDevice>) => onChange(relatedDevices.map((row, i) => i === index ? { ...row, ...change } : row), accessories)
  const matches = matchingAccessories(options, query).filter(option => !type || option.name === type) as Choice[]
  const picked = options.filter(row => !row.disabled && (row.available === undefined || row.available > 0) && selected.includes(row.id))
  const quantity = (value: string) => Math.min(9999, Math.max(1, Math.trunc(Number(value)) || 1))

  return <section className="form-section full-width-section accessory-editor" aria-label="随附配件">
    <div className="form-section-head"><h3>随附配件</h3><button ref={choose} type="button" className="section-add-button" disabled={disabled} onClick={() => { if (open) close(); else setOpen(true) }} aria-expanded={open} aria-controls="accessory-picker"><PlusIcon />选择配件</button></div>
    {open && <dialog ref={picker} className="accessory-picker" id="accessory-picker" aria-label="选择随附配件" onCancel={event => { event.preventDefault(); close() }} onKeyDown={event => { if (!event.nativeEvent?.isComposing && event.keyCode !== 229 && event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close() } }}>
      <div className="accessory-picker-heading"><h3>选择配件</h3><button type="button" className="accessory-picker-close" title="关闭" aria-label="关闭配件选择" onClick={close}><CloseIcon /></button></div>
      <div className="accessory-type-tabs"><button type="button" aria-pressed={!type} onClick={() => setType('')}>全部</button>{[...new Set(options.map(option=>option.name))].map(value=><button type="button" key={value} aria-pressed={type===value} onClick={()=>setType(value)}>{value}</button>)}</div>
      <div className="accessory-picker-search"><input ref={search} type="search" aria-label="搜索随附配件" placeholder="搜索品牌或类型" value={query} disabled={disabled} onKeyDown={event => { if (event.key === 'Enter' && !event.nativeEvent?.isComposing && event.keyCode !== 229) event.preventDefault() }} onChange={event => setQuery(event.target.value)} /><button type="button" className="button ghost" disabled={disabled || loading} onClick={() => setRevision(value => value + 1)}>刷新</button></div>
      {error ? <p role="alert">{error}</p> : loading ? <p role="status">正在读取配件…</p> : matches.length ? <div className="accessory-picker-results">{matches.map(option => <label className={`accessory-choice ${selected.includes(option.id) ? 'selected' : ''}`} key={option.id}>
        <input type="checkbox" disabled={disabled || option.available === 0 || !!(option.stockId && relatedDevices.some(d=>d.stockId===option.stockId))} checked={selected.includes(option.id)} onChange={event => setSelected(current => event.target.checked ? [...new Set([...current, option.id])] : current.filter(id => id !== option.id))} />
        <span className="accessory-identity"><strong>{option.model || option.name}</strong><span>{option.name}</span>{option.stockId ? <small>可用 {option.available}{relatedDevices.some(d=>d.stockId===option.stockId) ? ' · 已添加' : ''}</small> : <small>旧目录 · 不扣库存</small>}{option.specification && <small>{option.specification}</small>}</span>
      </label>)}</div> : <p className="accessory-picker-empty">未找到匹配库存，请在“资产 → 配件”入库</p>}
      <div className="accessory-picker-actions"><button type="button" className="button ghost" onClick={close}>取消</button><button type="button" className="button primary" disabled={disabled || loading || !!error || !picked.length} onClick={() => { if (disabled || loading || error || !picked.length) return; onChange([...relatedDevices, ...picked.map(accessorySnapshot)], accessories); close() }}>添加{picked.length ? `（${picked.length}）` : ''}</button></div>
    </dialog>}
    <div className="accessory-selected-list">{relatedDevices.map((device, index) => <div className="accessory-selected" key={`device-${index}`}>
      <div className="accessory-selected-main"><div className="accessory-identity"><strong>{device.name}</strong>{device.model && <span>{device.model}</span>}{device.specification && <small>{device.specification}</small>}</div>
        <label className="accessory-quantity"><span>数量</span><input aria-label={`${device.name} ${index + 1} 数量`} type="number" min={1} max={9999} step={1} required value={device.quantity} disabled={disabled} onChange={event => update(index, { quantity: quantity(event.target.value) })} /></label>
        <button type="button" className="remove-row-button" disabled={disabled} title={`移除配件 ${device.name}`} onClick={() => onChange(relatedDevices.filter((_, i) => i !== index), accessories)}><TrashIcon /></button>
      </div>
      {!device.stockId && <details className="accessory-identifiers-legacy"><summary>原有补充信息</summary><div className="accessory-identifiers"><label><span>序列号</span><input aria-label={`${device.name} ${index + 1} 序列号`} maxLength={160} value={device.serialNumber || ''} disabled={disabled} placeholder="选填" onChange={event => update(index, { serialNumber: event.target.value })} /></label><label><span>订单号</span><input aria-label={`${device.name} ${index + 1} 订单号`} maxLength={160} value={device.orderNumber || ''} disabled={disabled} placeholder="选填" onChange={event => update(index, { orderNumber: event.target.value })} /></label></div></details>}
    </div>)}
      {accessories.map((device, index) => <div className="accessory-selected" key={`legacy-${index}`}><div className="accessory-selected-main">
        <div className="accessory-identity"><strong>{device.name}</strong>{device.specification && <small>{device.specification}</small>}</div>
        <label className="accessory-quantity"><span>数量</span><input aria-label={`${device.name} 原有配件 ${index + 1} 数量`} type="number" min={1} max={9999} step={1} required value={device.quantity} disabled={disabled} onChange={event => onChange(relatedDevices, accessories.map((row, i) => i === index ? { ...row, quantity: quantity(event.target.value) } : row))} /></label>
        <button type="button" className="remove-row-button" disabled={disabled} title={`移除原有配件 ${device.name}`} onClick={() => onChange(relatedDevices, accessories.filter((_, i) => i !== index))}><TrashIcon /></button>
      </div></div>)}
      {!relatedDevices.length && !accessories.length && <p className="accessory-picker-empty">暂无随附配件</p>}
    </div>
  </section>
}
