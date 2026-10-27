import { useEffect, useRef, useState } from 'react'
import { api } from '../api'
import type { AccessoryOption, AssetAccessory, RelatedDevice } from '../types'
import { PlusIcon, TrashIcon } from './Icons'
import './Accessories.css'

const normalized = (value: string) => value.normalize('NFKC').replace(/\s+/g, '').toLocaleLowerCase()
export function matchingAccessories(options: AccessoryOption[], query: string): AccessoryOption[] {
  const term = normalized(query)
  if (!term) return []
  return options.filter(option => !option.disabled && [option.name, option.model, option.specification].some(value => normalized(value).includes(term))).slice(0, 8)
}
export function accessorySnapshot(option: AccessoryOption): RelatedDevice {
  return { name: option.name, model: option.model, specification: option.specification, quantity: 1, serialNumber: '', orderNumber: '' }
}

interface Props {
  relatedDevices: RelatedDevice[]
  accessories: AssetAccessory[]
  disabled: boolean
  onChange: (relatedDevices: RelatedDevice[], accessories: AssetAccessory[]) => void
}

export function AssetAccessories({ relatedDevices, accessories, disabled, onChange }: Props) {
  const [open, setOpen] = useState(false)
  const [options, setOptions] = useState<AccessoryOption[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState<number[]>([])
  const [revision, setRevision] = useState(0)
  const request = useRef(0)
  const search = useRef<HTMLInputElement>(null)
  const choose = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!open) return
    const token = ++request.current
    setLoading(true); setError(''); setOptions([]); setSelected([])
    api.accessoryChoices().then(rows => { if (token === request.current) setOptions(rows) })
      .catch(reason => { if (token === request.current) setError(reason instanceof Error ? reason.message : '读取配件目录失败') })
      .finally(() => { if (token === request.current) setLoading(false) })
    search.current?.focus()
    return () => { request.current++ }
  }, [open, revision])

  const close = () => { setOpen(false); setQuery(''); setSelected([]); choose.current?.focus() }
  const update = (index: number, change: Partial<RelatedDevice>) => onChange(relatedDevices.map((row, i) => i === index ? { ...row, ...change } : row), accessories)
  const matches = matchingAccessories(options, query)
  const picked = options.filter(row => !row.disabled && selected.includes(row.id))
  const quantity = (value: string) => Math.min(9999, Math.max(1, Math.trunc(Number(value)) || 1))

  return <section className="form-section full-width-section accessory-editor" aria-label="随附配件">
    <div className="form-section-head"><h3>随附配件</h3><button ref={choose} type="button" className="section-add-button" disabled={disabled} onClick={() => { if (open) close(); else setOpen(true) }} aria-expanded={open} aria-controls="accessory-picker"><PlusIcon />选择配件</button></div>
    {open && <div className="accessory-picker" id="accessory-picker" role="group" aria-label="选择随附配件" onKeyDown={event => { if (!event.nativeEvent?.isComposing && event.keyCode !== 229 && event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close() } }}>
      <div className="accessory-picker-search"><input ref={search} type="search" aria-label="搜索随附配件" placeholder="输入名称、型号或规格搜索" value={query} disabled={disabled} onKeyDown={event => { if (event.key === 'Enter' && !event.nativeEvent?.isComposing && event.keyCode !== 229) event.preventDefault() }} onChange={event => setQuery(event.target.value)} /><button type="button" className="button ghost" disabled={disabled || loading} onClick={() => setRevision(value => value + 1)}>刷新目录</button></div>
      {error ? <p role="alert">{error}</p> : loading ? <p role="status">正在读取配件…</p> : !query.trim() ? <p className="accessory-picker-empty">输入关键词查找配件</p> : matches.length ? <div className="accessory-picker-results">{matches.map(option => <label className={`accessory-choice ${selected.includes(option.id) ? 'selected' : ''}`} key={option.id}>
        <input type="checkbox" disabled={disabled} checked={selected.includes(option.id)} onChange={event => setSelected(current => event.target.checked ? [...new Set([...current, option.id])] : current.filter(id => id !== option.id))} />
        <span className="accessory-identity"><strong>{option.name}</strong>{option.model && <span>{option.model}</span>}{option.specification && <small>{option.specification}</small>}</span>
      </label>)}</div> : <p className="accessory-picker-empty">未找到匹配配件，请在“基础资料 → 随附配件”维护</p>}
      <div className="accessory-picker-actions"><button type="button" className="button ghost" onClick={close}>取消</button><button type="button" className="button primary" disabled={disabled || loading || !!error || !picked.length} onClick={() => { if (disabled || loading || error || !picked.length) return; onChange([...relatedDevices, ...picked.map(accessorySnapshot)], accessories); close() }}>添加{picked.length ? `（${picked.length}）` : ''}</button></div>
    </div>}
    <div className="accessory-selected-list">{relatedDevices.map((device, index) => <div className="accessory-selected" key={`device-${index}`}>
      <div className="accessory-selected-main"><div className="accessory-identity"><strong>{device.name}</strong>{device.model && <span>{device.model}</span>}{device.specification && <small>{device.specification}</small>}</div>
        <label className="accessory-quantity"><span>数量</span><input aria-label={`${device.name} ${index + 1} 数量`} type="number" min={1} max={9999} step={1} required value={device.quantity} disabled={disabled} onChange={event => update(index, { quantity: quantity(event.target.value) })} /></label>
        <button type="button" className="remove-row-button" disabled={disabled} title={`移除配件 ${device.name}`} onClick={() => onChange(relatedDevices.filter((_, i) => i !== index), accessories)}><TrashIcon /></button>
      </div>
      <div className="accessory-identifiers"><label><span>序列号</span><input aria-label={`${device.name} ${index + 1} 序列号`} maxLength={160} value={device.serialNumber || ''} disabled={disabled} placeholder="选填" onChange={event => update(index, { serialNumber: event.target.value })} /></label><label><span>订单号</span><input aria-label={`${device.name} ${index + 1} 订单号`} maxLength={160} value={device.orderNumber || ''} disabled={disabled} placeholder="选填" onChange={event => update(index, { orderNumber: event.target.value })} /></label></div>
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
