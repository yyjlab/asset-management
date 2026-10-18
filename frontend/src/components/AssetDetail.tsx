import { useEffect, useState } from 'react'
import type { Asset } from '../types'
import { CloneIcon, EditIcon, EyeIcon, ReturnIcon, TrashIcon } from './Icons'
import { AssetImageLightbox } from './AssetImageLightbox'
import { AssetHistoryPanel } from './AssetHistoryPanel'

interface Props {
  asset: Asset | null
  canEdit: boolean
  canCreate: boolean
  canReturn: boolean
  canDelete: boolean
  onClose: () => void
  onEdit: (asset: Asset) => void
  onClone: (asset: Asset) => void
  onReturn: (asset: Asset) => void
  onDelete: (asset: Asset) => void
}

const money = (value: number | null) => value == null ? '—' : `¥ ${Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2 })}`
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
const isMaintenance = (asset: Asset) => asset.status.name.includes('维修') || asset.status.name.includes('维护')
export function AssetDetail({ asset, canEdit, canCreate, canReturn, canDelete, onClose, onEdit, onClone, onReturn, onDelete }: Props) {
  const [qrUnavailable, setQrUnavailable] = useState(false)
  const [imagePreviewIndex, setImagePreviewIndex] = useState<number | null>(null)
  const [historyOpen, setHistoryOpen] = useState(false)
  useEffect(() => { setQrUnavailable(false); setImagePreviewIndex(null); setHistoryOpen(false) }, [asset?.id])
  if (!asset) return null
  const maintenance = isMaintenance(asset)
  const scrapped = asset.status.name.includes('报废')
  const state = scrapped ? '已报废' : maintenance ? '维护中' : asset.checkedOut ? '已经领出' : '当前可用'
  const stateClass = scrapped ? 'scrapped' : maintenance ? 'maintenance' : asset.checkedOut ? 'checked-out' : 'available'
  const profile = asset.category.assetProfile || 'GENERAL'
  const relatedItems = [
    ...(asset.relatedDevices || []),
    ...(asset.accessories || []).map((item) => ({ name: item.name, model: '', serialNumber: '', orderNumber: '', specification: item.specification, quantity: item.quantity })),
  ]
  const images = asset.imageUrls?.length ? asset.imageUrls : asset.imageUrl ? [asset.imageUrl] : []
  const identityRows = [
    ['资产名称', asset.name, '资产分类', asset.category.name],
  ]
  const computerSpecs = [
    ['电脑型号', asset.model.name],
    ['CPU', asset.cpu || '—'],
    ['内存', asset.memory || '—'],
    ['硬盘', asset.storage || '—'],
    ['显卡', asset.graphicsCard || '—'],
  ]
  const deviceRows = profile === 'COMPUTER' ? [] : profile === 'DISPLAY' ? [
    ['显示器型号', asset.model.name, '屏幕尺寸', asset.screenSize || '—'],
    ['分辨率', asset.displayResolution || '—', '显示接口', asset.displayInterface || '—'],
  ] : [
    ['设备型号', asset.model.name, '订单号', asset.orderNumber || '—'],
    ['厂家序列号', asset.manufacturerSerialNumber || '—', '设备类型', '通用设备'],
  ]
  const customParameterRows: string[][] = []
  for (let index = 0; index < (asset.customParameters || []).length; index += 2) {
    const current = asset.customParameters[index]
    const next = asset.customParameters[index + 1]
    customParameterRows.push([current.name, current.value, next?.name || '', next?.value || ''])
  }
  const parameterRows = [
    ...identityRows,
    ...deviceRows,
    ...customParameterRows,
    ['所属公司', asset.company.name, '归属部门', asset.ownershipDepartment || '未设置'],
    ['存放位置', asset.location.name, '领用情况', asset.checkedOut ? `${state} · ${asset.assignedTo || '未填写领用人'}` : state],
    ['创建时间', time(asset.createdAt), '更新时间', time(asset.updatedAt)],
    ['采购价格', money(asset.purchasePrice), '当前价值', money(asset.currentValue)],
  ]
  const boundAssets = profile === 'COMPUTER' ? asset.boundDisplays : asset.boundComputer ? [asset.boundComputer] : []

  return <section className="record-page">
    <div className="record-card-outer"><article className="record-card">
      <div className="record-scroll-body" tabIndex={0} aria-label="资产详情内容">
        <div className="record-summary-grid">
          <header className="record-summary-head">
            <div className="record-summary-title">
              <span className="record-kicker">ASSET RECORD</span>
              <div><h2>{asset.name}</h2><span className={`status-pill ${stateClass}`}>{state}</span></div>
              <p>资产编号 <strong>{asset.assetTag}</strong></p>
            </div>
            <a className="record-label-action" href={`/label-print?assetId=${asset.id}&assetTag=${encodeURIComponent(asset.assetTag)}`} target="_blank" rel="noopener" title="在新页面中查看并打印资产标签">
              <span className="record-label-action-icon"><EyeIcon /></span>
              <span>查看/打印资产标签</span>
              <span className={`record-label-preview ${qrUnavailable ? 'unavailable' : ''}`} aria-hidden="true">
                {qrUnavailable
                  ? <span className="record-label-preview-message"><strong>资产标签未配置</strong><small>请联系超级管理员前往设置</small></span>
                  : <><span className="record-label-preview-shell"><img src={`/api/assets/${asset.id}/qr`} onError={() => setQrUnavailable(true)} alt="" /></span><small>点击进入打印预览</small></>}
              </span>
            </a>
          </header>

          {profile === 'COMPUTER' && <section className="record-computer-config" aria-label="电脑核心配置">
            <div className="record-config-heading"><span>COMPUTER PROFILE</span><strong>电脑配置</strong></div>
            <div className="record-config-grid">
              {computerSpecs.map(([label, value]) => <div className="record-config-item" key={label}><span>{label}</span><strong title={value}>{value}</strong></div>)}
            </div>
          </section>}

          <div className="record-table">
            <div className="record-table-head"><span>参数名称</span><strong>参数内容</strong><span>参数名称</span><strong>参数内容</strong></div>
            {parameterRows.map(([labelA, valueA, labelB, valueB], index) => <div className="record-table-row" key={`${labelA}-${index}`}>
              <span>{labelA}</span><strong>{valueA}</strong><span>{labelB}</span><strong>{valueB}</strong>
            </div>)}
          </div>
        </div>

        {profile !== 'GENERAL' && <section className="record-subsection">
          <div className="record-subsection-head"><div><h3>设备绑定</h3><span>{boundAssets.length} 项</span></div></div>
          <div className="record-item-table related-record-table">
            <div className="record-item-head"><span>资产编号</span><span>资产名称</span><span>分类</span><span>型号</span><span>绑定关系</span><span>数量</span></div>
            {boundAssets.length === 0 ? <div className="record-item-empty">{profile === 'COMPUTER' ? '暂未绑定显示器' : '暂未绑定电脑'}</div> : boundAssets.map((item) => <div className="record-item-row" key={item.id}><strong>{item.assetTag}</strong><span>{item.name}</span><span>{item.category || '—'}</span><span>{item.model || '—'}</span><span>{profile === 'COMPUTER' ? '已配显示器' : '所属电脑'}</span><b>× 1</b></div>)}
          </div>
        </section>}

        <section className="record-subsection">
          <div className="record-subsection-head"><div><h3>随附配件</h3><span>{relatedItems.reduce((sum, item) => sum + item.quantity, 0)} 件</span></div></div>
          <div className="record-item-table related-record-table">
            <div className="record-item-head"><span>设备名称</span><span>型号</span><span>序列号</span><span>订单号</span><span>规格参数</span><span>数量</span></div>
            {relatedItems.length === 0 ? <div className="record-item-empty">暂无随附配件</div> : relatedItems.map((device, index) => <div className="record-item-row" key={`${device.name}-${index}`}><strong>{device.name}</strong><span>{device.model || '—'}</span><span>{device.serialNumber || '—'}</span><span>{device.orderNumber || '—'}</span><span>{device.specification || '—'}</span><b>× {device.quantity}</b></div>)}
          </div>
        </section>

        <section className="record-subsection"><button className="button ghost" onClick={() => setHistoryOpen(value => !value)}>{historyOpen ? '收起资产履历' : '查看资产履历'}</button>{historyOpen && <AssetHistoryPanel key={asset.id} assetId={asset.id} />}</section>
        <div className="record-bottom record-media-panel">
          <div className="record-notes-media">
            {images.length
              ? <div className="record-image-gallery">{images.map((source, index) => <button className="record-image-preview" title={`查看第${index + 1}张图片`} onClick={() => setImagePreviewIndex(index)} key={`${source.slice(-24)}-${index}`}><img src={source} alt={`${asset.name}图片${index + 1}`} /><span>{index === 0 ? '封面' : `${index + 1} / ${images.length}`}</span></button>)}</div>
              : <div className="record-placeholder record-large-placeholder">{asset.name.slice(0, 1)}</div>}
            <div className="record-notes"><span>备注</span><p>{asset.notes || '暂无备注'}</p></div>
          </div>
        </div>
      </div>

      <div className="record-actions danger-zone">
        <div>{canDelete && <button className="button danger-button" onClick={() => onDelete(asset)}><TrashIcon />删除资产</button>}</div>
        <div>
          {canReturn && asset.checkedOut && <button className="button return-button" onClick={() => onReturn(asset)}><ReturnIcon />归还资产</button>}
          {canCreate && <button className="button clone-button" onClick={() => onClone(asset)}><CloneIcon />克隆资产</button>}
          {canEdit && <button className="button warm" onClick={() => onEdit(asset)}><EditIcon />编辑资产</button>}
          <button className="button primary" onClick={onClose}>返回资产列表</button>
        </div>
      </div>
      <AssetImageLightbox asset={imagePreviewIndex == null ? null : asset} initialIndex={imagePreviewIndex ?? 0} onClose={() => setImagePreviewIndex(null)} />
    </article></div>
  </section>
}
