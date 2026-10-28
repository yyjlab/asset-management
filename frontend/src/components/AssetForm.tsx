import { useEffect, useRef, useState } from 'react'
import type { Assignee, Asset, AssetBundlePayload, AssetBundleResult, AssetEntryDefaults, AssetPayload, AssetProfile, CustomParameter, LookupType, LookupValue } from '../types'
import { lookupLabels } from '../types'
import { DragHandleIcon, PlusIcon, SaveIcon, TrashIcon, UploadIcon } from './Icons'
import { EditableCombobox } from './EditableCombobox'
import { AssetAccessories } from './AssetAccessories'
import type { ComboboxOption } from './EditableCombobox'
import { ApiError, api } from '../api'
import { buildAssetBundle, newBundleKey } from '../assetBundle'
import type { DisplayDraft } from '../assetBundle'
import { NewDisplayEditor } from './NewDisplayEditor'
import './AssetBundle.css'
import { domainStatusOf, domainStatusOptions } from '../domainJoin'
import type { DomainJoinStatus } from '../types'

interface Props {
  asset: Asset | null
  clone?: boolean
  lookups: LookupValue[]
  bindableAssets: Asset[]
  assignees: Assignee[]
  canAssign: boolean
  saving: boolean
  createdLookup: LookupValue | null
  onCancel: () => void
  onSave: (payload: AssetPayload) => Promise<void>
  onNewLookup: (type: LookupType) => void
  onDeleteLookup: (lookup: LookupValue) => Promise<boolean>
  canCreateRelated?: boolean
  entryDefaults?: AssetEntryDefaults
  onSaveBundle?: (payload: AssetBundlePayload) => Promise<AssetBundleResult>
  onContinueBundle?: (defaults: AssetEntryDefaults) => void
  onBundleRecovered?: () => void
}

const emptyPayload = (): AssetPayload => ({
  assetTag: '', name: '', ownershipDepartment: '', cpu: '', memory: '', storage: '', graphicsCard: '', manufacturerSerialNumber: '',
  screenSize: '', displayResolution: '', displayInterface: '', orderNumber: '',
  companyId: null, modelId: null, modelName: '', categoryId: null, statusId: null, locationId: null,
  purchasePrice: null, currentValue: null, checkedOut: false, assignedTo: '', assignedUserId: null,
  domainJoinStatus: 'UNKNOWN',
  imageUrl: '', imageUrls: [], notes: '', boundDisplayIds: [], boundComputerId: null, relatedDevices: [], accessories: [], customParameters: [],
})

function fromAsset(asset: Asset | null, clone = false): AssetPayload {
  if (!asset) return emptyPayload()
  const statusMeansCheckedOut = asset.status.name.includes('在用') || asset.status.name.includes('领出')
  return {
    assetTag: clone ? '' : asset.assetTag,
    version: clone ? undefined : asset.version,
    name: asset.name,
    ownershipDepartment: asset.ownershipDepartment || '',
    cpu: asset.cpu || '', memory: asset.memory || '', storage: asset.storage || '', graphicsCard: asset.graphicsCard || '',
    manufacturerSerialNumber: clone ? '' : asset.manufacturerSerialNumber || '',
    screenSize: asset.screenSize || '', displayResolution: asset.displayResolution || '',
    displayInterface: asset.displayInterface || '', orderNumber: asset.orderNumber || '',
    companyId: asset.company.id, modelId: asset.model.id, modelName: asset.model.name, categoryId: asset.category.id, statusId: asset.status.id,
    locationId: asset.location.id, purchasePrice: asset.purchasePrice, currentValue: asset.currentValue,
    checkedOut: clone ? false : asset.checkedOut || statusMeansCheckedOut, assignedTo: clone ? '' : asset.assignedTo || '',
    assignedUserId: clone ? null : asset.assignedUserId,
    domainJoinStatus: domainStatusOf(asset) === null ? null : clone ? 'UNKNOWN' : domainStatusOf(asset),
    imageUrl: asset.imageUrls?.[0] || asset.imageUrl || '',
    imageUrls: asset.imageUrls?.length ? [...asset.imageUrls] : asset.imageUrl ? [asset.imageUrl] : [],
    notes: asset.notes || '',
    boundDisplayIds: clone ? [] : (asset.boundDisplays || []).map((item) => item.id),
    boundComputerId: clone ? null : asset.boundComputer?.id ?? null,
    relatedDevices: (asset.relatedDevices || []).map((item) => ({ ...item })),
    accessories: (asset.accessories || []).map((item) => ({ ...item })),
    customParameters: (asset.customParameters || []).map((parameter) => ({ ...parameter })),
  }
}

const categoryProfile = (category?: LookupValue): AssetProfile => {
  if (category?.assetProfile) return category.assetProfile
  const name = category?.name || ''
  if (name.includes('显示器') || name.includes('屏幕') || name.includes('大屏')) return 'DISPLAY'
  if (name.includes('电脑') || name.includes('台式') || name.includes('笔记本') || name.toLowerCase().includes('mac')) return 'COMPUTER'
  return 'GENERAL'
}

