import { useEffect, useRef, useState } from 'react'
import type { AssetPayload, Assignee, LookupValue } from '../types'
import type { DisplayDraft } from '../assetBundle'
import { EditableCombobox } from './EditableCombobox'
import { TrashIcon } from './Icons'
import './AssetBundle.css'

interface Props {
  draft: DisplayDraft; number: number; lookups: LookupValue[]; assignees: Assignee[]; canAssign: boolean
  preparePhoto: (file: File) => Promise<string>
  onChange: (draft: DisplayDraft) => void; onRemove: () => void
  onProcessing: (id: string, active: boolean) => void
}

export function NewDisplayEditor({ draft, number, lookups, assignees, canAssign, preparePhoto, onChange, onRemove, onProcessing }: Props) {
  const [error, setError] = useState('')
  const [processing, setProcessing] = useState(false)
  const camera = useRef<HTMLInputElement>(null), gallery = useRef<HTMLInputElement>(null)
  const working = useRef(false), alive = useRef(true)
  const data = draft.data
  const set = <K extends keyof AssetPayload>(field: K, value: AssetPayload[K]) => onChange({ ...draft, data: { ...data, [field]: value } })
  useEffect(() => { alive.current = true; return () => { alive.current = false; onProcessing(draft.id, false) } }, [draft.id])
  const photos = async (files: File[]) => {
    if (working.current || !files.length) return
    working.current = true; setProcessing(true); setError(''); onProcessing(draft.id, true)
    try {
      const prepared: string[] = []
      for (const file of files.slice(0, 5 - data.imageUrls.length)) prepared.push(await preparePhoto(file))
      if (alive.current) {
        const images = [...data.imageUrls, ...prepared].slice(0, 5)
        onChange({ ...draft, data: { ...data, imageUrls: images, imageUrl: images[0] || '' } })
        if (files.length > 5 - data.imageUrls.length) setError('每台显示器最多5张照片')
      }
    } catch (reason) { if (alive.current) setError(reason instanceof Error ? reason.message : '照片处理失败') }
    finally { working.current = false; if (alive.current) setProcessing(false); onProcessing(draft.id, false) }
  }
  const lookup = (type: 'COMPANY' | 'LOCATION' | 'STATUS', field: 'companyId' | 'locationId' | 'statusId', label: string) => <label><span>{label} *</span><select required aria-label={`显示器${number}${label}`} value={data[field] ?? ''} onChange={event => {
    const id = Number(event.target.value)
    if (field === 'statusId') {
      const name = lookups.find(value => value.id === id)?.name || ''
      const checkedOut = name.includes('在用') || name.includes('领出')
      onChange({ ...draft, data: { ...data, statusId: id || null, checkedOut, assignedTo: checkedOut ? data.assignedTo : '', assignedUserId: checkedOut ? data.assignedUserId : null } })
    } else set(field, id || null)
  }}><option value="">请选择</option>{lookups.filter(value => value.type === type).map(value => <option key={value.id} value={value.id}>{value.name}</option>)}</select></label>

  return <article className="new-display-card" aria-label={`新显示器${number}`}>
    <header><h4>新显示器 {number}</h4><button type="button" className="button ghost" disabled={processing} onClick={onRemove}><TrashIcon />移除</button></header>
    <label className="bundle-inherit"><input type="checkbox" checked={draft.inheritCommon} disabled={processing} onChange={event => onChange({ ...draft, inheritCommon: event.target.checked })} /><span>公司、部门、位置、状态和领用信息随电脑</span></label>
    <fieldset disabled={processing}>
      {!draft.inheritCommon && <div className="bundle-display-fields">
        {lookup('COMPANY', 'companyId', '所属公司')}
        <label><span>归属部门</span><input maxLength={120} value={data.ownershipDepartment} onChange={event => set('ownershipDepartment', event.target.value)} /></label>
        {lookup('LOCATION', 'locationId', '存放位置')}{lookup('STATUS', 'statusId', '资产状态')}
        <label><span>领用人</span><input required={data.checkedOut} disabled={!data.checkedOut || !canAssign || !!data.assignedUserId} maxLength={120} value={data.assignedTo} onChange={event => set('assignedTo', event.target.value)} /></label>
        {canAssign && <label><span>关联领用账号</span><select value={data.assignedUserId ?? ''} disabled={!data.checkedOut} onChange={event => { const user = assignees.find(value => value.id === Number(event.target.value)); onChange({ ...draft, data: { ...data, assignedUserId: user?.id ?? null, assignedTo: user?.displayName ?? data.assignedTo } }) }}><option value="">不关联账号</option>{assignees.map(user => <option key={user.id} value={user.id}>{user.displayName}（{user.username}）</option>)}</select></label>}
      </div>}
      <div className="bundle-display-fields">
        <label><span>显示器分类 *</span><select aria-label={`显示器${number}分类`} required value={data.categoryId ?? ''} onChange={event => { const category = lookups.find(value => value.id === Number(event.target.value)); onChange({ ...draft, data: { ...data, categoryId: category?.id ?? null, name: category?.name || '显示器' } }) }}><option value="">请选择显示器分类</option>{lookups.filter(value => value.type === 'CATEGORY' && (value.assetProfile === 'DISPLAY' || !value.assetProfile && /显示器|屏幕|大屏/.test(value.name))).map(value => <option key={value.id} value={value.id}>{value.name}</option>)}</select></label>
        <label><span>资产名称 *</span><EditableCombobox editable required suggestOnly emptyText="未找到匹配资产名称" value={data.name} placeholder="输入资产名称搜索" options={lookups.filter(value => value.type === 'ASSET_NAME').map(value => ({ id: value.id, label: value.name }))} onChange={value => set('name', value.slice(0, 160))} /></label>
        <label><span>型号 *</span><EditableCombobox editable required suggestOnly value={data.modelName} selectedId={data.modelId} placeholder="输入显示器型号搜索" options={lookups.filter(value => value.type === 'MODEL').map(value => ({ id: value.id, label: value.name }))} onChange={(value, id) => onChange({ ...draft, data: { ...data, modelName: value.slice(0, 120), modelId: typeof id === 'number' ? id : null } })} /></label>
        <label><span>屏幕尺寸</span><input maxLength={80} placeholder="例如：27英寸" value={data.screenSize} onChange={event => set('screenSize', event.target.value)} /></label>
        <label><span>分辨率</span><input maxLength={120} placeholder="例如：2K" value={data.displayResolution} onChange={event => set('displayResolution', event.target.value)} /></label>
        <label><span>显示接口</span><input maxLength={160} placeholder="HDMI / DP" value={data.displayInterface} onChange={event => set('displayInterface', event.target.value)} /></label>
        <label><span>厂家序列号 SN</span><input maxLength={160} value={data.manufacturerSerialNumber} onChange={event => set('manufacturerSerialNumber', event.target.value)} /></label>
        <label><span>订单号</span><input maxLength={160} value={data.orderNumber} onChange={event => set('orderNumber', event.target.value)} /></label>
        <label><span>采购价格（元）</span><input type="number" min={0} step="0.01" value={data.purchasePrice ?? ''} onChange={event => set('purchasePrice', event.target.value ? Number(event.target.value) : null)} /></label>
      </div>
      <div className="bundle-display-photos"><span>显示器照片 {data.imageUrls.length}/5</span><button type="button" className="button ghost" disabled={data.imageUrls.length >= 5} onClick={() => camera.current?.click()}>拍照添加</button><button type="button" className="button ghost" disabled={data.imageUrls.length >= 5} onClick={() => gallery.current?.click()}>从相册选择</button>
        <input ref={camera} hidden type="file" accept="image/jpeg,image/png,image/webp" capture="environment" onChange={event => { const input = event.currentTarget; void photos(Array.from(input.files || [])).finally(() => { input.value = '' }) }} />
        <input ref={gallery} hidden type="file" accept="image/jpeg,image/png,image/webp" multiple onChange={event => { const input = event.currentTarget; void photos(Array.from(input.files || [])).finally(() => { input.value = '' }) }} />
      </div>
      <div className="bundle-photo-thumbs">{data.imageUrls.map((source, index) => <div key={index}><img src={source} alt={`显示器${number}照片${index + 1}`} /><button type="button" aria-label={`移除显示器${number}照片${index + 1}`} onClick={() => { const images = data.imageUrls.filter((_, i) => i !== index); onChange({ ...draft, data: { ...data, imageUrls: images, imageUrl: images[0] || '' } }) }}><TrashIcon /></button></div>)}</div>
    </fieldset>
    {processing && <p role="status">照片处理中…</p>}{error && <p role="alert" className="field-error">{error}</p>}
  </article>
}
