import { useRef, useState } from 'react'
import { BackToTop } from './BackToTop'
import type { Asset, AssetProfile } from '../types'
import { CloneIcon, EditIcon } from './Icons'
import { AssetImageLightbox } from './AssetImageLightbox'

interface Props {
  assets: Asset[]
  loading: boolean
  canEdit: boolean
  canCreate: boolean
  parameterProfile: AssetProfile | null
  onSelect: (asset: Asset) => void
  onEdit: (asset: Asset) => void
  onClone: (asset: Asset) => void
}

const money = (value: number | null) => value == null ? '' : `¥ ${Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2 })}`
const isMaintenance = (asset: Asset) => asset.status.name.includes('维修') || asset.status.name.includes('维护')
const isScrapped = (asset: Asset) => asset.status.name.includes('报废')
type AssetParameter = { name: string; value: string }

const firstThreeParameters = (asset: Asset): AssetParameter[] => {
  const profile = asset.category.assetProfile || 'GENERAL'
  const parameters: AssetParameter[] = profile === 'COMPUTER' ? [
    { name: 'CPU', value: asset.cpu || '' },
    { name: '内存', value: asset.memory || '' },
    { name: '硬盘', value: asset.storage || '' },
  ] : profile === 'DISPLAY' ? [
    { name: '屏幕尺寸', value: asset.screenSize || '' },
    { name: '分辨率', value: asset.displayResolution || '' },
    { name: '显示接口', value: asset.displayInterface || '' },
  ] : (asset.customParameters || []).map((parameter) => ({ name: parameter.name, value: parameter.value }))

  return [0, 1, 2].map((index) => parameters[index] || { name: `参数${index + 1}`, value: '' })
}

export function AssetTable({ assets, loading, canEdit, canCreate, onSelect, onEdit, onClone }: Props) {
  const [previewAsset, setPreviewAsset] = useState<Asset | null>(null)
  const tableRef = useRef<HTMLDivElement>(null)
  const showPurchasePrice = assets.some((asset) => asset.purchasePrice != null)
  const showCurrentValue = assets.some((asset) => asset.currentValue != null)
  const valueColumnCount = Number(showPurchasePrice) + Number(showCurrentValue)
  const assetParameters = assets.map(firstThreeParameters)
  const visibleParameterIndexes = [0, 1, 2].filter((index) => assetParameters.some((parameters) => Boolean(parameters[index]?.value.trim())))
  const columnCount = 9 + visibleParameterIndexes.length + valueColumnCount
  const tableMinimumWidth = 974 + visibleParameterIndexes.length * 128 + valueColumnCount * 110

  return (
    <><div className="table-wrap" ref={tableRef}>
      <table className={`asset-table invoice-asset-table value-columns-${valueColumnCount}`} style={{ minWidth: tableMinimumWidth }}>
        <colgroup>
          <col className="asset-col-tag" />
          <col className="asset-col-name" />
          <col className="asset-col-image" />
          <col className="asset-col-model" />
          {visibleParameterIndexes.map((index) => <col className={['asset-col-cpu', 'asset-col-memory', 'asset-col-storage'][index]} key={index} />)}
          <col className="asset-col-category" />
          <col className="asset-col-status" />
          <col className="asset-col-assignee" />
          <col className="asset-col-location" />
          {showPurchasePrice && <col className="asset-col-price" />}
          {showCurrentValue && <col className="asset-col-value" />}
          <col className="asset-col-actions" />
        </colgroup>
        <thead><tr>
          <th>资产编号</th><th>资产名称</th><th>图片</th><th>设备型号</th>{visibleParameterIndexes.length > 0 && <th colSpan={visibleParameterIndexes.length} scope="colgroup">规格配置</th>}<th>分类</th><th>状态</th>
          <th>领用人</th><th>位置</th>{showPurchasePrice && <th className="number">采购价格</th>}{showCurrentValue && <th className="number">当前价值</th>}<th className="sticky-action">操作</th>
        </tr></thead>
        <tbody>
          {loading ? <tr><td colSpan={columnCount}><div className="table-empty"><span className="spinner" />正在读取资产…</div></td></tr> :
           assets.length === 0 ? <tr><td colSpan={columnCount}><div className="table-empty"><strong>当前授权范围内暂无匹配资产</strong><span>{canCreate ? '可以调整筛选条件，或新增一项资产。' : '可以调整筛选条件；本人资产需由管理员确认并关联领用账号。'}</span></div></td></tr> :
           assets.map((asset) => {
             const scrapped = isScrapped(asset)
             const maintenance = isMaintenance(asset)
             const state = scrapped ? '已报废' : maintenance ? '维护中' : asset.checkedOut ? '已经领出' : '当前可用'
             const stateClass = scrapped ? 'scrapped' : maintenance ? 'maintenance' : asset.checkedOut ? 'checked-out' : 'available'
             const parameters = firstThreeParameters(asset)
             const primaryImage = asset.imageUrls?.[0] || asset.imageUrl
             return <tr key={asset.id} onClick={() => onSelect(asset)}>
               <td><span className="asset-tag">{asset.assetTag}</span></td>
               <td><strong className="asset-name compact-text" title={asset.name}>{asset.name}</strong></td>
               <td>{primaryImage ? <button className="image-preview-trigger" title={`点击查看${asset.imageUrls?.length || 1}张图片`} onClick={(event) => { event.stopPropagation(); setPreviewAsset(asset) }}><img className="table-image" src={primaryImage} alt={asset.name} /></button> : <span className="no-image">{asset.name.slice(0, 1)}</span>}</td>
               <td><span className="configuration-value compact-text" title={asset.model.name}>{asset.model.name}</span></td>
               {visibleParameterIndexes.map((index) => {
                 const parameter = parameters[index]
                 const text = parameter.value.trim() ? `${parameter.name}：${parameter.value}` : ''
                 return <td key={`${parameter.name}-${index}`}>{text && <span className="configuration-value compact-text" title={text}>{text}</span>}</td>
               })}
               <td><span className="compact-text" title={asset.category.name}>{asset.category.name}</span></td>
               <td><span className={`status-pill ${stateClass}`}>{state}</span></td>
               <td><span className="compact-text" title={asset.assignedTo || '暂未领用'}>{asset.assignedTo || '暂未领用'}</span></td>
               <td><span className="compact-text" title={asset.location.name}>{asset.location.name}</span></td>
               {showPurchasePrice && <td className="number value-cell">{money(asset.purchasePrice)}</td>}
               {showCurrentValue && <td className="number value-cell">{money(asset.currentValue)}</td>}
               <td className="sticky-action"><div className="row-actions">
                 {canCreate && <button className="clone-action" title="克隆资产" onClick={(event) => { event.stopPropagation(); onClone(asset) }}><CloneIcon /></button>}
                 {canEdit && <button className="edit-action" title="编辑资产" onClick={(event) => { event.stopPropagation(); onEdit(asset) }}><EditIcon /></button>}
               </div></td>
             </tr>
           })}
        </tbody>
      </table>
    </div><BackToTop containerRef={tableRef} /><AssetImageLightbox asset={previewAsset} onClose={() => setPreviewAsset(null)} /></>
  )
}