interface LookupFieldProps {
  type: LookupType
  value: number | null
  values: LookupValue[]
  required?: boolean
  onChange: (value: number | null) => void
  onNew?: () => void
  onDelete?: (lookup: LookupValue) => Promise<boolean>
}

function LookupField({ type, value, values, required, onChange, onNew, onDelete }: LookupFieldProps) {
  const typeValues = values.filter((item) => item.type === type)
  const selected = typeValues.find((item) => item.id === value)
  const displayName = (name: string) => type !== 'STATUS' ? name
    : name.includes('在用') || name.includes('领出') ? '已经领出'
      : name.includes('可领用') || name.includes('当前可用') ? '当前可用'
        : name.includes('维修') || name.includes('维护') ? '维护中'
          : name.includes('报废') ? '已报废' : name
  return <EditableCombobox
    required={required}
    value={selected ? displayName(selected.name) : ''}
    selectedId={value}
    placeholder={`请选择${lookupLabels[type]}`}
    options={typeValues.map((item) => ({ id: item.id, label: displayName(item.name) }))}
    allowCreate={Boolean(onNew)}
    onCreate={onNew}
    onChange={(_, id) => onChange(typeof id === 'number' ? id : null)}
    onDelete={onDelete ? (option) => onDelete(typeValues.find((item) => item.id === option.id)!) : undefined}
  />
}

const MAX_IMAGE_BYTES = 1536 * 1024
const MAX_CAMERA_SOURCE_BYTES = 20 * 1024 * 1024
const MAX_IMAGE_EDGE = 1920

const readImage = (file: File) => new Promise<string>((resolve, reject) => {
  if (typeof FileReader === 'undefined') { reject(new Error('当前浏览器禁止读取照片；Safari 开启锁定模式时，请检查此网站的设置')); return }
  const reader = new FileReader()
  reader.onload = () => resolve(String(reader.result))
  reader.onerror = () => reject(new Error('读取图片失败'))
  reader.readAsDataURL(file)
})

const loadImage = (source: string) => new Promise<HTMLImageElement>((resolve, reject) => {
  const image = new Image()
  image.onload = () => resolve(image)
  image.onerror = () => reject(new Error('无法识别图片'))
  image.src = source
})

const dataUrlBytes = (source: string) => Math.ceil((source.split(',')[1]?.length || 0) * 3 / 4)

export async function prepareImage(file: File) {
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type.toLowerCase())) throw new Error('仅支持 JPG、PNG 或 WebP 图片')
  if (file.size > MAX_CAMERA_SOURCE_BYTES) throw new Error('照片不能超过 20 MB')
  const source = await readImage(file)
  if (file.size <= MAX_IMAGE_BYTES) return source

  const image = await loadImage(source)
  const ratio = Math.min(1, MAX_IMAGE_EDGE / Math.max(image.naturalWidth, image.naturalHeight))
  const canvas = document.createElement('canvas')
  canvas.width = Math.max(1, Math.round(image.naturalWidth * ratio))
  canvas.height = Math.max(1, Math.round(image.naturalHeight * ratio))
  const context = canvas.getContext('2d')
  if (!context) throw new Error('当前浏览器无法压缩照片')
  context.drawImage(image, 0, 0, canvas.width, canvas.height)

  for (const quality of [0.86, 0.74, 0.62, 0.5]) {
    const compressed = canvas.toDataURL('image/jpeg', quality)
    if (dataUrlBytes(compressed) <= MAX_IMAGE_BYTES) return compressed
  }
  throw new Error('照片压缩后仍然过大，请降低相机分辨率后重试')
}

export function AssetForm({ asset, clone = false, lookups, bindableAssets, assignees, canAssign, saving, createdLookup, onCancel, onSave, onNewLookup, onDeleteLookup, canCreateRelated = false, entryDefaults, onSaveBundle, onContinueBundle, onBundleRecovered }: Props) {
  const [form, setForm] = useState<AssetPayload>(() => {
    const initial = fromAsset(asset, clone)
    if (!asset && entryDefaults) {
      Object.assign(initial, entryDefaults)
      initial.name = lookups.find(item => item.id === entryDefaults.categoryId)?.name || ''
      initial.statusId = lookups.find(item => item.type === 'STATUS' && ['当前可用', '可领用'].includes(item.name))?.id ?? null
    }
    if (clone) initial.statusId = lookups.find((item) => item.type === 'STATUS' && ['当前可用', '可领用'].includes(item.name))?.id ?? null
    return initial
  })
  const [imageError, setImageError] = useState('')
  const [parameterDrag, setParameterDrag] = useState<{ from: number; to: number } | null>(null)
  const moveParameter = (from: number, to: number) => {
    setForm((current) => {
      if (from === to || to < 0 || to >= current.customParameters.length) return current
      const parameters = [...current.customParameters]
      const [parameter] = parameters.splice(from, 1)
      parameters.splice(to, 0, parameter)
      return { ...current, customParameters: parameters }
    })
  }
  const [processingImages, setProcessingImages] = useState(false)
  const [domainOptions, setDomainOptions] = useState(domainStatusOptions)
  const [domainOptionsError, setDomainOptionsError] = useState('')
  const [newDisplays, setNewDisplays] = useState<DisplayDraft[]>([])
  const [showExisting, setShowExisting] = useState(false)
  const [displayQuery, setDisplayQuery] = useState('')
  const [bundleError, setBundleError] = useState('')
  const [bundleResult, setBundleResult] = useState<AssetBundleResult | null>(null)
  const [uncertain, setUncertain] = useState(false)
  const [bundleWorking, setBundleWorking] = useState(false)
  const [processingDisplayCount, setProcessingDisplayCount] = useState(0)
  const bundleKey = useRef('')
  const lastSubmission = useRef<AssetBundlePayload | null>(null)
  const bundleBusy = useRef(false)
  const displayJobs = useRef(new Set<string>())
  const imageProcessingRef = useRef(false)
  const cameraRef = useRef<HTMLInputElement>(null)
  const galleryRef = useRef<HTMLInputElement>(null)
  const set = <K extends keyof AssetPayload>(key: K, value: AssetPayload[K]) => setForm((current) => ({ ...current, [key]: value }))

  useEffect(() => {
    if (!createdLookup) return
    if (createdLookup.type === 'MODEL') {
      setForm((current) => ({ ...current, modelId: createdLookup.id, modelName: createdLookup.name }))
      return
    }
    const fieldByType: Partial<Record<LookupType, keyof AssetPayload>> = {
      COMPANY: 'companyId', MODEL: 'modelId', CATEGORY: 'categoryId', STATUS: 'statusId', LOCATION: 'locationId',
    }
    const field = fieldByType[createdLookup.type]
    if (field) setForm((current) => ({ ...current, [field]: createdLookup.id }))
  }, [createdLookup])

  const handleImages = async (files: File[]) => {
    if (imageProcessingRef.current) return
    setImageError('')
    if (files.length === 0) return
    const remaining = 5 - form.imageUrls.length
    if (remaining <= 0) { setImageError('资产图片最多上传5张'); return }
    const selected = files.slice(0, remaining)
    if (selected.some((file) => !file.type.startsWith('image/'))) { setImageError('请选择图片文件'); return }
    imageProcessingRef.current = true
    setProcessingImages(true)
    try {
      const prepared: string[] = []
      for (const file of selected) prepared.push(await prepareImage(file))
      setForm((current) => {
        const imageUrls = [...current.imageUrls, ...prepared].slice(0, 5)
        return { ...current, imageUrls, imageUrl: imageUrls[0] || '' }
      })
      if (files.length > remaining) setImageError(`最多上传5张，已添加前${remaining}张`)
    } catch (reason) {
      setImageError(reason instanceof Error ? reason.message : '照片处理失败，请重新拍摄')
    } finally {
      imageProcessingRef.current = false
      setProcessingImages(false)
    }
  }

  const removeImage = (index: number) => setForm((current) => {
    const imageUrls = current.imageUrls.filter((_, imageIndex) => imageIndex !== index)
    return { ...current, imageUrls, imageUrl: imageUrls[0] || '' }
  })

  const updateCustomParameter = (index: number, change: Partial<CustomParameter>) => set('customParameters', form.customParameters.map((item, itemIndex) => itemIndex === index ? { ...item, ...change } : item))
  const selectedCategory = lookups.find((item) => item.type === 'CATEGORY' && item.id === form.categoryId)
  const profile = categoryProfile(selectedCategory)
  useEffect(() => {
    if (profile !== 'COMPUTER') return
    let active = true
    setDomainOptionsError('')
    api.domainStatuses().then(options => { if (active) setDomainOptions(options) })
      .catch(() => { if (active) setDomainOptionsError('入域选项说明读取失败，暂用系统固定选项；不影响已保存的入域状态。') })
    return () => { active = false }
  }, [profile])
  const availableDisplays = bindableAssets.filter((item) => categoryProfile(item.category) === 'DISPLAY' && item.id !== asset?.id
    && (!item.boundComputer || item.boundComputer.id === asset?.id))
  const availableComputers = bindableAssets.filter((item) => categoryProfile(item.category) === 'COMPUTER' && item.id !== asset?.id)
  const suggestionOptions = (type: LookupType) => lookups.filter((item) => item.type === type).map((item) => ({ id: item.id, label: item.name }))
  const selectedSuggestionId = (type: LookupType, value: string) => lookups.find((item) => item.type === type && item.name.localeCompare(value, undefined, { sensitivity: 'accent' }) === 0)?.id ?? null
  const deleteSuggestion = async (option: ComboboxOption) => {
    const lookup = lookups.find((item) => item.id === option.id)
    return lookup ? onDeleteLookup(lookup) : false
  }
  const modelField = (label: string) => <label><span>{label} *</span><EditableCombobox editable required suggestOnly value={form.modelName} selectedId={form.modelId} placeholder="输入型号搜索" options={lookups.filter((item) => item.type === 'MODEL').map((item) => ({ id: item.id, label: item.name }))} onChange={(value, id) => setForm((current) => ({ ...current, modelName: value, modelId: typeof id === 'number' ? id : null }))} /></label>
  const onDisplayProcessing = (id: string, active: boolean) => {
    if (active) displayJobs.current.add(id); else displayJobs.current.delete(id)
    setProcessingDisplayCount(displayJobs.current.size)
  }
  const canBundle = canCreateRelated && Boolean(onSaveBundle) && profile === 'COMPUTER'
  // Keep ordinary edits of legacy records outside the new bundle-size limit working.
  const bundleMode = canBundle && (newDisplays.length > 0 || form.boundDisplayIds.length > 0 && form.boundDisplayIds.length <= 20)
  const matchingDisplays = availableDisplays.filter(item => form.boundDisplayIds.includes(item.id)
    || showExisting && displayQuery.trim() && `${item.assetTag} ${item.name} ${item.model.name} ${item.manufacturerSerialNumber || ''}`.toLowerCase().includes(displayQuery.trim().toLowerCase()))
  const selectedDisplays = matchingDisplays.filter(item => form.boundDisplayIds.includes(item.id))
  const visibleDisplays = [...selectedDisplays, ...matchingDisplays.filter(item => !form.boundDisplayIds.includes(item.id)).slice(0, 8)]
  const addDisplay = () => {
    if (newDisplays.length >= 8 || newDisplays.length + form.boundDisplayIds.length >= 20) return
    const category = lookups.find(item => item.type === 'CATEGORY' && categoryProfile(item) === 'DISPLAY')
    setNewDisplays(current => [...current, { id: newBundleKey(), inheritCommon: true, data: { ...emptyPayload(),
      companyId: form.companyId, ownershipDepartment: form.ownershipDepartment, locationId: form.locationId,
      statusId: form.statusId, checkedOut: form.checkedOut, assignedTo: form.assignedTo, assignedUserId: form.assignedUserId,
      name: category?.name || '显示器', categoryId: category?.id ?? null, domainJoinStatus: null } }])
  }
  const submitBundle = async (payload: AssetBundlePayload) => {
    if (!onSaveBundle || bundleBusy.current) return
    bundleBusy.current = true; setBundleWorking(true); setBundleError(''); lastSubmission.current = payload
    try { setBundleResult(await onSaveBundle(payload)); setUncertain(false) }
    catch (reason) {
      const unknown = !(reason instanceof ApiError) || reason.status >= 500 || reason.status === 408
      setUncertain(unknown)
      setBundleError(`${reason instanceof Error ? reason.message : '保存失败'}${unknown ? '。保存结果尚未确认，请查询结果或重试原提交，不要重复新建。' : ''}`)
    } finally { bundleBusy.current = false; setBundleWorking(false) }
  }
  const recoverBundle = async () => {
    if (!lastSubmission.current || bundleBusy.current) return
    bundleBusy.current = true; setBundleWorking(true)
    try { setBundleResult(await api.getAssetBundle(lastSubmission.current.requestKey)); setUncertain(false); setBundleError(''); onBundleRecovered?.() }
    catch (reason) { setBundleError(reason instanceof ApiError && reason.status === 404 ? '暂未查到完成记录，请稍后查询或重试原提交；不会重复建档。' : reason instanceof Error ? reason.message : '查询失败') }
    finally { bundleBusy.current = false; setBundleWorking(false) }
  }
  const submit = async () => {
    if (imageProcessingRef.current || displayJobs.current.size || saving || bundleBusy.current || uncertain) return
    const computer = { ...form, domainJoinStatus: profile === 'COMPUTER' ? form.domainJoinStatus || 'UNKNOWN' : null }
    if (!bundleMode) { await onSave(computer); return }
    try {
      if (!bundleKey.current) bundleKey.current = newBundleKey()
      const payload = buildAssetBundle(computer, newDisplays, bindableAssets, bundleKey.current, asset, clone)
      await submitBundle(payload)
    } catch (reason) { setBundleError(reason instanceof Error ? reason.message : '请检查整套设备信息') }
  }
  if (bundleResult) {
    const saved = [bundleResult.computer, ...bundleResult.displays]
    return <section className="bundle-result"><h2>整套设备已保存</h2><ul className="bundle-result-list">{saved.map(item => <li key={item.id}><span><strong>{item.assetTag}</strong> · {item.name} · {item.model.name}</span><a className="button ghost" href={`/label-print?assetId=${item.id}&assetTag=${encodeURIComponent(item.assetTag)}`} target="_blank" rel="noopener noreferrer">打印标签</a></li>)}</ul><div className="bundle-result-actions"><a className="button primary" href={`/label-print?assetIds=${saved.map(item => item.id).join(',')}`} target="_blank" rel="noopener noreferrer">打印本套标签</a>{onContinueBundle && <button type="button" className="button ghost" onClick={() => onContinueBundle({ companyId: form.companyId, ownershipDepartment: form.ownershipDepartment, locationId: form.locationId, categoryId: form.categoryId })}>继续录入下一套</button>}<button type="button" className="button ghost" onClick={onCancel}>返回资产列表</button></div></section>
  }

  return <section className="invoice-form-page">
    <div className="invoice-form-outer"><form className="invoice-form-card symmetric-asset-form" onSubmit={async (event) => { event.preventDefault(); await submit() }}>
      <div className="invoice-form-scroll-body">
      <fieldset className="bundle-form-fields" disabled={saving || bundleWorking || uncertain} inert={saving || bundleWorking || uncertain}>
      <div className="invoice-form-heading"><div><h2>{clone ? '克隆资产' : asset ? '编辑资产' : '新增资产'}</h2><p>资产编号：{form.assetTag || '保存后按日期与当日流水自动生成'} · {new Date().toLocaleDateString('zh-CN')}</p></div><span className="form-mode">{clone ? 'CLONE' : asset ? 'EDIT' : 'CREATE'}</span></div>

      <section className="form-section">
        <div className="form-section-head"><div><h3>基本信息</h3><p>编号、名称以及资产归属信息</p></div></div>
        <div className="form-row-grid basic-info-grid">
          <label><span>资产编号</span><div className="asset-number-control locked"><input readOnly value={form.assetTag} placeholder="保存时自动生成" /></div></label>
          <label><span>资产名称 *</span><EditableCombobox editable required suggestOnly emptyText="未找到匹配资产名称" value={form.name} selectedId={selectedSuggestionId('ASSET_NAME', form.name)} placeholder="输入资产名称搜索" options={suggestionOptions('ASSET_NAME')} onChange={(value) => set('name', value.slice(0, 160))} /></label>
          <label><span>所属公司 *</span><LookupField type="COMPANY" required value={form.companyId} values={lookups} onChange={(v) => set('companyId', v)} onNew={() => onNewLookup('COMPANY')} onDelete={onDeleteLookup} /></label>
          <label><span>归属部门</span><EditableCombobox editable value={form.ownershipDepartment} selectedId={selectedSuggestionId('DEPARTMENT', form.ownershipDepartment)} placeholder="输入或选择资产归属部门" options={suggestionOptions('DEPARTMENT')} onChange={(value) => set('ownershipDepartment', value.slice(0, 120))} onDelete={deleteSuggestion} /></label>
          <label><span>资产分类 *</span><LookupField type="CATEGORY" required value={form.categoryId} values={lookups} onChange={(v) => {
            const category = lookups.find((item) => item.id === v)
            const nextProfile = categoryProfile(category)
            if (nextProfile !== 'COMPUTER' && newDisplays.length) {
              if (!window.confirm('切换分类将移除尚未保存的新显示器，是否继续？')) return
              setNewDisplays([])
            }
            setForm((current) => ({ ...current, categoryId: v,
              domainJoinStatus: nextProfile === 'COMPUTER' ? current.domainJoinStatus || 'UNKNOWN' : null,
              customParameters: !asset && current.categoryId !== v && nextProfile === 'GENERAL'
                ? (category?.parameterTemplate || []).map((name) => ({ name, value: '' }))
                : current.customParameters,
              boundDisplayIds: nextProfile === 'COMPUTER' ? current.boundDisplayIds : [], boundComputerId: nextProfile === 'DISPLAY' ? current.boundComputerId : null }))
          }} onNew={() => onNewLookup('CATEGORY')} onDelete={onDeleteLookup} /></label>
          <label><span>存放位置 *</span><LookupField type="LOCATION" required value={form.locationId} values={lookups} onChange={(v) => set('locationId', v)} onNew={() => onNewLookup('LOCATION')} onDelete={onDeleteLookup} /></label>
          <label><span>资产状态 *</span><LookupField type="STATUS" required value={form.statusId} values={lookups} onChange={(v) => {
            const nextStatus = lookups.find((item) => item.id === v)
            const checkedOut = Boolean(nextStatus && (nextStatus.name.includes('在用') || nextStatus.name.includes('领出')))
            setForm((current) => ({ ...current, statusId: v, checkedOut, assignedTo: checkedOut ? current.assignedTo : '', assignedUserId: checkedOut ? current.assignedUserId : null }))
          }} /></label>
        </div>
      </section>

      <section className="form-section">
        <div className="form-section-head"><div><h3>{profile === 'DISPLAY' ? '显示器参数' : profile === 'COMPUTER' ? '电脑配置' : '设备参数'}</h3><p>{profile === 'DISPLAY' ? '显示器型号、规格与采购识别信息' : profile === 'COMPUTER' ? '电脑型号与主要硬件参数 · 入域情况由运维核实' : '通用设备的型号、订单号与自定义参数'}</p>{profile === 'COMPUTER' && domainOptionsError && <p role="alert">{domainOptionsError}</p>}</div>{profile === 'GENERAL' ? <button type="button" className="section-add-button" disabled={form.customParameters.length >= 5} title={form.customParameters.length >= 5 ? '一排最多显示5个自定义参数' : '添加自定义参数'} onClick={() => set('customParameters', [...form.customParameters, { name: '', value: '' }])}><PlusIcon />添加参数</button> : <span className={`profile-badge ${profile.toLowerCase()}`}>{profile === 'DISPLAY' ? '显示设备' : '电脑设备'}</span>}</div>
        <div className={`form-row-grid device-config-grid profile-${profile.toLowerCase()}`}>
          {profile === 'COMPUTER' && <>
            {modelField('电脑型号')}
            <label><span>CPU</span><EditableCombobox editable suggestOnly emptyText="未找到匹配 CPU" value={form.cpu} selectedId={selectedSuggestionId('CPU', form.cpu)} placeholder="输入 CPU 搜索" options={suggestionOptions('CPU')} onChange={(value) => set('cpu', value.slice(0, 200))} /></label>
            <label><span>内存</span><EditableCombobox editable value={form.memory} selectedId={form.memory} placeholder="可输入，或选择常用容量" options={['8G', '16G', '32G', '64G'].map((value) => ({ id: value, label: value }))} onChange={(value) => set('memory', value)} /></label>
            <label><span>硬盘</span><EditableCombobox editable value={form.storage} selectedId={form.storage} placeholder="可输入，或选择常用容量" options={['250G', '512G', '1T'].map((value) => ({ id: value, label: value }))} onChange={(value) => set('storage', value)} /></label>
            <label><span>显卡</span><EditableCombobox editable value={form.graphicsCard} selectedId={selectedSuggestionId('GRAPHICS_CARD', form.graphicsCard)} placeholder="输入或选择显卡" options={suggestionOptions('GRAPHICS_CARD')} onChange={(value) => set('graphicsCard', value.slice(0, 200))} onDelete={deleteSuggestion} /></label>
            <label><span>入域状态</span><select aria-label="入域状态" value={form.domainJoinStatus || 'UNKNOWN'} disabled={clone} onChange={event => set('domainJoinStatus', event.target.value as DomainJoinStatus)} title={clone ? '克隆的新电脑从待确认开始，请建档后核实' : domainOptions.find(option => option.code === form.domainJoinStatus)?.description}>{domainOptions.map(option => <option key={option.code} value={option.code}>{option.label}</option>)}</select></label>
          </>}
          {profile === 'DISPLAY' && <>
            {modelField('显示器型号')}
            <label><span>屏幕尺寸</span><input maxLength={80} value={form.screenSize} onChange={(event) => set('screenSize', event.target.value)} placeholder="例如 27 英寸" /></label>
            <label><span>分辨率</span><input maxLength={120} value={form.displayResolution} onChange={(event) => set('displayResolution', event.target.value)} placeholder="例如 3840 × 2160" /></label>
            <label><span>显示接口</span><input maxLength={160} value={form.displayInterface} onChange={(event) => set('displayInterface', event.target.value)} placeholder="HDMI / DP / Type-C" /></label>
            <label><span>订单号</span><input maxLength={160} value={form.orderNumber} onChange={(event) => set('orderNumber', event.target.value)} placeholder="采购订单号" /></label>
          </>}
          {profile === 'GENERAL' && <>
            {modelField('设备型号')}
            <label><span>订单号</span><input maxLength={160} value={form.orderNumber} onChange={(event) => set('orderNumber', event.target.value)} placeholder="采购订单号" /></label>
            {form.customParameters.map((parameter, index) => <div className={`custom-parameter-field${parameterDrag?.from === index ? ' parameter-dragging' : ''}${parameterDrag?.to === index ? ' parameter-drop-target' : ''}`} data-parameter-index={index} key={index}>
              <button type="button" className="parameter-drag-handle" disabled={saving || form.customParameters.length < 2}
                aria-label={`调整自定义参数 ${index + 1} 顺序`} title="拖动调整顺序，也可按左右方向键"
                onPointerDown={(event) => {
                  if (event.button !== 0) return
                  event.currentTarget.setPointerCapture(event.pointerId)
                  setParameterDrag({ from: index, to: index })
                }}
                onPointerMove={(event) => {
                  if (!event.currentTarget.hasPointerCapture(event.pointerId)) return
                  const target = document.elementFromPoint(event.clientX, event.clientY)?.closest<HTMLElement>('[data-parameter-index]')
                  const grid = event.currentTarget.closest('.device-config-grid')
                  const to = target && grid?.contains(target) ? Number(target.dataset.parameterIndex) : index
                  setParameterDrag({ from: index, to })
                }}
                onPointerUp={(event) => {
                  if (!event.currentTarget.hasPointerCapture(event.pointerId)) return
                  if (parameterDrag) moveParameter(parameterDrag.from, parameterDrag.to)
                  event.currentTarget.releasePointerCapture(event.pointerId)
                  setParameterDrag(null)
                }}
                onLostPointerCapture={() => setParameterDrag(null)}
                onPointerCancel={() => setParameterDrag(null)}
                onKeyDown={(event) => {
                  if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
                  event.preventDefault()
                  const to = index + (event.key === 'ArrowLeft' ? -1 : 1)
                  const grid = event.currentTarget.closest('.device-config-grid')
                  moveParameter(index, to)
                  requestAnimationFrame(() => grid?.querySelector<HTMLButtonElement>(`[data-parameter-index="${to}"] .parameter-drag-handle`)?.focus())
                }}><DragHandleIcon /></button>
              <input className="custom-parameter-key" required maxLength={120} value={parameter.name} onChange={(event) => updateCustomParameter(index, { name: event.target.value })} placeholder="参数名称" aria-label={`自定义参数 ${index + 1} 名称`} />
              <div><input required maxLength={1000} value={parameter.value} onChange={(event) => updateCustomParameter(index, { value: event.target.value })} placeholder="参数值" aria-label={`自定义参数 ${index + 1} 值`} /><button type="button" className="remove-row-button" title="删除参数" onClick={() => set('customParameters', form.customParameters.filter((_, itemIndex) => itemIndex !== index))}><TrashIcon /></button></div>
            </div>)}
          </>}
          <label><span>厂家序列号 SN</span><input maxLength={160} value={form.manufacturerSerialNumber} onChange={event => set('manufacturerSerialNumber', event.target.value)} placeholder="设备机身上的序列号" /></label>
        </div>
      </section>

      {profile !== 'GENERAL' && <section className="form-section full-width-section asset-binding-section">
        <div className="form-section-head"><div><h3>{profile === 'COMPUTER' ? '显示器' : '设备绑定'}</h3></div>{profile === 'COMPUTER' && <div className="bundle-display-tools"><button type="button" className="button ghost" onClick={() => setShowExisting(current => !current)}>选择已有显示器</button>{canBundle && <button type="button" className="section-add-button" disabled={newDisplays.length >= 8} onClick={addDisplay}><PlusIcon />新建显示器</button>}</div>}</div>
        {profile === 'COMPUTER'
          ? <>{showExisting && <label className="bundle-display-search"><span>搜索已有显示器</span><input type="search" value={displayQuery} onChange={event => setDisplayQuery(event.target.value)} placeholder="输入资产编号、型号或 SN 搜索" /></label>}<div className="asset-binding-grid">{visibleDisplays.map((item) => <label className={`asset-binding-option ${form.boundDisplayIds.includes(item.id) ? 'selected' : ''}`} key={item.id}><input type="checkbox" checked={form.boundDisplayIds.includes(item.id)} disabled={!form.boundDisplayIds.includes(item.id) && form.boundDisplayIds.length >= 20} onChange={(event) => set('boundDisplayIds', event.target.checked ? [...form.boundDisplayIds, item.id] : form.boundDisplayIds.filter((id) => id !== item.id))} /><span><strong>{item.assetTag}</strong><b>{item.name}</b><small>{item.model.name} · {item.location.name}</small></span></label>)}</div>{showExisting && displayQuery.trim() && !matchingDisplays.length && <p>未找到可绑定的显示器</p>}{newDisplays.map((draft, index) => <NewDisplayEditor key={draft.id} draft={draft} number={index + 1} lookups={lookups} assignees={assignees} canAssign={canAssign} preparePhoto={prepareImage} onChange={next => setNewDisplays(current => current.map(item => item.id === next.id ? next : item))} onRemove={() => setNewDisplays(current => current.filter(item => item.id !== draft.id))} onProcessing={onDisplayProcessing} />)}</>
          : <label className="bound-computer-field"><span>绑定电脑</span><select value={form.boundComputerId ?? ''} onChange={(event) => set('boundComputerId', event.target.value ? Number(event.target.value) : null)}><option value="">暂不绑定电脑</option>{availableComputers.map((item) => <option key={item.id} value={item.id}>{item.assetTag} · {item.name} · {item.model.name}</option>)}</select></label>}
      </section>}

      <AssetAccessories relatedDevices={form.relatedDevices} accessories={form.accessories} disabled={saving} onChange={(relatedDevices, accessories) => setForm(current => ({ ...current, relatedDevices, accessories }))} />

      <section className="form-section">
        <div className="form-section-head"><div><h3>价值与领用</h3><p>价格和当前使用状态</p></div></div>
        <div className="form-row-grid value-use-grid">
          <label><span>采购价格（元）</span><input min="0" step="0.01" type="number" value={form.purchasePrice ?? ''} onChange={(e) => set('purchasePrice', e.target.value === '' ? null : Number(e.target.value))} placeholder="0.00" /></label>
          <label><span>当前价值（元）</span><input min="0" step="0.01" type="number" value={form.currentValue ?? ''} onChange={(e) => set('currentValue', e.target.value === '' ? null : Number(e.target.value))} placeholder="0.00" /></label>
          <label><span>领用人</span><input disabled={!form.checkedOut || !canAssign || Boolean(form.assignedUserId)} required={form.checkedOut} maxLength={120} value={form.assignedTo} onChange={(e) => set('assignedTo', e.target.value)} placeholder={form.checkedOut ? '请输入领用人或选择关联账号' : '当前未领用'} /></label>
          {canAssign && <label><span>关联领用账号（用于“仅本人”权限）</span><select disabled={!form.checkedOut} value={form.assignedUserId ?? ''} onChange={event => {
            const account = assignees.find(user => user.id === Number(event.target.value))
            setForm(current => ({ ...current, assignedUserId: account?.id ?? null, assignedTo: account?.displayName ?? current.assignedTo }))
          }}><option value="">不关联账号，保留领用人文字</option>{assignees.map(user => <option key={user.id} value={user.id}>{user.displayName}（{user.username}）</option>)}</select></label>}
        </div>
      </section>

      <section className="form-section">
        <div className="form-section-head"><div><h3>图片与备注</h3><p>左右等宽，集中保存补充资料</p></div></div>
        <div className="symmetric-media-grid">
          <div><div className="asset-image-title"><span className="field-title">资产图片</span><b>{form.imageUrls.length} / 5</b></div><div className="asset-image-editor"><div className="asset-image-grid">{form.imageUrls.map((source, index) => <div className="asset-image-thumb" key={`${source.slice(-24)}-${index}`}><img src={source} alt={`${form.name || '资产'}图片${index + 1}`} /><span>{index === 0 ? '封面' : `图片${index + 1}`}</span><button type="button" title={`删除第${index + 1}张图片`} onClick={() => removeImage(index)}><TrashIcon /></button></div>)}{form.imageUrls.length < 5 && <button type="button" className="asset-image-add" onClick={() => cameraRef.current?.click()}><UploadIcon /><strong>拍照添加</strong><span>还可添加 {5 - form.imageUrls.length} 张</span></button>}</div><div className="asset-image-actions"><button type="button" onClick={() => cameraRef.current?.click()}>手机拍照</button><button type="button" onClick={() => galleryRef.current?.click()}>从相册或文件选择</button><span>第1张作为列表封面，大图自动压缩</span></div><input ref={cameraRef} hidden type="file" accept="image/jpeg,image/png,image/webp" capture="environment" onChange={(event) => { const input = event.currentTarget; void handleImages(Array.from(input.files || [])).finally(() => { input.value = '' }) }} /><input ref={galleryRef} hidden multiple type="file" accept="image/jpeg,image/png,image/webp" onChange={(event) => { const input = event.currentTarget; void handleImages(Array.from(input.files || [])).finally(() => { input.value = '' }) }} /></div>{imageError && <small className="field-error">{imageError}</small>}</div>
          <label><span>备注</span><textarea maxLength={2000} rows={7} value={form.notes} onChange={(e) => set('notes', e.target.value)} placeholder="记录保修、附件或其他说明…" /></label>
        </div>
      </section>
      </fieldset>
      {bundleError && <p role="alert" className="field-error">{bundleError}</p>}
      {uncertain && <div className="bundle-recovery"><button type="button" className="button ghost" disabled={bundleWorking} onClick={() => void recoverBundle()}>查询保存结果</button><button type="button" className="button primary" disabled={bundleWorking} onClick={() => lastSubmission.current && void submitBundle(lastSubmission.current)}>重试保存整套</button></div>}
      </div>

      <div className="invoice-form-actions"><button type="button" className="button ghost" disabled={saving || bundleWorking} onClick={() => { if (!uncertain || window.confirm('上次保存结果尚未确认，退出后请先检查资产列表，避免重复新建。仍要退出吗？')) onCancel() }}>取消</button><button className="button primary" disabled={saving || bundleWorking || processingImages || processingDisplayCount > 0 || uncertain}><SaveIcon />{processingImages || processingDisplayCount > 0 ? '图片处理中…' : saving || bundleWorking ? '保存中…' : bundleMode ? '保存整套设备' : clone ? '创建克隆资产' : asset ? '保存修改' : '创建资产'}</button></div>
    </form></div>
  </section>
}
